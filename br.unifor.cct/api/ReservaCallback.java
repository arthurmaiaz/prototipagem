package br.unifor.cct.api;

/**
 * Callback usado pela ReservaApi para não travar a tela enquanto a
 * requisição ao Apps Script está em andamento (seção 4 do documento de
 * divisão de tarefas: "resposta por callback para não travar a tela").
 */
public interface ReservaCallback {

    void onSucesso(ReservaResultado resultado);

    /**
     * Chamado em caso de falha de rede, timeout, ou erro retornado pelo
     * Apps Script. mensagem é sempre não nula e pronta para exibir ao usuário.
     */
    void onErro(String mensagem);
}
