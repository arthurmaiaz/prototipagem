package br.unifor.cct.biometria;

import android.os.Handler;
import android.os.Looper;

/**
 * Implementação falsa de FingerprintReader, sem hardware, para testar o
 * fluxo (TesteBiometriaActivity, BiometriaService) no emulador.
 *
 * Simula latência de captura com Handler.postDelayed, no mesmo estilo do
 * MockReservaApi (api/).
 */
public class FakeFingerprintReader implements FingerprintReader {

    private static final long LATENCIA_SIMULADA_MS = 600;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void capturar(CapturaCallback callback) {
        handler.postDelayed(() -> {
            byte[] dadosFake = new byte[]{1, 2, 3, 4};
            callback.onCapturado(new FingerprintTemplate(dadosFake));
        }, LATENCIA_SIMULADA_MS);
    }
}
