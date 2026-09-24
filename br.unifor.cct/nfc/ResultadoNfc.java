package br.unifor.cct.nfc;

/**
 * Resultado de uma leitura NFC, entregue pelo NfcService (Arthur) para a UI.
 *
 * Contrato combinado na seção 4 do documento de divisão de tarefas.
 */
public class ResultadoNfc {

    private final boolean sucesso;
    private final String codigoProjetor;
    private final String mensagem;

    public ResultadoNfc(boolean sucesso, String codigoProjetor, String mensagem) {
        this.sucesso = sucesso;
        this.codigoProjetor = codigoProjetor;
        this.mensagem = mensagem;
    }

    public boolean isSucesso() {
        return sucesso;
    }

    /**
     * Código do patrimônio do projetor lido na tag (ex.: "PAT-001").
     * Só é válido quando sucesso == true.
     */
    public String getCodigoProjetor() {
        return codigoProjetor;
    }

    /**
     * Mensagem para exibir ao usuário (erro ou confirmação).
     */
    public String getMensagem() {
        return mensagem;
    }
}
