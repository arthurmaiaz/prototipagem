package br.unifor.cct.biometria;

import android.os.Handler;
import android.os.Looper;

public class FakeFingerprintMatcher implements FingerprintMatcher {

    private static final long LATENCIA_SIMULADA_MS = 400;
    /** E-mail que o fake sempre identifica; o MockReservaApi tem o mesmo professor. */
    public static final String EMAIL_TESTE = "professor.teste@unifor.br";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void identificar(FingerprintTemplate template, MatchCallback callback) {
        handler.postDelayed(() -> {
            if (template == null || template.getDados() == null || template.getDados().length == 0) {
                callback.onResultado(new IdentificationResult(false, null, 0));
            } else {
                callback.onResultado(new IdentificationResult(true, EMAIL_TESTE, 100));
            }
        }, LATENCIA_SIMULADA_MS);
    }
}