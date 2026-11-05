package br.unifor.cct.rest;

import java.util.concurrent.CompletableFuture;

import br.unifor.cct.biometria.BiometriaCallback;
import br.unifor.cct.biometria.BiometriaService;
import br.unifor.cct.biometria.ResultadoBiometria;

/**
 * O que a rota POST /v1/biometric/identify usa para capturar e identificar
 * uma digital. Separado do BiometriaService para os testes trocarem o leitor
 * sem Android (Looper/Handler).
 */
public interface IdentificadorBiometria {

    /** Começa uma captura; o futuro termina com o resultado ou com o erro do leitor. */
    CompletableFuture<ResultadoBiometria> identificar();

    /** "simulado" (fakes) ou "hamster-dx" (leitor real), mostrado no /health. */
    String modo();

    /** Adapta o BiometriaService (callbacks) para CompletableFuture. */
    static IdentificadorBiometria de(BiometriaService servico, String modo) {
        return new IdentificadorBiometria() {
            @Override
            public CompletableFuture<ResultadoBiometria> identificar() {
                CompletableFuture<ResultadoBiometria> futuro = new CompletableFuture<>();
                servico.identificarProfessor(new BiometriaCallback() {
                    @Override
                    public void onSucesso(ResultadoBiometria resultado) {
                        futuro.complete(resultado);
                    }

                    @Override
                    public void onErro(String mensagem) {
                        futuro.completeExceptionally(new IllegalStateException(mensagem));
                    }
                });
                return futuro;
            }

            @Override
            public String modo() {
                return modo;
            }
        };
    }
}
