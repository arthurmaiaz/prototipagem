package br.unifor.cct.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Provisório: menu com as telas de teste, para abrir o app pelo ícone sem
 * precisar do adb. Será substituído pelo fluxo final (identificação,
 * leitura do projetor e confirmação da reserva).
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        int margem = (int) (24 * getResources().getDisplayMetrics().density);
        lista.setPadding(margem, margem, margem, margem);

        TextView titulo = new TextView(this);
        titulo.setText("Reserva de Projetores CCT\nTelas de teste");
        titulo.setTextSize(20);
        titulo.setPadding(0, 0, 0, margem);
        lista.addView(titulo);

        adicionar(lista, "Digital (Hamster DX)", TesteDigitalActivity.class);
        adicionar(lista, "Reserva (Apps Script)", TesteReservaActivity.class);
        adicionar(lista, "NFC", TesteNfcActivity.class);
        adicionar(lista, "Captura (SDK Nitgen)", TesteCapturaActivity.class);
        adicionar(lista, "Conexão USB", TesteUsbActivity.class);
        adicionar(lista, "Biometria fake", TesteBiometriaActivity.class);

        ScrollView rolagem = new ScrollView(this);
        rolagem.addView(lista);
        setContentView(rolagem);
    }

    private void adicionar(LinearLayout lista, String texto, Class<? extends Activity> tela) {
        Button botao = new Button(this);
        botao.setText(texto);
        botao.setOnClickListener(v -> startActivity(new Intent(this, tela)));
        lista.addView(botao);
    }
}
