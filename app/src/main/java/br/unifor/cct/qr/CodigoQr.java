package br.unifor.cct.qr;

import java.util.Locale;

import br.unifor.cct.model.Projetor;

/**
 * Tira o código de patrimônio do texto lido num QR Code.
 *
 * O padrão do app é "PAT-001", o mesmo da tag NFC e do Apps Script. Também
 * aceita o prefixo "projetor:" usado na PoC de QR Code (ex.: "projetor:PAT-001"),
 * mas o número continua tendo que ser PAT: "projetor:1" é recusado.
 *
 * O padrão PAT já recusa o que não é projetor (URL, Pix, texto qualquer), então
 * o prefixo é opcional.
 */
public final class CodigoQr {

    public static final String PREFIXO = "projetor:";

    private CodigoQr() {
    }

    /** @return o patrimônio (ex.: "PAT-001"), ou null se o texto não for de projetor. */
    public static String extrairPatrimonio(String texto) {
        if (texto == null) return null;
        String s = texto.trim();
        if (s.regionMatches(true, 0, PREFIXO, 0, PREFIXO.length())) {
            s = s.substring(PREFIXO.length()).trim();
        }
        s = s.toUpperCase(Locale.ROOT);
        return Projetor.codigoValido(s) ? s : null;
    }
}
