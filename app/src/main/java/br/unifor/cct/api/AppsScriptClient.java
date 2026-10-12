package br.unifor.cct.api;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.unifor.cct.model.Reserva;

/**
 * Implementação real da ReservaApi, falando com o Web App do Google Apps
 * Script (URL terminada em /exec).
 *
 * Toda chamada é um POST com JSON no corpo:
 *   { "acao": "criarReserva", "token": ..., "professorId": 1, "projetorId": "PAT-001",
 *     "data": "2026-09-22", "inicio": "19:00", "fim": "21:00" }
 *   { "acao": "cancelarReserva" | "consultarReserva", "token": ..., "reservaId": 1000 }
 * e a resposta é { "sucesso": ..., "reservaId": ..., "mensagem": ... }.
 *
 * O token autentica o terminal. Vai no corpo, e não num header, porque o
 * doPost(e) do Apps Script não tem acesso aos headers da requisição.
 *
 * A rede roda numa thread de fundo e o callback é chamado na thread
 * principal, então a tela pode atualizar a UI direto dentro dele.
 * "sucesso": false vindo do Apps Script (ex.: projetor ocupado) chega em
 * onErro com a mensagem do servidor, como descrito em ReservaCallback.
 */
public class AppsScriptClient implements ReservaApi {

    private static final int TIMEOUT_CONEXAO_MS = 10_000;
    // O Apps Script às vezes demora alguns segundos na primeira chamada.
    private static final int TIMEOUT_LEITURA_MS = 20_000;
    private static final int MAX_REDIRECIONAMENTOS = 5;

    private final String urlWebApp;
    private final String token;
    private final ExecutorService rede;
    private final Executor entrega;

    /**
     * @param urlWebApp URL de implantação do Web App (".../exec")
     * @param token     segredo compartilhado com o Apps Script para autenticar o terminal
     */
    public AppsScriptClient(String urlWebApp, String token) {
        this(exigirHttps(urlWebApp), token, new Handler(Looper.getMainLooper())::post);
    }

    /** Permite trocar a thread de entrega dos callbacks (usado nos testes). */
    AppsScriptClient(String urlWebApp, String token, Executor entrega) {
        this.urlWebApp = urlWebApp;
        this.token = token;
        this.rede = Executors.newSingleThreadExecutor();
        this.entrega = entrega;
    }

    private static String exigirHttps(String url) {
        if (url == null || !url.startsWith("https://")) {
            throw new IllegalArgumentException("A URL do Web App deve usar HTTPS.");
        }
        return url;
    }

    @Override
    public void criarReserva(int professorId, String projetorId, String data,
                             String inicio, String fim, ReservaCallback callback) {
        Reserva reserva;
        try {
            // Valida localmente antes de gastar uma ida ao servidor.
            reserva = Reserva.nova(professorId, projetorId, data, inicio, fim);
        } catch (IllegalArgumentException e) {
            entrega.execute(() -> callback.onErro(e.getMessage()));
            return;
        }
        enviar(callback, corpo("criarReserva")
                .put("professorId", reserva.getProfessorId())
                .put("projetorId", reserva.getProjetorCodigo())
                .put("data", reserva.getDataTexto())
                .put("inicio", reserva.getInicioTexto())
                .put("fim", reserva.getFimTexto()));
    }

    @Override
    public void cancelarReserva(int reservaId, ReservaCallback callback) {
        enviar(callback, corpo("cancelarReserva").put("reservaId", reservaId));
    }

    @Override
    public void consultarReserva(int reservaId, ReservaCallback callback) {
        enviar(callback, corpo("consultarReserva").put("reservaId", reservaId));
    }

    /** Libera a thread de rede. Chame quando o client não for mais usado. */
    public void encerrar() {
        rede.shutdown();
    }

    private Corpo corpo(String acao) {
        return new Corpo().put("acao", acao).put("token", token);
    }

    private void enviar(ReservaCallback callback, Corpo corpo) {
        rede.execute(() -> {
            ReservaResultado resultado;
            try {
                resultado = interpretar(post(corpo.json.toString()));
            } catch (SocketTimeoutException e) {
                entregarErro(callback, "O servidor demorou para responder. Tente novamente.");
                return;
            } catch (IOException e) {
                entregarErro(callback, "Sem conexão com o servidor. Verifique a internet.");
                return;
            } catch (RespostaInvalidaException e) {
                entregarErro(callback, e.getMessage());
                return;
            }
            if (resultado.isSucesso()) {
                entrega.execute(() -> callback.onSucesso(resultado));
            } else {
                entregarErro(callback, resultado.getMensagem());
            }
        });
    }

    private void entregarErro(ReservaCallback callback, String mensagem) {
        entrega.execute(() -> callback.onErro(mensagem));
    }

    /**
     * O Web App responde ao POST com um 302 para script.googleusercontent.com,
     * onde o resultado é buscado com GET. O redirecionamento é seguido à mão
     * porque o HttpURLConnection não segue de forma confiável quando o
     * método é POST.
     */
    private String post(String json) throws IOException, RespostaInvalidaException {
        String url = urlWebApp;
        String metodo = "POST";
        for (int i = 0; i <= MAX_REDIRECIONAMENTOS; i++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            try {
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(TIMEOUT_CONEXAO_MS);
                con.setReadTimeout(TIMEOUT_LEITURA_MS);
                con.setRequestMethod(metodo);
                con.setRequestProperty("Accept", "application/json");
                if (metodo.equals("POST")) {
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    con.setDoOutput(true);
                    con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    con.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = con.getOutputStream()) {
                        out.write(bytes);
                    }
                }

                int status = con.getResponseCode();
                if (status >= 300 && status < 400) {
                    String destino = con.getHeaderField("Location");
                    if (destino == null) {
                        throw new RespostaInvalidaException("Resposta inválida do servidor.");
                    }
                    url = new URL(new URL(url), destino).toString();
                    // 307/308 mantêm o POST; 301/302/303 viram GET.
                    if (status != 307 && status != 308) {
                        metodo = "GET";
                    }
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new RespostaInvalidaException(
                            "O servidor retornou um erro (HTTP " + status + ").");
                }
                try (InputStream in = con.getInputStream()) {
                    return lerTudo(in);
                }
            } finally {
                con.disconnect();
            }
        }
        throw new RespostaInvalidaException("Redirecionamentos demais ao falar com o servidor.");
    }

    static ReservaResultado interpretar(String texto) throws RespostaInvalidaException {
        try {
            JSONObject o = new JSONObject(texto);
            if (!o.has("sucesso")) {
                throw new RespostaInvalidaException("Resposta inválida do servidor.");
            }
            boolean sucesso = o.getBoolean("sucesso");
            Integer reservaId = o.isNull("reservaId") || !o.has("reservaId")
                    ? null : o.getInt("reservaId");
            String mensagem = o.optString("mensagem", "");
            if (mensagem.isEmpty()) {
                mensagem = sucesso ? "Operação concluída." : "O servidor recusou a operação.";
            }
            return new ReservaResultado(sucesso, reservaId, mensagem);
        } catch (JSONException e) {
            // Normalmente é a página HTML de login do Google: o Web App não
            // foi implantado com acesso "Qualquer pessoa".
            throw new RespostaInvalidaException("Resposta inválida do servidor.");
        }
    }

    private static String lerTudo(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bloco = new byte[4096];
        int lidos;
        while ((lidos = in.read(bloco)) != -1) {
            buffer.write(bloco, 0, lidos);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** JSONObject.put lança JSONException checada; aqui as chaves são fixas. */
    private static final class Corpo {
        final JSONObject json = new JSONObject();

        Corpo put(String chave, Object valor) {
            try {
                json.put(chave, valor);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }
    }

    static final class RespostaInvalidaException extends Exception {
        RespostaInvalidaException(String mensagem) {
            super(mensagem);
        }
    }
}
