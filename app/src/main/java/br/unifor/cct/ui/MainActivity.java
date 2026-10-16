package br.unifor.cct.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import br.unifor.cct.R;
import br.unifor.cct.biometria.FakeFingerprintMatcher;
import br.unifor.cct.model.Professor;
import br.unifor.cct.storage.ProfessorRepository;

/**
 * Tela inicial do aplicativo de Reserva de Projetores (CCT - Unifor).
 * Permite navegação aos módulos do sistema com barra inferior inspirada no
 * Unifor Mobile, com três acessos:
 * 1. Início (mantém todos os módulos originais: equipamentos, biometria, NFC e USB).
 * 2. Torpedos (área para mensagens ao suporte humano).
 * 3. Menu (acesso ao perfil, FAQ e assistente virtual com ícone de 3 barras).
 */
public class MainActivity extends Activity {

    private enum AbaNavegacao {
        INICIO,
        TORPEDOS,
        MENU
    }

    private AbaNavegacao abaAtual = AbaNavegacao.INICIO;

    // Telas dos containers
    private View viewInicio;
    private View viewTorpedos;
    private View viewMenu;

    // Itens da barra inferior
    private View tabInicio;
    private View tabTorpedos;
    private View tabMenu;
    private ImageView ivTabInicio;
    private ImageView ivTabTorpedos;
    private ImageView ivTabMenu;
    private TextView tvTabInicio;
    private TextView tvTabTorpedos;
    private TextView tvTabMenu;

    // Componentes de Torpedos
    private TextView tvTorpedoRemetente;
    private EditText etTorpedoMensagem;
    private TextView tvTorpedoCharCount;
    private Button btnEnviarTorpedo;
    private TextView tvTorpedoStatus;

    // Componentes do Menu
    private View cardMenuPerfilResumo;
    private TextView tvMenuNomeProfessor;
    private TextView tvMenuMatriculaProfessor;
    private View cardMenuPerfil;
    private View cardMenuFaq;
    private View cardMenuAssistente;

    private ProfessorRepository professorRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        professorRepository = new ProfessorRepository(this);

        inicializarComponentesInicio();
        inicializarComponentesTorpedos();
        inicializarComponentesMenu();
        inicializarBarraNavegacao();

        selecionarAba(AbaNavegacao.INICIO, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        atualizarDadosProfessor();
    }

    @Override
    public void onBackPressed() {
        if (abaAtual != AbaNavegacao.INICIO) {
            selecionarAba(AbaNavegacao.INICIO, true);
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Preserva integralmente todas as funcionalidades e acessos da tela inicial original.
     */
    private void inicializarComponentesInicio() {
        viewInicio = findViewById(R.id.viewInicio);

        View cardDigital = findViewById(R.id.cardCadastrarDigital);
        View cardReserva = findViewById(R.id.cardReservar);
        View cardNfc = findViewById(R.id.cardCadastrarTagNfc);
        View cardCaptura = findViewById(R.id.cardTesteCaptura);
        View cardUsb = findViewById(R.id.cardConexaoUsb);
        View cardBiometriaFake = findViewById(R.id.cardBiometriaFake);

        vincularAcesso(cardDigital, TesteDigitalActivity.class);
        vincularAcesso(cardReserva, ReservaActivity.class);
        vincularAcesso(cardNfc, TesteNfcActivity.class);
        vincularAcesso(cardCaptura, TesteCapturaActivity.class);
        vincularAcesso(cardUsb, TesteUsbActivity.class);
        vincularAcesso(cardBiometriaFake, TesteBiometriaActivity.class);

        animarEntrada(cardDigital, cardReserva, cardNfc, cardCaptura, cardUsb, cardBiometriaFake);
    }

    /**
     * Inicializa a tela de Torpedos para contato com o suporte humano.
     */
    private void inicializarComponentesTorpedos() {
        viewTorpedos = findViewById(R.id.viewTorpedos);
        tvTorpedoRemetente = findViewById(R.id.tvTorpedoRemetente);
        etTorpedoMensagem = findViewById(R.id.etTorpedoMensagem);
        tvTorpedoCharCount = findViewById(R.id.tvTorpedoCharCount);
        btnEnviarTorpedo = findViewById(R.id.btnEnviarTorpedo);
        tvTorpedoStatus = findViewById(R.id.tvTorpedoStatus);

        if (etTorpedoMensagem != null && tvTorpedoCharCount != null) {
            etTorpedoMensagem.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int count, int after) {
                    int tamanho = (s != null) ? s.length() : 0;
                    tvTorpedoCharCount.setText(tamanho + " / 500");
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
        }

        if (btnEnviarTorpedo != null) {
            btnEnviarTorpedo.setOnClickListener(v -> processarEnvioTorpedo());
        }
    }

    /**
     * Processa a tentativa de envio do torpedo. Como não há backend de envio disponível,
     * deixa explícito que a funcionalidade depende de integração e NÃO simula sucesso.
     */
    private void processarEnvioTorpedo() {
        if (etTorpedoMensagem == null) return;

        String mensagem = etTorpedoMensagem.getText().toString().trim();
        if (mensagem.isEmpty()) {
            Toast.makeText(this, R.string.torpedos_erro_vazio, Toast.LENGTH_SHORT).show();
            if (tvTorpedoStatus != null) {
                tvTorpedoStatus.setVisibility(View.GONE);
            }
            return;
        }

        if (tvTorpedoStatus != null) {
            tvTorpedoStatus.setVisibility(View.VISIBLE);
            tvTorpedoStatus.setText(R.string.torpedos_aviso_pendente);
        }
    }

    /**
     * Inicializa os acessos da aba Menu: Meu Perfil, FAQ e Assistente Virtual.
     */
    private void inicializarComponentesMenu() {
        viewMenu = findViewById(R.id.viewMenu);
        cardMenuPerfilResumo = findViewById(R.id.cardMenuPerfilResumo);
        tvMenuNomeProfessor = findViewById(R.id.tvMenuNomeProfessor);
        tvMenuMatriculaProfessor = findViewById(R.id.tvMenuMatriculaProfessor);
        cardMenuPerfil = findViewById(R.id.cardMenuPerfil);
        cardMenuFaq = findViewById(R.id.cardMenuFaq);
        cardMenuAssistente = findViewById(R.id.cardMenuAssistente);

        vincularAcesso(cardMenuPerfilResumo, PerfilActivity.class);
        vincularAcesso(cardMenuPerfil, PerfilActivity.class);
        vincularAcesso(cardMenuFaq, FaqActivity.class);
        vincularAcesso(cardMenuAssistente, AssistenteActivity.class);

        atualizarDadosProfessor();
    }

    /**
     * Inicializa a barra de navegação inferior estilo Unifor Mobile com 3 opções.
     */
    private void inicializarBarraNavegacao() {
        tabInicio = findViewById(R.id.tabInicio);
        tabTorpedos = findViewById(R.id.tabTorpedos);
        tabMenu = findViewById(R.id.tabMenu);

        ivTabInicio = findViewById(R.id.ivTabInicio);
        ivTabTorpedos = findViewById(R.id.ivTabTorpedos);
        ivTabMenu = findViewById(R.id.ivTabMenu);

        tvTabInicio = findViewById(R.id.tvTabInicio);
        tvTabTorpedos = findViewById(R.id.tvTabTorpedos);
        tvTabMenu = findViewById(R.id.tvTabMenu);

        if (tabInicio != null) {
            tabInicio.setOnClickListener(v -> selecionarAba(AbaNavegacao.INICIO, true));
        }
        if (tabTorpedos != null) {
            tabTorpedos.setOnClickListener(v -> selecionarAba(AbaNavegacao.TORPEDOS, true));
        }
        if (tabMenu != null) {
            tabMenu.setOnClickListener(v -> selecionarAba(AbaNavegacao.MENU, true));
        }
    }

    /**
     * Alterna a exibição entre as três telas e atualiza os estados visuais da barra inferior.
     */
    private void selecionarAba(AbaNavegacao aba, boolean animar) {
        this.abaAtual = aba;

        if (viewInicio != null) viewInicio.setVisibility(aba == AbaNavegacao.INICIO ? View.VISIBLE : View.GONE);
        if (viewTorpedos != null) viewTorpedos.setVisibility(aba == AbaNavegacao.TORPEDOS ? View.VISIBLE : View.GONE);
        if (viewMenu != null) viewMenu.setVisibility(aba == AbaNavegacao.MENU ? View.VISIBLE : View.GONE);

        int corAtiva = getColor(R.color.unifor_nav_active);
        int corInativa = getColor(R.color.unifor_nav_inactive);

        atualizarItemBarra(ivTabInicio, tvTabInicio, aba == AbaNavegacao.INICIO, corAtiva, corInativa);
        atualizarItemBarra(ivTabTorpedos, tvTabTorpedos, aba == AbaNavegacao.TORPEDOS, corAtiva, corInativa);
        atualizarItemBarra(ivTabMenu, tvTabMenu, aba == AbaNavegacao.MENU, corAtiva, corInativa);

        if (animar) {
            View viewAtiva = (aba == AbaNavegacao.INICIO) ? viewInicio :
                    (aba == AbaNavegacao.TORPEDOS) ? viewTorpedos : viewMenu;
            if (viewAtiva != null) {
                viewAtiva.setAlpha(0.6f);
                viewAtiva.animate().alpha(1f).setDuration(180).start();
            }
        }
    }

    private void atualizarItemBarra(ImageView icon, TextView text, boolean ativo, int corAtiva, int corInativa) {
        if (icon != null) {
            icon.setColorFilter(ativo ? corAtiva : corInativa);
        }
        if (text != null) {
            text.setTextColor(ativo ? corAtiva : corInativa);
            text.setTypeface(null, ativo ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void atualizarDadosProfessor() {
        Professor p = obterProfessorPrincipal();

        if (tvMenuNomeProfessor != null) {
            tvMenuNomeProfessor.setText(p.getNome());
        }
        if (tvMenuMatriculaProfessor != null) {
            String mat = p.getMatricula();
            tvMenuMatriculaProfessor.setText("Matrícula: " + (mat != null && !mat.trim().isEmpty() ? mat : "—"));
        }
        if (tvTorpedoRemetente != null) {
            String mat = p.getMatricula();
            tvTorpedoRemetente.setText(p.getNome() + " • Matrícula: " + (mat != null && !mat.trim().isEmpty() ? mat : "—"));
        }
    }

    private Professor obterProfessorPrincipal() {
        if (professorRepository != null) {
            List<Professor> lista = professorRepository.listar();
            if (!lista.isEmpty()) {
                return lista.get(0);
            }
        }
        // Professor cadastrado institucionalmente para testes
        return new Professor(FakeFingerprintMatcher.EMAIL_TESTE, "Professor Teste", "0001", true);
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
