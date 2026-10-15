package br.unifor.cct.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import br.unifor.cct.R;
import br.unifor.cct.nfc.NfcService;
import br.unifor.cct.nfc.ResultadoNfc;
import br.unifor.cct.rest.EnderecoLocal;
import br.unifor.cct.rest.PonteNfc;
import br.unifor.cct.rest.ServicoApiNfc;
import br.unifor.cct.rest.TokenApi;

/**
 * Tela isolada só para testar o NfcService num celular de verdade, sem
 * depender das telas do Nikolas nem do Apps Script.
 *
 * - Leitura: com a tela aberta, encoste uma tag e o código lido aparece.
 * - Cadastro: digite o código, toque em "Gravar tag" e encoste a tag.
 * - API REST: "Ligar API" sobe o ServicoApiNfc (porta 8787). Um
 *   POST /v1/nfc/read fica esperando a próxima tag lida nesta tela.
 *
 * Precisa de aparelho com NFC; no emulador a tela só mostra que o NFC
 * não está disponível.
 */
public class TesteNfcActivity extends Activity implements PonteNfc.Ouvinte {

    private static final int PEDIDO_NOTIFICACAO = 1;

    private final PonteNfc ponte = PonteNfc.padrao();
    private final Handler principal = new Handler(Looper.getMainLooper());

    private NfcService nfc;

    private TextView tvEstadoNfc;
    private Button btnConfiguracoes;
    private TextView tvModo;
    private TextView tvStatus;
    private EditText etCodigo;
    private CheckBox cbTravar;
    private Button btnGravar;
    private Button btnCancelar;
    private TextView tvApiEstado;
    private TextView tvApiToken;
    private TextView tvApiPedido;
    private Button btnApi;

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
        tvApiEstado = findViewById(R.id.tvApiEstado);
        tvApiToken = findViewById(R.id.tvApiToken);
        tvApiPedido = findViewById(R.id.tvApiPedido);
        btnApi = findViewById(R.id.btnApi);

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
        btnApi.setOnClickListener(v -> ligarOuDesligarApi());
        findViewById(R.id.btnCopiarToken).setOnClickListener(v -> copiarToken());
        findViewById(R.id.btnTrocarToken).setOnClickListener(v -> {
            TokenApi.trocar(this);
            atualizarApi();
            Toast.makeText(this, "Token trocado. Atualize o appnfc-test-token.txt.",
                    Toast.LENGTH_LONG).show();
        });

        atualizarModo();
    }

    /*
     * A ponte com a API usa onStart/onStop, e não onResume/onPause: cada tag
     * lida chega com onPause -> onNewIntent -> onResume, e marcar a tela como
     * fechada no onPause cancelaria o pedido da API bem na hora da leitura.
     */
    @Override
    protected void onStart() {
        super.onStart();
        ponte.setOuvinte(this);
        ponte.setTelaAberta(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rechecado aqui porque o usuário pode ter ligado o NFC nas configurações.
        atualizarEstadoNfc();
        nfc.habilitarLeitura();
        atualizarApi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfc.desabilitarLeitura();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ponte.setTelaAberta(false);
        ponte.setOuvinte(null);
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
        boolean paraApi = ponte.entregar(resultado);
        tvStatus.setText((resultado.isSucesso()
                ? "Código lido: " + resultado.getCodigoProjetor() + "\n" + resultado.getMensagem()
                : "Erro: " + resultado.getMensagem())
                + (paraApi ? "\n(enviado para a API)" : ""));
    }

    private void atualizarModo() {
        boolean gravando = nfc.isGravando();
        ponte.setGravando(gravando);
        tvModo.setText(gravando ? "Modo: gravação" : "Modo: leitura");
        btnGravar.setEnabled(!gravando && nfc.isAtivado());
        btnCancelar.setEnabled(gravando);
        etCodigo.setEnabled(!gravando);
        cbTravar.setEnabled(!gravando);
    }

    private void atualizarEstadoNfc() {
        ponte.setNfc(nfc.isDisponivel(), nfc.isAtivado());
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

    // ------------------------------------------------------------- API REST

    private void ligarOuDesligarApi() {
        if (ServicoApiNfc.isRodando()) {
            ServicoApiNfc.parar(this);
        } else {
            // Sem a permissão a API funciona, só a notificação não aparece.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PEDIDO_NOTIFICACAO);
            }
            ServicoApiNfc.iniciar(this);
        }
        // O serviço sobe de forma assíncrona; confere de novo logo depois.
        principal.postDelayed(this::atualizarApi, 500);
    }

    private void atualizarApi() {
        boolean rodando = ServicoApiNfc.isRodando();
        btnApi.setText(rodando ? "Desligar API" : "Ligar API");
        String ip = EnderecoLocal.ipv4();
        String erro = ServicoApiNfc.getUltimoErro();
        if (rodando) {
            tvApiEstado.setText("No ar em http://" + (ip == null ? "<sem rede>" : ip)
                    + ":" + ServicoApiNfc.PORTA + "\nGET /health · POST /v1/nfc/read · GET /v1/audit/logs");
        } else {
            tvApiEstado.setText(erro == null ? "API desligada." : "API desligada. " + erro);
        }
        tvApiToken.setText("Token: " + TokenApi.obter(this));
        tvApiPedido.setText(ponte.haPedidoPendente() ? "Pedido da API: encoste a tag agora." : "");
    }

    private void copiarToken() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Token da API NFC", TokenApi.obter(this)));
        Toast.makeText(this, "Token copiado.", Toast.LENGTH_SHORT).show();
    }

    // ----------------------------------------------------- PonteNfc.Ouvinte

    @Override
    public void onPedidoIniciado() {
        principal.post(this::atualizarApi);
    }

    @Override
    public void onPedidoEncerrado() {
        principal.post(this::atualizarApi);
    }
}
