package br.unifor.cct.biometria;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Chave AES-256 guardada no Android Keystore: ela nunca sai do aparelho e
 * não pode ser exportada. Se o app for desinstalado ou tiver os dados
 * apagados, a chave some junto e os templates antigos ficam ilegíveis,
 * o que é o comportamento desejado para dado biométrico.
 */
public final class CifraKeystore {

    private static final String PROVEDOR = "AndroidKeyStore";

    private CifraKeystore() {
    }

    /** Usa a chave com esse apelido, criando-a na primeira vez. */
    public static Cifra obter(String apelido) throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(PROVEDOR);
            keyStore.load(null);
            SecretKey chave = (SecretKey) keyStore.getKey(apelido, null);
            if (chave == null) {
                KeyGenerator gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEDOR);
                gerador.init(new KeyGenParameterSpec.Builder(apelido,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                chave = gerador.generateKey();
            }
            return new CifraAesGcm(chave);
        } catch (IOException e) {
            throw new GeneralSecurityException("Falha ao abrir o Android Keystore.", e);
        }
    }
}
