package br.unifor.cct.biometria;

/**
 * Resultado de uma identificaÃ§Ã£o biomÃ©trica 1:N, entregue pelo BiometriaService
 * (Nikolas) para a UI.
 *
 * Contrato combinado na seÃ§Ã£o 4 do documento de divisÃ£o de tarefas.
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
     * Id do professor identificado pelo motor biomÃ©trico (1:N).
     * SÃ³ Ã© vÃ¡lido quando sucesso == true; caso contrÃ¡rio, pode ser null.
     */
    public Integer getProfessorId() {
        return professorId;
    }

    /**
     * Mensagem para exibir ao usuÃ¡rio (erro ou confirmaÃ§Ã£o).
     */
    public String getMensagem() {
        return mensagem;
    }
}
