package br.unifor.cct.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import br.unifor.cct.admin.PinAdministrador.Verificacao;

public class PinAdministradorTest {

    @Rule
    public TemporaryFolder pasta = new TemporaryFolder();

    private File arquivo;
    private RelogioAjustavel relogio;

    @Before
    public void setUp() {
        arquivo = new File(pasta.getRoot(), "pin.properties");
        relogio = new RelogioAjustavel(Instant.parse("2026-10-16T12:00:00Z"));
    }

    private PinAdministrador novo() {
        return new PinAdministrador(arquivo, relogio);
    }

    @Test
    public void semPinAteDefinir() throws Exception {
        PinAdministrador pin = novo();
        assertFalse(pin.temPin());
        assertFalse(pin.liberado());
        assertEquals(Verificacao.SEM_PIN, pin.verificar("123456"));

        pin.definir("123456");
        assertTrue(pin.temPin());
        assertTrue("quem acabou de definir já fica liberado", pin.liberado());
    }

    @Test
    public void pinNaoFicaNoArquivo() throws Exception {
        novo().definir("482913");
        String conteudo = new String(Files.readAllBytes(arquivo.toPath()), StandardCharsets.UTF_8);
        assertFalse(conteudo.contains("482913"));
    }

    @Test
    public void verificaDepoisDeReabrir() throws Exception {
        novo().definir("123456");
        PinAdministrador reaberto = novo();
        assertTrue(reaberto.temPin());
        assertFalse("a liberação não sobrevive a reabrir", reaberto.liberado());
        assertEquals(Verificacao.INCORRETO, reaberto.verificar("654321"));
        assertEquals(Verificacao.CORRETO, reaberto.verificar("123456"));
        assertTrue(reaberto.liberado());
    }

    @Test
    public void formato() {
        assertTrue(PinAdministrador.formatoValido("123456"));
        assertTrue(PinAdministrador.formatoValido("12345678"));
        assertFalse(PinAdministrador.formatoValido("12345"));
        assertFalse(PinAdministrador.formatoValido("123456789"));
        assertFalse(PinAdministrador.formatoValido("12345a"));
        assertFalse(PinAdministrador.formatoValido(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void recusaPinCurto() throws Exception {
        novo().definir("1234");
    }

    @Test(expected = IllegalStateException.class)
    public void naoRedefineSemOAtual() throws Exception {
        PinAdministrador pin = novo();
        pin.definir("123456");
        pin.definir("999999");
    }

    @Test
    public void liberacaoExpiraERenova() throws Exception {
        PinAdministrador pin = novo();
        pin.definir("123456");
        relogio.avancar(Duration.ofMinutes(4));
        pin.renovar();
        relogio.avancar(Duration.ofMinutes(4));
        assertTrue("renovar conta de novo a partir de agora", pin.liberado());
        relogio.avancar(Duration.ofMinutes(2));
        assertFalse(pin.liberado());
        pin.renovar();
        assertFalse("renovar não libera quem já expirou", pin.liberado());
    }

    @Test
    public void encerrarTiraALiberacao() throws Exception {
        PinAdministrador pin = novo();
        pin.definir("123456");
        pin.encerrar();
        assertFalse(pin.liberado());
    }

    @Test
    public void bloqueiaDepoisDeCincoErrosMesmoReabrindo() throws Exception {
        novo().definir("123456");
        PinAdministrador pin = novo();
        for (int i = 1; i < PinAdministrador.MAX_TENTATIVAS; i++) {
            assertEquals(Verificacao.INCORRETO, pin.verificar("000000"));
        }
        assertEquals(1, pin.tentativasRestantes());
        assertEquals(Verificacao.BLOQUEADO, pin.verificar("000000"));

        PinAdministrador reaberto = novo();
        assertTrue(reaberto.bloqueado());
        assertEquals("nem o PIN certo passa durante o bloqueio",
                Verificacao.BLOQUEADO, reaberto.verificar("123456"));
        assertEquals(Duration.ofMinutes(5), reaberto.tempoBloqueioRestante());

        relogio.avancar(Duration.ofMinutes(5));
        assertFalse(reaberto.bloqueado());
        assertEquals(PinAdministrador.MAX_TENTATIVAS, reaberto.tentativasRestantes());
        assertEquals(Verificacao.CORRETO, reaberto.verificar("123456"));
    }

    @Test
    public void acertoZeraOsErros() throws Exception {
        PinAdministrador pin = novo();
        pin.definir("123456");
        pin.verificar("000000");
        pin.verificar("000000");
        pin.verificar("123456");
        assertEquals(PinAdministrador.MAX_TENTATIVAS, pin.tentativasRestantes());
    }

    @Test
    public void trocarExigeOAtual() throws Exception {
        PinAdministrador pin = novo();
        pin.definir("123456");
        assertEquals(Verificacao.INCORRETO, pin.trocar("111111", "222222"));
        assertEquals(Verificacao.CORRETO, pin.trocar("123456", "222222"));

        PinAdministrador reaberto = novo();
        assertEquals(Verificacao.INCORRETO, reaberto.verificar("123456"));
        assertEquals(Verificacao.CORRETO, reaberto.verificar("222222"));
    }

    @Test
    public void arquivoSemHashContaComoSemPin() throws Exception {
        Files.write(arquivo.toPath(), "falhas=2\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(novo().temPin());
    }

    /** Clock que o teste avança à mão. */
    private static class RelogioAjustavel extends Clock {
        private Instant agora;

        RelogioAjustavel(Instant agora) {
            this.agora = agora;
        }

        void avancar(Duration d) {
            agora = agora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
