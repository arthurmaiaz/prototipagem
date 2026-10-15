package br.unifor.cct.rest;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** Pedido HTTP já lido pelo ServidorHttp. Headers com o nome em minúsculas. */
public class Requisicao {

    private final String metodo;
    private final String caminho;
    private final Map<String, String> query;
    private final Map<String, String> headers;
    private final String corpo;
    private final String ipCliente;

    public Requisicao(String metodo, String caminho, Map<String, String> query,
                      Map<String, String> headers, String corpo, String ipCliente) {
        this.metodo = metodo;
        this.caminho = caminho;
        this.query = Collections.unmodifiableMap(query);
        this.headers = Collections.unmodifiableMap(headers);
        this.corpo = corpo;
        this.ipCliente = ipCliente;
    }

    public String getMetodo() {
        return metodo;
    }

    /** Sem a query string, ex.: "/v1/nfc/read". */
    public String getCaminho() {
        return caminho;
    }

    public String getQuery(String nome) {
        return query.get(nome);
    }

    public String getHeader(String nome) {
        return headers.get(nome.toLowerCase(Locale.ROOT));
    }

    /** Corpo em UTF-8 (vazio se não houver). */
    public String getCorpo() {
        return corpo;
    }

    public String getIpCliente() {
        return ipCliente;
    }
}
