package br.unifor.cct.biometria;

/**
 * Orquestra a captura (FingerprintReader) e a identificação (FingerprintMatcher),
 * entregando o resultado final como ResultadoBiometria através de BiometriaCallback.
 *
 * É esta classe que a UI (Nikolas) deve usar — nunca fala com
 * FingerprintReader/FingerprintMatcher diretamente. Troque os fakes pelas
 * implementações reais (HamsterDxReader / matcher real com NBioBSPJNI)
 * aqui, sem tocar em nenhuma tela.
 */
public class BiometriaService {

    private final FingerprintReader reader;
    private final FingerprintMatcher matcher;

    public BiometriaService(FingerprintReader reader, FingerprintMatcher matcher) {
        this.reader = reader;
        this.matcher = matcher;
    }

    public void identificarProfessor(BiometriaCallback callback) {
        reader.capturar(new FingerprintReader.CapturaCallback() {
            @Override
            public void onCapturado(FingerprintTemplate template) {
                matcher.identificar(template, new FingerprintMatcher.MatchCallback() {
                    @Override
                    public void onResultado(IdentificationResult resultado) {
                        if (resultado.isEncontrado()) {
                            callback.onSucesso(new ResultadoBiometria(
                                    true, resultado.getProfessorId(),
                                    "Professor identificado com sucesso."));
                        } else {
                            callback.onSucesso(new ResultadoBiometria(
                                    false, null,
                                    "Digital não reconhecida. Tente novamente."));
                        }
                    }

                    @Override
                    public void onErro(String mensagem) {
                        callback.onErro(mensagem);
                    }
                });
            }

            @Override
            public void onErro(String mensagem) {
                callback.onErro(mensagem);
            }
        });
    }
}
