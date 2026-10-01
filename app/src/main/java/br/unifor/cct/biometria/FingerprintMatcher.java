package br.unifor.cct.biometria;

public interface FingerprintMatcher {

    void identificar(FingerprintTemplate template, MatchCallback callback);

    interface MatchCallback {
        void onResultado(IdentificationResult resultado);
        void onErro(String mensagem);
    }
}