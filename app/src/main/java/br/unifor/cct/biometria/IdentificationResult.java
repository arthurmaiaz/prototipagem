package br.unifor.cct.biometria;

/** Resultado do FingerprintMatcher: de quem é a digital lida, se de alguém. */
public class IdentificationResult {

    private final boolean encontrado;
    private final String professorEmail;
    private final int score;

    public IdentificationResult(boolean encontrado, String professorEmail, int score) {
        this.encontrado = encontrado;
        this.professorEmail = professorEmail;
        this.score = score;
    }

    public boolean isEncontrado() {
        return encontrado;
    }

    /** E-mail do professor dono da digital; null se não foi encontrado. */
    public String getProfessorEmail() {
        return professorEmail;
    }

    public int getScore() {
        return score;
    }
}
