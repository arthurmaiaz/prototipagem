package br.unifor.cct.biometria;

/**
 * Abstrai o leitor de digitais (hardware). Implementação real:
 * HamsterDxReader, usando NBioBSPJNI. Implementação de testes:
 * FakeFingerprintReader.
 */
public interface FingerprintReader {

    void capturar(CapturaCallback callback);

    interface CapturaCallback {
        void onCapturado(FingerprintTemplate template);
        void onErro(String mensagem);
    }
}
