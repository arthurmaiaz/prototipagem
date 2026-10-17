package br.unifor.cct.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Servidor HTTP/1.1 mínimo, só para a API do NFC: uma requisição por
 * conexão (Connection: close), corpo com Content-Length e resposta JSON.
 *
 * Feito à mão para não trazer dependência nova ao app; não depende de
 * Android, então os testes rodam na JVM com sockets de verdade.
 */
public class ServidorHttp {

    public interface Handler {
        Resposta tratar(Requisicao requisicao);
    }

    /** Conexões atendidas ao mesmo tempo (uma leitura NFC pode segurar uma por até 35 s). */
    private static final int THREADS = 4;
    private static final int LIMITE_CABECALHO = 8 * 1024;
    private static final int LIMITE_CORPO = 16 * 1024;
    /** Tempo máximo para o cliente mandar o pedido inteiro. */
    private static final int TIMEOUT_LEITURA_MS = 15_000;

    private final int porta;
    private final Handler handler;

    private ServerSocket socket;
    private ExecutorService conexoes;
    private Thread aceitador;

    /** @param porta 0 escolhe uma porta livre (usado nos testes) */
    public ServidorHttp(int porta, Handler handler) {
        this.porta = porta;
        this.handler = handler;
    }

    /** Abre a porta em todas as interfaces. @throws IOException se a porta estiver em uso */
    public synchronized void iniciar() throws IOException {
        if (socket != null) return;
        ServerSocket novo = new ServerSocket();
        novo.setReuseAddress(true);
        novo.bind(new InetSocketAddress(porta));
        socket = novo;
        conexoes = Executors.newFixedThreadPool(THREADS);
        aceitador = new Thread(this::aceitar, "ServidorHttp-" + novo.getLocalPort());
        aceitador.start();
    }

    /** Porta em que está ouvindo (útil quando foi criado com porta 0). */
    public synchronized int getPorta() {
        return socket == null ? porta : socket.getLocalPort();
    }

    public synchronized boolean isRodando() {
        return socket != null;
    }

    public synchronized void parar() {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignorado) {
            // já fechado
        }
        conexoes.shutdownNow();
        socket = null;
    }

    private void aceitar() {
        ServerSocket servidor;
        ExecutorService pool;
        synchronized (this) {
            servidor = socket;
            pool = conexoes;
        }
        while (!servidor.isClosed()) {
            Socket cliente;
            try {
                cliente = servidor.accept();
            } catch (IOException e) {
                return; // parar() fechou o socket
            }
            try {
                pool.execute(() -> atender(cliente));
            } catch (RejectedExecutionException e) {
                fechar(cliente);
            }
        }
    }

    private void atender(Socket cliente) {
        try {
            cliente.setSoTimeout(TIMEOUT_LEITURA_MS);
            Resposta resposta;
            try {
                Requisicao requisicao = ler(cliente);
                try {
                    resposta = handler.tratar(requisicao);
                } catch (RuntimeException e) {
                    resposta = erro(500, "Erro interno: " + e);
                }
            } catch (PedidoInvalido e) {
                resposta = erro(e.status, e.getMessage());
            }
            escrever(cliente.getOutputStream(), resposta);
        } catch (IOException ignorado) {
            // cliente desconectou ou demorou demais
        } finally {
            fechar(cliente);
        }
    }

    // ---------------------------------------------------------------- leitura

    private static class PedidoInvalido extends Exception {
        final int status;

        PedidoInvalido(int status, String mensagem) {
            super(mensagem);
            this.status = status;
        }
    }

    private static Requisicao ler(Socket cliente) throws IOException, PedidoInvalido {
        InputStream in = cliente.getInputStream();
        String cabecalho = lerCabecalho(in);
        String[] linhas = cabecalho.split("\r\n");
        String[] partes = linhas[0].split(" ");
        if (partes.length != 3 || !partes[2].startsWith("HTTP/1.")) {
            throw new PedidoInvalido(400, "Linha de requisição inválida.");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < linhas.length; i++) {
            int dois = linhas[i].indexOf(':');
            if (dois <= 0) continue;
            headers.put(linhas[i].substring(0, dois).trim().toLowerCase(Locale.ROOT),
                    linhas[i].substring(dois + 1).trim());
        }

        int tamanho = 0;
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                tamanho = Integer.parseInt(contentLength);
            } catch (NumberFormatException e) {
                throw new PedidoInvalido(400, "Content-Length inválido.");
            }
        }
        if (tamanho < 0) throw new PedidoInvalido(400, "Content-Length inválido.");
        if (tamanho > LIMITE_CORPO) throw new PedidoInvalido(413, "Corpo grande demais.");
        byte[] corpo = new byte[tamanho];
        int lidos = 0;
        while (lidos < tamanho) {
            int n = in.read(corpo, lidos, tamanho - lidos);
            if (n < 0) throw new PedidoInvalido(400, "Corpo incompleto.");
            lidos += n;
        }

        String alvo = partes[1];
        int interrogacao = alvo.indexOf('?');
        String caminho = interrogacao < 0 ? alvo : alvo.substring(0, interrogacao);
        Map<String, String> query = interrogacao < 0
                ? new LinkedHashMap<>() : query(alvo.substring(interrogacao + 1));

        String ip = cliente.getInetAddress() == null ? null : cliente.getInetAddress().getHostAddress();
        return new Requisicao(partes[0].toUpperCase(Locale.ROOT), caminho, query, headers,
                new String(corpo, StandardCharsets.UTF_8), ip);
    }

    /** Lê até a linha em branco que separa o cabeçalho do corpo. */
    private static String lerCabecalho(InputStream in) throws IOException, PedidoInvalido {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int ultimos = 0; // últimos 4 bytes lidos, para achar "\r\n\r\n"
        while (ultimos != 0x0D0A0D0A) {
            int b = in.read();
            if (b < 0) throw new PedidoInvalido(400, "Pedido incompleto.");
            buffer.write(b);
            if (buffer.size() > LIMITE_CABECALHO) {
                throw new PedidoInvalido(431, "Cabeçalho grande demais.");
            }
            ultimos = (ultimos << 8) | b;
        }
        String texto = buffer.toString(StandardCharsets.ISO_8859_1.name());
        return texto.substring(0, texto.length() - 4);
    }

    private static Map<String, String> query(String texto) throws PedidoInvalido {
        Map<String, String> mapa = new LinkedHashMap<>();
        for (String par : texto.split("&")) {
            if (par.isEmpty()) continue;
            int igual = par.indexOf('=');
            try {
                String nome = URLDecoder.decode(igual < 0 ? par : par.substring(0, igual), "UTF-8");
                String valor = igual < 0 ? "" : URLDecoder.decode(par.substring(igual + 1), "UTF-8");
                mapa.put(nome, valor);
            } catch (IllegalArgumentException | UnsupportedEncodingException e) {
                throw new PedidoInvalido(400, "Query string inválida.");
            }
        }
        return mapa;
    }

    // -------------------------------------------------------------- escrita

    private static void escrever(OutputStream out, Resposta resposta) throws IOException {
        byte[] corpo = resposta.getJson().getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder()
                .append("HTTP/1.1 ").append(resposta.getStatus()).append(' ')
                .append(motivo(resposta.getStatus())).append("\r\n")
                .append("Content-Type: application/json; charset=utf-8\r\n")
                .append("Content-Length: ").append(corpo.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n");
        for (Map.Entry<String, String> h : resposta.getHeaders().entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(corpo);
        out.flush();
    }

    private static Resposta erro(int status, String mensagem) {
        String json = "{\"success\":false,\"message\":\""
                + mensagem.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return new Resposta(status, json);
    }

    private static String motivo(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 422: return "Unprocessable Entity";
            case 431: return "Request Header Fields Too Large";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default: return status < 500 ? "Error" : "Internal Server Error";
        }
    }

    private static void fechar(Socket cliente) {
        try {
            cliente.close();
        } catch (IOException ignorado) {
            // nada a fazer
        }
    }
}
