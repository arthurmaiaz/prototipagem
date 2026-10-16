package br.unifor.cct.admin;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PIN da coordenação, exigido para cadastrar e remover digitais no
 * terminal. Identificar o professor não pede PIN.
 *
 * O PIN nunca é guardado: o arquivo tem só o sal e o hash PBKDF2, mais o
 * contador de tentativas erradas. Fica em noBackupFilesDir, junto com o
 * banco de digitais. Quem esquecer o PIN precisa limpar os dados do app,
 * o que apaga também as digitais (a chave do Keystore vai junto). Assim,
 * trocar o PIN à força nunca dá acesso às digitais já cadastradas.
 *
 * Depois de MAX_TENTATIVAS erros seguidos, novas tentativas ficam
 * bloqueadas por TEMPO_BLOQUEIO, mesmo que o app seja reaberto. Um acerto
 * libera as ações de administrador por TEMPO_LIBERADO; a liberação fica só
 * na memória.
 */
public class PinAdministrador {

    public static final int TAMANHO_MINIMO = 4;
    public static final int TAMANHO_MAXIMO = 6;
    public static final int MAX_TENTATIVAS = 5;
    public static final Duration TEMPO_BLOQUEIO = Duration.ofMinutes(5);
    public static final Duration TEMPO_LIBERADO = Duration.ofMinutes(5);

    static final String ARQUIVO_PADRAO = "pin_administrador.properties";
    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    private static final int ITERACOES = 120_000;
    private static final int BITS_HASH = 256;
    private static final int BYTES_SAL = 16;

    /** Resultado de verificar(). */
    public enum Verificacao { CORRETO, INCORRETO, BLOQUEADO, SEM_PIN }

    private final File arquivo;
    private final Clock relogio;
    private final SecureRandom aleatorio = new SecureRandom();
    private final Properties dados = new Properties();
    private Instant liberadoAte = Instant.EPOCH;

    public PinAdministrador(File arquivo, Clock relogio) {
        this.arquivo = arquivo;
        this.relogio = relogio;
        carregar();
    }

    public static PinAdministrador padrao(Context context) {
        return new PinAdministrador(new File(context.getNoBackupFilesDir(), ARQUIVO_PADRAO),
                Clock.systemUTC());
    }

    /** Só dígitos, entre TAMANHO_MINIMO e TAMANHO_MAXIMO. */
    public static boolean formatoValido(String pin) {
        return pin != null && pin.matches("\\d{" + TAMANHO_MINIMO + "," + TAMANHO_MAXIMO + "}");
    }

    public boolean temPin() {
        return dados.getProperty("hash") != null;
    }

    /**
     * Cria o primeiro PIN. Para trocar um PIN existente, use trocar(), que
     * exige o atual.
     */
    public void definir(String pin) throws IOException {
        if (temPin()) throw new IllegalStateException("O PIN já foi definido. Use trocar().");
        gravarPin(pin);
        liberar();
    }

    /** Troca o PIN; falha (sem trocar) se o atual não conferir. */
    public Verificacao trocar(String atual, String novo) throws IOException {
        Verificacao v = verificar(atual);
        if (v == Verificacao.CORRETO) gravarPin(novo);
        return v;
    }

    /** Confere o PIN, conta os erros e, se acertar, libera as ações por um tempo. */
    public Verificacao verificar(String pin) throws IOException {
        if (!temPin()) return Verificacao.SEM_PIN;
        if (bloqueado()) return Verificacao.BLOQUEADO;

        if (formatoValido(pin) && MessageDigest.isEqual(
                calcularHash(pin, decodificar("sal")), decodificar("hash"))) {
            dados.remove("falhas");
            dados.remove("bloqueadoAte");
            salvar();
            liberar();
            return Verificacao.CORRETO;
        }

        int falhas = falhas() + 1;
        if (falhas >= MAX_TENTATIVAS) {
            dados.remove("falhas");
            dados.setProperty("bloqueadoAte",
                    String.valueOf(relogio.instant().plus(TEMPO_BLOQUEIO).toEpochMilli()));
        } else {
            dados.setProperty("falhas", String.valueOf(falhas));
        }
        salvar();
        return bloqueado() ? Verificacao.BLOQUEADO : Verificacao.INCORRETO;
    }

    public boolean liberado() {
        return relogio.instant().isBefore(liberadoAte);
    }

    /** Renova a liberação depois de uma ação de administrador. */
    public void renovar() {
        if (liberado()) liberar();
    }

    /** Encerra a liberação antes do tempo (ex.: ao sair da tela). */
    public void encerrar() {
        liberadoAte = Instant.EPOCH;
    }

    public boolean bloqueado() {
        return relogio.instant().isBefore(bloqueadoAte());
    }

    /** Tempo até poder tentar de novo; zero se não estiver bloqueado. */
    public Duration tempoBloqueioRestante() {
        Duration resto = Duration.between(relogio.instant(), bloqueadoAte());
        return resto.isNegative() ? Duration.ZERO : resto;
    }

    public int tentativasRestantes() {
        return MAX_TENTATIVAS - falhas();
    }

    // ------------------------------------------------------------- interno

    private void liberar() {
        liberadoAte = relogio.instant().plus(TEMPO_LIBERADO);
    }

    private void gravarPin(String pin) throws IOException {
        if (!formatoValido(pin)) {
            throw new IllegalArgumentException("O PIN precisa ter de " + TAMANHO_MINIMO
                    + " a " + TAMANHO_MAXIMO + " dígitos.");
        }
        byte[] sal = new byte[BYTES_SAL];
        aleatorio.nextBytes(sal);
        dados.setProperty("sal", Base64.getEncoder().encodeToString(sal));
        dados.setProperty("hash", Base64.getEncoder().encodeToString(calcularHash(pin, sal)));
        salvar();
    }

    private static byte[] calcularHash(String pin, byte[] sal) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), sal, ITERACOES, BITS_HASH);
        try {
            return SecretKeyFactory.getInstance(ALGORITMO).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponível neste aparelho.", e);
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] decodificar(String chave) {
        return Base64.getDecoder().decode(dados.getProperty(chave));
    }

    private int falhas() {
        try {
            return Integer.parseInt(dados.getProperty("falhas", "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Instant bloqueadoAte() {
        try {
            return Instant.ofEpochMilli(Long.parseLong(dados.getProperty("bloqueadoAte", "0")));
        } catch (NumberFormatException e) {
            return Instant.EPOCH;
        }
    }

    private void carregar() {
        if (!arquivo.exists()) return;
        try (InputStream in = new FileInputStream(arquivo)) {
            dados.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // Arquivo corrompido: tratado como terminal sem PIN. Limpar os
            // dados do app (que apaga as digitais) é a forma de resetar.
            dados.clear();
        }
        if (dados.getProperty("hash") == null || dados.getProperty("sal") == null) {
            dados.remove("hash");
            dados.remove("sal");
        }
    }

    /** Grava num temporário e troca, para não deixar o arquivo pela metade. */
    private void salvar() throws IOException {
        File temporario = new File(arquivo.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temporario)) {
            dados.store(out, null);
        }
        if (!temporario.renameTo(arquivo)) {
            throw new IOException("Não foi possível gravar " + arquivo.getName());
        }
    }
}
