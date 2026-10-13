package br.unifor.cct.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.nitgen.SDK.AndroidBSP.NBioBSPJNI;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.unifor.cct.BuildConfig;
import br.unifor.cct.R;

/**
 * Tela isolada para testar a captura da digital no Hamster DX com o
 * eNBioBSP SDK da Nitgen, antes de existir o HamsterDxReader.
 *
 * - Abrir leitor: o SDK pede a permissão USB e avisa em OnConnected.
 * - Capturar digital: captura e gera o template (FIR) em memória.
 * - Capturar e comparar: captura de novo e compara 1:1 com a anterior
 *   (VerifyMatch), para ver se o mesmo dedo é reconhecido.
 *
 * Nada é gravado em disco nem enviado: a imagem só aparece na tela e o
 * template fica na memória até a tela fechar. O serial do SDK vem de
 * nitgen.serial no local.properties.
 */
public class TesteCapturaActivity extends Activity {

    /** Tempo máximo esperando o dedo, em ms. */
    private static final int TIMEOUT_CAPTURA_MS = 10_000;
    /** Qualidade da imagem (40 a 100) a partir da qual a captura é aceita, como no exemplo da Nitgen. */
    private static final int QUALIDADE_MINIMA = 60;
    private static final DateTimeFormatter FORMATO_REGISTRO = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** A captura bloqueia até ler o dedo, então roda fora da thread principal. */
    private final ExecutorService captura = Executors.newSingleThreadExecutor();

    private NBioBSPJNI bsp;
    private boolean conectado;
    private boolean capturando;
    /** Template da última captura bem-sucedida, só em memória. */
    private volatile NBioBSPJNI.FIR_HANDLE templateAnterior;

    private TextView tvLeitor;
    private Button btnAbrir;
    private Button btnCapturar;
    private Button btnComparar;
    private Button btnCancelar;
    private ImageView ivDigital;
    private TextView tvQualidade;
    private TextView tvRegistro;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teste_captura);

        TextView tvSdk = findViewById(R.id.tvSdk);
        tvLeitor = findViewById(R.id.tvLeitor);
        btnAbrir = findViewById(R.id.btnAbrir);
        btnCapturar = findViewById(R.id.btnCapturar);
        btnComparar = findViewById(R.id.btnComparar);
        btnCancelar = findViewById(R.id.btnCancelar);
        ivDigital = findViewById(R.id.ivDigital);
        tvQualidade = findViewById(R.id.tvQualidade);
        tvRegistro = findViewById(R.id.tvRegistro);

        btnAbrir.setOnClickListener(v -> abrirOuFechar());
        btnCapturar.setOnClickListener(v -> capturar(false));
        btnComparar.setOnClickListener(v -> capturar(true));
        btnCancelar.setOnClickListener(v -> {
            registrar("Cancelando captura...");
            bsp.CaptureCancel();
        });

        if (BuildConfig.NITGEN_SERIAL.isEmpty()) {
            tvSdk.setText("SDK: falta nitgen.serial no local.properties");
            btnAbrir.setEnabled(false);
            atualizarBotoes();
            return;
        }
        bsp = new NBioBSPJNI(BuildConfig.NITGEN_SERIAL, this, callback);
        if (bsp.IsErrorOccured()) {
            tvSdk.setText("SDK: erro ao iniciar (" + erro() + ")");
            btnAbrir.setEnabled(false);
        } else {
            tvSdk.setText("SDK: eNBioBSP " + bsp.GetVersion());
            configurar();
        }
        atualizarBotoes();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        captura.shutdownNow();
        if (bsp != null) {
            if (capturando) bsp.CaptureCancel();
            if (templateAnterior != null) templateAnterior.dispose();
            bsp.dispose();
        }
    }

    /** Mesmos valores do app de exemplo da Nitgen. */
    private void configurar() {
        NBioBSPJNI.INIT_INFO_0 init = bsp.new INIT_INFO_0();
        bsp.GetInitInfo(init);
        init.DefaultTimeout = TIMEOUT_CAPTURA_MS;
        init.EnrollImageQuality = 50;
        init.IdentifyImageQuality = 50;
        init.VerifyImageQuality = 30;
        init.SecurityLevel = NBioBSPJNI.FIR_SECURITY_LEVEL.LOW;
        bsp.SetInitInfo(init);
        if (bsp.IsErrorOccured()) {
            registrar("Aviso: não foi possível ajustar a configuração (" + erro() + ").");
        }
    }

    private void abrirOuFechar() {
        if (conectado) {
            bsp.releaseDevice();
            conectado = false;
            registrar("Leitor fechado.");
        } else {
            registrar("Abrindo leitor... (aceite a permissão USB se o Android pedir)");
            bsp.OpenDevice();
        }
        atualizarBotoes();
    }

    private void capturar(boolean comparar) {
        capturando = true;
        atualizarBotoes();
        tvQualidade.setText("");
        registrar(comparar ? "Coloque o MESMO dedo no leitor..." : "Coloque o dedo no leitor...");

        captura.execute(() -> {
            try {
                capturarNaThread(comparar);
            } catch (RuntimeException e) {
                fimDaCaptura("Erro inesperado: " + e);
            }
        });
    }

    /** Roda na thread de captura. */
    private void capturarNaThread(boolean comparar) {
        NBioBSPJNI.FIR_HANDLE template = bsp.new FIR_HANDLE();
        // Obrigatório, apesar de o guia dizer que pode ser null: o SDK grava
        // a imagem aqui e gera o template a partir dela, sem checar null
        // (com null, a biblioteca nativa derruba o app). Contém a imagem
        // da digital, então é descartado logo após a captura.
        NBioBSPJNI.FIR_HANDLE imagem = bsp.new FIR_HANDLE();
        NBioBSPJNI.CAPTURED_DATA dados = bsp.new CAPTURED_DATA();
        int purpose = comparar ? NBioBSPJNI.FIR_PURPOSE.VERIFY : NBioBSPJNI.FIR_PURPOSE.ENROLL;

        bsp.Capture(purpose, template, TIMEOUT_CAPTURA_MS, imagem, dados);
        // Lido antes do dispose, que pode sobrescrever o código de erro.
        String falha = bsp.IsErrorOccured() ? erro() : null;
        imagem.dispose();

        if (falha != null) {
            template.dispose();
            fimDaCaptura("Captura falhou: " + falha);
            return;
        }

        String resumo = "Captura OK. Template gerado (" + tamanhoTemplate(template) + " caracteres).";
        if (comparar) {
            String resultado = comparar(templateAnterior, template);
            template.dispose();
            fimDaCaptura(resumo + "\n" + resultado);
        } else {
            NBioBSPJNI.FIR_HANDLE antigo = templateAnterior;
            templateAnterior = template;
            if (antigo != null) antigo.dispose();
            fimDaCaptura(resumo + "\nAgora use \"Capturar e comparar\" com o mesmo dedo ou com outro.");
        }
    }

    /** Roda na thread de captura. */
    private String comparar(NBioBSPJNI.FIR_HANDLE a, NBioBSPJNI.FIR_HANDLE b) {
        NBioBSPJNI.INPUT_FIR entradaA = bsp.new INPUT_FIR();
        NBioBSPJNI.INPUT_FIR entradaB = bsp.new INPUT_FIR();
        entradaA.SetFIRHandle(a);
        entradaB.SetFIRHandle(b);
        // O SDK escreve o resultado dentro deste objeto via JNI. Precisa ser
        // uma instância nova: nunca Boolean.FALSE, que é compartilhado.
        @SuppressWarnings({"deprecation", "BooleanConstructorCall", "UseValueOf"})
        Boolean igual = new Boolean(false);
        bsp.VerifyMatch(entradaA, entradaB, igual, null);
        if (bsp.IsErrorOccured()) {
            return "Comparação falhou: " + erro();
        }
        return igual ? "RESULTADO: mesma digital." : "RESULTADO: digitais diferentes.";
    }

    /** Tamanho do template em texto, só para mostrar que ele foi gerado. */
    private int tamanhoTemplate(NBioBSPJNI.FIR_HANDLE template) {
        NBioBSPJNI.FIR_TEXTENCODE texto = bsp.new FIR_TEXTENCODE();
        bsp.GetTextFIRFromHandle(template, texto);
        return bsp.IsErrorOccured() || texto.TextFIR == null ? 0 : texto.TextFIR.length();
    }

    private final NBioBSPJNI.CAPTURE_CALLBACK callback = new NBioBSPJNI.CAPTURE_CALLBACK() {
        @Override
        public void OnConnected() {
            naTela(() -> {
                conectado = true;
                registrar("Leitor aberto.");
                atualizarBotoes();
            });
        }

        @Override
        public void OnDisConnected() {
            naTela(() -> {
                conectado = false;
                registrar("Leitor desconectado (" + erro() + ").");
                atualizarBotoes();
            });
        }

        /**
         * Chamado várias vezes durante a captura, a cada imagem lida.
         * Devolver USER_CANCEL encerra a captura com a imagem atual, como
         * faz o exemplo da Nitgen; NONE continua tentando.
         */
        @Override
        public int OnCaptured(NBioBSPJNI.CAPTURED_DATA dados) {
            int qualidade = dados.getImageQuality();
            Bitmap imagem = dados.getImage();
            naTela(() -> {
                if (imagem != null) ivDigital.setImageBitmap(imagem);
                tvQualidade.setText("Qualidade: " + qualidade
                        + (qualidade >= QUALIDADE_MINIMA ? " (boa)" : " (mantenha o dedo)"));
            });
            if (qualidade >= QUALIDADE_MINIMA) {
                return NBioBSPJNI.ERROR.NBioAPIERROR_USER_CANCEL;
            }
            if (dados.getDeviceError() != NBioBSPJNI.ERROR.NBioAPIERROR_NONE) {
                return dados.getDeviceError();
            }
            return NBioBSPJNI.ERROR.NBioAPIERROR_NONE;
        }
    };

    private void naTela(Runnable acao) {
        runOnUiThread(acao);
    }

    /** Chamado pela thread de captura ao terminar: registra e libera os botões. */
    private void fimDaCaptura(String mensagem) {
        naTela(() -> {
            capturando = false;
            registrar(mensagem);
            atualizarBotoes();
        });
    }

    private String erro() {
        return "código 0x" + Integer.toHexString(bsp.GetErrorCode());
    }

    private void atualizarBotoes() {
        boolean pronto = bsp != null && conectado && !capturando;
        btnAbrir.setText(conectado ? "Fechar leitor" : "Abrir leitor");
        btnAbrir.setEnabled(bsp != null && !capturando);
        btnCapturar.setEnabled(pronto);
        btnComparar.setEnabled(pronto && templateAnterior != null);
        btnCancelar.setEnabled(capturando);
    }

    /** Linha nova no topo do registro, com o horário. */
    private void registrar(String texto) {
        String hora = LocalTime.now().format(FORMATO_REGISTRO);
        CharSequence atual = tvRegistro.getText();
        tvRegistro.setText(hora + "  " + texto + (atual.length() == 0 ? "" : "\n\n" + atual));
    }
}
