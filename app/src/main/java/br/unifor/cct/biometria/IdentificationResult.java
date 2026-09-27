package br.unifor.cct.biometria;

/**
 * Resultado bruto de uma identificação 1:N feita pelo FingerprintMatcher,
 * antes de virar um ResultadoBiometria (formato exposto pro
 * BiometriaService/UI).
 */
public class IdentificationResult {

    private final boolean encontrado;
    private final Integer professorId;
    private final int score;

    public IdentificationResult(boolean encontrado, Integer professorId, int score) {
        this.encontrado = encontrado;
        this.professorId = professorId;
        this.score = score;
    }

    public boolean isEncontrado() {
        return encontrado;
    }

    public Integer getProfessorId() {
        return professorId;
    }

    /**
     * Score de confiança do match, na escala do SDK biométrico usado.
     * Sem significado definido para os fakes; será relevante na
     * implementação real (NBioBSPJNI).
     */
    public int getScore() {
        return score;
    }
}
