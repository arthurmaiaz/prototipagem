package br.unifor.cct.fluxo;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;

import br.unifor.cct.api.ReservaApi;
import br.unifor.cct.api.ReservaCallback;
import br.unifor.cct.api.ReservaResultado;
import br.unifor.cct.biometria.BiometriaCallback;
import br.unifor.cct.biometria.ResultadoBiometria;
import br.unifor.cct.model.Professor;
import br.unifor.cct.model.Projetor;
import br.unifor.cct.model.Reserva;
import br.unifor.cct.nfc.ResultadoNfc;

/**
 * Sequência do terminal (Fase 5), sem nenhuma tela: a Activity só repassa
 * os eventos (dedo, tag, botões) e desenha o Estado que recebe.
 *
 * Reservar:  DIGITAL -> IDENTIFICANDO -> PROJETOR -> HORARIO -> ENVIANDO -> CONCLUIDA
 * Devolver:  PROJETOR -> ENVIANDO -> CONCLUIDA   (não pede digital)
 *
 * Erros não encerram o fluxo: o Estado volta para a etapa em que dá para
 * tentar de novo, com a mensagem e erro = true. Ex.: projetor ocupado
 * volta para HORARIO; digital não reconhecida fica em DIGITAL.
 *
 * Tudo roda na thread principal: os callbacks do BiometriaService, do
 * NfcService e da ReservaApi já chegam nela. Respostas que chegam depois
 * de reiniciar() ou voltar() são ignoradas.
 */
public class FluxoReserva {

    /** Duração sugerida na etapa HORARIO; o professor pode mudar. */
    public static final int DURACAO_SUGERIDA_MIN = 120;

    public enum Modo { RESERVAR, DEVOLVER }

    public enum Etapa { DIGITAL, IDENTIFICANDO, PROJETOR, HORARIO, ENVIANDO, CONCLUIDA }

    /** BiometriaService::identificarProfessor serve aqui. */
    public interface Identificador {
        void identificarProfessor(BiometriaCallback callback);
    }

    /** ProfessorRepository::buscarPorEmail serve aqui. */
    public interface Professores {
        Professor buscarPorEmail(String email);
    }

    public interface Listener {
        void onEstado(Estado estado);
    }

    private final Identificador identificador;
    private final Professores professores;
    private final ReservaApi api;
    private final Clock relogio;
    private Listener listener = estado -> { };

    private Estado estado;
    /** Muda a cada reinício ou volta; respostas de rodadas antigas são ignoradas. */
    private int rodada;

    public FluxoReserva(Identificador identificador, Professores professores,
                        ReservaApi api, Clock relogio) {
        this.identificador = identificador;
        this.professores = professores;
        this.api = api;
        this.relogio = relogio;
        this.estado = Estado.inicial(Modo.RESERVAR);
    }

    /** Recebe o estado atual na hora e cada mudança depois. */
    public void setListener(Listener listener) {
        this.listener = listener == null ? estado -> { } : listener;
        this.listener.onEstado(estado);
    }

    public Estado getEstado() {
        return estado;
    }

    /** Começa do zero no modo escolhido. */
    public void iniciar(Modo modo) {
        rodada++;
        publicar(Estado.inicial(modo));
    }

    /** Volta ao começo do modo atual (ex.: depois de CONCLUIDA ou por inatividade). */
    public void reiniciar() {
        iniciar(estado.modo);
    }

    /** Uma etapa para trás: HORARIO -> PROJETOR -> DIGITAL. */
    public void voltar() {
        switch (estado.etapa) {
            case HORARIO:
                rodada++;
                publicar(estado.semProjetor().em(Etapa.PROJETOR)
                        .comMensagem("Aproxime o celular da etiqueta do projetor.", false));
                break;
            case PROJETOR:
                if (estado.modo == Modo.RESERVAR) {
                    rodada++;
                    publicar(Estado.inicial(Modo.RESERVAR));
                }
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------ digital

    /** O professor vai colocar o dedo: chama o leitor. */
    public void lerDigital() {
        if (estado.modo != Modo.RESERVAR || estado.etapa != Etapa.DIGITAL) return;
        int minhaRodada = ++rodada;
        publicar(estado.em(Etapa.IDENTIFICANDO).comMensagem("Coloque o dedo no leitor.", false));
        identificador.identificarProfessor(new BiometriaCallback() {
            @Override
            public void onSucesso(ResultadoBiometria resultado) {
                if (minhaRodada != rodada) return;
                if (!resultado.isSucesso() || resultado.getProfessorEmail() == null) {
                    publicar(estado.em(Etapa.DIGITAL).comMensagem(
                            "Digital não reconhecida. Tente de novo.", true));
                    return;
                }
                identificado(resultado.getProfessorEmail());
            }

            @Override
            public void onErro(String mensagem) {
                if (minhaRodada != rodada) return;
                publicar(estado.em(Etapa.DIGITAL).comMensagem(mensagem, true));
            }
        });
    }

    private void identificado(String professorEmail) {
        Professor professor = professores.buscarPorEmail(professorEmail);
        if (professor != null && !professor.isAtivo()) {
            publicar(estado.em(Etapa.DIGITAL).comMensagem(
                    professor.getNome() + " está inativo. Procure a coordenação.", true));
            return;
        }
        // Professor fora do cadastro local (não sincronizado) segue mesmo
        // assim: quem decide é o Apps Script.
        Estado novo = estado.comProfessor(Professor.normalizarEmail(professorEmail),
                professor == null ? null : professor.getNome());
        publicar(novo.em(Etapa.PROJETOR).comMensagem(
                "Olá, " + novo.nomeOuEmail() + ". Aproxime o celular da etiqueta do projetor.", false));
    }

    // ----------------------------------------------------------- projetor

    /** Leitura do NfcService. Leituras fora da etapa PROJETOR são ignoradas. */
    public void projetorLido(ResultadoNfc resultado) {
        if (estado.etapa != Etapa.PROJETOR) return;
        if (!resultado.isSucesso()) {
            publicar(estado.comMensagem(resultado.getMensagem(), true));
            return;
        }
        informarProjetor(resultado.getCodigoProjetor());
    }

    /** Código vindo do NFC ou, quando integrado, do QR Code. */
    public void informarProjetor(String codigo) {
        if (estado.etapa != Etapa.PROJETOR) return;
        String normalizado = codigo == null ? "" : codigo.trim().toUpperCase(Locale.ROOT);
        if (!Projetor.codigoValido(normalizado)) {
            publicar(estado.comMensagem("Código de projetor inválido: " + codigo, true));
            return;
        }
        if (estado.modo == Modo.DEVOLVER) {
            devolver(normalizado);
            return;
        }
        LocalDateTime agora = LocalDateTime.now(relogio).withSecond(0).withNano(0);
        LocalTime inicio = agora.toLocalTime();
        LocalTime fim = inicio.plusMinutes(DURACAO_SUGERIDA_MIN);
        if (fim.isBefore(inicio)) fim = LocalTime.of(23, 59); // passaria da meia-noite
        publicar(estado.comProjetor(normalizado)
                .comHorario(agora.toLocalDate().format(Reserva.FORMATO_DATA),
                        inicio.format(Reserva.FORMATO_HORA), fim.format(Reserva.FORMATO_HORA))
                .em(Etapa.HORARIO)
                .comMensagem("Confira o horário e confirme.", false));
    }

    // ------------------------------------------------------------ horário

    /** Confirma a reserva com o horário escolhido (formatos aaaa-mm-dd e hh:mm). */
    public void confirmarHorario(String data, String inicio, String fim) {
        if (estado.etapa != Etapa.HORARIO) return;
        Estado comHorario = estado.comHorario(data, inicio, fim);
        Reserva reserva;
        try {
            reserva = Reserva.nova(estado.professorEmail, estado.projetorCodigo, data, inicio, fim);
        } catch (IllegalArgumentException e) {
            publicar(comHorario.comMensagem(e.getMessage(), true));
            return;
        }
        if (reserva.jaTerminou(LocalDateTime.now(relogio))) {
            publicar(comHorario.comMensagem("Esse horário já passou. Escolha um horário futuro.", true));
            return;
        }
        int minhaRodada = ++rodada;
        publicar(comHorario.em(Etapa.ENVIANDO).comMensagem("Enviando a reserva...", false));
        api.criarReserva(reserva.getProfessorEmail(), reserva.getProjetorCodigo(),
                reserva.getDataTexto(), reserva.getInicioTexto(), reserva.getFimTexto(),
                resposta(minhaRodada, Etapa.HORARIO));
    }

    // ----------------------------------------------------------- devolução

    private void devolver(String codigo) {
        int minhaRodada = ++rodada;
        publicar(estado.comProjetor(codigo).em(Etapa.ENVIANDO)
                .comMensagem("Registrando a devolução...", false));
        api.devolverProjetor(codigo, resposta(minhaRodada, Etapa.PROJETOR));
    }

    /** Sucesso vai para CONCLUIDA; recusa ou falha volta para a etapa indicada. */
    private ReservaCallback resposta(int minhaRodada, Etapa seRecusar) {
        return new ReservaCallback() {
            @Override
            public void onSucesso(ReservaResultado resultado) {
                if (minhaRodada != rodada) return;
                if (resultado.isSucesso()) {
                    publicar(estado.comReservaId(resultado.getReservaId()).em(Etapa.CONCLUIDA)
                            .comMensagem(resultado.getMensagem(), false));
                } else {
                    recusado(resultado.getMensagem());
                }
            }

            @Override
            public void onErro(String mensagem) {
                if (minhaRodada != rodada) return;
                recusado(mensagem);
            }

            private void recusado(String mensagem) {
                Estado volta = estado.em(seRecusar);
                if (seRecusar == Etapa.PROJETOR) volta = volta.semProjetor();
                publicar(volta.comMensagem(mensagem, true));
            }
        };
    }

    private void publicar(Estado novo) {
        estado = novo;
        listener.onEstado(novo);
    }

    // ------------------------------------------------------------- estado

    /**
     * Foto imutável do fluxo para a tela desenhar. Campos que ainda não
     * foram preenchidos ficam null.
     */
    public static final class Estado {
        public final Modo modo;
        public final Etapa etapa;
        public final String professorEmail;
        public final String professorNome;
        public final String projetorCodigo;
        public final String data;
        public final String inicio;
        public final String fim;
        public final Integer reservaId;
        /** Instrução ou resultado para mostrar; null quando não há. */
        public final String mensagem;
        /** true se a mensagem é de erro (mostrar em destaque). */
        public final boolean erro;

        private Estado(Modo modo, Etapa etapa, String professorEmail, String professorNome,
                       String projetorCodigo, String data, String inicio, String fim,
                       Integer reservaId, String mensagem, boolean erro) {
            this.modo = modo;
            this.etapa = etapa;
            this.professorEmail = professorEmail;
            this.professorNome = professorNome;
            this.projetorCodigo = projetorCodigo;
            this.data = data;
            this.inicio = inicio;
            this.fim = fim;
            this.reservaId = reservaId;
            this.mensagem = mensagem;
            this.erro = erro;
        }

        static Estado inicial(Modo modo) {
            if (modo == Modo.DEVOLVER) {
                return new Estado(modo, Etapa.PROJETOR, null, null, null, null, null, null, null,
                        "Aproxime o celular da etiqueta do projetor que está devolvendo.", false);
            }
            return new Estado(modo, Etapa.DIGITAL, null, null, null, null, null, null, null,
                    "Toque em ler digital e coloque o dedo no leitor.", false);
        }

        /** Nome do professor, ou o e-mail se ele não estiver no cadastro local. */
        public String nomeOuEmail() {
            if (professorNome != null) return professorNome;
            return professorEmail == null ? "" : professorEmail;
        }

        Estado em(Etapa nova) {
            return new Estado(modo, nova, professorEmail, professorNome, projetorCodigo,
                    data, inicio, fim, reservaId, mensagem, erro);
        }

        Estado comMensagem(String texto, boolean deErro) {
            return new Estado(modo, etapa, professorEmail, professorNome, projetorCodigo,
                    data, inicio, fim, reservaId, texto, deErro);
        }

        Estado comProfessor(String email, String nome) {
            return new Estado(modo, etapa, email, nome, projetorCodigo,
                    data, inicio, fim, reservaId, mensagem, erro);
        }

        Estado comProjetor(String codigo) {
            return new Estado(modo, etapa, professorEmail, professorNome, codigo,
                    data, inicio, fim, reservaId, mensagem, erro);
        }

        Estado semProjetor() {
            return new Estado(modo, etapa, professorEmail, professorNome, null,
                    null, null, null, reservaId, mensagem, erro);
        }

        Estado comHorario(String novaData, String novoInicio, String novoFim) {
            return new Estado(modo, etapa, professorEmail, professorNome, projetorCodigo,
                    novaData, novoInicio, novoFim, reservaId, mensagem, erro);
        }

        Estado comReservaId(Integer id) {
            return new Estado(modo, etapa, professorEmail, professorNome, projetorCodigo,
                    data, inicio, fim, id, mensagem, erro);
        }

        @Override
        public String toString() {
            return "Estado{" + modo + " " + etapa + ", prof=" + professorEmail + ", " + projetorCodigo
                    + ", " + data + " " + inicio + "-" + fim + ", reserva=" + reservaId
                    + (erro ? ", ERRO: " : ", ") + mensagem + "}";
        }
    }
}
