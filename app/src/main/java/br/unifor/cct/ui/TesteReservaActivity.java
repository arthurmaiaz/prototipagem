package br.unifor.cct.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import br.unifor.cct.BuildConfig;
import br.unifor.cct.R;
import br.unifor.cct.api.ProfessoresCallback;
import br.unifor.cct.api.ReservaApi;
import br.unifor.cct.api.ReservaApiFactory;
import br.unifor.cct.api.ReservaCallback;
import br.unifor.cct.api.ReservaResultado;
import br.unifor.cct.model.Professor;
import br.unifor.cct.model.Reserva;
import br.unifor.cct.storage.ProfessorRepository;

/**
 * Tela isolada só para testar a ReservaApi e a sincronização de professores
 * contra o Apps Script de verdade, sem depender das telas do Nikolas.
 *
 * Usa ReservaApiFactory: com appsScript.url e appsScript.token no
 * local.properties fala com o Web App; sem eles, usa o MockReservaApi.
 * O topo da tela mostra qual dos dois está em uso.
 *
 * Cada operação é anotada no registro com o horário, para conferir depois
 * com a aba "reservas" da planilha.
 */
public class TesteReservaActivity extends Activity {

    private static final DateTimeFormatter FORMATO_REGISTRO = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ReservaApi api = ReservaApiFactory.obter();
    private ProfessorRepository professores;

    private EditText etProfessorId;
    private EditText etProjetor;
    private EditText etData;
    private EditText etInicio;
    private EditText etFim;
    private EditText etReservaId;
    private Button btnCriar;
    private Button btnConsultar;
    private Button btnCancelar;
    private Button btnSincronizar;
    private ProgressBar progressBar;
    private TextView tvRegistro;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teste_reserva);

        professores = new ProfessorRepository(this);

        TextView tvServidor = findViewById(R.id.tvServidor);
        etProfessorId = findViewById(R.id.etProfessorId);
        etProjetor = findViewById(R.id.etProjetor);
        etData = findViewById(R.id.etData);
        etInicio = findViewById(R.id.etInicio);
        etFim = findViewById(R.id.etFim);
        etReservaId = findViewById(R.id.etReservaId);
        btnCriar = findViewById(R.id.btnCriar);
        btnConsultar = findViewById(R.id.btnConsultar);
        btnCancelar = findViewById(R.id.btnCancelar);
        btnSincronizar = findViewById(R.id.btnSincronizar);
        progressBar = findViewById(R.id.progressBar);
        tvRegistro = findViewById(R.id.tvRegistro);

        tvServidor.setText(ReservaApiFactory.usandoAppsScript()
                ? "Servidor: Apps Script\n" + resumirUrl(BuildConfig.APPS_SCRIPT_URL)
                : "Servidor: MockReservaApi (local.properties sem URL/token)");
        etData.setText(LocalDate.now().format(Reserva.FORMATO_DATA));

        btnCriar.setOnClickListener(v -> criar());
        btnConsultar.setOnClickListener(v -> comReservaId(id -> {
            registrar("Consultando reserva " + id + "...");
            ocupado(true);
            api.consultarReserva(id, callback("Consulta"));
        }));
        btnCancelar.setOnClickListener(v -> comReservaId(id -> {
            registrar("Cancelando reserva " + id + "...");
            ocupado(true);
            api.cancelarReserva(id, callback("Cancelamento"));
        }));
        btnSincronizar.setOnClickListener(v -> sincronizar());

        mostrarProfessoresLocais();
    }

    private void criar() {
        Integer professorId = inteiro(etProfessorId);
        if (professorId == null) {
            registrar("Erro: id do professor inválido.");
            return;
        }
        String projetor = etProjetor.getText().toString().trim().toUpperCase(Locale.ROOT);
        String data = etData.getText().toString().trim();
        String inicio = etInicio.getText().toString().trim();
        String fim = etFim.getText().toString().trim();

        Professor local = professores.buscarPorId(professorId);
        registrar("Criando reserva: professor " + professorId
                + (local == null ? "" : " (" + local.getNome() + ")")
                + ", " + projetor + ", " + data + " " + inicio + "-" + fim + "...");
        ocupado(true);
        api.criarReserva(professorId, projetor, data, inicio, fim, new ReservaCallback() {
            @Override
            public void onSucesso(ReservaResultado resultado) {
                ocupado(false);
                if (resultado.getReservaId() != null) {
                    etReservaId.setText(String.valueOf(resultado.getReservaId()));
                }
                registrar("OK (id " + resultado.getReservaId() + "): " + resultado.getMensagem());
            }

            @Override
            public void onErro(String mensagem) {
                ocupado(false);
                registrar("Erro: " + mensagem);
            }
        });
    }

    private void sincronizar() {
        registrar("Sincronizando professores...");
        ocupado(true);
        professores.sincronizar(ReservaApiFactory.obterProfessorApi(), new ProfessoresCallback() {
            @Override
            public void onSucesso(List<Professor> lista) {
                ocupado(false);
                registrar("OK: " + lista.size() + " professor(es) salvos no aparelho.");
                mostrarProfessoresLocais();
            }

            @Override
            public void onErro(String mensagem) {
                ocupado(false);
                registrar("Erro: " + mensagem + " (cadastro local mantido)");
            }
        });
    }

    private void mostrarProfessoresLocais() {
        List<Professor> lista = professores.listar();
        if (lista.isEmpty()) {
            registrar("Cadastro local vazio. Toque em \"Sincronizar professores\".");
            return;
        }
        StringBuilder sb = new StringBuilder("Cadastro local:");
        for (Professor p : lista) {
            sb.append("\n  ").append(p.getId()).append(" - ").append(p.getNome())
                    .append(p.isAtivo() ? "" : " (inativo)");
        }
        registrar(sb.toString());
    }

    private ReservaCallback callback(String operacao) {
        return new ReservaCallback() {
            @Override
            public void onSucesso(ReservaResultado resultado) {
                ocupado(false);
                registrar(operacao + " OK: " + resultado.getMensagem());
            }

            @Override
            public void onErro(String mensagem) {
                ocupado(false);
                registrar(operacao + " erro: " + mensagem);
            }
        };
    }

    private interface AcaoComId {
        void executar(int id);
    }

    private void comReservaId(AcaoComId acao) {
        Integer id = inteiro(etReservaId);
        if (id == null) {
            registrar("Erro: informe o id da reserva (ou crie uma antes).");
            return;
        }
        acao.executar(id);
    }

    private static Integer inteiro(EditText campo) {
        try {
            return Integer.parseInt(campo.getText().toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Desliga os botões enquanto uma chamada está em andamento. */
    private void ocupado(boolean sim) {
        progressBar.setVisibility(sim ? View.VISIBLE : View.GONE);
        btnCriar.setEnabled(!sim);
        btnConsultar.setEnabled(!sim);
        btnCancelar.setEnabled(!sim);
        btnSincronizar.setEnabled(!sim);
    }

    /** Linha nova no topo do registro, com o horário. */
    private void registrar(String texto) {
        String hora = LocalTime.now().format(FORMATO_REGISTRO);
        CharSequence atual = tvRegistro.getText();
        tvRegistro.setText(hora + "  " + texto + (atual.length() == 0 ? "" : "\n\n" + atual));
    }

    /** Mostra só o começo e o fim da URL, para não expor o id inteiro na tela. */
    private static String resumirUrl(String url) {
        return url.length() <= 60 ? url : url.substring(0, 40) + "..." + url.substring(url.length() - 12);
    }
}
