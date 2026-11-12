package br.unifor.cct.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import br.unifor.cct.qr.ResultadoQr;

/** POST /v1/qr/read: sobe o ServidorHttp numa porta livre e faz o papel da tela da câmera. */
public class QrRestApiTest {

    private static final String TOKEN = "token-de-teste";

    private PonteQr qr;
    private ServidorHttp servidor;
    private String base;

    @Before
    public void setUp() throws Exception {
        qr = new PonteQr();
        servidor = new ServidorHttp(0, new NfcRestApi(new PonteNfc(), new Auditoria(), () -> TOKEN,
                "teste", null, qr));
        servidor.iniciar();
        base = "http://127.0.0.1:" + servidor.getPorta();
    }

    @After
    public void tearDown() {
        servidor.parar();
    }

    @Test
    public void leituraComATelaAbertaDevolveOPatrimonio() throws Exception {
        qr.setTelaAberta(true);
        entregarAoIniciar("projetor:PAT-002");

        Http r = chamar("POST", "/v1/qr/read", "{}");
        assertEquals(200, r.status);
        assertTrue(r.json.getBoolean("success"));
        assertEquals("ok", r.json.getString("status"));
        assertTrue(r.json.getBoolean("valid"));
        assertEquals("PAT-002", r.json.getString("patrimony"));
        assertEquals("projetor:PAT-002", r.json.getString("text"));
        assertEquals("QR_CODE", r.json.getString("format"));
        assertTrue(r.json.has("durationMs"));
        assertTrue(r.json.has("readAt"));
        assertFalse(qr.haPedidoPendente());
    }

    @Test
    public void qrQueNaoEDeProjetorVemComPatrimonioNulo() throws Exception {
        qr.setTelaAberta(true);
        entregarAoIniciar("https://exemplo.com/nao-e-projetor");

        Http r = chamar("POST", "/v1/qr/read", "{}");
        assertEquals(200, r.status);
        assertFalse(r.json.getBoolean("valid"));
        assertTrue(r.json.isNull("patrimony"));
        assertEquals("https://exemplo.com/nao-e-projetor", r.json.getString("text"));
    }

    @Test
    public void comATelaFechadaAApiAbreATela() throws Exception {
        entregarAoIniciar("PAT-001");
        qr.setAbridor(() -> qr.setTelaAberta(true));

        Http r = chamar("POST", "/v1/qr/read", "{}");
        assertEquals(200, r.status);
        assertEquals("PAT-001", r.json.getString("patrimony"));
    }

    @Test
    public void semComoAbrirATelaDa503() throws Exception {
        Http r = chamar("POST", "/v1/qr/read", "{}");
        assertEquals(503, r.status);
        assertFalse(r.json.getBoolean("success"));
        assertFalse(qr.haPedidoPendente());
    }

    @Test
    public void telaQueNaoAbreNoPrazoDa503() throws Exception {
        qr.setAbridor(() -> { });    // o Android bloqueou a abertura
        Http r = chamar("POST", "/v1/qr/read", "{\"timeoutSeconds\":1}");
        assertEquals(503, r.status);
        assertFalse(qr.haPedidoPendente());
    }

    @Test
    public void semLeituraNoTempoDa408NoFormatoDaPoc() throws Exception {
        qr.setTelaAberta(true);
        Http r = chamar("POST", "/v1/qr/read", "{\"timeoutSeconds\":1}");
        assertEquals(408, r.status);
        assertEquals("timeout", r.json.getString("status"));
        assertTrue(r.json.isNull("text"));
        assertFalse(qr.haPedidoPendente());
    }

    @Test
    public void fecharATelaDuranteALeituraDa503() throws Exception {
        qr.setTelaAberta(true);
        qr.setOuvinte(new PonteQr.Ouvinte() {
            @Override
            public void onPedidoIniciado() {
                CompletableFuture.runAsync(() -> qr.setTelaAberta(false));
            }

            @Override
            public void onPedidoEncerrado() {
            }
        });
        Http r = chamar("POST", "/v1/qr/read", "{}");
        assertEquals(503, r.status);
        assertTrue(r.json.getString("message").contains("fechada"));
    }

    @Test
    public void segundoPedidoAoMesmoTempoDa409() throws Exception {
        qr.setTelaAberta(true);
        CompletableFuture<Http> primeiro = CompletableFuture.supplyAsync(() -> {
            try {
                return chamar("POST", "/v1/qr/read", "{\"timeoutSeconds\":3}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        while (!qr.haPedidoPendente()) Thread.sleep(10);

        assertEquals(409, chamar("POST", "/v1/qr/read", "{}").status);
        qr.entregar(new ResultadoQr("PAT-001", ResultadoQr.FORMATO_QR));
        assertEquals(200, primeiro.get().status);
    }

    @Test
    public void timeoutInvalidoDa400() throws Exception {
        qr.setTelaAberta(true);
        assertEquals(400, chamar("POST", "/v1/qr/read", "{\"timeoutSeconds\":99}").status);
        assertFalse(qr.haPedidoPendente());
    }

    @Test
    public void healthMostraOEstadoDoQr() throws Exception {
        qr.setTelaAberta(true);
        Http r = chamar("GET", "/health", null);
        assertTrue(r.json.getBoolean("qrAvailable"));
        assertTrue(r.json.getBoolean("qrScreenOpen"));
        assertFalse(r.json.getBoolean("qrPending"));
    }

    @Test
    public void semPonteDeQrContinua501() throws Exception {
        ServidorHttp antigo = new ServidorHttp(0, new NfcRestApi(new PonteNfc(), new Auditoria(),
                () -> TOKEN, "teste", null));
        antigo.iniciar();
        try {
            String salvo = base;
            base = "http://127.0.0.1:" + antigo.getPorta();
            assertEquals(501, chamar("POST", "/v1/qr/read", "{}").status);
            assertFalse(chamar("GET", "/health", null).json.getBoolean("qrAvailable"));
            base = salvo;
        } finally {
            antigo.parar();
        }
    }

    // ------------------------------------------------------------------ apoio

    /** Faz o papel da tela: entrega a leitura assim que o pedido começa a esperar. */
    private void entregarAoIniciar(String texto) {
        qr.setOuvinte(new PonteQr.Ouvinte() {
            @Override
            public void onPedidoIniciado() {
                CompletableFuture.runAsync(() -> {
                    // Com a tela fechada, espera o abridor abri-la antes de entregar.
                    while (!qr.isTelaAberta()) Thread.onSpinWait();
                    qr.entregar(new ResultadoQr(texto, ResultadoQr.FORMATO_QR));
                });
            }

            @Override
            public void onPedidoEncerrado() {
            }
        });
    }

    private static final class Http {
        final int status;
        final JSONObject json;

        Http(int status, JSONObject json) {
            this.status = status;
            this.json = json;
        }
    }

    private Http chamar(String metodo, String caminho, String corpo) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + caminho).openConnection();
        c.setRequestMethod(metodo);
        c.setConnectTimeout(2000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (corpo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(corpo.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        return new Http(status, new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)));
    }
}
