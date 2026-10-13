package br.unifor.cct.biometria.nitgen;

import com.nitgen.SDK.AndroidBSP.NBioBSPJNI;

import java.nio.charset.StandardCharsets;

import br.unifor.cct.biometria.FingerprintReader;
import br.unifor.cct.biometria.FingerprintTemplate;

/**
 * FingerprintReader com o leitor Nitgen Hamster DX. Substitui o
 * FakeFingerprintReader no BiometriaService.
 *
 * O leitor precisa estar aberto (NitgenSdk.abrir) antes de capturar.
 * Os callbacks chegam na thread principal.
 */
public class HamsterDxReader implements FingerprintReader {

    private final NitgenSdk sdk;

    public HamsterDxReader(NitgenSdk sdk) {
        this.sdk = sdk;
    }

    @Override
    public void capturar(CapturaCallback callback) {
        // ENROLL, como no exemplo da Nitgen: a busca 1:N compara com
        // templates de cadastro, que também são ENROLL.
        sdk.executar(() -> sdk.capturar(NBioBSPJNI.FIR_PURPOSE.ENROLL),
                new NitgenSdk.Resultado<String>() {
                    @Override
                    public void onSucesso(String template) {
                        callback.onCapturado(new FingerprintTemplate(
                                template.getBytes(StandardCharsets.UTF_8)));
                    }

                    @Override
                    public void onErro(String mensagem) {
                        callback.onErro(mensagem);
                    }
                });
    }
}
