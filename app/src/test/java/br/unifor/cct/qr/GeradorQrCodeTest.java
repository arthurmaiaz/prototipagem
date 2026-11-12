package br.unifor.cct.qr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** Critério de aceite da PoC: gerar o QR do patrimônio e recuperar o mesmo valor na leitura. */
public class GeradorQrCodeTest {

    @Test
    public void geraELeDeVoltaOMesmoPatrimonio() throws Exception {
        for (String codigo : Arrays.asList("PAT-001", "PAT-002", "PAT-12345", "projetor:PAT-003")) {
            int[] pixels = GeradorQrCode.gerarPixels(codigo);
            assertEquals(codigo, DecodificadorQr.decodificar(
                    pixels, GeradorQrCode.TAMANHO_PADRAO, GeradorQrCode.TAMANHO_PADRAO));
        }
    }

    @Test
    public void leituraDeVoltaPassaNaValidacaoDoApp() throws Exception {
        int[] pixels = GeradorQrCode.gerarPixels("PAT-001", 300);
        ResultadoQr lido = new ResultadoQr(DecodificadorQr.decodificar(pixels, 300, 300), ResultadoQr.FORMATO_IMAGEM);
        assertTrue(lido.isValido());
        assertEquals("PAT-001", lido.getPatrimonio());
    }

    @Test
    public void imagemSoDePretoEBranco() throws Exception {
        int[] pixels = GeradorQrCode.gerarPixels("PAT-001", 120);
        assertEquals(120 * 120, pixels.length);
        for (int p : pixels) {
            assertTrue(p == 0xFF000000 || p == 0xFFFFFFFF);
        }
    }

    @Test
    public void imagemSemQrCodeDevolveNulo() {
        int[] branco = new int[200 * 200];
        Arrays.fill(branco, 0xFFFFFFFF);
        assertNull(DecodificadorQr.decodificar(branco, 200, 200));
    }

    @Test(expected = IllegalArgumentException.class)
    public void conteudoVazioEhRecusado() throws Exception {
        GeradorQrCode.gerarPixels("");
    }

    @Test
    public void amostraReduzFotoGrande() {
        assertEquals(1, LeitorQrImagem.calcularAmostra(1600, 1200));
        assertEquals(2, LeitorQrImagem.calcularAmostra(3200, 2400));
        assertEquals(4, LeitorQrImagem.calcularAmostra(4000, 3000));
        assertEquals(1, LeitorQrImagem.calcularAmostra(600, 600));
    }
}
