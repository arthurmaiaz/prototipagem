package br.unifor.cct.rest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Registro de todas as chamadas à API (GET /v1/audit/logs).
 *
 * Fica só em memória, enquanto o app estiver aberto, com as
 * CAPACIDADE chamadas mais recentes (cobre o offset máximo de 10000 com
 * limit 50). Não guarda o token nem o corpo dos pedidos.
 */
public class Auditoria {

    public static final int CAPACIDADE = 10_050;

    public static final class Entrada {
        public final long id;
        public final String horario;
        public final String ipCliente;
        public final String metodo;
        public final String caminho;
        public final int status;
        public final String correlationId;
        public final long duracaoMs;
        /** Patrimônio devolvido na leitura, ou null. */
        public final String patrimonio;

        Entrada(long id, String horario, String ipCliente, String metodo, String caminho,
                int status, String correlationId, long duracaoMs, String patrimonio) {
            this.id = id;
            this.horario = horario;
            this.ipCliente = ipCliente;
            this.metodo = metodo;
            this.caminho = caminho;
            this.status = status;
            this.correlationId = correlationId;
            this.duracaoMs = duracaoMs;
            this.patrimonio = patrimonio;
        }
    }

    /** Mais recente primeiro. */
    private final Deque<Entrada> entradas = new ArrayDeque<>();
    private long proximoId = 1;

    public synchronized void registrar(String horario, String ipCliente, String metodo,
                                       String caminho, int status, String correlationId,
                                       long duracaoMs, String patrimonio) {
        entradas.addFirst(new Entrada(proximoId++, horario, ipCliente, metodo, caminho,
                status, correlationId, duracaoMs, patrimonio));
        while (entradas.size() > CAPACIDADE) {
            entradas.removeLast();
        }
    }

    public synchronized int total() {
        return entradas.size();
    }

    /** Página de entradas, da mais recente para a mais antiga. */
    public synchronized List<Entrada> pagina(int offset, int limit) {
        List<Entrada> pagina = new ArrayList<>();
        Iterator<Entrada> it = entradas.iterator();
        for (int i = 0; it.hasNext() && pagina.size() < limit; i++) {
            Entrada e = it.next();
            if (i >= offset) pagina.add(e);
        }
        return Collections.unmodifiableList(pagina);
    }
}
