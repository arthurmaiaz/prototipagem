package br.unifor.cct.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import br.unifor.cct.R;
import br.unifor.cct.biometria.BiometriaCallback;
import br.unifor.cct.biometria.BiometriaService;
import br.unifor.cct.biometria.FakeFingerprintMatcher;
import br.unifor.cct.biometria.FakeFingerprintReader;
import br.unifor.cct.biometria.ResultadoBiometria;

/**
 * Tela isolada so para testar o fluxo de biometria (BiometriaService +
 * fakes), sem depender das telas reais do Nikolas nem do Apps Script.
 *
 * Usa FakeFingerprintReader + FakeFingerprintMatcher, entao roda em
 * emulador, sem hardware. Quando o HamsterDxReader/matcher real estiver
 * pronto, troque as duas linhas do "new BiometriaService(...)" pelas
 * implementacoes reais para testar com o leitor fisico.
 */
public class TesteBiometriaActivity extends Activity {

    private final BiometriaService biometriaService =
            new BiometriaService(new FakeFingerprintReader(), new FakeFingerprintMatcher());

    private TextView tvStatus;
    private ProgressBar progressBar;
    private Button btnIdentificar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teste_biometria);

        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);
        btnIdentificar = findViewById(R.id.btnIdentificar);

        btnIdentificar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                iniciarIdentificacao();
            }
        });
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
                        tvStatus.setText("Professor identificado (id="
                                + resultado.getProfessorId() + ")\n"
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
}
