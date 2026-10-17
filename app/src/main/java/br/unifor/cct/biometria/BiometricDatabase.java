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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import br.unifor.cct.model.Professor;

/**
 * Templates de digital dos professores, guardados só no aparelho.
 *
 * As digitais ficam em ARRAYS_PADRAO arrays de TAMANHO_ARRAY_PADRAO posições
 * (2 x 300). Cada posição está livre (null) ou guarda o cadastro de um
 * professor, com o e-mail da aba "professores" e uma ou mais amostras do
 * mesmo dedo (templates em texto gerados pelo SDK do leitor). Nunca guarda a
 * imagem da digital. As posições são numeradas de 0 a capacidade() - 1: a
 * posição p fica no array p / tamanhoArray().
 *
 * - Cadastro: escolhe o array com menos digitais e grava na primeira
 *   posição livre dele (ou reaproveita a posição do professor, se ele já
 *   tinha digital). Assim os arrays ficam do mesmo tamanho.
 * - Identificação: cada array é percorrido numa thread, ao mesmo tempo,
 *   comparando a digital lida com as amostras de cada posição. Por isso o
 *   tempo é o de um array, não o da soma (identificar).
 *
 * O arquivo inteiro é cifrado (AES-GCM, chave no Android Keystore) e fica
 * em noBackupFilesDir, fora do backup automático do Android.
 *
 * Os métodos fazem leitura e escrita em disco: chame fora da thread
 * principal.
 */
public class BiometricDatabase {

    /** Quantos arrays de digitais, percorridos em paralelo na identificação. */
    public static final int ARRAYS_PADRAO = 2;
    /** Posições de cada array. */
    public static final int TAMANHO_ARRAY_PADRAO = 300;

    private static final String ARQUIVO_PADRAO = "digitais.bin";
    private static final String APELIDO_CHAVE = "br.unifor.cct.digitais";
    /**
     * 1: um "template" por professor; 2: lista "templates"; 3: com a
     * "posicao" no array; 4: "professorEmail" no lugar de "professorId".
     * Digitais das versões 1 a 3 (id numérico) não têm como ser ligadas a um
     * e-mail: são descartadas e precisam ser cadastradas de novo.
     */
    private static final int VERSAO = 4;

    /** Uma posição ocupada do array. */
    public static final class Registro {

        private final int posicao;
        private final String professorEmail;
        private final List<String> amostras;

        Registro(int posicao, String professorEmail, List<String> amostras) {
            this.posicao = posicao;
            this.professorEmail = professorEmail;
            this.amostras = Collections.unmodifiableList(new ArrayList<>(amostras));
        }

        public int getPosicao() {
            return posicao;
        }

        public String getProfessorEmail() {
            return professorEmail;
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
    private final int arrays;
    private final int tamanhoArray;
    private final int capacidade;

    /** posição -> cadastro; null = posição livre. */
    private Registro[] posicoes;
    private boolean falhaAoCarregar;
    private int descartadasFormatoAntigo;

    /** Threads da busca nos arrays 1, 2...; o array 0 roda em quem chamou. */
    private ExecutorService paralelo;

    public BiometricDatabase(File arquivo, Cifra cifra) {
        this(arquivo, cifra, ARRAYS_PADRAO, TAMANHO_ARRAY_PADRAO);
    }

    public BiometricDatabase(File arquivo, Cifra cifra, int arrays, int tamanhoArray) {
        if (arrays <= 0 || tamanhoArray <= 0) {
            throw new IllegalArgumentException("Arrays e tamanho devem ser maiores que zero.");
        }
        this.arquivo = arquivo;
        this.cifra = cifra;
        this.arrays = arrays;
        this.tamanhoArray = tamanhoArray;
        this.capacidade = arrays * tamanhoArray;
    }

    /** Banco do app: arquivo em noBackupFilesDir, cifrado com chave do Keystore. */
    public static BiometricDatabase padrao(Context context) throws GeneralSecurityException {
        return new BiometricDatabase(
                new File(context.getNoBackupFilesDir(), ARQUIVO_PADRAO),
                CifraKeystore.obter(APELIDO_CHAVE));
    }

    /** Total de posições, somando todos os arrays. */
    public int capacidade() {
        return capacidade;
    }

    public int arrays() {
        return arrays;
    }

    public int tamanhoArray() {
        return tamanhoArray;
    }

    /** @return em qual array fica a posição (0, 1, ...). */
    public int arrayDa(int posicao) {
        return posicao / tamanhoArray;
    }

    /** Digitais ocupadas em cada array. */
    public synchronized int[] quantidadePorArray() {
        int[] total = new int[arrays];
        Registro[] array = carregar();
        for (int i = 0; i < array.length; i++) {
            if (array[i] != null) total[arrayDa(i)]++;
        }
        return total;
    }

    /** Quantas posições estão ocupadas. */
    public synchronized int quantidade() {
        int total = 0;
        for (Registro r : carregar()) {
            if (r != null) total++;
        }
        return total;
    }

    /**
     * @return a posição onde a próxima digital nova vai ficar: a primeira
     *         livre do array com menos digitais, ou -1 se todos estiverem cheios
     */
    public synchronized int posicaoLivre() {
        return proximaLivre(carregar());
    }

    /** @return a posição do professor, ou -1 se ele não tiver digital. */
    public synchronized int posicaoDo(String professorEmail) {
        String email = normalizar(professorEmail);
        Registro[] array = carregar();
        for (int i = 0; i < array.length; i++) {
            if (array[i] != null && array[i].professorEmail.equals(email)) return i;
        }
        return -1;
    }

    /** @return as amostras do professor, ou null se ele não tiver digital cadastrada. */
    public synchronized List<String> buscar(String professorEmail) {
        int posicao = posicaoDo(professorEmail);
        return posicao < 0 ? null : posicoes[posicao].amostras;
    }

    /** @return o cadastro guardado na posição, ou null se ela estiver livre. */
    public synchronized Registro naPosicao(int posicao) {
        Registro[] array = carregar();
        return posicao >= 0 && posicao < array.length ? array[posicao] : null;
    }

    /**
     * Cadastra ou troca a digital do professor (todas as amostras de uma vez).
     * Se ele já tinha digital, fica na mesma posição; senão vai para
     * posicaoLivre().
     *
     * @return a posição do array onde a digital ficou
     * @throws IllegalStateException se o array estiver cheio
     */
    public synchronized int salvar(String professorEmail, List<String> amostras) throws IOException {
        String email = normalizar(professorEmail);
        if (email.isEmpty()) {
            throw new IllegalArgumentException("E-mail do professor vazio.");
        }
        if (amostras == null || amostras.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma amostra.");
        }
        for (String a : amostras) {
            if (a == null || a.isEmpty()) {
                throw new IllegalArgumentException("Amostra vazia.");
            }
        }
        int posicao = posicaoDo(email);
        if (posicao < 0) {
            posicao = posicaoLivre();
        }
        if (posicao < 0) {
            throw new IllegalStateException(
                    "Não há posição livre: as " + capacidade + " digitais já estão em uso.");
        }
        Registro[] novo = carregar().clone();
        novo[posicao] = new Registro(posicao, email, amostras);
        persistir(novo);
        return posicao;
    }

    /** Libera a posição do professor. @return true se ele tinha digital cadastrada. */
    public synchronized boolean remover(String professorEmail) throws IOException {
        int posicao = posicaoDo(professorEmail);
        if (posicao < 0) {
            return false;
        }
        Registro[] novo = carregar().clone();
        novo[posicao] = null;
        persistir(novo);
        return true;
    }

    /**
     * Compara a digital lida com as amostras de todas as posições ocupadas,
     * percorrendo os arrays ao mesmo tempo.
     *
     * Cada comparador só pode ser usado por uma thread por vez (o SDK guarda
     * o resultado dentro da instância). Com um comparador por array, cada
     * array roda na sua thread; com menos, os arrays são divididos entre
     * eles (com um só, a busca é sequencial).
     *
     * Não para no primeiro acerto: se a digital conferir com mais de um
     * professor, a lista traz todos, e quem chamou decide (o NitgenMatcher
     * recusa, para não confundir professores).
     *
     * @return os e-mails dos professores cuja digital conferiu, na ordem das
     *         posições (vazia se ninguém conferiu)
     */
    public <E extends Exception> List<String> identificar(List<? extends Comparador<E>> comparadores)
            throws E {
        if (comparadores == null || comparadores.isEmpty()) {
            throw new IllegalArgumentException("Nenhum comparador.");
        }
        Registro[] array;
        synchronized (this) {
            array = carregar().clone();
        }
        // As comparações rodam fora do lock: no SDK cada uma leva alguns ms.
        int grupos = Math.min(comparadores.size(), arrays);
        List<Future<List<Registro>>> outros = new ArrayList<>();
        for (int g = 1; g < grupos; g++) {
            final int grupo = g;
            outros.add(executorParalelo().submit(
                    () -> percorrer(array, grupo, grupos, comparadores.get(grupo))));
        }
        List<Registro> achados = new ArrayList<>(percorrer(array, 0, grupos, comparadores.get(0)));
        for (Future<List<Registro>> f : outros) {
            achados.addAll(resultado(f));
        }
        Collections.sort(achados, (a, b) -> Integer.compare(a.posicao, b.posicao));
        List<String> emails = new ArrayList<>();
        for (Registro r : achados) emails.add(r.professorEmail);
        return emails;
    }

    /**
     * Amostras de até maxRegistros professores, para conferir se a busca em
     * paralelo dá o mesmo resultado que a sequencial (NitgenMatcher).
     */
    public synchronized List<List<String>> amostrasDeExemplo(int maxRegistros) {
        List<List<String>> lista = new ArrayList<>();
        for (Registro r : carregar()) {
            if (r == null) continue;
            lista.add(r.amostras);
            if (lista.size() >= maxRegistros) break;
        }
        return lista;
    }

    /** Variante sequencial, com um só comparador. */
    public <E extends Exception> List<String> identificar(Comparador<E> comparador) throws E {
        return identificar(Collections.singletonList(comparador));
    }

    /** Percorre os arrays do grupo (array % grupos == grupo). */
    private <E extends Exception> List<Registro> percorrer(Registro[] array, int grupo, int grupos,
                                                          Comparador<E> comparador) throws E {
        List<Registro> achados = new ArrayList<>();
        for (int a = grupo; a < arrays; a += grupos) {
            for (int i = a * tamanhoArray; i < (a + 1) * tamanhoArray; i++) {
                Registro registro = array[i];
                if (registro == null) continue;
                for (String amostra : registro.amostras) {
                    if (comparador.mesmaDigital(amostra)) {
                        achados.add(registro);
                        break;
                    }
                }
            }
        }
        return achados;
    }

    /** Espera a thread e repassa a exceção do comparador como se fosse daqui. */
    @SuppressWarnings("unchecked")
    private static <E extends Exception> List<Registro> resultado(Future<List<Registro>> f) throws E {
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException) throw (RuntimeException) causa;
            if (causa instanceof Error) throw (Error) causa;
            throw (E) causa;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Identificação interrompida.", e);
        }
    }

    private synchronized ExecutorService executorParalelo() {
        if (paralelo == null) {
            paralelo = Executors.newFixedThreadPool(Math.max(1, arrays - 1), tarefa -> {
                Thread t = new Thread(tarefa, "busca-digitais");
                t.setDaemon(true);
                return t;
            });
        }
        return paralelo;
    }

    /**
     * Quantas digitais do formato antigo (id numérico, antes do e-mail) foram
     * descartadas ao abrir o banco. Elas precisam ser cadastradas de novo.
     */
    public synchronized int digitaisDescartadas() {
        carregar();
        return descartadasFormatoAntigo;
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
            // Primeiro quem já tem posição gravada; depois os sem posição.
            List<JSONObject> semPosicao = new ArrayList<>();
            for (int i = 0; i < digitais.length(); i++) {
                JSONObject d = digitais.getJSONObject(i);
                if (!d.has("professorEmail")) {
                    descartadasFormatoAntigo++;
                    continue;
                }
                int posicao = d.optInt("posicao", -1);
                if (posicao >= 0 && posicao < capacidade && posicoes[posicao] == null) {
                    posicoes[posicao] = deJson(posicao, d);
                } else {
                    semPosicao.add(d);
                }
            }
            for (JSONObject d : semPosicao) {
                int livre = proximaLivre(posicoes);
                if (livre < 0) break;
                posicoes[livre] = deJson(livre, d);
            }
        } catch (IOException | GeneralSecurityException | JSONException e) {
            posicoes = new Registro[capacidade];
            falhaAoCarregar = true;
            descartadasFormatoAntigo = 0;
        }
        return posicoes;
    }

    /** Primeira posição livre do array com menos digitais (empate: o de menor número). */
    private int proximaLivre(Registro[] array) {
        int melhorArray = -1;
        int menosOcupadas = Integer.MAX_VALUE;
        for (int a = 0; a < arrays; a++) {
            int ocupadas = 0;
            for (int i = a * tamanhoArray; i < (a + 1) * tamanhoArray; i++) {
                if (array[i] != null) ocupadas++;
            }
            if (ocupadas < tamanhoArray && ocupadas < menosOcupadas) {
                melhorArray = a;
                menosOcupadas = ocupadas;
            }
        }
        if (melhorArray < 0) return -1;
        for (int i = melhorArray * tamanhoArray; i < (melhorArray + 1) * tamanhoArray; i++) {
            if (array[i] == null) return i;
        }
        return -1;
    }

    private static String normalizar(String email) {
        return Professor.normalizarEmail(email);
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
        return new Registro(posicao, normalizar(d.getString("professorEmail")), amostras);
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
                        .put("professorEmail", r.professorEmail)
                        .put("templates", new JSONArray(r.amostras)));
            }
            String json = new JSONObject()
                    .put("versao", VERSAO)
                    .put("arrays", arrays)
                    .put("tamanhoArray", tamanhoArray)
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
        posicoes = novo;
        falhaAoCarregar = false;
    }
}
