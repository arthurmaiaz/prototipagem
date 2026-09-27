package br.unifor.cct.biometria;

/**
 * Abstrai o motor de identificação 1:N (compara um template capturado
 * contra a base de professores cadastrados). A implementação real fará
 * essa comparação via NBioBSPJNI; a fake devolve um resultado simulado.
 */
public interface FingerprintMatcher {

    void identificar(FingerprintTemplate template, MatchCallback callback);

    interface MatchCallback {
        void onResultado(IdentificationResult resultado);
        void onErro(String mensagem);
    }
}
