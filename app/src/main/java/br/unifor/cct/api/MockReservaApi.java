package br.unifor.cct.api;

import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;

import br.unifor.cct.biometria.FakeFingerprintMatcher;
import br.unifor.cct.model.Professor;

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
public class MockReservaApi implements ReservaApi, ProfessorApi {

    private static final long LATENCIA_SIMULADA_MS = 800;
    private int proximoReservaId = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void criarReserva(String professorEmail, String projetorId, String data,
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
    public void registrarDevolucao(int reservaId, ReservaCallback callback) {
        handler.postDelayed(() -> callback.onSucesso(
                new ReservaResultado(true, reservaId, "Devolução registrada.")),
                LATENCIA_SIMULADA_MS);
    }

    @Override
    public void devolverProjetor(String projetorId, ReservaCallback callback) {
        handler.postDelayed(() -> callback.onSucesso(
                new ReservaResultado(true, proximoReservaId - 1, "Devolução registrada.")),
                LATENCIA_SIMULADA_MS);
    }

    @Override
    public void consultarReserva(int reservaId, ReservaCallback callback) {
        handler.postDelayed(() -> callback.onSucesso(
                new ReservaResultado(true, reservaId, "Reserva ativa.")),
                LATENCIA_SIMULADA_MS);
    }

    /** O primeiro é o mesmo e-mail que o FakeFingerprintMatcher devolve. */
    @Override
    public void listarProfessores(ProfessoresCallback callback) {
        handler.postDelayed(() -> callback.onSucesso(Arrays.asList(
                new Professor(FakeFingerprintMatcher.EMAIL_TESTE, "Professor Teste", "0001", true),
                new Professor("inativa@unifor.br", "Professora Inativa", "0002", false))),
                LATENCIA_SIMULADA_MS);
    }
}
