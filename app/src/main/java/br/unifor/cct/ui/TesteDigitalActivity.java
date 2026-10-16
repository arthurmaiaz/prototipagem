package br.unifor.cct.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import br.unifor.cct.BuildConfig;
import br.unifor.cct.R;
import br.unifor.cct.admin.PinAdministrador;
import br.unifor.cct.admin.PinAdministrador.Verificacao;
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
 *
 * Cadastrar e remover pedem o PIN da coordenação (PinAdministrador). No
 * primeiro uso a tela pede para criar o PIN. Identificar não pede PIN.
 */
public class TesteDigitalActivity extends Activity implements NitgenSdk.Listener {

    private static final DateTimeFormatter FORMATO_REGISTRO = DateTimeFormatter.ofPattern("HH:mm:ss");

    private NitgenSdk sdk;
    private BiometricDatabase banco;
    private CadastroDigitalNitgen cadastro;
    private BiometriaService biometria;
    private ProfessorRepository professores;
    private PinAdministrador pin;

    private boolean ocupado;
    private boolean digitaisCarregadas;
    private boolean abertaPeloLeitor;

    private TextView tvEstado;
    private TextView tvInstrucao;
    private EditText etProfessorId;
    private TextView tvNomeProfessor;
    private Button btnAbrir;
    private Button btnCadastrar;
    private Button btnRemover;
    private Button btnTrocarPin;
    private Button btnIdentificar;
    private Button btnCancelar;
    private ImageView ivDigital;
    private TextView tvQualidade;
    private TextView tvRegistro;

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
        btnTrocarPin = findViewById(R.id.btnTrocarPin);
        btnIdentificar = findViewById(R.id.btnIdentificar);
        btnCancelar = findViewById(R.id.btnCancelar);
        ivDigital = findViewById(R.id.ivDigital);
        tvQualidade = findViewById(R.id.tvQualidade);
        tvRegistro = findViewById(R.id.tvRegistro);

        abertaPeloLeitor = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction());
        professores = new ProfessorRepository(this);
        pin = PinAdministrador.padrao(this);
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
        btnCadastrar.setOnClickListener(v -> comoAdministrador(this::cadastrar));
        btnRemover.setOnClickListener(v -> comoAdministrador(this::remover));
        btnTrocarPin.setOnClickListener(v -> trocarPin());
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
        biometria = new BiometriaService(new HamsterDxReader(sdk), new NitgenMatcher(sdk, banco));

        tvEstado.setText("SDK " + sdk.getVersao() + ". Carregando digitais...");
        sdk.executar(banco::quantidade, new NitgenSdk.Resultado<Integer>() {
            @Override
            public void onSucesso(Integer quantidade) {
                digitaisCarregadas = true;
                if (abertaPeloLeitor && !sdk.isConectado()) {
                    registrar("Leitor conectado: abrindo...");
                    sdk.abrir();
                }
                tvEstado.setText("SDK " + sdk.getVersao() + ". " + quantidade + " de "
                        + banco.capacidade() + " posições ocupadas.");
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
                && sdk != null && digitaisCarregadas && !sdk.isConectado()) {
            registrar("Leitor conectado: abrindo...");
            sdk.abrir();
        }
    }

    /** Saiu da tela: a próxima ação de administrador pede o PIN de novo. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!ocupado) pin.encerrar();
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
        tvInstrucao.setText("Coloque o dedo no leitor.");
        biometria.identificarProfessor(new BiometriaCallback() {
            @Override
            public void onSucesso(ResultadoBiometria resultado) {
                if (resultado.isSucesso()) {
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

    // ------------------------------------------------- PIN da coordenação

    /** Roda a ação se o PIN já foi digitado há pouco; senão pede o PIN antes. */
    private void comoAdministrador(Runnable acao) {
        if (pin.liberado()) {
            pin.renovar();
            acao.run();
        } else if (!pin.temPin()) {
            criarPin(acao);
        } else {
            pedirPin(acao);
        }
    }

    private void criarPin(Runnable depois) {
        EditText novo = campoPin("Novo PIN (" + PinAdministrador.TAMANHO_MINIMO + " a "
                + PinAdministrador.TAMANHO_MAXIMO + " dígitos)");
        EditText repetir = campoPin("Repita o PIN");
        dialogo("Criar PIN da coordenação",
                "Este terminal ainda não tem PIN. Só quem souber o PIN poderá cadastrar "
                        + "e remover digitais. Se ele for esquecido, será preciso limpar os "
                        + "dados do app, o que apaga todas as digitais.",
                "Criar", () -> {
                    String a = novo.getText().toString();
                    if (!PinAdministrador.formatoValido(a)) {
                        registrar("Erro: o PIN precisa ter de " + PinAdministrador.TAMANHO_MINIMO
                                + " a " + PinAdministrador.TAMANHO_MAXIMO + " dígitos.");
                    } else if (!a.equals(repetir.getText().toString())) {
                        registrar("Erro: os dois PINs não são iguais.");
                    } else {
                        emSegundoPlano(() -> {
                            pin.definir(a);
                            return Verificacao.CORRETO;
                        }, v -> {
                            registrar("PIN da coordenação criado.");
                            depois.run();
                        });
                    }
                }, novo, repetir);
    }

    private void pedirPin(Runnable depois) {
        if (pin.bloqueado()) {
            avisarBloqueio();
            return;
        }
        EditText campo = campoPin("PIN");
        dialogo("PIN da coordenação", "Cadastrar e remover digitais exige o PIN.", "Confirmar",
                () -> emSegundoPlano(() -> pin.verificar(campo.getText().toString()), v -> {
                    if (v == Verificacao.CORRETO) {
                        depois.run();
                    } else {
                        avisarErroPin(v);
                    }
                }), campo);
    }

    private void trocarPin() {
        if (!pin.temPin()) {
            criarPin(() -> { });
            return;
        }
        if (pin.bloqueado()) {
            avisarBloqueio();
            return;
        }
        EditText atual = campoPin("PIN atual");
        EditText novo = campoPin("Novo PIN");
        EditText repetir = campoPin("Repita o novo PIN");
        dialogo("Trocar PIN da coordenação", null, "Trocar", () -> {
            String n = novo.getText().toString();
            if (!PinAdministrador.formatoValido(n)) {
                registrar("Erro: o novo PIN precisa ter de " + PinAdministrador.TAMANHO_MINIMO
                        + " a " + PinAdministrador.TAMANHO_MAXIMO + " dígitos.");
            } else if (!n.equals(repetir.getText().toString())) {
                registrar("Erro: os dois PINs novos não são iguais.");
            } else {
                emSegundoPlano(() -> pin.trocar(atual.getText().toString(), n), v -> {
                    if (v == Verificacao.CORRETO) {
                        registrar("PIN da coordenação trocado.");
                    } else {
                        avisarErroPin(v);
                    }
                });
            }
        }, atual, novo, repetir);
    }

    private void avisarErroPin(Verificacao v) {
        if (v == Verificacao.BLOQUEADO) {
            avisarBloqueio();
        } else {
            registrar("PIN incorreto. Restam " + pin.tentativasRestantes() + " tentativa(s).");
        }
    }

    private void avisarBloqueio() {
        long minutos = Math.max(1, (pin.tempoBloqueioRestante().getSeconds() + 59) / 60);
        registrar("Muitas tentativas erradas. Tente de novo em " + minutos + " minuto(s).");
    }

    private EditText campoPin(String dica) {
        EditText campo = new EditText(this);
        campo.setHint(dica);
        campo.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        campo.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(PinAdministrador.TAMANHO_MAXIMO) });
        return campo;
    }

    private void dialogo(String titulo, String mensagem, String botao, Runnable aoConfirmar,
                         EditText... campos) {
        LinearLayout caixa = new LinearLayout(this);
        caixa.setOrientation(LinearLayout.VERTICAL);
        int margem = (int) (20 * getResources().getDisplayMetrics().density);
        caixa.setPadding(margem, margem / 2, margem, 0);
        for (EditText campo : campos) caixa.addView(campo);
        new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(mensagem)
                .setView(caixa)
                .setPositiveButton(botao, (d, w) -> aoConfirmar.run())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    interface TarefaPin {
        Verificacao executar() throws IOException;
    }

    interface AoTerminar {
        void aceitar(Verificacao v);
    }

    /** O PBKDF2 leva uma fração de segundo: fica fora da thread da tela. */
    private void emSegundoPlano(TarefaPin tarefa, AoTerminar aoTerminar) {
        btnCadastrar.setEnabled(false);
        btnRemover.setEnabled(false);
        btnTrocarPin.setEnabled(false);
        new Thread(() -> {
            Verificacao v;
            String erro = null;
            try {
                v = tarefa.executar();
            } catch (IOException e) {
                v = null;
                erro = e.getMessage();
            }
            Verificacao resultado = v;
            String mensagemErro = erro;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                atualizarBotoes();
                if (resultado == null) {
                    registrar("Erro ao gravar o PIN: " + mensagemErro);
                } else {
                    aoTerminar.aceitar(resultado);
                }
            });
        }, "pin-administrador").start();
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
        boolean pronto = conectado && digitaisCarregadas && !ocupado;
        btnAbrir.setText(conectado ? "Fechar leitor" : "Abrir leitor");
        btnAbrir.setEnabled(iniciado && !ocupado);
        btnCadastrar.setEnabled(pronto);
        btnIdentificar.setEnabled(pronto);
        btnRemover.setEnabled(iniciado && digitaisCarregadas && !ocupado);
        btnCancelar.setEnabled(ocupado);
        btnTrocarPin.setEnabled(!ocupado);
    }

    /** Linha nova no topo do registro, com o horário. */
    private void registrar(String texto) {
        String hora = LocalTime.now().format(FORMATO_REGISTRO);
        CharSequence atual = tvRegistro.getText();
        tvRegistro.setText(hora + "  " + texto + (atual.length() == 0 ? "" : "\n\n" + atual));
    }
}
