package br.unifor.cct.rest;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;

/**
 * Token Bearer da API do NFC. Gerado no próprio celular na primeira vez
 * (32 bytes aleatórios em base64url) e guardado em SharedPreferences
 * privado. Quem chama a API copia o valor da tela de NFC (ex.: para o
 * appnfc-test-token.txt do test-api.ps1).
 */
public final class TokenApi {

    private static final String PREFS = "api_nfc";
    private static final String CHAVE = "token";

    private TokenApi() {
    }

    public static synchronized String obter(Context context) {
        SharedPreferences prefs = prefs(context);
        String token = prefs.getString(CHAVE, null);
        if (token == null) {
            token = gerar();
            prefs.edit().putString(CHAVE, token).apply();
        }
        return token;
    }

    /** Troca o token: quem usava o anterior passa a receber 401. */
    public static synchronized String trocar(Context context) {
        String token = gerar();
        prefs(context).edit().putString(CHAVE, token).apply();
        return token;
    }

    private static String gerar() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
