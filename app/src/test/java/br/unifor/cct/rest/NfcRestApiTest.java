package br.unifor.cct.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import br.unifor.cct.nfc.ResultadoNfc;

/** Sobe o ServidorHttp numa porta livre e chama a API como o test-api.ps1 faz. */
public class NfcRestApiTest {

    private static final String TOKEN = "token-de-teste";

    private PonteNfc ponte;
    private ServidorHttp servidor;
    private String base;

    @Before
    public void setUp() throws Exception {
        ponte = new PonteNfc();
        ponte.setNfc(true, true);
        ponte.setTelaAberta(true);
        servidor = new ServidorHttp(0, new NfcRestApi(ponte, new Auditoria(), () -> TOKEN, "teste"));
        servidor.iniciar();
        base = "http://127.0.0.1:" + servidor.getPorta();
    }

    @After
    public void tearDown() {
        servidor.parar();
    }

    @Test
    public void semTokenDa401() throws Exception {
        Http r = chamar("GET", "/health", null, null);
        assertEquals(401, r.status);
        assertFalse(r.json.getBoolean("success"));
        assertEquals("Bearer", r.header("WWW-Authenticate"));
    }

    @Test
    public void tokenErradoDa401() throws Exception {
        assertEquals(401, chamar("GET", "/health", "outro", null).status);
    }

    @Test
    public void healthComTokenEcoaCorrelationId() throws Exception {
        Http r = chamar("GET", "/health", TOKEN, null, "abc-123");
        assertEquals(200, r.status);
        assertEquals("ok", r.json.getString("status"));
        assertTrue(r.json.getBoolean("nfcScreenOpen"));
        assertEquals("abc-123", r.json.getString("correlationId"));
        assertEquals("abc-123", r.header("X-Correlation-Id"));
    }

    @Test
    public void leituraDevolveOPatrimonioQuandoATagChega() throws Exception {
        entregarAoIniciar(new ResultadoNfc(
                true, "PAT-001", "Projetor PAT-001 identificado.",
                Arrays.asList("PAT-001", "Sala 12"),
                Collections.singletonList("https://unifor.br"),
                "04A1B2C3", Arrays.asList("NfcA", "Ndef")));

        Http r = chamar("POST", "/v1/nfc/read", TOKEN, "{}");
        assertEquals(200, r.status);
        assertTrue(r.json.getBoolean("success"));
        assertTrue(r.json.getBoolean("valid"));
        assertEquals("PAT-001", r.json.getString("patrimony"));
        assertEquals(new JSONArray(Arrays.asList("PAT-001", "Sala 12")).toString(),
                r.json.getJSONArray("texts").toString());
        assertEquals("https://unifor.br", r.json.getJSONArray("urls").getString(0));
        assertEquals("04A1B2C3", r.json.getString("tagId"));
        assertFalse(ponte.haPedidoPendente());
    }

    @Test
    public void tagSemCodigoValidoVemComPatrimonioNulo() throws Exception {
        entregarAoIniciar(new ResultadoNfc(false, null, "Esta tag não contém um código de projetor.",
                Collections.singletonList("ola"), null, "0102", null));
        Http r = chamar("POST", "/v1/nfc/read", TOKEN, "{}");
        assertEquals(200, r.status);
        assertFalse(r.json.getBoolean("valid"));
        assertTrue(r.json.isNull("patrimony"));
        assertEquals("ola", r.json.getJSONArray("texts").getString(0));
    }

    @Test
    public void semTagNoTempoDa408() throws Exception {
        Http r = chamar("POST", "/v1/nfc/read", TOKEN, "{\"timeoutSeconds\":1}");
        assertEquals(408, r.status);
        assertFalse(ponte.haPedidoPendente());
    }

    @Test
    public void timeoutForaDoIntervaloDa400() throws Exception {
        assertEquals(400, chamar("POST", "/v1/nfc/read", TOKEN, "{\"timeoutSeconds\":99}").status);
        assertEquals(400, chamar("POST", "/v1/nfc/read", TOKEN, "nao-e-json").status);
    }

    @Test
    public void segundaLeituraSimultaneaDa409() throws Exception {
        CompletableFuture<Http> primeira = CompletableFuture.supplyAsync(() -> {
            try {
                return chamar("POST", "/v1/nfc/read", TOKEN, "{\"timeoutSeconds\":3}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long limite = System.currentTimeMillis() + 2000;
        while (!ponte.haPedidoPendente() && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
        }
        assertEquals(409, chamar("POST", "/v1/nfc/read", TOKEN, "{}").status);
        assertEquals(408, primeira.get(5, TimeUnit.SECONDS).status);
    }

    @Test
    public void telaFechadaDa503() throws Exception {
        ponte.setTelaAberta(false);
        assertEquals(503, chamar("POST", "/v1/nfc/read", TOKEN, "{}").status);
    }

    @Test
    public void fecharATelaDuranteAEsperaDa503() throws Exception {
        ponte.setOuvinte(new PonteNfc.Ouvinte() {
            @Override
            public void onPedidoIniciado() {
                CompletableFuture.runAsync(() -> ponte.setTelaAberta(false));
            }

            @Override
            public void onPedidoEncerrado() {
            }
        });
        assertEquals(503, chamar("POST", "/v1/nfc/read", TOKEN, "{}").status);
    }

    @Test
    public void modoDeGravacaoDa409() throws Exception {
        ponte.setGravando(true);
        assertEquals(409, chamar("POST", "/v1/nfc/read", TOKEN, "{}").status);
    }

    @Test
    public void qrCodeAindaNaoImplementado() throws Exception {
        assertEquals(501, chamar("POST", "/v1/qr/read", TOKEN, "{}").status);
    }

    @Test
    public void rotaEMetodoErrados() throws Exception {
        assertEquals(404, chamar("GET", "/v1/nada", TOKEN, null).status);
        assertEquals(405, chamar("GET", "/v1/nfc/read", TOKEN, null).status);
    }

    @Test
    public void logsRegistramAsChamadasComPaginacao() throws Exception {
        chamar("GET", "/health", null, null);          // 401
        chamar("GET", "/health", TOKEN, null, "um");   // 200
        chamar("POST", "/v1/qr/read", TOKEN, "{}");    // 501

        Http r = chamar("GET", "/v1/audit/logs?limit=2&offset=0", TOKEN, null);
        assertEquals(200, r.status);
        assertEquals(3, r.json.getInt("total"));
        JSONArray itens = r.json.getJSONArray("items");
        assertEquals(2, itens.length());
        // Mais recente primeiro.
        assertEquals("/v1/qr/read", itens.getJSONObject(0).getString("path"));
        assertEquals(501, itens.getJSONObject(0).getInt("status"));
        assertEquals("um", itens.getJSONObject(1).getString("correlationId"));

        // A consulta anterior também entrou no log: agora são 4, e a 401 é a última.
        Http segunda = chamar("GET", "/v1/audit/logs?limit=2&offset=3", TOKEN, null);
        assertEquals(4, segunda.json.getInt("total"));
        assertEquals(401, segunda.json.getJSONArray("items").getJSONObject(0).getInt("status"));
    }

    @Test
    public void logsComParametrosInvalidosDa400() throws Exception {
        assertEquals(400, chamar("GET", "/v1/audit/logs?limit=0", TOKEN, null).status);
        assertEquals(400, chamar("GET", "/v1/audit/logs?limit=51", TOKEN, null).status);
        assertEquals(400, chamar("GET", "/v1/audit/logs?offset=10001", TOKEN, null).status);
        assertEquals(400, chamar("GET", "/v1/audit/logs?limit=abc", TOKEN, null).status);
    }

    @Test
    public void pedidoMalformadoDa400() throws Exception {
        try (Socket s = new Socket("127.0.0.1", servidor.getPorta())) {
            OutputStream out = s.getOutputStream();
            out.write("ISSO NAO E HTTP\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String resposta = new String(lerTudo(s.getInputStream()), StandardCharsets.UTF_8);
            assertTrue(resposta, resposta.startsWith("HTTP/1.1 400"));
        }
    }

    // --------------------------------------------------------------- apoio

    /** Faz o papel da tela: entrega a leitura assim que o pedido começa a esperar. */
    private void entregarAoIniciar(ResultadoNfc resultado) {
        ponte.setOuvinte(new PonteNfc.Ouvinte() {
            @Override
            public void onPedidoIniciado() {
                CompletableFuture.runAsync(() -> ponte.entregar(resultado));
            }

            @Override
            public void onPedidoEncerrado() {
            }
        });
    }

    private static final class Http {
        final int status;
        final JSONObject json;
        final HttpURLConnection conexao;

        Http(int status, JSONObject json, HttpURLConnection conexao) {
            this.status = status;
            this.json = json;
            this.conexao = conexao;
        }

        String header(String nome) {
            return conexao.getHeaderField(nome);
        }
    }

    private Http chamar(String metodo, String caminho, String token, String corpo) throws Exception {
        return chamar(metodo, caminho, token, corpo, null);
    }

    private Http chamar(String metodo, String caminho, String token, String corpo,
                        String correlationId) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + caminho).openConnection();
        c.setRequestMethod(metodo);
        c.setConnectTimeout(2000);
        c.setReadTimeout(10_000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (correlationId != null) c.setRequestProperty("X-Correlation-Id", correlationId);
        if (corpo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(corpo.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String texto = new String(lerTudo(in), StandardCharsets.UTF_8);
        return new Http(status, new JSONObject(texto), c);
    }

    private static byte[] lerTudo(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
