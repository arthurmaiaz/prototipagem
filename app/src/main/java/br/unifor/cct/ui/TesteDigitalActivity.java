package br.unifor.cct.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.security.GeneralSecurityException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import br.unifor.cct.BuildConfig;
import br.unifor.cct.R;
import br.unifor.cct.biometria.BiometriaCallback;
import br.unifor.cct.biometria.BiometriaService;
import br.unifor.cct.biometria.BiometricDatabase;
import br.unifor.cct.biometria.ResultadoBiometria;
import br.unifor.cct.biometria.nitgen.CadastroDigitalNitgen;
import br.unifor.cct.biometria.nitgen.HamsterDxReader;
import br.unifor.cct.biometria.nitgen.NitgenException;
import br.unifor.cct.biometria.nitgen.NitgenMatcher;
import br.unifor.cct.biometria.nitgen.NitgenSdk;
import br.unifor.cct.model.Professor;
import br.unifor.cct.storage.ProfessorRepository;

/**
 * Tela isolada para testar a biometria real de ponta a ponta com o Hamster
 * DX: cadastrar a digital de um professor, identificar quem colocou o dedo
 * (pelo BiometriaService, como as telas finais vão fazer) e remover.
 *
 * Abre sozinha ao conectar o Hamster DX (USB_DEVICE_ATTACHED no manifest);
 * nesse caso o Android já concedeu a permissão USB e o leitor é aberto
 * automaticamente.
 *
 * As digitais ficam no BiometricDatabase (cifrado, só no aparelho). Os
 * nomes vêm do ProfessorRepository: sincronize antes na TesteReservaActivity
 * para vê-los aqui.
 */
public class TesteDigitalActivity extends Activity implements NitgenSdk.Listener {

    private static final DateTimeFormatter FORMATO_REGISTRO = DateTimeFormatter.ofPattern("HH:mm:ss");

    private NitgenSdk sdk;
    private BiometricDatabase banco;
    private CadastroDigitalNitgen cadastro;
    private BiometriaService biometria;
    private ProfessorRepository professores;

    private boolean ocupado;
    private boolean indiceCarregado;
    private boolean abertaPeloLeitor;

    private TextView tvEstado;
    private TextView tvInstrucao;
    private EditText etProfessorId;
    private TextView tvNomeProfessor;
    private Button btnAbrir;
    private Button btnCadastrar;
    private Button btnRemover;
    private Button btnIdentificar;
    private Button btnVerPerfil;
    private Button btnCancelar;
    private ImageView ivDigital;
    private TextView tvQualidade;
    private TextView tvRegistro;
    private View btnVoltarDigital;
    private Integer professorIdentificadoId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teste_digital);

        tvEstado = findViewById(R.id.tvEstado);
        tvInstrucao = findViewById(R.id.tvInstrucao);
        etProfessorId = findViewById(R.id.etProfessorId);
        tvNomeProfessor = findViewById(R.id.tvNomeProfessor);
        btnAbrir = findViewById(R.id.btnAbrir);
        btnCadastrar = findViewById(R.id.btnCadastrar);
        btnRemover = findViewById(R.id.btnRemover);
        btnIdentificar = findViewById(R.id.btnIdentificar);
        btnVerPerfil = findViewById(R.id.btnVerPerfil);
        btnCancelar = findViewById(R.id.btnCancelar);
        btnVoltarDigital = findViewById(R.id.btnVoltarDigital);
        ivDigital = findViewById(R.id.ivDigital);
        tvQualidade = findViewById(R.id.tvQualidade);
        tvRegistro = findViewById(R.id.tvRegistro);

        if (btnVoltarDigital != null) {
            btnVoltarDigital.setOnClickListener(v -> finish());
        }
        if (btnVerPerfil != null) {
            btnVerPerfil.setOnClickListener(v -> {
                if (professorIdentificadoId != null) {
                    Intent intent = new Intent(this, PerfilActivity.class);
                    intent.putExtra("professorId", professorIdentificadoId);
                    startActivity(intent);
                }
            });
        }

        abertaPeloLeitor = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction());
        professores = new ProfessorRepository(this);
        etProfessorId.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mostrarNome();
            }
        });
        mostrarNome();

        btnAbrir.setOnClickListener(v -> abrirOuFechar());
        btnCadastrar.setOnClickListener(v -> cadastrar());
        btnRemover.setOnClickListener(v -> remover());
        btnIdentificar.setOnClickListener(v -> identificar());
        btnCancelar.setOnClickListener(v -> sdk.cancelarCaptura());

        try {
            banco = BiometricDatabase.padrao(this);
            sdk = new NitgenSdk(this, BuildConfig.NITGEN_SERIAL);
        } catch (GeneralSecurityException e) {
            tvEstado.setText("Erro ao abrir o Android Keystore: " + e.getMessage());
            atualizarBotoes();
            return;
        } catch (NitgenException e) {
            tvEstado.setText(e.getMessage());
            atualizarBotoes();
            return;
        }
        sdk.setListener(this);
        cadastro = new CadastroDigitalNitgen(sdk, banco);
        NitgenMatcher matcher = new NitgenMatcher(sdk);
        biometria = new BiometriaService(new HamsterDxReader(sdk), matcher);

        tvEstado.setText("SDK " + sdk.getVersao() + ". Carregando digitais...");
        matcher.carregar(banco, new NitgenSdk.Resultado<Integer>() {
            @Override
            public void onSucesso(Integer quantidade) {
                indiceCarregado = true;
                if (abertaPeloLeitor && !sdk.isConectado()) {
                    registrar("Leitor conectado: abrindo...");
                    sdk.abrir();
                }
                tvEstado.setText("SDK " + sdk.getVersao() + ". " + quantidade + " digital(is) cadastrada(s).");
                if (banco.houveFalhaAoCarregar()) {
                    registrar("Aviso: o arquivo de digitais não pôde ser lido e foi ignorado. "
                            + "As digitais precisam ser cadastradas de novo.");
                }
                atualizarBotoes();
            }

            @Override
            public void onErro(String mensagem) {
                tvEstado.setText("Erro ao carregar as digitais: " + mensagem);
            }
        });
        atualizarBotoes();
    }

    /** O leitor foi reconectado com a tela já aberta (singleTop). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())
                && sdk != null && indiceCarregado && !sdk.isConectado()) {
            registrar("Leitor conectado: abrindo...");
            sdk.abrir();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sdk != null) sdk.encerrar();
    }

    // --------------------------------------------------------------- ações

    private void abrirOuFechar() {
        if (sdk.isConectado()) {
            sdk.fechar();
            registrar("Leitor fechado.");
        } else {
            registrar("Abrindo leitor... (aceite a permissão USB se o Android pedir)");
            sdk.abrir();
        }
        atualizarBotoes();
    }

    private void cadastrar() {
        Integer id = professorId();
        if (id == null) return;
        inicio("Cadastrando a digital do professor " + id + "...");
        cadastro.cadastrar(id, new CadastroDigitalNitgen.Callback() {
            @Override
            public void onEtapa(String instrucao) {
                tvInstrucao.setText(instrucao);
                tvQualidade.setText("");
            }

            @Override
            public void onConcluido(String mensagem) {
                fim(mensagem);
            }

            @Override
            public void onErro(String mensagem) {
                fim("Erro: " + mensagem);
            }
        });
    }

    private void remover() {
        Integer id = professorId();
        if (id == null) return;
        inicio("Removendo a digital do professor " + id + "...");
        cadastro.remover(id, new CadastroDigitalNitgen.Callback() {
            @Override
            public void onEtapa(String instrucao) {
            }

            @Override
            public void onConcluido(String mensagem) {
                fim(mensagem);
            }

            @Override
            public void onErro(String mensagem) {
                fim("Erro: " + mensagem);
            }
        });
    }

    private void identificar() {
        inicio("Identificando...");
        if (btnVerPerfil != null) btnVerPerfil.setVisibility(View.GONE);
        professorIdentificadoId = null;
        tvInstrucao.setText("Coloque o dedo no leitor.");
        biometria.identificarProfessor(new BiometriaCallback() {
            @Override
            public void onSucesso(ResultadoBiometria resultado) {
                if (resultado.isSucesso()) {
                    professorIdentificadoId = resultado.getProfessorId();
                    if (btnVerPerfil != null) btnVerPerfil.setVisibility(View.VISIBLE);
                    Professor p = professores.buscarPorId(resultado.getProfessorId());
                    fim("IDENTIFICADO: professor " + resultado.getProfessorId()
                            + (p == null ? " (nome não sincronizado)" : " - " + p.getNome()));
                } else {
                    fim("NÃO IDENTIFICADO: " + resultado.getMensagem());
                }
            }

            @Override
            public void onErro(String mensagem) {
                fim("Erro: " + mensagem);
            }
        });
    }

    // ---------------------------------------------------- NitgenSdk.Listener

    @Override
    public void onConectado() {
        registrar("Leitor aberto.");
        atualizarBotoes();
    }

    @Override
    public void onDesconectado() {
        registrar("Leitor desconectado.");
        atualizarBotoes();
    }

    @Override
    public void onImagem(int qualidade, Bitmap imagem) {
        if (imagem != null) ivDigital.setImageBitmap(imagem);
        tvQualidade.setText("Qualidade: " + qualidade
                + (qualidade >= NitgenSdk.QUALIDADE_MINIMA ? " (boa)" : " (mantenha o dedo)"));
    }

    // ------------------------------------------------------------- apoio

    private void inicio(String mensagem) {
        ocupado = true;
        registrar(mensagem);
        tvQualidade.setText("");
        atualizarBotoes();
    }

    private void fim(String mensagem) {
        ocupado = false;
        tvInstrucao.setText("");
        registrar(mensagem);
        atualizarBotoes();
    }

    private Integer professorId() {
        try {
            int id = Integer.parseInt(etProfessorId.getText().toString().trim());
            if (id > 0) return id;
        } catch (NumberFormatException ignorado) {
            // cai no erro abaixo
        }
        registrar("Erro: informe um id de professor maior que zero.");
        return null;
    }

    private void mostrarNome() {
        Professor p;
        try {
            p = professores.buscarPorId(Integer.parseInt(etProfessorId.getText().toString().trim()));
        } catch (NumberFormatException e) {
            p = null;
        }
        tvNomeProfessor.setText(p == null ? "Professor não encontrado no cadastro local"
                : p.getNome() + (p.isAtivo() ? "" : " (inativo)"));
    }

    private void atualizarBotoes() {
        boolean iniciado = sdk != null;
        boolean conectado = iniciado && sdk.isConectado();
        boolean pronto = conectado && indiceCarregado && !ocupado;
        btnAbrir.setText(conectado ? "Fechar leitor" : "Abrir leitor");
        btnAbrir.setEnabled(iniciado && !ocupado);
        btnCadastrar.setEnabled(pronto);
        btnIdentificar.setEnabled(pronto);
        btnRemover.setEnabled(iniciado && indiceCarregado && !ocupado);
        btnCancelar.setEnabled(ocupado);
    }

    /** Linha nova no topo do registro, com o horário. */
    private void registrar(String texto) {
        String hora = LocalTime.now().format(FORMATO_REGISTRO);
        CharSequence atual = tvRegistro.getText();
        tvRegistro.setText(hora + "  " + texto + (atual.length() == 0 ? "" : "\n\n" + atual));
    }
}
