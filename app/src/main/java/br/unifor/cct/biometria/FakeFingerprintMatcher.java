package br.unifor.cct.biometria;

import android.os.Handler;
import android.os.Looper;

/**
 * Implementação falsa de FingerprintMatcher, sem base real de professores.
 * Sempre "reconhece" o template capturado como um professor fixo (id 1),
 * para permitir testar o fluxo completo (BiometriaService -> ResultadoBiometria
 * -> ReservaApi.criarReserva) sem depender do SDK Nitgen.
 *
 * Ajuste PROFESSOR_ID_FAKE (ou a condição abaixo) se quiser simular falha
 * de match durante os testes.
 */
public class FakeFingerprintMatcher implements FingerprintMatcher {

    private static final long LATENCIA_SIMULADA_MS = 400;
    private static final int PROFESSOR_ID_FAKE = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void identificar(FingerprintTemplate template, MatchCallback callback) {
        handler.postDelayed(() -> {
            if (template == null || template.getDados() == null || template.getDados().length == 0) {
                callback.onResultado(new IdentificationResult(false, null, 0));
            } else {
                callback.onResultado(new IdentificationResult(true, PROFESSOR_ID_FAKE, 100));
            }
        }, LATENCIA_SIMULADA_MS);
    }
}
