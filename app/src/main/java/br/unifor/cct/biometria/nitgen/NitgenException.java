package br.unifor.cct.biometria.nitgen;

/** Falha do leitor ou do SDK, com mensagem pronta para mostrar ao usuário. */
public class NitgenException extends Exception {

    public NitgenException(String mensagem) {
        super(mensagem);
    }
}
