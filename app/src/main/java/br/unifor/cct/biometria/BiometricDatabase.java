package br.unifor.cct.biometria;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Templates de digital dos professores, guardados só no aparelho.
 *
 * As digitais ficam num array de tamanho fixo (capacidade()): cada posição
 * está livre (null) ou guarda o cadastro de um professor, com o id da aba
 * "professores" e uma ou mais amostras do mesmo dedo (templates em texto
 * gerados pelo SDK do leitor). Nunca guarda a imagem da digital.
 *
 * - Cadastro: percorre o array até achar a primeira posição livre e grava
 *   ali (ou reaproveita a posição do professor, se ele já tinha digital).
 * - Identificação: percorre o array posição por posição, comparando a
 *   digital lida com as amostras de cada uma (identificar).
 *
 * O arquivo inteiro é cifrado (AES-GCM, chave no Android Keystore) e fica
 * em noBackupFilesDir, fora do backup automático do Android.
 *
 * Os métodos fazem leitura e escrita em disco: chame fora da thread
 * principal.
 */
public class BiometricDatabase {

    /** Quantas digitais cabem no aparelho. */
    public static final int CAPACIDADE_PADRAO = 100;

    private static final String ARQUIVO_PADRAO = "digitais.bin";
    private static final String APELIDO_CHAVE = "br.unifor.cct.digitais";
    /**
     * 1: um "template" por professor; 2: lista "templates"; 3: com a
     * "posicao" no array. As versões 1 e 2 ainda são lidas: cada professor
     * vai para a primeira posição livre.
     */
    private static final int VERSAO = 3;

    /** Uma posição ocupada do array. */
    public static final class Registro {

        private final int posicao;
        private final int professorId;
        private final List<String> amostras;

        Registro(int posicao, int professorId, List<String> amostras) {
            this.posicao = posicao;
            this.professorId = professorId;
            this.amostras = Collections.unmodifiableList(new ArrayList<>(amostras));
        }

        public int getPosicao() {
            return posicao;
        }

        public int getProfessorId() {
            return professorId;
        }

        public List<String> getAmostras() {
            return amostras;
        }
    }

    /** Compara a digital lida com uma amostra guardada (ex.: VerifyMatch do SDK). */
    public interface Comparador<E extends Exception> {
        boolean mesmaDigital(String amostra) throws E;
    }

    private final File arquivo;
    private final Cifra cifra;
    private final int capacidade;

    /** posição -> cadastro; null = posição livre. */
    private Registro[] posicoes;
    private boolean falhaAoCarregar;

    public BiometricDatabase(File arquivo, Cifra cifra) {
        this(arquivo, cifra, CAPACIDADE_PADRAO);
    }

    public BiometricDatabase(File arquivo, Cifra cifra, int capacidade) {
        if (capacidade <= 0) {
            throw new IllegalArgumentException("Capacidade deve ser maior que zero.");
        }
        this.arquivo = arquivo;
        this.cifra = cifra;
        this.capacidade = capacidade;
    }

    /** Banco do app: arquivo em noBackupFilesDir, cifrado com chave do Keystore. */
    public static BiometricDatabase padrao(Context context) throws GeneralSecurityException {
        return new BiometricDatabase(
                new File(context.getNoBackupFilesDir(), ARQUIVO_PADRAO),
                CifraKeystore.obter(APELIDO_CHAVE));
    }

    public int capacidade() {
        return capacidade;
    }

    /** Quantas posições estão ocupadas. */
    public synchronized int quantidade() {
        int total = 0;
        for (Registro r : carregar()) {
            if (r != null) total++;
        }
        return total;
    }

    /** @return a primeira posição livre do array, ou -1 se estiver cheio. */
    public synchronized int posicaoLivre() {
        return primeiraLivre(carregar());
    }

    /** @return a posição do professor no array, ou -1 se ele não tiver digital. */
    public synchronized int posicaoDo(int professorId) {
        Registro[] array = carregar();
        for (int i = 0; i < array.length; i++) {
            if (array[i] != null && array[i].professorId == professorId) return i;
        }
        return -1;
    }

    /** @return as amostras do professor, ou null se ele não tiver digital cadastrada. */
    public synchronized List<String> buscar(int professorId) {
        int posicao = posicaoDo(professorId);
        return posicao < 0 ? null : posicoes[posicao].amostras;
    }

    /** @return o cadastro guardado na posição, ou null se ela estiver livre. */
    public synchronized Registro naPosicao(int posicao) {
        Registro[] array = carregar();
        return posicao >= 0 && posicao < array.length ? array[posicao] : null;
    }

    /**
     * Cadastra ou troca a digital do professor (todas as amostras de uma vez).
     * Se ele já tinha digital, fica na mesma posição; senão vai para a
     * primeira posição livre.
     *
     * @return a posição do array onde a digital ficou
     * @throws IllegalStateException se o array estiver cheio
     */
    public synchronized int salvar(int professorId, List<String> amostras) throws IOException {
        if (amostras == null || amostras.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma amostra.");
        }
        for (String a : amostras) {
            if (a == null || a.isEmpty()) {
                throw new IllegalArgumentException("Amostra vazia.");
            }
        }
        int posicao = posicaoDo(professorId);
        if (posicao < 0) {
            posicao = posicaoLivre();
        }
        if (posicao < 0) {
            throw new IllegalStateException(
                    "Não há posição livre: as " + capacidade + " digitais já estão em uso.");
        }
        Registro[] novo = carregar().clone();
        novo[posicao] = new Registro(posicao, professorId, amostras);
        persistir(novo);
        return posicao;
    }

    /** Libera a posição do professor. @return true se ele tinha digital cadastrada. */
    public synchronized boolean remover(int professorId) throws IOException {
        int posicao = posicaoDo(professorId);
        if (posicao < 0) {
            return false;
        }
        Registro[] novo = carregar().clone();
        novo[posicao] = null;
        persistir(novo);
        return true;
    }

    /**
     * Percorre o array inteiro comparando a digital lida com as amostras de
     * cada posição ocupada.
     *
     * Não para no primeiro acerto: se a digital conferir com mais de um
     * professor, a lista traz todos, e quem chamou decide (o NitgenMatcher
     * recusa, para não confundir professores).
     *
     * @return os ids dos professores cuja digital conferiu, na ordem das
     *         posições (vazia se ninguém conferiu)
     */
    public <E extends Exception> List<Integer> identificar(Comparador<E> comparador) throws E {
        Registro[] array;
        synchronized (this) {
            array = carregar().clone();
        }
        // As comparações rodam fora do lock: no SDK cada uma leva alguns ms.
        List<Integer> encontrados = new ArrayList<>();
        for (Registro registro : array) {
            if (registro == null) continue;
            for (String amostra : registro.amostras) {
                if (comparador.mesmaDigital(amostra)) {
                    encontrados.add(registro.professorId);
                    break;
                }
            }
        }
        return encontrados;
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

    private Registro[] carregar() {
        if (posicoes != null) {
            return posicoes;
        }
        posicoes = new Registro[capacidade];
        if (!arquivo.exists()) {
            return posicoes;
        }
        try {
            byte[] claro = cifra.decifrar(Files.readAllBytes(arquivo.toPath()));
            JSONArray digitais = new JSONObject(new String(claro, StandardCharsets.UTF_8))
                    .getJSONArray("digitais");
            // Primeiro quem já tem posição gravada; depois os do formato antigo.
            List<JSONObject> semPosicao = new ArrayList<>();
            for (int i = 0; i < digitais.length(); i++) {
                JSONObject d = digitais.getJSONObject(i);
                int posicao = d.optInt("posicao", -1);
                if (posicao >= 0 && posicao < capacidade && posicoes[posicao] == null) {
                    posicoes[posicao] = deJson(posicao, d);
                } else {
                    semPosicao.add(d);
                }
            }
            for (JSONObject d : semPosicao) {
                int livre = primeiraLivre(posicoes);
                if (livre < 0) break;
                posicoes[livre] = deJson(livre, d);
            }
        } catch (IOException | GeneralSecurityException | JSONException e) {
            posicoes = new Registro[capacidade];
            falhaAoCarregar = true;
        }
        return posicoes;
    }

    private static int primeiraLivre(Registro[] array) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == null) return i;
        }
        return -1;
    }

    private static Registro deJson(int posicao, JSONObject d) throws JSONException {
        List<String> amostras = new ArrayList<>();
        if (d.has("templates")) {
            JSONArray lista = d.getJSONArray("templates");
            for (int j = 0; j < lista.length(); j++) {
                amostras.add(lista.getString(j));
            }
        } else {
            amostras.add(d.getString("template"));
        }
        return new Registro(posicao, d.getInt("professorId"), amostras);
    }

    /** Grava num arquivo temporário e troca no fim, para nunca deixar um arquivo pela metade. */
    private void persistir(Registro[] novo) throws IOException {
        byte[] cifrado;
        try {
            JSONArray digitais = new JSONArray();
            for (Registro r : novo) {
                if (r == null) continue;
                digitais.put(new JSONObject()
                        .put("posicao", r.posicao)
                        .put("professorId", r.professorId)
                        .put("templates", new JSONArray(r.amostras)));
            }
            String json = new JSONObject()
                    .put("versao", VERSAO)
                    .put("capacidade", capacidade)
                    .put("digitais", digitais)
                    .toString();
            cifrado = cifra.cifrar(json.getBytes(StandardCharsets.UTF_8));
        } catch (JSONException | GeneralSecurityException e) {
            throw new IOException("Falha ao cifrar as digitais.", e);
        }

        File temporario = new File(arquivo.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporario)) {
            out.write(cifrado);
            out.getFD().sync();
        }
        if (!temporario.renameTo(arquivo)) {
            temporario.delete();
            throw new IOException("Falha ao gravar o arquivo de digitais.");
        }
        posicoes = novo;
        falhaAoCarregar = false;
    }
}
