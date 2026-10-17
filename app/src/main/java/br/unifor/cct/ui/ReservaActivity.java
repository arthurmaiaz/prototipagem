package br.unifor.cct.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.List;

import br.unifor.cct.BuildConfig;
import br.unifor.cct.R;
import br.unifor.cct.api.ProfessoresCallback;
import br.unifor.cct.api.ReservaApiFactory;
import br.unifor.cct.biometria.BiometriaCallback;
import br.unifor.cct.biometria.BiometriaService;
import br.unifor.cct.biometria.BiometricDatabase;
import br.unifor.cct.biometria.nitgen.HamsterDxReader;
import br.unifor.cct.biometria.nitgen.NitgenException;
import br.unifor.cct.biometria.nitgen.NitgenMatcher;
import br.unifor.cct.biometria.nitgen.NitgenSdk;
import br.unifor.cct.fluxo.FluxoReserva;
import br.unifor.cct.fluxo.FluxoReserva.Estado;
import br.unifor.cct.fluxo.FluxoReserva.Etapa;
import br.unifor.cct.fluxo.FluxoReserva.Modo;
import br.unifor.cct.model.Professor;
import br.unifor.cct.nfc.NfcService;
import br.unifor.cct.storage.ProfessorRepository;

/**
 * Tela do terminal (Fase 5): digital -> etiqueta do projetor -> horário ->
 * confirmação, ou só a etiqueta para devolver.
 *
 * Toda a regra está no FluxoReserva; aqui só se liga o leitor, o NFC e os
 * botões ao fluxo e se desenha o Estado. O visual é provisório.
 *
 * Depois de concluir, a tela volta sozinha ao começo em VOLTA_AUTOMATICA_MS.
 * Parada no meio por INATIVIDADE_MS, também volta ao começo, para o
 * próximo professor não continuar a reserva do anterior.
 */
public class ReservaActivity extends Activity implements NitgenSdk.Listener {

    private static final long VOLTA_AUTOMATICA_MS = 15_000;
    private static final long INATIVIDADE_MS = 120_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recomecar = this::reiniciarFluxo;

    private FluxoReserva fluxo;
    private NitgenSdk sdk;
    private NfcService nfc;
    private ProfessorRepository professores;
    private boolean digitaisCarregadas;
    /** Motivo de a biometria não funcionar, ou null. */
    private String erroBiometria;

    private Button btnModoReservar;
    private Button btnModoDevolver;
    private TextView tvEtapa;
    private TextView tvResumo;
    private TextView tvMensagem;
    private TextView tvAviso;
    private ProgressBar progresso;
    private Button btnLerDigital;
    private View grupoHorario;
    private EditText etData;
    private EditText etInicio;
    private EditText etFim;
    private Button btnVoltar;
    private Button btnRecomecar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reserva);

        btnModoReservar = findViewById(R.id.btnModoReservar);
        btnModoDevolver = findViewById(R.id.btnModoDevolver);
        tvEtapa = findViewById(R.id.tvEtapa);
        tvResumo = findViewById(R.id.tvResumo);
        tvMensagem = findViewById(R.id.tvMensagem);
        tvAviso = findViewById(R.id.tvAviso);
        progresso = findViewById(R.id.progresso);
        btnLerDigital = findViewById(R.id.btnLerDigital);
        grupoHorario = findViewById(R.id.grupoHorario);
        etData = findViewById(R.id.etData);
        etInicio = findViewById(R.id.etInicio);
        etFim = findViewById(R.id.etFim);
        btnVoltar = findViewById(R.id.btnVoltar);
        btnRecomecar = findViewById(R.id.btnRecomecar);

        professores = new ProfessorRepository(this);
        sincronizarProfessores();

        BiometriaService biometria = iniciarBiometria();
        fluxo = new FluxoReserva(
                biometria == null ? this::semLeitor : biometria::identificarProfessor,
                professores::buscarPorEmail, ReservaApiFactory.obter(), Clock.systemDefaultZone());

        nfc = new NfcService(this);
        nfc.setLeituraCallback(fluxo::projetorLido);

        btnModoReservar.setOnClickListener(v -> fluxo.iniciar(Modo.RESERVAR));
        btnModoDevolver.setOnClickListener(v -> fluxo.iniciar(Modo.DEVOLVER));
        btnLerDigital.setOnClickListener(v -> lerDigital());
        findViewById(R.id.btnConfirmar).setOnClickListener(v -> fluxo.confirmarHorario(
                etData.getText().toString().trim(),
                etInicio.getText().toString().trim(),
                etFim.getText().toString().trim()));
        btnVoltar.setOnClickListener(v -> fluxo.voltar());
        btnRecomecar.setOnClickListener(v -> fluxo.reiniciar());
        findViewById(R.id.btnTesteApi).setOnClickListener(
                v -> startActivity(new Intent(this, TesteReservaActivity.class)));

        fluxo.setListener(this::desenhar);
    }

    @Override
    protected void onResume() {
        super.onResume();
        nfc.habilitarLeitura();
        atualizarAviso();
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
        handler.removeCallbacksAndMessages(null);
        nfc.encerrar();
        if (sdk != null) sdk.encerrar();
    }

    // ---------------------------------------------------------- leitor

    /** Abre o SDK e o banco de digitais; null se não for possível. */
    private BiometriaService iniciarBiometria() {
        BiometricDatabase banco;
        try {
            banco = BiometricDatabase.padrao(this);
            sdk = new NitgenSdk(this, BuildConfig.NITGEN_SERIAL);
        } catch (GeneralSecurityException | NitgenException e) {
            sdk = null;
            erroBiometria = "Biometria indisponível: " + e.getMessage();
            return null;
        }
        sdk.setListener(this);
        sdk.executar(banco::quantidade, new NitgenSdk.Resultado<Integer>() {
            @Override
            public void onSucesso(Integer quantidade) {
                digitaisCarregadas = true;
                if (!sdk.isConectado()) sdk.abrir();
                atualizarAviso();
            }

            @Override
            public void onErro(String mensagem) {
                erroBiometria = "Erro ao carregar as digitais: " + mensagem;
                atualizarAviso();
            }
        });
        return new BiometriaService(new HamsterDxReader(sdk), new NitgenMatcher(sdk, banco));
    }

    private void semLeitor(BiometriaCallback callback) {
        callback.onErro(erroBiometria);
    }

    private void lerDigital() {
        if (sdk != null && !sdk.isConectado()) {
            if (digitaisCarregadas) sdk.abrir();
            tvMensagem.setText("Conecte o leitor biométrico e tente de novo.");
            return;
        }
        fluxo.lerDigital();
    }

    @Override
    public void onConectado() {
        atualizarAviso();
    }

    @Override
    public void onDesconectado() {
        atualizarAviso();
    }

    @Override
    public void onImagem(int qualidade, Bitmap imagem) {
        // A tela final pode mostrar a qualidade da leitura; aqui não é usada.
    }

    private void sincronizarProfessores() {
        professores.sincronizar(ReservaApiFactory.obterProfessorApi(), new ProfessoresCallback() {
            @Override
            public void onSucesso(List<Professor> lista) {
            }

            @Override
            public void onErro(String mensagem) {
                // Sem rede, usa o cadastro local; o Apps Script valida na reserva.
            }
        });
    }

    private void reiniciarFluxo() {
        fluxo.reiniciar();
    }

    // ----------------------------------------------------------- desenho

    private void desenhar(Estado e) {
        tvEtapa.setText(titulo(e));
        tvResumo.setText(resumo(e));
        tvMensagem.setText(e.mensagem == null ? "" : e.mensagem);
        tvMensagem.setTextColor(e.erro ? 0xFFC62828 : 0xFF212121);

        btnModoReservar.setEnabled(e.modo != Modo.RESERVAR);
        btnModoDevolver.setEnabled(e.modo != Modo.DEVOLVER);
        boolean esperando = e.etapa == Etapa.IDENTIFICANDO || e.etapa == Etapa.ENVIANDO;
        progresso.setVisibility(esperando ? View.VISIBLE : View.GONE);
        btnLerDigital.setVisibility(e.etapa == Etapa.DIGITAL ? View.VISIBLE : View.GONE);
        grupoHorario.setVisibility(e.etapa == Etapa.HORARIO ? View.VISIBLE : View.GONE);
        if (e.etapa == Etapa.HORARIO) {
            mostrarSeDiferente(etData, e.data);
            mostrarSeDiferente(etInicio, e.inicio);
            mostrarSeDiferente(etFim, e.fim);
        }
        boolean podeVoltar = e.etapa == Etapa.HORARIO
                || (e.etapa == Etapa.PROJETOR && e.modo == Modo.RESERVAR);
        btnVoltar.setVisibility(podeVoltar ? View.VISIBLE : View.GONE);
        btnRecomecar.setVisibility(esperando ? View.GONE : View.VISIBLE);
        btnRecomecar.setText(e.etapa == Etapa.CONCLUIDA ? "Concluir" : "Começar de novo");

        handler.removeCallbacks(recomecar);
        boolean noComeco = e.professorEmail == null && e.projetorCodigo == null
                && (e.etapa == Etapa.DIGITAL || e.etapa == Etapa.PROJETOR);
        if (e.etapa == Etapa.CONCLUIDA) {
            handler.postDelayed(recomecar, VOLTA_AUTOMATICA_MS);
        } else if (!noComeco && !esperando) {
            handler.postDelayed(recomecar, INATIVIDADE_MS);
        }
    }

    private static String titulo(Estado e) {
        switch (e.etapa) {
            case DIGITAL:
            case IDENTIFICANDO:
                return "1. Identificação";
            case PROJETOR:
                return e.modo == Modo.DEVOLVER ? "Devolver projetor" : "2. Projetor";
            case HORARIO:
                return "3. Horário";
            case ENVIANDO:
                return "Enviando...";
            case CONCLUIDA:
                return e.modo == Modo.DEVOLVER ? "Devolução registrada" : "Reserva confirmada";
            default:
                return "";
        }
    }

    private static String resumo(Estado e) {
        StringBuilder s = new StringBuilder();
        if (e.professorEmail != null) s.append("Professor: ").append(e.nomeOuEmail()).append('\n');
        if (e.projetorCodigo != null) s.append("Projetor: ").append(e.projetorCodigo).append('\n');
        if (e.etapa == Etapa.CONCLUIDA && e.modo == Modo.RESERVAR) {
            s.append("Horário: ").append(e.data).append(", ").append(e.inicio)
                    .append(" às ").append(e.fim).append('\n');
        }
        if (e.reservaId != null) s.append("Reserva nº ").append(e.reservaId);
        return s.toString().trim();
    }

    /** Não reescreve o campo à toa, para não mexer no cursor de quem digita. */
    private static void mostrarSeDiferente(EditText campo, String valor) {
        String texto = valor == null ? "" : valor;
        if (!texto.equals(campo.getText().toString())) campo.setText(texto);
    }

    private void atualizarAviso() {
        StringBuilder s = new StringBuilder();
        if (erroBiometria != null) s.append(erroBiometria).append('\n');
        if (!ReservaApiFactory.usandoAppsScript()) {
            s.append("Modo de teste: reservas simuladas (local.properties sem URL/token).\n");
        }
        if (!nfc.isDisponivel()) {
            s.append("Este aparelho não tem NFC.\n");
        } else if (!nfc.isAtivado()) {
            s.append("O NFC está desligado: ative nas configurações.\n");
        }
        if (sdk != null && digitaisCarregadas && !sdk.isConectado()) {
            s.append("Leitor biométrico não conectado.\n");
        }
        tvAviso.setText(s.toString().trim());
    }
}
