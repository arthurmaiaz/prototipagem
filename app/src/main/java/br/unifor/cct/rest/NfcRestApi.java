package br.unifor.cct.rest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import br.unifor.cct.nfc.ResultadoNfc;

/**
 * Rotas da API REST do NFC, no mesmo contrato do test-api.ps1 do professor
 * (docs/nfc/openapi.yaml):
 *
 * <pre>
 * GET  /health                          estado do serviço e do NFC
 * POST /v1/nfc/read                     espera a próxima tag e devolve o patrimônio
 * POST /v1/qr/read                      ainda não implementado (501)
 * GET  /v1/audit/logs?limit=&offset=    chamadas registradas
 * </pre>
 *
 * Todas exigem "Authorization: Bearer &lt;token&gt;". O X-Correlation-Id do
 * pedido volta no header e no JSON (se não vier, é gerado).
 */
public class NfcRestApi implements ServidorHttp.Handler {

    public static final int TIMEOUT_PADRAO_S = 30;
    /** O test-api.ps1 desiste depois de 40 s; fica uma folga para a resposta chegar. */
    public static final int TIMEOUT_MAXIMO_S = 35;
    public static final int LIMIT_PADRAO = 20;
    public static final int LIMIT_MAXIMO = 50;
    public static final int OFFSET_MAXIMO = 10_000;

    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final PonteNfc ponte;
    private final Auditoria auditoria;
    private final Supplier<String> token;
    private final String versao;

    /** @param token lido a cada pedido, porque pode ser trocado na tela */
    public NfcRestApi(PonteNfc ponte, Auditoria auditoria, Supplier<String> token, String versao) {
        this.ponte = ponte;
        this.auditoria = auditoria;
        this.token = token;
        this.versao = versao;
    }

    @Override
    public Resposta tratar(Requisicao req) {
        long inicio = System.nanoTime();
        String correlationId = correlationId(req);
        String[] patrimonio = new String[1];
        JSONObject json;
        int status;
        try {
            json = new JSONObject();
            status = rotear(req, json, patrimonio);
            json.put("correlationId", correlationId);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        long duracaoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
        auditoria.registrar(Instant.now().toString(), req.getIpCliente(), req.getMetodo(),
                req.getCaminho(), status, correlationId, duracaoMs, patrimonio[0]);

        Resposta resposta = new Resposta(status, json.toString()).comHeader("X-Correlation-Id", correlationId);
        if (status == 401) resposta.comHeader("WWW-Authenticate", "Bearer");
        return resposta;
    }

    private int rotear(Requisicao req, JSONObject json, String[] patrimonio) throws JSONException {
        if (!autorizado(req)) {
            return falha(json, 401, "Token ausente ou inválido. Use Authorization: Bearer <token>.");
        }
        String metodo = req.getMetodo();
        switch (req.getCaminho()) {
            case "/health":
                return "GET".equals(metodo) ? health(json) : metodoErrado(json, "GET");
            case "/v1/nfc/read":
                return "POST".equals(metodo) ? lerNfc(req, json, patrimonio) : metodoErrado(json, "POST");
            case "/v1/qr/read":
                return "POST".equals(metodo)
                        ? falha(json, 501, "Leitura de QR Code ainda não integrada neste app.")
                        : metodoErrado(json, "POST");
            case "/v1/audit/logs":
                return "GET".equals(metodo) ? logs(req, json) : metodoErrado(json, "GET");
            default:
                return falha(json, 404, "Rota não encontrada: " + req.getCaminho());
        }
    }

    // --------------------------------------------------------------- rotas

    private int health(JSONObject json) throws JSONException {
        json.put("status", "ok")
                .put("service", "br.unifor.cct NFC API")
                .put("version", versao)
                .put("time", Instant.now().toString())
                .put("nfcAvailable", ponte.isNfcDisponivel())
                .put("nfcEnabled", ponte.isNfcLigado())
                .put("nfcScreenOpen", ponte.isTelaAberta())
                .put("writing", ponte.isGravando())
                .put("readPending", ponte.haPedidoPendente());
        return 200;
    }

    private int lerNfc(Requisicao req, JSONObject json, String[] patrimonio) throws JSONException {
        int timeoutS = TIMEOUT_PADRAO_S;
        String corpo = req.getCorpo().trim();
        if (!corpo.isEmpty()) {
            JSONObject pedido;
            try {
                pedido = new JSONObject(corpo);
            } catch (JSONException e) {
                return falha(json, 400, "Corpo precisa ser um objeto JSON (ex.: {}).");
            }
            if (pedido.has("timeoutSeconds")) {
                Object valor = pedido.get("timeoutSeconds");
                if (!(valor instanceof Integer) || (Integer) valor < 1 || (Integer) valor > TIMEOUT_MAXIMO_S) {
                    return falha(json, 400, "timeoutSeconds deve ser inteiro de 1 a " + TIMEOUT_MAXIMO_S + ".");
                }
                timeoutS = (Integer) valor;
            }
        }

        if (!ponte.isNfcDisponivel()) return falha(json, 503, "Este aparelho não tem NFC.");
        if (!ponte.isNfcLigado()) return falha(json, 503, "O NFC do celular está desligado.");
        if (!ponte.isTelaAberta()) {
            return falha(json, 503, "Abra a tela de NFC no celular: só ela consegue ler a tag.");
        }
        if (ponte.isGravando()) return falha(json, 409, "O celular está no modo de gravação de tag.");

        CompletableFuture<ResultadoNfc> pedido = ponte.abrirPedido();
        if (pedido == null) return falha(json, 409, "Já existe uma leitura esperando a tag.");
        ResultadoNfc resultado;
        try {
            resultado = pedido.get(timeoutS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return falha(json, 408, "Nenhuma tag foi encostada em " + timeoutS + " s.");
        } catch (ExecutionException e) {
            return falha(json, 503, e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return falha(json, 503, "Serviço encerrado durante a leitura.");
        } finally {
            ponte.fecharPedido(pedido);
        }

        if (resultado.getTagId() == null) {
            return falha(json, 422, resultado.getMensagem());
        }
        patrimonio[0] = resultado.isSucesso() ? resultado.getCodigoProjetor() : null;
        json.put("success", true)
                .put("valid", resultado.isSucesso())
                .put("patrimony", patrimonio[0] == null ? JSONObject.NULL : patrimonio[0])
                .put("message", resultado.getMensagem())
                .put("tagId", resultado.getTagId())
                .put("techList", new JSONArray(resultado.getTecnologias()))
                .put("texts", new JSONArray(resultado.getTextos()))
                .put("urls", new JSONArray(resultado.getUrls()))
                .put("readAt", Instant.now().toString());
        return 200;
    }

    private int logs(Requisicao req, JSONObject json) throws JSONException {
        Integer limit = inteiro(req.getQuery("limit"), LIMIT_PADRAO);
        Integer offset = inteiro(req.getQuery("offset"), 0);
        if (limit == null || limit < 1 || limit > LIMIT_MAXIMO) {
            return falha(json, 400, "limit deve ser inteiro de 1 a " + LIMIT_MAXIMO + ".");
        }
        if (offset == null || offset < 0 || offset > OFFSET_MAXIMO) {
            return falha(json, 400, "offset deve ser inteiro de 0 a " + OFFSET_MAXIMO + ".");
        }
        JSONArray itens = new JSONArray();
        List<Auditoria.Entrada> pagina = auditoria.pagina(offset, limit);
        for (Auditoria.Entrada e : pagina) {
            itens.put(new JSONObject()
                    .put("id", e.id)
                    .put("timestamp", e.horario)
                    .put("clientIp", e.ipCliente == null ? JSONObject.NULL : e.ipCliente)
                    .put("method", e.metodo)
                    .put("path", e.caminho)
                    .put("status", e.status)
                    .put("correlationId", e.correlationId)
                    .put("durationMs", e.duracaoMs)
                    .put("patrimony", e.patrimonio == null ? JSONObject.NULL : e.patrimonio));
        }
        json.put("success", true)
                .put("total", auditoria.total())
                .put("limit", limit)
                .put("offset", offset)
                .put("items", itens);
        return 200;
    }

    // --------------------------------------------------------------- apoio

    private boolean autorizado(Requisicao req) {
        String esperado = token.get();
        String header = req.getHeader("Authorization");
        if (esperado == null || esperado.isEmpty() || header == null
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        // Comparação em tempo constante, para não vazar o token pelo tempo de resposta.
        return MessageDigest.isEqual(
                header.substring(7).trim().getBytes(StandardCharsets.UTF_8),
                esperado.getBytes(StandardCharsets.UTF_8));
    }

    private static String correlationId(Requisicao req) {
        String recebido = req.getHeader("X-Correlation-Id");
        if (recebido != null && CORRELATION_ID.matcher(recebido.trim()).matches()) {
            return recebido.trim();
        }
        return UUID.randomUUID().toString();
    }

    private static Integer inteiro(String texto, int padrao) {
        if (texto == null || texto.isEmpty()) return padrao;
        try {
            return Integer.parseInt(texto);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int metodoErrado(JSONObject json, String permitido) throws JSONException {
        return falha(json, 405, "Use " + permitido + " nesta rota.");
    }

    private static int falha(JSONObject json, int status, String mensagem) throws JSONException {
        json.put("success", false).put("message", mensagem);
        return status;
    }
}
