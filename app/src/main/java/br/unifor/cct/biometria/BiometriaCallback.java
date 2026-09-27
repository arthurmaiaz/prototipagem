package br.unifor.cct.biometria;

public interface BiometriaCallback {
    void onSucesso(ResultadoBiometria resultado);
    void onErro(String mensagem);
}