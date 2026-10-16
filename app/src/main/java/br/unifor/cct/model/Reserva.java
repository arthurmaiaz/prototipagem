package br.unifor.cct.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Reserva de um projetor por um professor em um intervalo de horário.
 *
 * Os campos seguem o formato de mensagens da ReservaApi / Apps Script:
 * data "yyyy-MM-dd", inicio e fim "HH:mm". Os métodos get*Texto()
 * devolvem exatamente esse formato para montar o JSON.
 */
public class Reserva {

    public static final DateTimeFormatter FORMATO_DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm");

    /** null enquanto a reserva ainda não foi criada no servidor. */
    private final Integer id;
    private final int professorId;
    private final String projetorCodigo;
    private final LocalDate data;
    private final LocalTime inicio;
    private final LocalTime fim;
    private final StatusReserva status;

    public Reserva(Integer id, int professorId, String projetorCodigo,
                   LocalDate data, LocalTime inicio, LocalTime fim, StatusReserva status) {
        if (!Projetor.codigoValido(projetorCodigo)) {
            throw new IllegalArgumentException("Código de projetor inválido: " + projetorCodigo);
        }
        if (data == null || inicio == null || fim == null || status == null) {
            throw new IllegalArgumentException("Data, horário e status são obrigatórios.");
        }
        if (!fim.isAfter(inicio)) {
            throw new IllegalArgumentException("O horário de fim deve ser depois do início.");
        }
        this.id = id;
        this.professorId = professorId;
        this.projetorCodigo = projetorCodigo;
        this.data = data;
        this.inicio = inicio;
        this.fim = fim;
        this.status = status;
    }

    /**
     * Monta uma reserva nova (sem id, status ATIVA) a partir dos textos
     * no formato da API. Lança IllegalArgumentException se algum campo
     * estiver fora do formato.
     */
    public static Reserva nova(int professorId, String projetorCodigo,
                               String data, String inicio, String fim) {
        try {
            return new Reserva(null, professorId, projetorCodigo,
                    LocalDate.parse(data, FORMATO_DATA),
                    LocalTime.parse(inicio, FORMATO_HORA),
                    LocalTime.parse(fim, FORMATO_HORA),
                    StatusReserva.ATIVA);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Data ou horário fora do formato: " + e.getParsedString(), e);
        }
    }

    /**
     * Duas reservas do mesmo projetor no mesmo dia conflitam se os
     * intervalos se sobrepõem. Encostar (uma termina 21:00, outra começa
     * 21:00) não é conflito. Só reservas ATIVA conflitam: canceladas e
     * finalizadas (projetor devolvido) liberam o horário.
     *
     * A checagem oficial é do Apps Script; esta serve para validar
     * antes de enviar.
     */
    public boolean conflitaCom(Reserva outra) {
        return status == StatusReserva.ATIVA
                && outra.status == StatusReserva.ATIVA
                && projetorCodigo.equals(outra.projetorCodigo)
                && data.equals(outra.data)
                && inicio.isBefore(outra.fim)
                && outra.inicio.isBefore(fim);
    }

    public long getDuracaoMinutos() {
        return Duration.between(inicio, fim).toMinutes();
    }

    /**
     * true se o horário de fim já passou. O Apps Script recusa reservas
     * assim; esta checagem serve para avisar antes de enviar.
     */
    public boolean jaTerminou(LocalDateTime agora) {
        return !LocalDateTime.of(data, fim).isAfter(agora);
    }

    public Reserva comId(int novoId) {
        return new Reserva(novoId, professorId, projetorCodigo, data, inicio, fim, status);
    }

    public Reserva comStatus(StatusReserva novoStatus) {
        return new Reserva(id, professorId, projetorCodigo, data, inicio, fim, novoStatus);
    }

    public Integer getId() {
        return id;
    }

    public int getProfessorId() {
        return professorId;
    }

    public String getProjetorCodigo() {
        return projetorCodigo;
    }

    public LocalDate getData() {
        return data;
    }

    public LocalTime getInicio() {
        return inicio;
    }

    public LocalTime getFim() {
        return fim;
    }

    public StatusReserva getStatus() {
        return status;
    }

    public String getDataTexto() {
        return data.format(FORMATO_DATA);
    }

    public String getInicioTexto() {
        return inicio.format(FORMATO_HORA);
    }

    public String getFimTexto() {
        return fim.format(FORMATO_HORA);
    }

    @Override
    public String toString() {
        return "Reserva{" + id + ", prof=" + professorId + ", " + projetorCodigo + ", "
                + getDataTexto() + " " + getInicioTexto() + "-" + getFimTexto() + ", " + status + "}";
    }
}
