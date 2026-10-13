package br.unifor.cct.biometria;

import java.security.GeneralSecurityException;

/**
 * Cifra usada pelo BiometricDatabase. Existe como interface para os testes
 * unitários usarem uma chave comum, já que o Android Keystore só funciona
 * no aparelho.
 */
public interface Cifra {

    byte[] cifrar(byte[] dados) throws GeneralSecurityException;

    byte[] decifrar(byte[] dados) throws GeneralSecurityException;
}
