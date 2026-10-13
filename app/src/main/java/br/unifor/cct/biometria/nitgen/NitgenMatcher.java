package br.unifor.cct.biometria.nitgen;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import br.unifor.cct.biometria.BiometricDatabase;
import br.unifor.cct.biometria.FingerprintMatcher;
import br.unifor.cct.biometria.FingerprintTemplate;
import br.unifor.cct.biometria.IdentificationResult;

/**
 * FingerprintMatcher com a busca 1:N do SDK da Nitgen (IndexSearch).
 * Substitui o FakeFingerprintMatcher no BiometriaService.
 *
 * O índice do SDK fica só em memória: chame carregar() ao abrir o app para
 * montá-lo a partir do BiometricDatabase. O SDK não informa uma pontuação
 * na busca 1:N, então o score do IdentificationResult é sempre 0.
 */
public class NitgenMatcher implements FingerprintMatcher {

    private final NitgenSdk sdk;

    public NitgenMatcher(NitgenSdk sdk) {
        this.sdk = sdk;
    }

    /** Refaz o índice com todas as digitais do banco. Entrega quantas foram carregadas. */
    public void carregar(BiometricDatabase banco, NitgenSdk.Resultado<Integer> resultado) {
        sdk.executar(() -> {
            Map<Integer, List<String>> digitais = banco.todos();
            sdk.limparIndice();
            for (Map.Entry<Integer, List<String>> d : digitais.entrySet()) {
                sdk.indexar(d.getKey(), d.getValue());
            }
            return digitais.size();
        }, resultado);
    }

    @Override
    public void identificar(FingerprintTemplate template, MatchCallback callback) {
        if (template == null || template.getDados() == null || template.getDados().length == 0) {
            callback.onResultado(new IdentificationResult(false, null, 0));
            return;
        }
        String texto = new String(template.getDados(), StandardCharsets.UTF_8);
        sdk.executar(() -> sdk.identificar(texto), new NitgenSdk.Resultado<Integer>() {
            @Override
            public void onSucesso(Integer professorId) {
                callback.onResultado(new IdentificationResult(professorId != null, professorId, 0));
            }

            @Override
            public void onErro(String mensagem) {
                callback.onErro(mensagem);
            }
        });
    }
}
