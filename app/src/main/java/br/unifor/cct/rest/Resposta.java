package br.unifor.cct.rest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resposta HTTP com corpo JSON, escrita pelo ServidorHttp. */
public class Resposta {

    private final int status;
    private final String json;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public Resposta(int status, String json) {
        this.status = status;
        this.json = json;
    }

    public Resposta comHeader(String nome, String valor) {
        headers.put(nome, valor);
        return this;
    }

    public int getStatus() {
        return status;
    }

    public String getJson() {
        return json;
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(headers);
    }
}
