package br.unifor.cct.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import br.unifor.cct.model.Professor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Usa um servidor HTTP local que imita o Web App do Apps Script:
 * o POST em /exec responde 302 para /echo, e o GET em /echo devolve o JSON.
 */
public class AppsScriptClientTest {

    private MockWebServer servidor;
    private AppsScriptClient client;

    @Before
    public void setUp() throws IOException {
        servidor = new MockWebServer();
        servidor.start();
        client = new AppsScriptClient(servidor.url("/exec").toString(), "segredo", Runnable::run);
    }

    @After
    public void tearDown() throws IOException {
        client.encerrar();
        servidor.shutdown();
    }

    private void responderComoAppsScript(int status, String json) {
        servidor.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/echo"));
        servidor.enqueue(new MockResponse().setResponseCode(status).setBody(json));
    }

    @Test
    public void criarReservaEnviaCamposESegueRedirecionamento() throws Exception {
        responderComoAppsScript(200,
                "{\"sucesso\":true,\"reservaId\":1000,\"mensagem\":\"Reserva realizada.\"}");

        Resposta r = chamar(cb -> client.criarReserva(7, "PAT-001", "2026-09-22", "19:00", "21:00", cb));

        assertNull(r.erro);
        assertEquals(Integer.valueOf(1000), r.resultado.getReservaId());
        assertEquals("Reserva realizada.", r.resultado.getMensagem());

        RecordedRequest post = servidor.takeRequest();
        assertEquals("POST", post.getMethod());
        assertEquals("/exec", post.getPath());
        JSONObject enviado = new JSONObject(post.getBody().readUtf8());
        assertEquals("criarReserva", enviado.getString("acao"));
        assertEquals("segredo", enviado.getString("token"));
        assertEquals(7, enviado.getInt("professorId"));
        assertEquals("PAT-001", enviado.getString("projetorId"));
        assertEquals("2026-09-22", enviado.getString("data"));
        assertEquals("19:00", enviado.getString("inicio"));
        assertEquals("21:00", enviado.getString("fim"));

        RecordedRequest get = servidor.takeRequest();
        assertEquals("GET", get.getMethod());
        assertEquals("/echo", get.getPath());
    }

    @Test
    public void cancelarEnviaReservaId() throws Exception {
        responderComoAppsScript(200,
                "{\"sucesso\":true,\"reservaId\":1000,\"mensagem\":\"Reserva cancelada.\"}");

        Resposta r = chamar(cb -> client.cancelarReserva(1000, cb));

        assertNull(r.erro);
        JSONObject enviado = new JSONObject(servidor.takeRequest().getBody().readUtf8());
        assertEquals("cancelarReserva", enviado.getString("acao"));
        assertEquals(1000, enviado.getInt("reservaId"));
    }

    @Test
    public void sucessoFalseDoServidorViraErroComMensagemDoServidor() throws Exception {
        responderComoAppsScript(200,
                "{\"sucesso\":false,\"reservaId\":null,\"mensagem\":\"Projetor ocupado nesse horário.\"}");

        Resposta r = chamar(cb -> client.criarReserva(7, "PAT-001", "2026-09-22", "19:00", "21:00", cb));

        assertEquals("Projetor ocupado nesse horário.", r.erro);
    }

    @Test
    public void paginaHtmlViraErroDeRespostaInvalida() throws Exception {
        responderComoAppsScript(200, "<html>Fazer login</html>");

        Resposta r = chamar(cb -> client.consultarReserva(1000, cb));

        assertEquals("Resposta inválida do servidor.", r.erro);
    }

    @Test
    public void erroHttpViraErro() throws Exception {
        responderComoAppsScript(500, "");

        Resposta r = chamar(cb -> client.consultarReserva(1000, cb));

        assertTrue(r.erro, r.erro.contains("HTTP 500"));
    }

    @Test
    public void dadosInvalidosNemChegamAoServidor() throws Exception {
        Resposta r = chamar(cb -> client.criarReserva(7, "PAT-001", "2026-09-22", "21:00", "19:00", cb));

        assertEquals("O horário de fim deve ser depois do início.", r.erro);
        assertEquals(0, servidor.getRequestCount());
    }

    @Test
    public void semServidorViraErroDeConexao() throws Exception {
        servidor.shutdown();

        Resposta r = chamar(cb -> client.consultarReserva(1000, cb));

        assertEquals("Sem conexão com o servidor. Verifique a internet.", r.erro);
    }

    @Test(expected = IllegalArgumentException.class)
    public void construtorPublicoExigeHttps() {
        new AppsScriptClient("http://script.google.com/macros/s/x/exec", "segredo");
    }

    @Test
    public void listarProfessoresConverteListaEIgnoraLinhasIncompletas() throws Exception {
        responderComoAppsScript(200, "{\"sucesso\":true,\"reservaId\":null,\"mensagem\":\"3 professor(es).\","
                + "\"professores\":["
                + "{\"id\":1,\"nome\":\"Ana Souza\",\"matricula\":\"12345\",\"ativo\":true},"
                + "{\"id\":2,\"nome\":\"Bruno Lima\",\"matricula\":null,\"ativo\":false},"
                + "{\"id\":3,\"nome\":\"\",\"matricula\":null,\"ativo\":true}]}");

        RespostaProfessores r = listar();

        assertNull(r.erro);
        assertEquals(2, r.professores.size());
        assertEquals("Ana Souza", r.professores.get(0).getNome());
        assertEquals("12345", r.professores.get(0).getMatricula());
        assertTrue(r.professores.get(0).isAtivo());
        assertNull(r.professores.get(1).getMatricula());
        assertEquals(false, r.professores.get(1).isAtivo());

        JSONObject enviado = new JSONObject(servidor.takeRequest().getBody().readUtf8());
        assertEquals("listarProfessores", enviado.getString("acao"));
        assertEquals("segredo", enviado.getString("token"));
    }

    @Test
    public void listarProfessoresComTokenErradoViraErro() throws Exception {
        responderComoAppsScript(200,
                "{\"sucesso\":false,\"reservaId\":null,\"mensagem\":\"Terminal não autorizado.\"}");

        assertEquals("Terminal não autorizado.", listar().erro);
    }

    @Test
    public void listarProfessoresSemCampoProfessoresViraRespostaInvalida() throws Exception {
        responderComoAppsScript(200, "{\"sucesso\":true,\"reservaId\":null,\"mensagem\":\"ok\"}");

        assertEquals("Resposta inválida do servidor.", listar().erro);
    }

    // --- apoio ---

    private static class RespostaProfessores {
        List<Professor> professores;
        String erro;
    }

    private RespostaProfessores listar() throws InterruptedException {
        RespostaProfessores r = new RespostaProfessores();
        CountDownLatch fim = new CountDownLatch(1);
        client.listarProfessores(new ProfessoresCallback() {
            @Override
            public void onSucesso(List<Professor> professores) {
                r.professores = professores;
                fim.countDown();
            }

            @Override
            public void onErro(String mensagem) {
                r.erro = mensagem;
                fim.countDown();
            }
        });
        assertTrue("callback não foi chamado", fim.await(5, TimeUnit.SECONDS));
        return r;
    }

    private interface Chamada {
        void executar(ReservaCallback cb);
    }

    private static class Resposta {
        ReservaResultado resultado;
        String erro;
    }

    private static Resposta chamar(Chamada chamada) throws InterruptedException {
        Resposta r = new Resposta();
        CountDownLatch fim = new CountDownLatch(1);
        chamada.executar(new ReservaCallback() {
            @Override
            public void onSucesso(ReservaResultado resultado) {
                r.resultado = resultado;
                fim.countDown();
            }

            @Override
            public void onErro(String mensagem) {
                r.erro = mensagem;
                fim.countDown();
            }
        });
        assertTrue("callback não foi chamado", fim.await(5, TimeUnit.SECONDS));
        return r;
    }
}
