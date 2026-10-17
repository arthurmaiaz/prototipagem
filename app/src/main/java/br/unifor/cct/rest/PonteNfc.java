package br.unifor.cct.rest;

import java.util.concurrent.CompletableFuture;

import br.unifor.cct.nfc.ResultadoNfc;

/**
 * Liga a API REST (threads do ServidorHttp) à tela que lê as tags.
 *
 * Só uma Activity em primeiro plano recebe tags no Android, então o
 * serviço não lê sozinho: um POST /v1/nfc/read abre um pedido aqui e
 * espera; a TesteNfcActivity entrega a próxima leitura com entregar().
 *
 * Um pedido por vez. Singleton do processo (padrao()), compartilhado
 * entre o ServicoApiNfc e a tela; os testes criam instâncias próprias.
 */
public class PonteNfc {

    /** Avisos para a tela. Chamado na thread do servidor, não na principal. */
    public interface Ouvinte {
        void onPedidoIniciado();

        void onPedidoEncerrado();
    }

    private static final PonteNfc PADRAO = new PonteNfc();

    public static PonteNfc padrao() {
        return PADRAO;
    }

    private volatile boolean nfcDisponivel;
    private volatile boolean nfcLigado;
    private volatile boolean telaAberta;
    private volatile boolean gravando;
    private volatile Ouvinte ouvinte;

    private CompletableFuture<ResultadoNfc> pendente;

    // ---------------------------------------------------- estado (pela tela)

    public void setNfc(boolean disponivel, boolean ligado) {
        nfcDisponivel = disponivel;
        nfcLigado = ligado;
    }

    /** Ao fechar a tela, o pedido em espera é encerrado na hora (503). */
    public void setTelaAberta(boolean aberta) {
        telaAberta = aberta;
        if (!aberta) {
            synchronized (this) {
                if (pendente != null) {
                    pendente.completeExceptionally(
                            new IllegalStateException("A tela de NFC foi fechada antes da leitura."));
                }
            }
        }
    }

    public void setGravando(boolean gravando) {
        this.gravando = gravando;
    }

    public void setOuvinte(Ouvinte ouvinte) {
        this.ouvinte = ouvinte;
    }

    public boolean isNfcDisponivel() {
        return nfcDisponivel;
    }

    public boolean isNfcLigado() {
        return nfcLigado;
    }

    public boolean isTelaAberta() {
        return telaAberta;
    }

    public boolean isGravando() {
        return gravando;
    }

    public synchronized boolean haPedidoPendente() {
        return pendente != null;
    }

    // ------------------------------------------------------------- pedidos

    /** @return o pedido novo, ou null se já houver um esperando. */
    CompletableFuture<ResultadoNfc> abrirPedido() {
        CompletableFuture<ResultadoNfc> pedido;
        synchronized (this) {
            if (pendente != null) return null;
            pedido = new CompletableFuture<>();
            pendente = pedido;
        }
        Ouvinte o = ouvinte;
        if (o != null) o.onPedidoIniciado();
        return pedido;
    }

    void fecharPedido(CompletableFuture<ResultadoNfc> pedido) {
        synchronized (this) {
            if (pendente != pedido) return;
            pendente = null;
        }
        Ouvinte o = ouvinte;
        if (o != null) o.onPedidoEncerrado();
    }

    /**
     * Chamado pela tela a cada tag lida.
     *
     * @return true se havia um pedido da API esperando e ele recebeu a leitura
     */
    public synchronized boolean entregar(ResultadoNfc resultado) {
        return pendente != null && pendente.complete(resultado);
    }
}
