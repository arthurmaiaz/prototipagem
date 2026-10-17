package br.unifor.cct.biometria.nitgen;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.nitgen.SDK.AndroidBSP.NBioBSPJNI;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Único ponto do app que fala com o eNBioBSP SDK da Nitgen (leitor
 * Hamster DX). HamsterDxReader, NitgenMatcher e CadastroDigitalNitgen
 * usam esta classe; nada fora do pacote nitgen conhece o SDK.
 *
 * Todas as chamadas ao SDK rodam numa única thread (executar), porque a
 * captura bloqueia até ler o dedo e o SDK não é feito para ser chamado
 * de várias threads ao mesmo tempo. Os métodos marcados "só dentro de
 * executar" são síncronos e não podem ser chamados de outro lugar.
 *
 * Os templates circulam como texto (TextFIR, base64 gerado pelo SDK).
 * A busca de quem colocou o dedo não usa o IndexSearch do SDK: o
 * BiometricDatabase percorre os arrays de digitais e chama mesmaDigital
 * para cada posição. Para percorrer os arrays ao mesmo tempo, cada thread
 * extra usa a sua própria instância do SDK (criarComparacoes), só para
 * comparar: a instância principal nunca é usada por duas threads.
 * A imagem da digital nunca sai desta classe, a não ser para o Listener
 * mostrar na tela durante a captura.
 *
 * Criar na thread principal: o SDK cria um Handler no construtor.
 */
public class NitgenSdk {

    /** Tempo máximo esperando o dedo, em ms. */
    public static final int TIMEOUT_CAPTURA_MS = 10_000;
    /**
     * Qualidade da imagem a partir da qual a captura é encerrada pelo app.
     * O Hamster DX só informa 60, 80 ou 100, e o próprio SDK às vezes
     * encerra a captura em 60 antes de chegar aqui.
     */
    public static final int QUALIDADE_MINIMA = 80;
    /** Menor qualidade que o SDK devolve; é a aceita no cadastro. */
    public static final int QUALIDADE_ACEITA = 60;
    /**
     * Tempo máximo esperando o leitor dizer que o dedo saiu. No Hamster DX o
     * CheckFinger continuou indicando dedo mesmo depois de tirado, então
     * passado esse tempo o cadastro segue (vira uma pausa fixa).
     */
    private static final int TIMEOUT_TIRAR_DEDO_MS = 3_000;
    /** Mínimo entre as leituras do cadastro, mesmo que o leitor diga que não há dedo. */
    private static final int INTERVALO_MINIMO_MS = 700;
    /**
     * Nível de segurança da comparação 1:1 (escala de 1 a 9).
     * Com NORMAL (5), o indicador de uma pessoa foi reconhecido como o de
     * outra nos testes. Confundir professores é pior do que pedir para
     * colocar o dedo de novo, então fica em HIGH.
     */
    private static final int NIVEL_SEGURANCA = NBioBSPJNI.FIR_SECURITY_LEVEL.HIGH;
    private static final String TAG = "NitgenSdk";

    /** Eventos do leitor, sempre entregues na thread principal. */
    public interface Listener {
        void onConectado();

        void onDesconectado();

        /** Cada imagem lida durante a captura, para feedback na tela. */
        void onImagem(int qualidade, Bitmap imagem);
    }

    public interface Tarefa<T> {
        T executar() throws NitgenException;
    }

    public interface Resultado<T> {
        void onSucesso(T valor);

        void onErro(String mensagem);
    }

    private final NBioBSPJNI bsp;
    private final Context contexto;
    private final String serial;
    private final List<Comparacao> comparacoes = new ArrayList<>();
    /** Por que a última instância extra falhou (só para a tela de teste). */
    private volatile String falhaComparacao;
    private final ExecutorService thread = Executors.newSingleThreadExecutor();
    private final Handler principal = new Handler(Looper.getMainLooper());

    private volatile Listener listener;
    private volatile boolean conectado;

    /**
     * @param serial código serial do SDK (nitgen.serial no local.properties)
     * @throws NitgenException se o SDK não iniciar (ex.: serial inválido)
     */
    public NitgenSdk(Context context, String serial) throws NitgenException {
        if (serial == null || serial.isEmpty()) {
            throw new NitgenException("Falta nitgen.serial no local.properties.");
        }
        this.contexto = context.getApplicationContext();
        this.serial = serial;
        long inicio = SystemClock.elapsedRealtime();
        bsp = new NBioBSPJNI(serial, contexto, callback);
        Log.d(TAG, "SDK iniciado em " + (SystemClock.elapsedRealtime() - inicio) + " ms");
        if (bsp.IsErrorOccured()) {
            String erro = codigo(bsp.GetErrorCode());
            bsp.dispose();
            throw new NitgenException("O SDK do leitor não iniciou (" + erro + ").");
        }
        configurar(bsp);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public String getVersao() {
        return bsp.GetVersion();
    }

    public boolean isConectado() {
        return conectado;
    }

    /** Abre o leitor. O SDK pede a permissão USB; o resultado chega no Listener. */
    public void abrir() {
        bsp.OpenDevice();
    }

    public void fechar() {
        bsp.releaseDevice();
        conectado = false;
    }

    /** Interrompe uma captura em andamento; ela termina com "Captura cancelada". */
    public void cancelarCaptura() {
        bsp.CaptureCancel();
    }

    /**
     * Chamar quando o app não for mais usar o leitor. Cancela a captura em
     * andamento e libera o leitor na thread do SDK, depois da tarefa atual,
     * para não travar a tela ao fechar.
     */
    public void encerrar() {
        bsp.CaptureCancel();
        thread.execute(() -> {
            long inicio = SystemClock.elapsedRealtime();
            bsp.releaseDevice();
            bsp.dispose();
            for (Comparacao c : comparacoes) c.liberar();
            comparacoes.clear();
            Log.d(TAG, "SDK liberado em " + (SystemClock.elapsedRealtime() - inicio) + " ms");
        });
        thread.shutdown();
    }

    /** Roda a tarefa na thread do SDK e entrega o resultado na thread principal. */
    public <T> void executar(Tarefa<T> tarefa, Resultado<T> resultado) {
        thread.execute(() -> {
            try {
                T valor = tarefa.executar();
                principal.post(() -> resultado.onSucesso(valor));
            } catch (NitgenException e) {
                principal.post(() -> resultado.onErro(e.getMessage()));
            } catch (RuntimeException e) {
                principal.post(() -> resultado.onErro("Erro inesperado no leitor: " + e));
            }
        });
    }

    /** Entrega um aviso na thread principal, de dentro de uma tarefa. */
    public void naPrincipal(Runnable acao) {
        principal.post(acao);
    }

    // ------------------------------------------------- só dentro de executar

    /**
     * Captura uma digital e devolve o template em texto.
     *
     * @param finalidade NBioBSPJNI.FIR_PURPOSE: ENROLL para cadastrar e para
     *                   identificar (comparada com as amostras do cadastro,
     *                   que também são ENROLL), VERIFY para a comparação 1:1
     */
    public String capturar(int finalidade) throws NitgenException {
        return capturar(finalidade, 0);
    }

    /**
     * Como capturar(finalidade), mas recusa a leitura se a qualidade final
     * ficar abaixo de qualidadeExigida (usado no cadastro).
     */
    public String capturar(int finalidade, int qualidadeExigida) throws NitgenException {
        if (!conectado) {
            throw new NitgenException("Leitor biométrico não conectado.");
        }
        NBioBSPJNI.FIR_HANDLE template = bsp.new FIR_HANDLE();
        // Obrigatório, apesar de o guia dizer que pode ser null: o SDK grava
        // a imagem aqui e gera o template a partir dela, sem checar null.
        // Contém a imagem da digital, então é descartado logo em seguida.
        NBioBSPJNI.FIR_HANDLE imagem = bsp.new FIR_HANDLE();
        NBioBSPJNI.CAPTURED_DATA dados = bsp.new CAPTURED_DATA();
        try {
            bsp.Capture(finalidade, template, TIMEOUT_CAPTURA_MS, imagem, dados);
            // Lido antes dos dispose, que podem sobrescrever o código de erro.
            if (bsp.IsErrorOccured()) {
                int erro = bsp.GetErrorCode();
                Log.d(TAG, "Capture(finalidade=" + finalidade + ") erro " + codigo(erro));
                throw new NitgenException(mensagemDeErro(erro));
            }
            NBioBSPJNI.FIR_TEXTENCODE texto = bsp.new FIR_TEXTENCODE();
            bsp.GetTextFIRFromHandle(template, texto, NBioBSPJNI.FIR_FORMAT.STANDARD);
            if (bsp.IsErrorOccured() || texto.TextFIR == null || texto.TextFIR.isEmpty()) {
                throw new NitgenException("O leitor não gerou o template da digital.");
            }
            int qualidade = dados.getImageQuality();
            Log.d(TAG, "Capture(finalidade=" + finalidade + ") qualidade " + qualidade
                    + ", template " + texto.TextFIR.length() + " caracteres");
            if (qualidade < qualidadeExigida) {
                throw new NitgenException("Leitura fraca (qualidade " + qualidade
                        + "). Apoie o dedo inteiro, sem pressionar demais, e tente de novo.");
            }
            return texto.TextFIR;
        } finally {
            imagem.dispose();
            template.dispose();
        }
    }

    /**
     * Dá tempo para o dedo sair do leitor, para a próxima leitura ser uma
     * colocação nova do dedo, e não a mesma imagem de novo. Usa o
     * CheckFinger quando ele responde; senão, espera o tempo máximo.
     */
    public void aguardarDedoSair() throws NitgenException {
        long inicio = SystemClock.elapsedRealtime();
        byte[] temDedo = new byte[1];
        int ultimoErro = -1;
        int ultimoValor = -1;
        while (true) {
            long passou = SystemClock.elapsedRealtime() - inicio;
            int erro = bsp.CheckFinger(temDedo);
            if (erro != ultimoErro || temDedo[0] != ultimoValor) {
                Log.d(TAG, "CheckFinger " + codigo(erro) + ", dedo=" + temDedo[0] + " em " + passou + " ms");
                ultimoErro = erro;
                ultimoValor = temDedo[0];
            }
            if (erro == NBioBSPJNI.ERROR.NBioAPIERROR_NONE && temDedo[0] == 0
                    && passou >= INTERVALO_MINIMO_MS) {
                return;
            }
            if (passou >= TIMEOUT_TIRAR_DEDO_MS) {
                Log.d(TAG, "Sem confirmação de que o dedo saiu; seguindo após " + passou + " ms");
                return;
            }
            dormir(150);
        }
    }

    private static void dormir(long ms) throws NitgenException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NitgenException("Leitura interrompida.");
        }
    }

    /** Compara dois templates 1:1. */
    public boolean mesmaDigital(String a, String b) throws NitgenException {
        return comparar(bsp, a, b);
    }

    /**
     * Cria até `quantas` instâncias extras do SDK, cada uma para ser usada
     * por uma thread da busca em paralelo. Para na primeira que falhar: o
     * SDK beta pode não aceitar mais de uma instância, e aí a busca fica
     * sequencial. As instâncias são liberadas em encerrar().
     */
    public List<Comparacao> criarComparacoes(int quantas) {
        falhaComparacao = null;
        while (comparacoes.size() < quantas) {
            NBioBSPJNI extra;
            try {
                extra = new NBioBSPJNI(serial, contexto, SEM_EVENTOS);
            } catch (RuntimeException | UnsatisfiedLinkError e) {
                Log.w(TAG, "Instância extra do SDK não foi criada: " + e);
                falhaComparacao = e.toString();
                break;
            }
            if (extra.IsErrorOccured()) {
                Log.w(TAG, "Instância extra do SDK com erro " + codigo(extra.GetErrorCode()));
                falhaComparacao = "erro " + codigo(extra.GetErrorCode());
                extra.dispose();
                break;
            }
            configurar(extra);
            comparacoes.add(new Comparacao(extra));
        }
        return new ArrayList<>(comparacoes);
    }

    /** Por que a última instância extra não foi criada (null se não falhou). */
    public String getFalhaComparacao() {
        return falhaComparacao;
    }

    /** Libera as instâncias extras (ex.: a busca em paralelo não passou na conferência). */
    public void liberarComparacoes() {
        for (Comparacao c : comparacoes) c.liberar();
        comparacoes.clear();
    }

    /**
     * Instância extra do SDK, só para VerifyMatch. Cada uma deve ser usada
     * por uma thread de cada vez.
     */
    public static final class Comparacao {
        private final NBioBSPJNI bsp;

        private Comparacao(NBioBSPJNI bsp) {
            this.bsp = bsp;
        }

        public boolean mesmaDigital(String a, String b) throws NitgenException {
            return comparar(bsp, a, b);
        }

        private void liberar() {
            bsp.dispose();
        }
    }

    // ------------------------------------------------------------ internos

    private static boolean comparar(NBioBSPJNI bsp, String a, String b) throws NitgenException {
        // O SDK escreve o resultado dentro deste objeto via JNI. Precisa ser
        // uma instância nova: nunca Boolean.FALSE, que é compartilhado.
        @SuppressWarnings({"deprecation", "BooleanConstructorCall", "UseValueOf"})
        Boolean igual = new Boolean(false);
        bsp.VerifyMatch(entrada(bsp, a), entrada(bsp, b), igual, null);
        if (bsp.IsErrorOccured()) {
            throw new NitgenException("Falha ao comparar digitais (" + codigo(bsp.GetErrorCode()) + ").");
        }
        return igual;
    }

    private static NBioBSPJNI.INPUT_FIR entrada(NBioBSPJNI bsp, String template) {
        NBioBSPJNI.FIR_TEXTENCODE texto = bsp.new FIR_TEXTENCODE();
        texto.TextFIR = template;
        NBioBSPJNI.INPUT_FIR entrada = bsp.new INPUT_FIR();
        entrada.SetTextFIR(texto);
        return entrada;
    }

    /** Mesmos valores do app de exemplo da Nitgen. */
    private static void configurar(NBioBSPJNI bsp) {
        NBioBSPJNI.INIT_INFO_0 init = bsp.new INIT_INFO_0();
        bsp.GetInitInfo(init);
        init.DefaultTimeout = TIMEOUT_CAPTURA_MS;
        init.EnrollImageQuality = 50;
        init.IdentifyImageQuality = 50;
        init.VerifyImageQuality = 30;
        init.SecurityLevel = NIVEL_SEGURANCA;
        bsp.SetInitInfo(init);
    }

    private static String mensagemDeErro(int codigo) {
        switch (codigo) {
            case NBioBSPJNI.ERROR.NBioAPIERROR_CAPTURE_TIMEOUT:
                return "Nenhum dedo detectado a tempo. Tente de novo.";
            case NBioBSPJNI.ERROR.NBioAPIERROR_USER_CANCEL:
                return "Captura cancelada.";
            case NBioBSPJNI.ERROR.NBioAPIERROR_DEVICE_NOT_OPENED:
                return "Leitor biométrico não conectado.";
            default:
                return "Erro do leitor (" + codigo(codigo) + ").";
        }
    }

    private static String codigo(int codigo) {
        return "código 0x" + Integer.toHexString(codigo);
    }

    /** Instâncias extras não abrem o leitor: os eventos são ignorados. */
    private static final NBioBSPJNI.CAPTURE_CALLBACK SEM_EVENTOS = new NBioBSPJNI.CAPTURE_CALLBACK() {
        @Override
        public void OnConnected() {
        }

        @Override
        public void OnDisConnected() {
        }

        @Override
        public int OnCaptured(NBioBSPJNI.CAPTURED_DATA dados) {
            return NBioBSPJNI.ERROR.NBioAPIERROR_USER_CANCEL;
        }
    };

    private final NBioBSPJNI.CAPTURE_CALLBACK callback = new NBioBSPJNI.CAPTURE_CALLBACK() {
        @Override
        public void OnConnected() {
            conectado = true;
            principal.post(() -> {
                Listener l = listener;
                if (l != null) l.onConectado();
            });
        }

        @Override
        public void OnDisConnected() {
            conectado = false;
            principal.post(() -> {
                Listener l = listener;
                if (l != null) l.onDesconectado();
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
            principal.post(() -> {
                Listener l = listener;
                if (l != null) l.onImagem(qualidade, imagem);
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
}
