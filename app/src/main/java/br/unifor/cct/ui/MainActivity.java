package br.unifor.cct.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import br.unifor.cct.R;

/**
 * Tela inicial do aplicativo de Reserva de Projetores (CCT - Unifor).
 * Permite navegação direta aos módulos do sistema com layout moderno
 * inspirado na identidade visual da Unifor e no Unifor Mobile.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View cardDigital = findViewById(R.id.cardCadastrarDigital);
        View cardReserva = findViewById(R.id.cardReservar);
        View cardNfc = findViewById(R.id.cardCadastrarTagNfc);
        View cardCaptura = findViewById(R.id.cardTesteCaptura);
        View cardUsb = findViewById(R.id.cardConexaoUsb);
        View cardBiometriaFake = findViewById(R.id.cardBiometriaFake);

        vincularAcesso(cardDigital, TesteDigitalActivity.class);
        vincularAcesso(cardReserva, TesteReservaActivity.class);
        vincularAcesso(cardNfc, TesteNfcActivity.class);
        vincularAcesso(cardCaptura, TesteCapturaActivity.class);
        vincularAcesso(cardUsb, TesteUsbActivity.class);
        vincularAcesso(cardBiometriaFake, TesteBiometriaActivity.class);

        animarEntrada(cardDigital, cardReserva, cardNfc, cardCaptura, cardUsb, cardBiometriaFake);
    }

    private void vincularAcesso(View view, Class<? extends Activity> destino) {
        if (view != null) {
            view.setOnClickListener(v -> startActivity(new Intent(this, destino)));
        }
    }

    private void animarEntrada(View... cards) {
        long delay = 50;
        for (View card : cards) {
            if (card != null) {
                card.setAlpha(0f);
                card.setTranslationY(20f);
                card.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(260)
                        .setStartDelay(delay)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                delay += 40;
            }
        }
    }
}
