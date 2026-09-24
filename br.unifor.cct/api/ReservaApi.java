package br.unifor.cct.api;

/**
 * Contrato combinado na seção 4 do documento de divisão de tarefas.
 *
 * Arthur implementa esta interface contra o Google Apps Script.
 * O front-end (Nikolas) consome apenas esta interface — nunca fala
 * diretamente com o Apps Script — então uma implementação mock pode
 * substituir a real sem tocar em nenhuma tela.
 *
 * Segue o formato de mensagens da seção 14 do documento de arquitetura:
 * acao, professorId, projetorId, data, inicio, fim / sucesso, reservaId, mensagem.
 */
public interface ReservaApi {

    /**
     * @param professorId  id retornado pela identificação biométrica (ResultadoBiometria)
     * @param projetorId   código lido por QR Code ou NFC (ex.: "PAT-001")
     * @param data         formato "yyyy-MM-dd", ex.: "2026-09-22"
     * @param inicio       formato "HH:mm", ex.: "19:00"
     * @param fim          formato "HH:mm", ex.: "21:00"
     */
    void criarReserva(int professorId, String projetorId, String data,
                       String inicio, String fim, ReservaCallback callback);

    void cancelarReserva(int reservaId, ReservaCallback callback);

    void consultarReserva(int reservaId, ReservaCallback callback);
}
