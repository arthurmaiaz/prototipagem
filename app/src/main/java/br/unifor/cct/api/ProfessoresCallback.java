package br.unifor.cct.api;

import java.util.List;

import br.unifor.cct.model.Professor;

/**
 * Resposta da ProfessorApi. Sempre chamado na thread principal.
 */
public interface ProfessoresCallback {

    void onSucesso(List<Professor> professores);

    /** mensagem é sempre não nula e pronta para exibir ao usuário. */
    void onErro(String mensagem);
}
