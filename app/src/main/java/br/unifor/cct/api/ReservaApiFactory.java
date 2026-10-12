package br.unifor.cct.api;

import br.unifor.cct.BuildConfig;

/**
 * Ponto único para as telas obterem a ReservaApi.
 *
 * Se appsScript.url e appsScript.token estiverem preenchidos no
 * local.properties, devolve o AppsScriptClient; senão, o MockReservaApi.
 * Assim quem ainda não tem a URL do Web App consegue rodar o app.
 */
public final class ReservaApiFactory {

    private static ReservaApi instancia;

    private ReservaApiFactory() {
    }

    public static synchronized ReservaApi obter() {
        if (instancia == null) {
            instancia = usandoAppsScript()
                    ? new AppsScriptClient(BuildConfig.APPS_SCRIPT_URL, BuildConfig.APPS_SCRIPT_TOKEN)
                    : new MockReservaApi();
        }
        return instancia;
    }

    public static boolean usandoAppsScript() {
        return !BuildConfig.APPS_SCRIPT_URL.isEmpty() && !BuildConfig.APPS_SCRIPT_TOKEN.isEmpty();
    }
}
