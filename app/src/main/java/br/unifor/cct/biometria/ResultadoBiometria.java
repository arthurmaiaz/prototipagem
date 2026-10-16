package br.unifor.cct.biometria;

/**
 * Resultado de uma identificação biométrica 1:N, entregue pelo
 * BiometriaService para a UI.
 *
 * Contrato combinado na seção 4 do documento de divisão de tarefas, com o
 * e-mail no lugar do id numérico do professor.
 */
public class ResultadoBiometria {

    private final boolean sucesso;
    private final String professorEmail;
    private final String mensagem;

    public ResultadoBiometria(boolean sucesso, String professorEmail, String mensagem) {
        this.sucesso = sucesso;
        this.professorEmail = professorEmail;
        this.mensagem = mensagem;
    }

    public boolean isSucesso() {
        return sucesso;
    }

    /**
     * E-mail do professor identificado (o mesmo da aba professores).
     * Só é válido quando sucesso == true; caso contrário, pode ser null.
     */
    public String getProfessorEmail() {
        return professorEmail;
    }

    /** Mensagem para exibir ao usuário (erro ou confirmação). */
    public String getMensagem() {
        return mensagem;
    }
}
