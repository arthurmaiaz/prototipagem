package br.unifor.cct.qr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CodigoQrTest {

    @Test
    public void aceitaOPadraoPat() {
        assertEquals("PAT-001", CodigoQr.extrairPatrimonio("PAT-001"));
        assertEquals("PAT-12345", CodigoQr.extrairPatrimonio("PAT-12345"));
    }

    @Test
    public void ignoraEspacosNasPontasEMaiusculas() {
        assertEquals("PAT-001", CodigoQr.extrairPatrimonio("  pat-001 \n"));
    }

    @Test
    public void aceitaOPrefixoDaPoc() {
        assertEquals("PAT-002", CodigoQr.extrairPatrimonio("projetor:PAT-002"));
        assertEquals("PAT-002", CodigoQr.extrairPatrimonio("PROJETOR: pat-002"));
    }

    @Test
    public void recusaONumeroLivreDaPoc() {
        // A PoC grava "projetor:1"; aqui o número precisa estar no padrão PAT.
        assertNull(CodigoQr.extrairPatrimonio("projetor:1"));
        assertNull(CodigoQr.extrairPatrimonio("projetor:P-04"));
    }

    @Test
    public void recusaOQueNaoEProjetor() {
        assertNull(CodigoQr.extrairPatrimonio(null));
        assertNull(CodigoQr.extrairPatrimonio(""));
        assertNull(CodigoQr.extrairPatrimonio("https://exemplo.com/nao-e-projetor"));
        assertNull(CodigoQr.extrairPatrimonio("BEM-VINDO-UNIFOR"));
        assertNull(CodigoQr.extrairPatrimonio("PAT-01"));
        assertNull(CodigoQr.extrairPatrimonio("PAT-001 sala 3"));
        assertNull(CodigoQr.extrairPatrimonio("projetor:"));
    }

    @Test
    public void resultadoGuardaOTextoOriginal() {
        ResultadoQr valido = new ResultadoQr("projetor:PAT-003", ResultadoQr.FORMATO_QR);
        assertTrue(valido.isValido());
        assertEquals("PAT-003", valido.getPatrimonio());
        assertEquals("projetor:PAT-003", valido.getTexto());

        ResultadoQr invalido = new ResultadoQr("https://exemplo.com", ResultadoQr.FORMATO_QR);
        assertFalse(invalido.isValido());
        assertNull(invalido.getPatrimonio());
    }
}
