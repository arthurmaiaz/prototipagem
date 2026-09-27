package br.unifor.cct.biometria;

/**
 * Callback exposto pelo BiometriaService para a UI (ex.: TesteBiometriaActivity,
 * futura IdentificacaoActivity do Nikolas). Mesma ideia do ReservaCallback
 * em api/.
 */
public interface BiometriaCallback {
    void onSucesso(ResultadoBiometria resultado);
    void onErro(String mensagem);
}
