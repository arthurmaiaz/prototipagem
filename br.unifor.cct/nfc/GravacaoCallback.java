package br.unifor.cct.nfc;

/**
 * Recebe o resultado da gravação de uma tag (modo de cadastro).
 * Sempre chamado na thread principal.
 */
public interface GravacaoCallback {

    /**
     * @param sucesso  true se a tag foi gravada
     * @param mensagem texto pronto para exibir ao usuário
     */
    void onGravado(boolean sucesso, String mensagem);
}
