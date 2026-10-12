package br.unifor.cct.api;

import br.unifor.cct.BuildConfig;

/**
 * Ponto único para as telas obterem a ReservaApi e a ProfessorApi.
 *
 * Se appsScript.url e appsScript.token estiverem preenchidos no
 * local.properties, devolve o AppsScriptClient; senão, o MockReservaApi.
 * Assim quem ainda não tem a URL do Web App consegue rodar o app.
 * As duas APIs são o mesmo objeto, que implementa as duas interfaces.
 */
public final class ReservaApiFactory {

    private static ReservaApi reservas;
    private static ProfessorApi professores;

    private ReservaApiFactory() {
    }

    public static synchronized ReservaApi obter() {
        criarSeNecessario();
        return reservas;
    }

    public static synchronized ProfessorApi obterProfessorApi() {
        criarSeNecessario();
        return professores;
    }

    private static void criarSeNecessario() {
        if (reservas != null) {
            return;
        }
        if (usandoAppsScript()) {
            AppsScriptClient client =
                    new AppsScriptClient(BuildConfig.APPS_SCRIPT_URL, BuildConfig.APPS_SCRIPT_TOKEN);
            reservas = client;
            professores = client;
        } else {
            MockReservaApi mock = new MockReservaApi();
            reservas = mock;
            professores = mock;
        }
    }

    public static boolean usandoAppsScript() {
        return !BuildConfig.APPS_SCRIPT_URL.isEmpty() && !BuildConfig.APPS_SCRIPT_TOKEN.isEmpty();
    }
}
