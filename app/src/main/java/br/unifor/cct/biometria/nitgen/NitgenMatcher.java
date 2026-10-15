package br.unifor.cct.biometria.nitgen;

import java.nio.charset.StandardCharsets;
import java.util.List;

import br.unifor.cct.biometria.BiometricDatabase;
import br.unifor.cct.biometria.FingerprintMatcher;
import br.unifor.cct.biometria.FingerprintTemplate;
import br.unifor.cct.biometria.IdentificationResult;

/**
 * FingerprintMatcher que percorre o array de digitais do BiometricDatabase,
 * comparando a digital lida com cada posição (VerifyMatch 1:1 do SDK da
 * Nitgen). Substitui o FakeFingerprintMatcher no BiometriaService.
 *
 * Se a digital conferir com mais de um professor, não identifica ninguém:
 * confundir professores é pior do que pedir para colocar o dedo de novo.
 * O SDK não informa uma pontuação na comparação, então o score do
 * IdentificationResult é sempre 0.
 */
public class NitgenMatcher implements FingerprintMatcher {

    private final NitgenSdk sdk;
    private final BiometricDatabase banco;

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
            List<Integer> encontrados = banco.identificar(amostra -> sdk.mesmaDigital(lida, amostra));
            if (encontrados.size() > 1) {
                throw new NitgenException("A digital conferiu com mais de um professor "
                        + encontrados + ". Coloque o dedo de novo.");
            }
            return encontrados.isEmpty() ? null : encontrados.get(0);
        }, new NitgenSdk.Resultado<Integer>() {
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
