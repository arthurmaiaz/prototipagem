package br.unifor.cct.biometria;

/**
 * Resultado de uma identificação biométrica 1:N, entregue pelo BiometriaService
 * (Nikolas) para a UI.
 *
 * Contrato combinado na seção 4 do documento de divisão de tarefas.
 */
public class ResultadoBiometria {

    private final boolean sucesso;
    private final Integer professorId;
    private final String mensagem;

    public ResultadoBiometria(boolean sucesso, Integer professorId, String mensagem) {
        this.sucesso = sucesso;
        this.professorId = professorId;
        this.mensagem = mensagem;
    }

    public boolean isSucesso() {
        return sucesso;
    }

    /**
     * Id do professor identificado pelo motor biométrico (1:N).
     * Só é válido quando sucesso == true; caso contrário, pode ser null.
     */
    public Integer getProfessorId() {
        return professorId;
    }

    /**
     * Mensagem para exibir ao usuário (erro ou confirmação).
     */
    public String getMensagem() {
        return mensagem;
    }
}
