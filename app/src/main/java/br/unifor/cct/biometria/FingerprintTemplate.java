package br.unifor.cct.biometria;

/**
 * Template biométrico capturado pelo leitor (FingerprintReader).
 *
 * É um dado opaco: o formato interno depende do SDK usado (NBioBSPJNI da
 * Nitgen, na implementação real). O FingerprintMatcher é quem sabe
 * interpretar/comparar os bytes.
 */
public class FingerprintTemplate {

    private final byte[] dados;

    public FingerprintTemplate(byte[] dados) {
        this.dados = dados;
    }

    public byte[] getDados() {
        return dados;
    }

    @Override
    public String toString() {
        return "FingerprintTemplate{" + (dados == null ? 0 : dados.length) + " bytes}";
    }
}
