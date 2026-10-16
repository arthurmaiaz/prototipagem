package br.unifor.cct.fluxo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.unifor.cct.api.ReservaApi;
import br.unifor.cct.api.ReservaCallback;
import br.unifor.cct.api.ReservaResultado;
import br.unifor.cct.biometria.BiometriaCallback;
import br.unifor.cct.biometria.ResultadoBiometria;
import br.unifor.cct.fluxo.FluxoReserva.Estado;
import br.unifor.cct.fluxo.FluxoReserva.Etapa;
import br.unifor.cct.fluxo.FluxoReserva.Modo;
import br.unifor.cct.model.Professor;
import br.unifor.cct.nfc.ResultadoNfc;

public class FluxoReservaTest {

    /** 22/09/2026 às 19:00 em Fortaleza. */
    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-09-22T22:00:00Z"),
            ZoneId.of("America/Fortaleza"));

    private BiometriaCallback leituraPendente;
    private final ApiFalsa api = new ApiFalsa();
    private final Map<Integer, Professor> cadastro = new HashMap<>();
    private final List<Estado> estados = new ArrayList<>();
    private FluxoReserva fluxo;

    @Before
    public void setUp() {
        cadastro.put(1, new Professor(1, "Ana Souza", "123", true));
        cadastro.put(2, new Professor(2, "Bruno Lima", null, false));
        fluxo = new FluxoReserva(cb -> leituraPendente = cb, cadastro::get, api, RELOGIO);
        fluxo.setListener(estados::add);
    }

    private Estado atual() {
        return fluxo.getEstado();
    }

    private void digitalDe(Integer professorId) {
        fluxo.lerDigital();
        leituraPendente.onSucesso(new ResultadoBiometria(professorId != null, professorId, ""));
    }

    private void tag(String codigo) {
        fluxo.projetorLido(new ResultadoNfc(true, codigo, "Projetor " + codigo + " identificado."));
    }

    @Test
    public void reservaDePontaAPonta() {
        assertEquals(Etapa.DIGITAL, atual().etapa);
        assertEquals("setListener entrega o estado atual", 1, estados.size());

        fluxo.lerDigital();
        assertEquals(Etapa.IDENTIFICANDO, atual().etapa);
        leituraPendente.onSucesso(new ResultadoBiometria(true, 1, "ok"));
        assertEquals(Etapa.PROJETOR, atual().etapa);
        assertEquals("Ana Souza", atual().professorNome);

        tag("PAT-001");
        assertEquals(Etapa.HORARIO, atual().etapa);
        assertEquals("PAT-001", atual().projetorCodigo);
        assertEquals("2026-09-22", atual().data);
        assertEquals("19:00", atual().inicio);
        assertEquals("21:00", atual().fim);

        fluxo.confirmarHorario("2026-09-22", "19:00", "20:40");
        assertEquals(Etapa.ENVIANDO, atual().etapa);
        assertEquals("criarReserva 1 PAT-001 2026-09-22 19:00 20:40", api.ultimaChamada);

        api.pendente.onSucesso(new ReservaResultado(true, 1000, "Reserva realizada com sucesso."));
        assertEquals(Etapa.CONCLUIDA, atual().etapa);
        assertEquals(Integer.valueOf(1000), atual().reservaId);
        assertEquals("Reserva realizada com sucesso.", atual().mensagem);
        assertFalse(atual().erro);

        fluxo.reiniciar();
        assertEquals(Etapa.DIGITAL, atual().etapa);
        assertNull(atual().professorId);
    }

    @Test
    public void digitalNaoReconhecidaFicaEmDigital() {
        digitalDe(null);
        assertEquals(Etapa.DIGITAL, atual().etapa);
        assertTrue(atual().erro);

        fluxo.lerDigital();
        leituraPendente.onErro("Leitor desconectado.");
        assertEquals(Etapa.DIGITAL, atual().etapa);
        assertEquals("Leitor desconectado.", atual().mensagem);
    }

    @Test
    public void professorInativoNaoPassa() {
        digitalDe(2);
        assertEquals(Etapa.DIGITAL, atual().etapa);
        assertEquals("Bruno Lima está inativo. Procure a coordenação.", atual().mensagem);
    }

    @Test
    public void professorForaDoCadastroLocalSegueComOId() {
        digitalDe(7);
        assertEquals(Etapa.PROJETOR, atual().etapa);
        assertEquals("professor 7", atual().nomeOuId());
    }

    @Test
    public void tagInvalidaOuComErroFicaEmProjetor() {
        digitalDe(1);
        fluxo.projetorLido(new ResultadoNfc(false, null, "Tag vazia."));
        assertEquals(Etapa.PROJETOR, atual().etapa);
        assertEquals("Tag vazia.", atual().mensagem);

        fluxo.informarProjetor("sala 3");
        assertEquals(Etapa.PROJETOR, atual().etapa);
        assertTrue(atual().erro);

        fluxo.informarProjetor(" pat-003 ");
        assertEquals("código do QR Code é normalizado", "PAT-003", atual().projetorCodigo);
    }

    @Test
    public void eventosForaDaEtapaSaoIgnorados() {
        tag("PAT-001");
        assertEquals("tag antes da digital", Etapa.DIGITAL, atual().etapa);
        fluxo.confirmarHorario("2026-09-22", "19:00", "20:00");
        assertNull(api.ultimaChamada);

        digitalDe(1);
        fluxo.lerDigital();
        assertEquals("lerDigital fora da etapa DIGITAL", Etapa.PROJETOR, atual().etapa);
    }

    @Test
    public void horarioInvalidoOuPassadoNaoEnvia() {
        digitalDe(1);
        tag("PAT-001");
        fluxo.confirmarHorario("2026-09-22", "21:00", "19:00");
        assertEquals(Etapa.HORARIO, atual().etapa);
        assertEquals("O horário de fim deve ser depois do início.", atual().mensagem);
        assertEquals("o que foi digitado fica na tela", "21:00", atual().inicio);

        fluxo.confirmarHorario("2026-09-22", "17:00", "18:30");
        assertEquals("Esse horário já passou. Escolha um horário futuro.", atual().mensagem);

        fluxo.confirmarHorario("22/09/2026", "19:00", "20:00");
        assertTrue(atual().erro);
        assertNull(api.ultimaChamada);
    }

    @Test
    public void recusaDoServidorVoltaParaHorario() {
        digitalDe(1);
        tag("PAT-001");
        fluxo.confirmarHorario("2026-09-22", "19:00", "21:00");
        api.pendente.onErro("Projetor PAT-001 já reservado das 19:00 às 21:00 nesse dia.");
        assertEquals(Etapa.HORARIO, atual().etapa);
        assertTrue(atual().erro);
        assertEquals("PAT-001", atual().projetorCodigo);

        fluxo.confirmarHorario("2026-09-22", "21:00", "22:00");
        api.pendente.onSucesso(new ReservaResultado(false, null, "Recusado."));
        assertEquals("sucesso=false do mock também conta como recusa", Etapa.HORARIO, atual().etapa);
    }

    @Test
    public void voltarDesfazUmaEtapa() {
        digitalDe(1);
        tag("PAT-001");
        fluxo.voltar();
        assertEquals(Etapa.PROJETOR, atual().etapa);
        assertNull(atual().projetorCodigo);
        assertEquals("Ana Souza", atual().professorNome);
        fluxo.voltar();
        assertEquals(Etapa.DIGITAL, atual().etapa);
        assertNull(atual().professorId);
    }

    @Test
    public void respostaAtrasadaDepoisDeReiniciarEIgnorada() {
        fluxo.lerDigital();
        BiometriaCallback antiga = leituraPendente;
        fluxo.reiniciar();
        antiga.onSucesso(new ResultadoBiometria(true, 1, "ok"));
        assertEquals(Etapa.DIGITAL, atual().etapa);

        digitalDe(1);
        tag("PAT-001");
        fluxo.confirmarHorario("2026-09-22", "19:00", "21:00");
        ReservaCallback pedido = api.pendente;
        fluxo.reiniciar();
        pedido.onSucesso(new ReservaResultado(true, 1000, "ok"));
        assertEquals(Etapa.DIGITAL, atual().etapa);
    }

    @Test
    public void devolucaoSoPrecisaDaTag() {
        fluxo.iniciar(Modo.DEVOLVER);
        assertEquals(Etapa.PROJETOR, atual().etapa);
        fluxo.lerDigital();
        assertNull("devolução não usa a digital", leituraPendente);

        tag("PAT-001");
        assertEquals(Etapa.ENVIANDO, atual().etapa);
        assertEquals("devolverProjetor PAT-001", api.ultimaChamada);
        api.pendente.onErro("O projetor PAT-001 não tem reserva em uso para devolver.");
        assertEquals(Etapa.PROJETOR, atual().etapa);
        assertNull(atual().projetorCodigo);

        tag("PAT-001");
        api.pendente.onSucesso(new ReservaResultado(true, 1000, "Devolução registrada."));
        assertEquals(Etapa.CONCLUIDA, atual().etapa);
        assertEquals(Integer.valueOf(1000), atual().reservaId);

        fluxo.reiniciar();
        assertEquals("reiniciar mantém o modo", Modo.DEVOLVER, atual().modo);
        fluxo.voltar();
        assertEquals(Etapa.PROJETOR, atual().etapa);
    }

    @Test
    public void sugestaoNaoPassaDaMeiaNoite() {
        Clock tarde = Clock.fixed(Instant.parse("2026-09-23T01:30:00Z"), ZoneId.of("America/Fortaleza"));
        fluxo = new FluxoReserva(cb -> cb.onSucesso(new ResultadoBiometria(true, 1, "")),
                cadastro::get, api, tarde);
        fluxo.lerDigital();
        tag("PAT-001");
        assertEquals("22:30", atual().inicio);
        assertEquals("23:59", atual().fim);
    }

    /** Guarda o último pedido para o teste responder quando quiser. */
    private static class ApiFalsa implements ReservaApi {
        String ultimaChamada;
        ReservaCallback pendente;

        @Override
        public void criarReserva(int professorId, String projetorId, String data,
                                 String inicio, String fim, ReservaCallback callback) {
            ultimaChamada = "criarReserva " + professorId + " " + projetorId + " " + data
                    + " " + inicio + " " + fim;
            pendente = callback;
        }

        @Override
        public void devolverProjetor(String projetorId, ReservaCallback callback) {
            ultimaChamada = "devolverProjetor " + projetorId;
            pendente = callback;
        }

        @Override
        public void cancelarReserva(int reservaId, ReservaCallback callback) {
            throw new AssertionError("não usado no fluxo");
        }

        @Override
        public void registrarDevolucao(int reservaId, ReservaCallback callback) {
            throw new AssertionError("não usado no fluxo");
        }

        @Override
        public void consultarReserva(int reservaId, ReservaCallback callback) {
            throw new AssertionError("não usado no fluxo");
        }
    }
}
