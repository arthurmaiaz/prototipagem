package br.unifor.cct.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import br.unifor.cct.R;

/**
 * Tela do Assistente Virtual do CCT - Unifor.
 * Apresenta a interface inicial do assistente com tópicos rápidos de ajuda e
 * deixa explícito que a integração com o serviço de inteligência artificial
 * depende de backend futuro, sem simular respostas automatizadas inexistentes.
 */
public class AssistenteActivity extends Activity {

    private ImageView btnVoltar;
    private View chipSugestao1;
    private View chipSugestao2;
    private View chipSugestao3;
    private TextView tvFeedback;
    private EditText etMensagem;
    private View btnEnviar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assistente);

        btnVoltar = findViewById(R.id.btnVoltarAssistente);
        chipSugestao1 = findViewById(R.id.chipSugestao1);
        chipSugestao2 = findViewById(R.id.chipSugestao2);
        chipSugestao3 = findViewById(R.id.chipSugestao3);
        tvFeedback = findViewById(R.id.tvAssistenteFeedback);
        etMensagem = findViewById(R.id.etAssistenteMensagem);
        btnEnviar = findViewById(R.id.btnEnviarAssistente);

        btnVoltar.setOnClickListener(v -> finish());

        chipSugestao1.setOnClickListener(v -> preencherSugestao(
                "Como realizar uma reserva de projetor?",
                "Para reservar, selecione o módulo 'Reservar' na tela inicial, informe o ID do professor e o código patrimonial do projetor (ex: PAT-001)."));

        chipSugestao2.setOnClickListener(v -> preencherSugestao(
                "Como cadastrar minha digital no leitor USB?",
                "Conecte o leitor Hamster DX via cabo USB OTG, acesse 'Cadastrar digital' e posicione o dedo no prisma do sensor até a confirmação."));

        chipSugestao3.setOnClickListener(v -> preencherSugestao(
                "Privacidade de dados e LGPD no CCT",
                "Seus dados biométricos nunca saem do aparelho. Eles ficam cifrados no banco local e apenas seu identificador numérico é enviado para o sistema de reservas."));

        btnEnviar.setOnClickListener(v -> {
            String texto = etMensagem.getText().toString().trim();
            if (texto.isEmpty()) {
                Toast.makeText(this, "Digite sua dúvida antes de enviar.", Toast.LENGTH_SHORT).show();
                return;
            }
            // Deixa explícito que a inteligência artificial depende de integração futura
            if (tvFeedback != null) {
                tvFeedback.setVisibility(View.VISIBLE);
                tvFeedback.setText(R.string.assistente_envio_pendente);
            }
        });
    }

    private void preencherSugestao(String pergunta, String orientacao) {
        if (etMensagem != null) {
            etMensagem.setText(pergunta);
            etMensagem.setSelection(pergunta.length());
        }
        if (tvFeedback != null) {
            tvFeedback.setVisibility(View.VISIBLE);
            tvFeedback.setText("ℹ️ " + orientacao + "\n\n(Nota: o assistente interativo por IA depende de integração futura)");
        }
    }
}
