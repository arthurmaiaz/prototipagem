package br.unifor.cct.api;

/**
 * Espelha a resposta JSON do Google Apps Script (seção 12/14 do documento
 * de arquitetura): { "sucesso": ..., "reservaId": ..., "mensagem": ... }
 */
public class ReservaResultado {

    private final boolean sucesso;
    private final Integer reservaId;
    private final String mensagem;

    public ReservaResultado(boolean sucesso, Integer reservaId, String mensagem) {
        this.sucesso = sucesso;
        this.reservaId = reservaId;
        this.mensagem = mensagem;
    }

    public boolean isSucesso() {
        return sucesso;
    }

    public Integer getReservaId() {
        return reservaId;
    }

    public String getMensagem() {
        return mensagem;
    }
}
