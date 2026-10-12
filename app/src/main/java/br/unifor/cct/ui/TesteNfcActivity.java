package br.unifor.cct.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Locale;

import br.unifor.cct.R;
import br.unifor.cct.nfc.NfcService;
import br.unifor.cct.nfc.ResultadoNfc;

/**
 * Tela isolada só para testar o NfcService num celular de verdade, sem
 * depender das telas do Nikolas nem do Apps Script.
 *
 * - Leitura: com a tela aberta, encoste uma tag e o código lido aparece.
 * - Cadastro: digite o código, toque em "Gravar tag" e encoste a tag.
 *
 * Precisa de aparelho com NFC; no emulador a tela só mostra que o NFC
 * não está disponível.
 */
public class TesteNfcActivity extends Activity {

    private NfcService nfc;

    private TextView tvEstadoNfc;
    private Button btnConfiguracoes;
    private TextView tvModo;
    private TextView tvStatus;
    private EditText etCodigo;
    private CheckBox cbTravar;
    private Button btnGravar;
    private Button btnCancelar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teste_nfc);

        tvEstadoNfc = findViewById(R.id.tvEstadoNfc);
        btnConfiguracoes = findViewById(R.id.btnConfiguracoes);
        tvModo = findViewById(R.id.tvModo);
        tvStatus = findViewById(R.id.tvStatus);
        etCodigo = findViewById(R.id.etCodigo);
        cbTravar = findViewById(R.id.cbTravar);
        btnGravar = findViewById(R.id.btnGravar);
        btnCancelar = findViewById(R.id.btnCancelar);

        nfc = new NfcService(this);
        nfc.setLeituraCallback(this::mostrarLeitura);

        btnConfiguracoes.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
        btnGravar.setOnClickListener(v -> iniciarGravacao());
        btnCancelar.setOnClickListener(v -> {
            nfc.cancelarGravacao();
            tvStatus.setText("Gravação cancelada.");
            atualizarModo();
        });

        atualizarModo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rechecado aqui porque o usuário pode ter ligado o NFC nas configurações.
        atualizarEstadoNfc();
        nfc.habilitarLeitura();
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfc.desabilitarLeitura();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        nfc.processarIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nfc.encerrar();
    }

    private void iniciarGravacao() {
        String codigo = etCodigo.getText().toString();
        boolean travar = cbTravar.isChecked();
        nfc.iniciarGravacao(codigo, travar, (sucesso, mensagem) -> {
            tvStatus.setText(sucesso ? mensagem : "Erro: " + mensagem);
            atualizarModo();
        });
        if (nfc.isGravando()) {
            tvStatus.setText("Encoste a tag para gravar " + codigo.trim().toUpperCase(Locale.ROOT) + ".");
            atualizarModo();
        }
    }

    private void mostrarLeitura(ResultadoNfc resultado) {
        tvStatus.setText(resultado.isSucesso()
                ? "Código lido: " + resultado.getCodigoProjetor() + "\n" + resultado.getMensagem()
                : "Erro: " + resultado.getMensagem());
    }

    private void atualizarModo() {
        boolean gravando = nfc.isGravando();
        tvModo.setText(gravando ? "Modo: gravação" : "Modo: leitura");
        btnGravar.setEnabled(!gravando && nfc.isAtivado());
        btnCancelar.setEnabled(gravando);
        etCodigo.setEnabled(!gravando);
        cbTravar.setEnabled(!gravando);
    }

    private void atualizarEstadoNfc() {
        if (!nfc.isDisponivel()) {
            tvEstadoNfc.setText("Este aparelho não tem NFC.");
            btnConfiguracoes.setVisibility(View.GONE);
        } else if (!nfc.isAtivado()) {
            tvEstadoNfc.setText("O NFC está desligado.");
            btnConfiguracoes.setVisibility(View.VISIBLE);
        } else {
            tvEstadoNfc.setText("NFC ligado.");
            btnConfiguracoes.setVisibility(View.GONE);
        }
        atualizarModo();
    }
}
