package br.unifor.cct.api;

/**
 * Busca no servidor o cadastro de professores (aba "professores" do Sheets),
 * para o terminal manter o ProfessorRepository atualizado.
 *
 * Fica separada da ReservaApi para não mudar o contrato de reservas
 * combinado com o front-end.
 */
public interface ProfessorApi {

    void listarProfessores(ProfessoresCallback callback);
}
