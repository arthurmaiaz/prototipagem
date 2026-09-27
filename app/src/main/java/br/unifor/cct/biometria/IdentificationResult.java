package br.unifor.cct.biometria;

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

    public int getScore() {
        return score;
    }
}