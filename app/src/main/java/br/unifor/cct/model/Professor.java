package br.unifor.cct.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Professor cadastrado no terminal.
 *
 * O e-mail identifica o professor em todo o sistema: é o que a
 * identificação biométrica devolve (ResultadoBiometria) e o que vai para o
 * Apps Script (coluna email da aba professores). Fica sempre normalizado
 * (sem espaços nas pontas, em minúsculas).
 *
 * Esta classe NÃO guarda nenhum dado biométrico: o template da digital fica
 * só no BiometricDatabase (pacote biometria), por causa da LGPD.
 */
public class Professor {

    /** Mesmo padrão do PADRAO_EMAIL do Apps Script. */
    private static final Pattern PADRAO_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final String email;
    private final String nome;
    private final String matricula;
    private final boolean ativo;

    public Professor(String email, String nome, String matricula, boolean ativo) {
        String normalizado = normalizarEmail(email);
        if (!emailValido(normalizado)) {
            throw new IllegalArgumentException("E-mail do professor inválido: " + email);
        }
        if (nome == null || nome.trim().isEmpty()) {
            throw new IllegalArgumentException("Nome do professor é obrigatório.");
        }
        this.email = normalizado;
        this.nome = nome.trim();
        this.matricula = matricula;
        this.ativo = ativo;
    }

    /** "  Ana@Unifor.BR " vira "ana@unifor.br". null vira "". */
    public static String normalizarEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Confere o formato (depois de normalizar). */
    public static boolean emailValido(String email) {
        return PADRAO_EMAIL.matcher(normalizarEmail(email)).matches();
    }

    public String getEmail() {
        return email;
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
        return email.equals(((Professor) o).email);
    }

    @Override
    public int hashCode() {
        return email.hashCode();
    }

    @Override
    public String toString() {
        return "Professor{" + email + ", " + nome + (ativo ? "" : ", inativo") + "}";
    }
}
