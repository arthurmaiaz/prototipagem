package br.unifor.cct.biometria.nitgen;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import br.unifor.cct.biometria.BiometricDatabase;
import br.unifor.cct.biometria.FingerprintMatcher;
import br.unifor.cct.biometria.FingerprintTemplate;
import br.unifor.cct.biometria.IdentificationResult;

/**
 * FingerprintMatcher que percorre os arrays de digitais do
 * BiometricDatabase ao mesmo tempo, comparando a digital lida com cada
 * posição (VerifyMatch 1:1 do SDK da Nitgen). Substitui o
 * FakeFingerprintMatcher no BiometriaService.
 *
 * Cada array extra usa uma instância própria do SDK. Antes de confiar na
 * busca em paralelo, ela é conferida uma vez: as mesmas comparações são
 * feitas em todas as instâncias ao mesmo tempo e precisam dar o mesmo
 * resultado da instância principal. Se o SDK não aceitar instâncias extras
 * ou o resultado divergir, a busca fica sequencial (mais lenta, mas certa).
 * A conferência precisa de pelo menos uma digital cadastrada; até lá, a
 * busca é sequencial.
 *
 * Se a digital conferir com mais de um professor, não identifica ninguém:
 * confundir professores é pior do que pedir para colocar o dedo de novo.
 * O SDK não informa uma pontuação na comparação, então o score do
 * IdentificationResult é sempre 0.
 */
public class NitgenMatcher implements FingerprintMatcher {

    private static final String TAG = "NitgenMatcher";
    /** Professores usados na conferência e quantas vezes cada rodada repete. */
    private static final int REGISTROS_CONFERENCIA = 4;
    private static final int RODADAS_CONFERENCIA = 3;

    private enum Paralelo { PENDENTE, ATIVO, DESATIVADO }

    private final NitgenSdk sdk;
    private final BiometricDatabase banco;

    // Só usados na thread do SDK (dentro de sdk.executar).
    private volatile Paralelo paralelo = Paralelo.PENDENTE;
    private List<NitgenSdk.Comparacao> extras = new ArrayList<>();
    /** Por que a busca em paralelo foi desativada (para a tela de teste). */
    private volatile String motivoSequencial;
    /** Duração da última busca nos arrays, em ms (-1 antes da primeira). */
    private volatile long ultimaBuscaMs = -1;

    public NitgenMatcher(NitgenSdk sdk, BiometricDatabase banco) {
        this.sdk = sdk;
        this.banco = banco;
    }

    @Override
    public void identificar(FingerprintTemplate template, MatchCallback callback) {
        if (template == null || template.getDados() == null || template.getDados().length == 0) {
            callback.onResultado(new IdentificationResult(false, null, 0));
            return;
        }
        String lida = new String(template.getDados(), StandardCharsets.UTF_8);
        sdk.executar(() -> {
            prepararParalelo();
            List<BiometricDatabase.Comparador<NitgenException>> comparadores = new ArrayList<>();
            comparadores.add(amostra -> sdk.mesmaDigital(lida, amostra));
            if (paralelo == Paralelo.ATIVO) {
                for (NitgenSdk.Comparacao c : extras) {
                    comparadores.add(amostra -> c.mesmaDigital(lida, amostra));
                }
            }
            long inicio = System.nanoTime();
            List<String> encontrados = banco.identificar(comparadores);
            ultimaBuscaMs = (System.nanoTime() - inicio) / 1_000_000;
            Log.i(TAG, "Busca em " + ultimaBuscaMs + " ms com " + comparadores.size() + " thread(s).");
            if (encontrados.size() > 1) {
                throw new NitgenException("A digital conferiu com mais de um professor "
                        + encontrados + ". Coloque o dedo de novo.");
            }
            return encontrados.isEmpty() ? null : encontrados.get(0);
        }, new NitgenSdk.Resultado<String>() {
            @Override
            public void onSucesso(String professorEmail) {
                callback.onResultado(new IdentificationResult(professorEmail != null, professorEmail, 0));
            }

            @Override
            public void onErro(String mensagem) {
                callback.onErro(mensagem);
            }
        });
    }

    /**
     * Para a tela de teste mostrar se a busca está em paralelo, por que não
     * está e quanto tempo levou a última.
     */
    public String descricaoBusca() {
        String modo;
        switch (paralelo) {
            case ATIVO:
                modo = "Busca em paralelo: " + (extras.size() + 1) + " threads.";
                break;
            case DESATIVADO:
                modo = "Busca sequencial (paralelo desativado: " + motivoSequencial + ").";
                break;
            default:
                modo = "Busca sequencial até a primeira digital ser cadastrada e conferida.";
        }
        long ms = ultimaBuscaMs;
        return ms < 0 ? modo : modo + " Última busca: " + ms + " ms.";
    }

    /** Duração da última busca nos arrays, em ms (-1 antes da primeira). */
    public long getUltimaBuscaMs() {
        return ultimaBuscaMs;
    }

    /** Cria as instâncias extras e confere a busca em paralelo, uma vez. */
    private void prepararParalelo() {
        if (paralelo != Paralelo.PENDENTE) return;
        if (banco.arrays() < 2) {
            motivoSequencial = "só há um array";
            paralelo = Paralelo.DESATIVADO;
            return;
        }
        List<String> amostras = new ArrayList<>();
        for (List<String> doProfessor : banco.amostrasDeExemplo(REGISTROS_CONFERENCIA)) {
            amostras.addAll(doProfessor);
        }
        if (amostras.size() < 2) return; // ainda sem digitais para conferir

        extras = sdk.criarComparacoes(banco.arrays() - 1);
        if (extras.isEmpty()) {
            String falha = sdk.getFalhaComparacao();
            desativar("o SDK não criou instâncias extras" + (falha == null ? "" : ", " + falha));
            return;
        }
        try {
            if (conferir(amostras)) {
                paralelo = Paralelo.ATIVO;
                Log.i(TAG, "Busca em paralelo ativa com " + (extras.size() + 1) + " threads.");
            } else {
                desativar("as instâncias deram resultados diferentes");
            }
        } catch (Exception e) {
            desativar("erro na conferência: " + e);
        }
    }

    /**
     * Todas as instâncias comparam todos os pares de amostras ao mesmo tempo,
     * várias vezes; o resultado precisa ser igual ao da instância principal
     * sozinha. Os pares incluem amostras do mesmo dedo (o cadastro guarda
     * duas), então há acertos e erros esperados.
     */
    private boolean conferir(List<String> amostras) throws Exception {
        List<String[]> pares = new ArrayList<>();
        for (int i = 0; i < amostras.size(); i++) {
            for (int j = i + 1; j < amostras.size(); j++) {
                pares.add(new String[] {amostras.get(i), amostras.get(j)});
            }
        }
        boolean[] esperado = new boolean[pares.size()];
        for (int k = 0; k < pares.size(); k++) {
            esperado[k] = sdk.mesmaDigital(pares.get(k)[0], pares.get(k)[1]);
        }

        ExecutorService threads = Executors.newFixedThreadPool(extras.size());
        try {
            List<Future<Boolean>> resultados = new ArrayList<>();
            for (NitgenSdk.Comparacao c : extras) {
                resultados.add(threads.submit(() -> confere(pares, esperado, c::mesmaDigital)));
            }
            boolean ok = confere(pares, esperado, sdk::mesmaDigital);
            for (Future<Boolean> f : resultados) {
                ok &= f.get();
            }
            return ok;
        } finally {
            threads.shutdown();
        }
    }

    private interface Comparar {
        boolean mesmaDigital(String a, String b) throws NitgenException;
    }

    private static boolean confere(List<String[]> pares, boolean[] esperado, Comparar comparar)
            throws NitgenException {
        for (int rodada = 0; rodada < RODADAS_CONFERENCIA; rodada++) {
            for (int k = 0; k < pares.size(); k++) {
                if (comparar.mesmaDigital(pares.get(k)[0], pares.get(k)[1]) != esperado[k]) {
                    return false;
                }
            }
        }
        return true;
    }

    private void desativar(String motivo) {
        Log.w(TAG, "Busca em paralelo desativada: " + motivo);
        motivoSequencial = motivo;
        sdk.liberarComparacoes();
        extras = new ArrayList<>();
        paralelo = Paralelo.DESATIVADO;
    }
}
