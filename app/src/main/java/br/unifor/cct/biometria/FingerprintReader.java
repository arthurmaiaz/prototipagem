package br.unifor.cct.biometria;

public interface FingerprintReader {

    void capturar(CapturaCallback callback);

    interface CapturaCallback {
        void onCapturado(FingerprintTemplate template);
        void onErro(String mensagem);
    }
}