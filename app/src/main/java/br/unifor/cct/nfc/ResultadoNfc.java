package br.unifor.cct.nfc;

import java.util.Collections;
import java.util.List;

/**
 * Resultado de uma leitura NFC, entregue pelo NfcService (Arthur) para a UI.
 *
 * Contrato combinado na seção 4 do documento de divisão de tarefas.
 * Além do código do projetor, traz o que veio na tag (textos e URLs NDEF,
 * id e tecnologias), usado pela API REST (pacote rest).
 */
public class ResultadoNfc {

    private final boolean sucesso;
    private final String codigoProjetor;
    private final String mensagem;
    private final List<String> textos;
    private final List<String> urls;
    private final String tagId;
    private final List<String> tecnologias;

    public ResultadoNfc(boolean sucesso, String codigoProjetor, String mensagem) {
        this(sucesso, codigoProjetor, mensagem, null, null, null, null);
    }

    public ResultadoNfc(boolean sucesso, String codigoProjetor, String mensagem,
                        List<String> textos, List<String> urls,
                        String tagId, List<String> tecnologias) {
        this.sucesso = sucesso;
        this.codigoProjetor = codigoProjetor;
        this.mensagem = mensagem;
        this.textos = imutavel(textos);
        this.urls = imutavel(urls);
        this.tagId = tagId;
        this.tecnologias = imutavel(tecnologias);
    }

    private static List<String> imutavel(List<String> lista) {
        return lista == null ? Collections.emptyList() : Collections.unmodifiableList(lista);
    }

    public boolean isSucesso() {
        return sucesso;
    }

    /**
     * Código do patrimônio do projetor lido na tag (ex.: "PAT-001").
     * Só é válido quando sucesso == true.
     */
    public String getCodigoProjetor() {
        return codigoProjetor;
    }

    /**
     * Mensagem para exibir ao usuário (erro ou confirmação).
     */
    public String getMensagem() {
        return mensagem;
    }

    /** Todos os records de texto (RTD_TEXT) da tag, mesmo os que não são código de projetor. */
    public List<String> getTextos() {
        return textos;
    }

    /** Todos os records de URI/URL da tag. */
    public List<String> getUrls() {
        return urls;
    }

    /** Id (UID) da tag em hexadecimal, ou null se a tag não chegou a ser lida. */
    public String getTagId() {
        return tagId;
    }

    /** Tecnologias da tag, ex.: "NfcA", "Ndef" (sem o pacote android.nfc.tech). */
    public List<String> getTecnologias() {
        return tecnologias;
    }
}
