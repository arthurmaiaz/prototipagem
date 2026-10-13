package br.unifor.cct.biometria;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM com uma chave já existente. O resultado é o IV (12 bytes) seguido
 * do texto cifrado com a tag de autenticação, então qualquer alteração no
 * arquivo é detectada ao decifrar.
 *
 * No app a chave vem do Android Keystore (CifraKeystore); nos testes, de um
 * KeyGenerator comum.
 */
public class CifraAesGcm implements Cifra {

    private static final String TRANSFORMACAO = "AES/GCM/NoPadding";
    private static final int TAMANHO_IV = 12;
    private static final int TAMANHO_TAG_BITS = 128;

    private final SecretKey chave;

    public CifraAesGcm(SecretKey chave) {
        this.chave = chave;
    }

    @Override
    public byte[] cifrar(byte[] dados) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMACAO);
        cipher.init(Cipher.ENCRYPT_MODE, chave, new SecureRandom());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != TAMANHO_IV) {
            throw new GeneralSecurityException("IV inesperado.");
        }
        byte[] cifrado = cipher.doFinal(dados);
        return ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array();
    }

    @Override
    public byte[] decifrar(byte[] dados) throws GeneralSecurityException {
        if (dados == null || dados.length <= TAMANHO_IV) {
            throw new GeneralSecurityException("Dados cifrados incompletos.");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMACAO);
        cipher.init(Cipher.DECRYPT_MODE, chave,
                new GCMParameterSpec(TAMANHO_TAG_BITS, dados, 0, TAMANHO_IV));
        return cipher.doFinal(dados, TAMANHO_IV, dados.length - TAMANHO_IV);
    }
}
