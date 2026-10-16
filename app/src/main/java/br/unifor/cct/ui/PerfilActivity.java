package br.unifor.cct.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

import br.unifor.cct.R;
import br.unifor.cct.api.ProfessoresCallback;
import br.unifor.cct.api.ReservaApiFactory;
import br.unifor.cct.model.Professor;
import br.unifor.cct.storage.ProfessorRepository;

/**
 * Tela "Meu Perfil" do aplicativo de Reservas do CCT - Unifor.
 * Exibe as informações reais cadastradas do professor (nome, matrícula e vinculação CCT),
 * permite conferência direta com o servidor institucional e informa sobre a
 * imutabilidade de dados sem validação administrativa.
 */
public class PerfilActivity extends Activity {

    private ProfessorRepository professores;

    private ImageView btnVoltarPerfil;
    private View btnAlterarFoto;
    private ImageView ivFotoPerfil;
    private TextView tvPerfilNome;
    private TextView tvPerfilCurso;
    private TextView tvPerfilSituacao;
    private TextView tvPerfilNomeDetalhe;
    private TextView tvPerfilMatriculaDetalhe;
    private TextView tvPerfilCursoDetalhe;
    private Button btnConferirDados;
    private ProgressBar pbConferir;
    private TextView tvConferirResultado;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_perfil);

        professores = new ProfessorRepository(this);

        btnVoltarPerfil = findViewById(R.id.btnVoltarPerfil);
        btnAlterarFoto = findViewById(R.id.btnAlterarFoto);
        ivFotoPerfil = findViewById(R.id.ivFotoPerfil);
        tvPerfilNome = findViewById(R.id.tvPerfilNome);
        tvPerfilCurso = findViewById(R.id.tvPerfilCurso);
        tvPerfilSituacao = findViewById(R.id.tvPerfilSituacao);
        tvPerfilNomeDetalhe = findViewById(R.id.tvPerfilNomeDetalhe);
        tvPerfilMatriculaDetalhe = findViewById(R.id.tvPerfilMatriculaDetalhe);
        tvPerfilCursoDetalhe = findViewById(R.id.tvPerfilCursoDetalhe);
        btnConferirDados = findViewById(R.id.btnConferirDados);
        pbConferir = findViewById(R.id.pbConferir);
        tvConferirResultado = findViewById(R.id.tvConferirResultado);

        btnVoltarPerfil.setOnClickListener(v -> finish());

        // Alteração de foto com aviso de suporte dependente de integração
        View.OnClickListener listenerFoto = v -> exibirAvisoAlteracaoFoto();
        btnAlterarFoto.setOnClickListener(listenerFoto);
        ivFotoPerfil.setOnClickListener(listenerFoto);

        // Conferência dos dados cadastrados contra a API/Google Sheets
        btnConferirDados.setOnClickListener(v -> conferirDadosNoServidor());

        atualizarDadosNaTela();
    }

    private void atualizarDadosNaTela() {
        Professor p = obterProfessorPrincipal();
        tvPerfilNome.setText(p.getNome());
        tvPerfilNomeDetalhe.setText(p.getNome());

        String matricula = p.getMatricula();
        if (matricula == null || matricula.trim().isEmpty()) {
            matricula = "Não informada no cadastro";
        }
        tvPerfilMatriculaDetalhe.setText(matricula);

        tvPerfilCurso.setText(R.string.perfil_curso_cct);
        tvPerfilCursoDetalhe.setText(R.string.perfil_curso_cct);

        if (p.isAtivo()) {
            tvPerfilSituacao.setText(R.string.perfil_situacao_ativo);
            tvPerfilSituacao.setTextColor(getColor(R.color.unifor_status_active));
        } else {
            tvPerfilSituacao.setText(R.string.perfil_situacao_inativo);
            tvPerfilSituacao.setTextColor(getColor(R.color.unifor_status_pending_text));
        }
    }

    private Professor obterProfessorPrincipal() {
        int idExtra = getIntent().getIntExtra("professorId", -1);
        if (idExtra > 0) {
            Professor p = professores.buscarPorId(idExtra);
            if (p != null) {
                return p;
            }
        }
        List<Professor> lista = professores.listar();
        if (!lista.isEmpty()) {
            return lista.get(0);
        }
        // Retorna o professor institucional de teste padronizado no projeto
        return new Professor(1, "Professor Teste", "0001", true);
    }

    private void conferirDadosNoServidor() {
        pbConferir.setVisibility(View.VISIBLE);
        tvConferirResultado.setVisibility(View.GONE);
        btnConferirDados.setEnabled(false);

        professores.sincronizar(ReservaApiFactory.obterProfessorApi(), new ProfessoresCallback() {
            @Override
            public void onSucesso(List<Professor> lista) {
                pbConferir.setVisibility(View.GONE);
                btnConferirDados.setEnabled(true);
                tvConferirResultado.setVisibility(View.VISIBLE);
                tvConferirResultado.setTextColor(getColor(R.color.unifor_status_active));
                tvConferirResultado.setText(getString(R.string.perfil_conferido_sucesso));
                atualizarDadosNaTela();
            }

            @Override
            public void onErro(String mensagem) {
                pbConferir.setVisibility(View.GONE);
                btnConferirDados.setEnabled(true);
                tvConferirResultado.setVisibility(View.VISIBLE);
                tvConferirResultado.setTextColor(getColor(R.color.unifor_status_pending_text));
                tvConferirResultado.setText("Aviso de sincronização: " + mensagem + " (dados locais mantidos).");
            }
        });
    }

    private void exibirAvisoAlteracaoFoto() {
        new AlertDialog.Builder(this)
                .setTitle("Alteração de Foto de Perfil")
                .setMessage(R.string.perfil_alterar_foto_aviso)
                .setPositiveButton("Entendido", (dialog, which) -> dialog.dismiss())
                .show();
    }
}
