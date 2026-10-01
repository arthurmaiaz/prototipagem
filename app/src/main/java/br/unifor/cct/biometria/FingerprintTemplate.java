package br.unifor.cct.biometria;

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