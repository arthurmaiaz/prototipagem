package br.unifor.cct.biometria;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Templates de digital dos professores, guardados só no aparelho.
 *
 * Cada professor tem uma ou mais amostras do mesmo dedo (templates em texto
 * gerados pelo SDK do leitor), ligadas ao mesmo id da aba "professores".
 * Nunca guarda a imagem da digital.
 *
 * O arquivo inteiro é cifrado (AES-GCM, chave no Android Keystore) e fica
 * em noBackupFilesDir, fora do backup automático do Android. É a fonte de
 * verdade: o índice de busca do leitor é refeito a partir daqui.
 *
 * Os métodos fazem leitura e escrita em disco: chame fora da thread
 * principal.
 */
public class BiometricDatabase {

    private static final String ARQUIVO_PADRAO = "digitais.bin";
    private static final String APELIDO_CHAVE = "br.unifor.cct.digitais";
    /** 1: um "template" por professor; 2: lista "templates". A 1 ainda é lida. */
    private static final int VERSAO = 2;

    private final File arquivo;
    private final Cifra cifra;

    private Map<Integer, List<String>> cache;
    private boolean falhaAoCarregar;

    public BiometricDatabase(File arquivo, Cifra cifra) {
        this.arquivo = arquivo;
        this.cifra = cifra;
    }

    /** Banco do app: arquivo em noBackupFilesDir, cifrado com chave do Keystore. */
    public static BiometricDatabase padrao(Context context) throws GeneralSecurityException {
        return new BiometricDatabase(
                new File(context.getNoBackupFilesDir(), ARQUIVO_PADRAO),
                CifraKeystore.obter(APELIDO_CHAVE));
    }

    /** Cópia de todos os cadastros: professorId -> amostras. */
    public synchronized Map<Integer, List<String>> todos() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(carregar()));
    }

    /** @return as amostras do professor, ou null se ele não tiver digital cadastrada. */
    public synchronized List<String> buscar(int professorId) {
        return carregar().get(professorId);
    }

    /** Cadastra ou troca a digital do professor (todas as amostras de uma vez). */
    public synchronized void salvar(int professorId, List<String> amostras) throws IOException {
        if (amostras == null || amostras.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma amostra.");
        }
        for (String a : amostras) {
            if (a == null || a.isEmpty()) {
                throw new IllegalArgumentException("Amostra vazia.");
            }
        }
        Map<Integer, List<String>> novo = new LinkedHashMap<>(carregar());
        novo.put(professorId, Collections.unmodifiableList(new ArrayList<>(amostras)));
        persistir(novo);
    }

    /** @return true se o professor tinha digital cadastrada. */
    public synchronized boolean remover(int professorId) throws IOException {
        if (!carregar().containsKey(professorId)) {
            return false;
        }
        Map<Integer, List<String>> novo = new LinkedHashMap<>(carregar());
        novo.remove(professorId);
        persistir(novo);
        return true;
    }

    /**
     * true se havia um arquivo que não pôde ser lido (ex.: a chave do
     * Keystore foi apagada ou o arquivo foi alterado). Nesse caso o banco
     * começa vazio e as digitais precisam ser cadastradas de novo.
     */
    public synchronized boolean houveFalhaAoCarregar() {
        carregar();
        return falhaAoCarregar;
    }

    private Map<Integer, List<String>> carregar() {
        if (cache != null) {
            return cache;
        }
        cache = new LinkedHashMap<>();
        if (!arquivo.exists()) {
            return cache;
        }
        try {
            byte[] claro = cifra.decifrar(Files.readAllBytes(arquivo.toPath()));
            JSONArray digitais = new JSONObject(new String(claro, StandardCharsets.UTF_8))
                    .getJSONArray("digitais");
            for (int i = 0; i < digitais.length(); i++) {
                JSONObject d = digitais.getJSONObject(i);
                List<String> amostras = new ArrayList<>();
                if (d.has("templates")) {
                    JSONArray lista = d.getJSONArray("templates");
                    for (int j = 0; j < lista.length(); j++) {
                        amostras.add(lista.getString(j));
                    }
                } else {
                    amostras.add(d.getString("template"));
                }
                cache.put(d.getInt("professorId"), Collections.unmodifiableList(amostras));
            }
        } catch (IOException | GeneralSecurityException | JSONException e) {
            cache.clear();
            falhaAoCarregar = true;
        }
        return cache;
    }

    /** Grava num arquivo temporário e troca no fim, para nunca deixar um arquivo pela metade. */
    private void persistir(Map<Integer, List<String>> novo) throws IOException {
        byte[] cifrado;
        try {
            JSONArray digitais = new JSONArray();
            for (Map.Entry<Integer, List<String>> e : novo.entrySet()) {
                digitais.put(new JSONObject()
                        .put("professorId", e.getKey())
                        .put("templates", new JSONArray(e.getValue())));
            }
            String json = new JSONObject().put("versao", VERSAO).put("digitais", digitais).toString();
            cifrado = cifra.cifrar(json.getBytes(StandardCharsets.UTF_8));
        } catch (JSONException | GeneralSecurityException e) {
            throw new IOException("Falha ao cifrar as digitais.", e);
        }

        File temporario = new File(arquivo.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporario)) {
            out.write(cifrado);
            out.getFD().sync();
        }
        try {
            try {
                Files.move(temporario.toPath(), arquivo.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporario.toPath(), arquivo.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            temporario.delete();
            throw new IOException("Falha ao gravar o arquivo de digitais.", e);
        }
        cache = novo;
        falhaAoCarregar = false;
    }
}
