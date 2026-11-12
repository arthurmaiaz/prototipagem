package br.unifor.cct.rest;

import java.util.concurrent.CompletableFuture;

import br.unifor.cct.qr.ResultadoQr;

/**
 * Liga a API REST (threads do ServidorHttp) à LerQrCodeActivity, que é quem
 * usa a câmera. Mesmo modelo da PonteNfc: um POST /v1/qr/read abre um pedido
 * aqui e espera; a tela entrega a próxima leitura com entregar().
 *
 * Diferente do NFC, a API tenta abrir a tela sozinha (abridor), como na PoC de
 * QR Code. O Android só deixa abrir se o app estiver em primeiro plano; senão a
 * tela tem que ser aberta à mão.
 *
 * Um pedido por vez. Singleton do processo (padrao()); os testes criam instâncias próprias.
 */
public class PonteQr {

    /** Avisos para a tela. Chamado na thread do servidor, não na principal. */
    public interface Ouvinte {
        void onPedidoIniciado();

        void onPedidoEncerrado();
    }

    private static final PonteQr PADRAO = new PonteQr();

    public static PonteQr padrao() {
        return PADRAO;
    }

    private volatile boolean telaAberta;
    private volatile Ouvinte ouvinte;
    private volatile Runnable abridor;

    private CompletableFuture<ResultadoQr> pendente;

    // ---------------------------------------------------- estado (pela tela)

    /** Ao fechar a tela, o pedido em espera é encerrado na hora (503). */
    public void setTelaAberta(boolean aberta) {
        telaAberta = aberta;
        if (!aberta) {
            synchronized (this) {
                if (pendente != null) {
                    pendente.completeExceptionally(
                            new IllegalStateException("A tela de QR Code foi fechada antes da leitura."));
                }
            }
        }
    }

    public void setOuvinte(Ouvinte ouvinte) {
        this.ouvinte = ouvinte;
    }

    /** Quem abre a tela da câmera quando chega um pedido e ela está fechada. */
    public void setAbridor(Runnable abridor) {
        this.abridor = abridor;
    }

    public boolean isTelaAberta() {
        return telaAberta;
    }

    public synchronized boolean haPedidoPendente() {
        return pendente != null;
    }

    // ------------------------------------------------------------- pedidos

    /** Pede para abrir a tela. @return false se não há como abrir. */
    boolean abrirTela() {
        Runnable a = abridor;
        if (a == null) return false;
        try {
            a.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return o pedido novo, ou null se já houver um esperando. */
    CompletableFuture<ResultadoQr> abrirPedido() {
        CompletableFuture<ResultadoQr> pedido;
        synchronized (this) {
            if (pendente != null) return null;
            pedido = new CompletableFuture<>();
            pendente = pedido;
        }
        Ouvinte o = ouvinte;
        if (o != null) o.onPedidoIniciado();
        return pedido;
    }

    void fecharPedido(CompletableFuture<ResultadoQr> pedido) {
        synchronized (this) {
            if (pendente != pedido) return;
            pendente = null;
        }
        Ouvinte o = ouvinte;
        if (o != null) o.onPedidoEncerrado();
    }

    /**
     * Chamado pela tela a cada QR Code lido.
     *
     * @return true se havia um pedido da API esperando e ele recebeu a leitura
     */
    public synchronized boolean entregar(ResultadoQr resultado) {
        return pendente != null && pendente.complete(resultado);
    }
}
