package br.unifor.cct.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import br.unifor.cct.R;

/**
 * Tela de Perguntas Frequentes (FAQ) do aplicativo de Reservas do CCT - Unifor.
 * Apresenta de forma organizada em sanfona interativa as dúvidas e respostas
 * estritamente baseadas na documentação e funcionamento técnico do projeto.
 */
public class FaqActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_faq);

        ImageView btnVoltar = findViewById(R.id.btnVoltarFaq);
        btnVoltar.setOnClickListener(v -> finish());

        configurarItemFaq(R.id.cardFaq1, R.id.tvRespostaFaq1, R.id.ivChevronFaq1);
        configurarItemFaq(R.id.cardFaq2, R.id.tvRespostaFaq2, R.id.ivChevronFaq2);
        configurarItemFaq(R.id.cardFaq3, R.id.tvRespostaFaq3, R.id.ivChevronFaq3);
        configurarItemFaq(R.id.cardFaq4, R.id.tvRespostaFaq4, R.id.ivChevronFaq4);
        configurarItemFaq(R.id.cardFaq5, R.id.tvRespostaFaq5, R.id.ivChevronFaq5);
        configurarItemFaq(R.id.cardFaq6, R.id.tvRespostaFaq6, R.id.ivChevronFaq6);
    }

    private void configurarItemFaq(int cardId, int respostaId, int chevronId) {
        View card = findViewById(cardId);
        TextView resposta = findViewById(respostaId);
        ImageView chevron = findViewById(chevronId);

        if (card != null && resposta != null && chevron != null) {
            card.setOnClickListener(v -> {
                boolean visivel = resposta.getVisibility() == View.VISIBLE;
                if (visivel) {
                    resposta.setVisibility(View.GONE);
                    chevron.setImageResource(R.drawable.ic_expand_more);
                } else {
                    resposta.setVisibility(View.VISIBLE);
                    chevron.setImageResource(R.drawable.ic_expand_less);
                }
            });
        }
    }
}
