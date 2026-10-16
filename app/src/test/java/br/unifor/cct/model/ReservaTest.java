package br.unifor.cct.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;

public class ReservaTest {

    @Test
    public void novaConverteFormatoDaApi() {
        Reserva r = Reserva.nova(1, "PAT-001", "2026-09-22", "19:00", "21:00");
        assertNull(r.getId());
        assertEquals(StatusReserva.ATIVA, r.getStatus());
        assertEquals("2026-09-22", r.getDataTexto());
        assertEquals("19:00", r.getInicioTexto());
        assertEquals("21:00", r.getFimTexto());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejeitaFimAntesDoInicio() {
        Reserva.nova(1, "PAT-001", "2026-09-22", "21:00", "19:00");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejeitaDataForaDoFormato() {
        Reserva.nova(1, "PAT-001", "22/09/2026", "19:00", "21:00");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejeitaCodigoDeProjetorInvalido() {
        Reserva.nova(1, "projetor 1", "2026-09-22", "19:00", "21:00");
    }

    @Test
    public void sobreposicaoConflita() {
        Reserva a = Reserva.nova(1, "PAT-001", "2026-09-22", "19:00", "21:00");
        Reserva b = Reserva.nova(2, "PAT-001", "2026-09-22", "20:00", "22:00");
        assertTrue(a.conflitaCom(b));
        assertTrue(b.conflitaCom(a));
    }

    @Test
    public void horariosEncostadosNaoConflitam() {
        Reserva a = Reserva.nova(1, "PAT-001", "2026-09-22", "19:00", "21:00");
        Reserva b = Reserva.nova(2, "PAT-001", "2026-09-22", "21:00", "22:00");
        assertFalse(a.conflitaCom(b));
    }

    @Test
    public void projetorOuDiaDiferenteNaoConflita() {
        Reserva a = Reserva.nova(1, "PAT-001", "2026-09-22", "19:00", "21:00");
        assertFalse(a.conflitaCom(Reserva.nova(2, "PAT-002", "2026-09-22", "19:00", "21:00")));
        assertFalse(a.conflitaCom(Reserva.nova(2, "PAT-001", "2026-09-23", "19:00", "21:00")));
    }

    @Test
    public void reservaCanceladaNaoConflita() {
        Reserva a = Reserva.nova(1, "PAT-001", "2026-09-22", "19:00", "21:00");
        Reserva b = Reserva.nova(2, "PAT-001", "2026-09-22", "19:00", "21:00")
                .comStatus(StatusReserva.CANCELADA);
        assertFalse(a.conflitaCom(b));
    }

    @Test
    public void reservaFinalizadaNaoConflita() {
        Reserva a = Reserva.nova(1, "PAT-001", "2026-09-22", "19:00", "21:00");
        Reserva b = Reserva.nova(2, "PAT-001", "2026-09-22", "19:00", "21:00")
                .comStatus(StatusReserva.FINALIZADA);
        assertFalse(a.conflitaCom(b));
    }

    @Test
    public void duracaoEmMinutos() {
        assertEquals(150, Reserva.nova(1, "PAT-001", "2026-09-22", "19:00", "21:30").getDuracaoMinutos());
    }

    @Test
    public void jaTerminouComparaComOFim() {
        Reserva r = Reserva.nova(1, "PAT-001", "2026-09-22", "19:00", "21:00");
        assertFalse(r.jaTerminou(LocalDateTime.of(2026, 9, 22, 20, 0)));
        assertTrue(r.jaTerminou(LocalDateTime.of(2026, 9, 22, 21, 0)));
        assertTrue(r.jaTerminou(LocalDateTime.of(2026, 9, 23, 8, 0)));
    }

    @Test
    public void codigoDeProjetor() {
        assertTrue(Projetor.codigoValido("PAT-001"));
        assertTrue(Projetor.codigoValido("PAT-1234"));
        assertFalse(Projetor.codigoValido("PAT-01"));
        assertFalse(Projetor.codigoValido("pat-001"));
        assertFalse(Projetor.codigoValido(" PAT-001"));
        assertFalse(Projetor.codigoValido(null));
    }
}
