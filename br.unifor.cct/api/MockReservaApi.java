package br.unifor.cct.api;

import android.os.Handler;
import android.os.Looper;

/**
 * Implementação falsa de ReservaApi, para montar e testar as telas
 * (IdentificacaoActivity, ReservaActivity) sem depender do Apps Script
 * nem esperar a implementação real do Arthur.
 *
 * Simula uma pequena latência de rede com Handler.postDelayed, pra você
 * já testar loading/estado de espera na UI.
 *
 * Troque MockReservaApi() pela implementação real (AppsScriptClient)
 * quando o backend estiver pronto — nenhuma tela precisa mudar, pois
 * ambas conversam só através da interface ReservaApi.
 */
public class MockReservaApi implements ReservaApi {

    private static final long LATENCIA_SIMULADA_MS = 800;
    private int proximoReservaId = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void criarReserva(int professorId, String projetorId, String data,
                              String inicio, String fim, ReservaCallback callback) {
        handler.postDelayed(() -> {
            int reservaId = proximoReservaId++;
            callback.onSucesso(new ReservaResultado(
                    true, reservaId, "Reserva realizada com sucesso."));
        }, LATENCIA_SIMULADA_MS);
    }

    @Override
    public void cancelarReserva(int reservaId, ReservaCallback callback) {
        handler.postDelayed(() -> callback.onSucesso(
                new ReservaResultado(true, reservaId, "Reserva cancelada.")),
                LATENCIA_SIMULADA_MS);
    }

    @Override
    public void consultarReserva(int reservaId, ReservaCallback callback) {
        handler.postDelayed(() -> callback.onSucesso(
                new ReservaResultado(true, reservaId, "Reserva ativa.")),
                LATENCIA_SIMULADA_MS);
    }
}
