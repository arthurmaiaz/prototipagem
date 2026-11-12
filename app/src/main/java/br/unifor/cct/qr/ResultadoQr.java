package br.unifor.cct.qr;

/** Uma leitura de QR Code: o texto bruto e o patrimônio que saiu dele. */
public class ResultadoQr {

    /** Lido pela câmera. */
    public static final String FORMATO_QR = "QR_CODE";
    /** Lido de uma imagem da galeria. */
    public static final String FORMATO_IMAGEM = "IMAGEM";
    /** Digitado no botão "Digitar manualmente" da tela. */
    public static final String FORMATO_MANUAL = "MANUAL";

    private final String texto;
    private final String patrimonio;
    private final String formato;

    public ResultadoQr(String texto, String formato) {
        this.texto = texto;
        this.patrimonio = CodigoQr.extrairPatrimonio(texto);
        this.formato = formato;
    }

    /** Texto exatamente como veio do QR Code. */
    public String getTexto() {
        return texto;
    }

    /** Patrimônio no padrão "PAT-001", ou null se o QR não for de projetor. */
    public String getPatrimonio() {
        return patrimonio;
    }

    public boolean isValido() {
        return patrimonio != null;
    }

    public String getFormato() {
        return formato;
    }

    public String getMensagem() {
        return isValido()
                ? "Projetor " + patrimonio + " identificado."
                : "QR Code lido, mas não é de um projetor (use o padrão PAT-001).";
    }
}
