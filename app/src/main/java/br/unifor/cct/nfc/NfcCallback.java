package br.unifor.cct.nfc;

/**
 * Recebe o resultado de cada leitura de tag NFC.
 * Sempre chamado na thread principal, então a UI pode atualizar as views direto.
 */
public interface NfcCallback {

    void onResultado(ResultadoNfc resultado);
}
