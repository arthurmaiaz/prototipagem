package br.unifor.cct.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.unifor.cct.model.Professor;

/**
 * Cadastro local dos professores do terminal (id, nome, matrícula, ativo).
 *
 * Serve para a UI mostrar o nome de quem foi identificado pela biometria
 * sem precisar ir ao servidor. A fonte oficial é a aba "professores" do
 * Google Sheets: use substituirTodos() para sincronizar com ela.
 *
 * Nada de biometria aqui: o vínculo digital -> professorId fica no
 * BiometricDatabase. Os dados ficam em SharedPreferences privado do app
 * (MODE_PRIVATE), num único JSON.
 *
 * Os métodos são síncronos; o volume (dezenas de professores) é pequeno o
 * bastante para chamar na thread principal.
 */
public class ProfessorRepository {

    private static final String PREFS = "professores";
    private static final String CHAVE_LISTA = "lista";

    private final SharedPreferences prefs;

    /** Cache em memória, carregado do disco no primeiro acesso. */
    private Map<Integer, Professor> cache;

    public ProfessorRepository(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @return o professor, ou null se o id não estiver cadastrado. */
    public synchronized Professor buscarPorId(int id) {
        return carregar().get(id);
    }

    /** @return todos os professores, na ordem em que foram cadastrados. */
    public synchronized List<Professor> listar() {
        return Collections.unmodifiableList(new ArrayList<>(carregar().values()));
    }

    /** Insere ou atualiza (pelo id). */
    public synchronized void salvar(Professor professor) {
        carregar().put(professor.getId(), professor);
        persistir();
    }

    /** @return true se havia um professor com esse id. */
    public synchronized boolean remover(int id) {
        boolean removido = carregar().remove(id) != null;
        if (removido) {
            persistir();
        }
        return removido;
    }

    /** Troca o cadastro inteiro, ex.: após baixar a lista do Apps Script. */
    public synchronized void substituirTodos(List<Professor> professores) {
        Map<Integer, Professor> novo = new LinkedHashMap<>();
        for (Professor p : professores) {
            novo.put(p.getId(), p);
        }
        cache = novo;
        persistir();
    }

    private Map<Integer, Professor> carregar() {
        if (cache != null) {
            return cache;
        }
        cache = new LinkedHashMap<>();
        String json = prefs.getString(CHAVE_LISTA, null);
        if (json == null) {
            return cache;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                Professor p = deJson(array.getJSONObject(i));
                cache.put(p.getId(), p);
            }
        } catch (JSONException | IllegalArgumentException e) {
            // Dado corrompido: começa vazio em vez de derrubar o app.
            // A próxima sincronização com o servidor repõe a lista.
            cache.clear();
        }
        return cache;
    }

    private void persistir() {
        JSONArray array = new JSONArray();
        try {
            for (Professor p : cache.values()) {
                array.put(paraJson(p));
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Falha ao serializar professores.", e);
        }
        prefs.edit().putString(CHAVE_LISTA, array.toString()).apply();
    }

    private static JSONObject paraJson(Professor p) throws JSONException {
        return new JSONObject()
                .put("id", p.getId())
                .put("nome", p.getNome())
                .put("matricula", p.getMatricula() == null ? JSONObject.NULL : p.getMatricula())
                .put("ativo", p.isAtivo());
    }

    private static Professor deJson(JSONObject o) throws JSONException {
        return new Professor(
                o.getInt("id"),
                o.getString("nome"),
                o.isNull("matricula") ? null : o.getString("matricula"),
                o.optBoolean("ativo", true));
    }
}
