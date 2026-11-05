package br.unifor.cct.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import br.unifor.cct.R;
import br.unifor.cct.biometria.BiometriaCallback;
import br.unifor.cct.biometria.BiometriaService;
import br.unifor.cct.biometria.FakeFingerprintMatcher;
import br.unifor.cct.biometria.FakeFingerprintReader;
import br.unifor.cct.biometria.ResultadoBiometria;
import br.unifor.cct.rest.EnderecoLocal;
import br.unifor.cct.rest.ServicoApiNfc;
import br.unifor.cct.rest.TokenApi;

/**
 * Tela isolada so para testar o fluxo de biometria (BiometriaService +
 * fakes), sem depender das telas reais do Nikolas nem do Apps Script.
 *
 * Usa FakeFingerprintReader + FakeFingerprintMatcher, entao roda em
 * emulador, sem hardware. Quando o HamsterDxReader/matcher real estiver
 * pronto, troque as duas linhas do "new BiometriaService(...)" pelas
 * implementacoes reais para testar com o leitor fisico.
 *
 * API REST: o mesmo servidor da tela de NFC (ServicoApiNfc, porta 8787)
 * atende POST /v1/biometric/identify com o leitor simulado. Aqui ele pode
 * ser ligado e a tela mostra IP, porta e token para quem chama.
 */
public class TesteBiometriaActivity extends Activity {

    private static final int PEDIDO_NOTIFICACAO = 1;

    private final BiometriaService biometriaService =
            new BiometriaService(new FakeFingerprintReader(), new FakeFingerprintMatcher());
    private final Handler principal = new Handler(Looper.getMainLooper());

    private TextView tvStatus;
    private ProgressBar progressBar;
    private Button btnIdentificar;
    private TextView tvApiEstado;
    private TextView tvApiToken;
    private TextView tvApiPedido;
    private Button btnApi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teste_biometria);

        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);
        btnIdentificar = findViewById(R.id.btnIdentificar);
        tvApiEstado = findViewById(R.id.tvApiEstado);
        tvApiToken = findViewById(R.id.tvApiToken);
        tvApiPedido = findViewById(R.id.tvApiPedido);
        btnApi = findViewById(R.id.btnApi);

        btnIdentificar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                iniciarIdentificacao();
            }
        });
        btnApi.setOnClickListener(v -> ligarOuDesligarApi());
        findViewById(R.id.btnCopiarToken).setOnClickListener(v -> copiarToken());
        findViewById(R.id.btnTrocarToken).setOnClickListener(v -> {
            TokenApi.trocar(this);
            atualizarApi();
            Toast.makeText(this, "Token trocado. Atualize o appnfc-test-token.txt.",
                    Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        atualizarApi();
    }

    private void iniciarIdentificacao() {
        btnIdentificar.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Capturando digital...");

        biometriaService.identificarProfessor(new BiometriaCallback() {
            @Override
            public void onSucesso(ResultadoBiometria resultado) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnIdentificar.setEnabled(true);

                    if (resultado.isSucesso()) {
                        tvStatus.setText("Professor identificado ("
                                + resultado.getProfessorEmail() + ")\n"
                                + resultado.getMensagem());
                    } else {
                        tvStatus.setText(resultado.getMensagem());
                    }
                });
            }

            @Override
            public void onErro(String mensagem) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    btnIdentificar.setEnabled(true);
                    tvStatus.setText("Erro: " + mensagem);
                });
            }
        });
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
                    + ":" + ServicoApiNfc.PORTA
                    + "\nIP: " + (ip == null ? "<sem rede>" : ip) + " · Porta: " + ServicoApiNfc.PORTA
                    + "\nPOST /v1/biometric/identify · GET /health · GET /v1/audit/logs");
        } else {
            tvApiEstado.setText(erro == null ? "API desligada." : "API desligada. " + erro);
        }
        tvApiToken.setText("Token: " + TokenApi.obter(this));
        tvApiPedido.setText(rodando ? "Leitor da API: simulado (sem Hamster DX)." : "");
    }

    private void copiarToken() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Token da API", TokenApi.obter(this)));
        Toast.makeText(this, "Token copiado.", Toast.LENGTH_SHORT).show();
    }
}
