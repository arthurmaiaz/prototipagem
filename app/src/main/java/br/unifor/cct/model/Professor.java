package br.unifor.cct.model;

import java.util.Objects;

/**
 * Professor cadastrado no terminal.
 *
 * O id é o mesmo professorId devolvido pela identificação biométrica
 * (ResultadoBiometria) e enviado ao Apps Script. Esta classe NÃO guarda
 * nenhum dado biométrico: o template da digital fica só no
 * BiometricDatabase (pacote biometria), por causa da LGPD.
 */
public class Professor {

    private final int id;
    private final String nome;
    private final String matricula;
    private final boolean ativo;

    public Professor(int id, String nome, String matricula, boolean ativo) {
        if (nome == null || nome.trim().isEmpty()) {
            throw new IllegalArgumentException("Nome do professor é obrigatório.");
        }
        this.id = id;
        this.nome = nome.trim();
        this.matricula = matricula;
        this.ativo = ativo;
    }

    public int getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getMatricula() {
        return matricula;
    }

    /**
     * Professor inativo continua no cadastro (para o histórico de reservas),
     * mas não pode fazer novas reservas.
     */
    public boolean isAtivo() {
        return ativo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Professor)) return false;
        return id == ((Professor) o).id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return "Professor{" + id + ", " + nome + (ativo ? "" : ", inativo") + "}";
    }
}
