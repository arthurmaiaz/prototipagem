package br.unifor.cct.model;

import java.util.regex.Pattern;

/**
 * Projetor identificado pelo código de patrimônio gravado na tag NFC
 * ou no QR Code (padrão "PAT-001").
 */
public class Projetor {

    private static final Pattern PADRAO_CODIGO = Pattern.compile("PAT-\\d{3,}");

    private final String codigo;
    private final String descricao;
    private final boolean ativo;

    public Projetor(String codigo, String descricao, boolean ativo) {
        if (!codigoValido(codigo)) {
            throw new IllegalArgumentException("Código de projetor inválido: " + codigo);
        }
        this.codigo = codigo;
        this.descricao = descricao;
        this.ativo = ativo;
    }

    /**
     * Confere se o texto segue o padrão de patrimônio (ex.: "PAT-001").
     * Útil para validar o que veio da tag NFC ou do QR Code antes de
     * chamar a ReservaApi.
     */
    public static boolean codigoValido(String codigo) {
        return codigo != null && PADRAO_CODIGO.matcher(codigo).matches();
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescricao() {
        return descricao;
    }

    /**
     * Projetor inativo (em manutenção, baixado etc.) não pode ser reservado.
     */
    public boolean isAtivo() {
        return ativo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Projetor)) return false;
        return codigo.equals(((Projetor) o).codigo);
    }

    @Override
    public int hashCode() {
        return codigo.hashCode();
    }

    @Override
    public String toString() {
        return "Projetor{" + codigo + (ativo ? "" : ", inativo") + "}";
    }
}
