package br.unifor.cct.nfc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.unifor.cct.model.Projetor;

/**
 * Leitura e gravação de tags NFC com o código do patrimônio do projetor
 * gravado em NDEF (record de texto, ex.: "PAT-001"). Só códigos no padrão
 * de Projetor.codigoValido() são aceitos, tanto na leitura quanto na gravação.
 *
 * Uso na Activity (IdentificacaoActivity / ReservaActivity):
 *
 * <pre>
 * private NfcService nfc;
 *
 * onCreate:    nfc = new NfcService(this);
 *              nfc.setLeituraCallback(resultado -> { ... });
 * onResume:    nfc.habilitarLeitura();
 * onPause:     nfc.desabilitarLeitura();
 * onNewIntent: nfc.processarIntent(intent);
 * onDestroy:   nfc.encerrar();
 * </pre>
 *
 * A Activity precisa ter android:launchMode="singleTop" no manifesto.
 * Todos os callbacks são entregues na thread principal.
 */
public class NfcService {

    private final Activity activity;
    private final NfcAdapter adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private NfcCallback leituraCallback;

    // Modo de gravação: ativo enquanto codigoParaGravar != null
    private String codigoParaGravar;
    private boolean travarAposGravar;
    private GravacaoCallback gravacaoCallback;

    public NfcService(Activity activity) {
        this.activity = activity;
        this.adapter = NfcAdapter.getDefaultAdapter(activity);
    }

    /** O aparelho tem hardware NFC? */
    public boolean isDisponivel() {
        return adapter != null;
    }

    /** O NFC está ligado nas configurações do aparelho? */
    public boolean isAtivado() {
        return adapter != null && adapter.isEnabled();
    }

    public void setLeituraCallback(NfcCallback callback) {
        this.leituraCallback = callback;
    }

    /** Chamar em onResume(): o app passa a receber as tags direto, sem seletor de apps. */
    public void habilitarLeitura() {
        if (adapter == null || !adapter.isEnabled()) return;
        Intent intent = new Intent(activity, activity.getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(activity, 0, intent, flags);
        // filtros nulos: recebe qualquer tag, inclusive tags novas ainda sem NDEF
        adapter.enableForegroundDispatch(activity, pi, null, null);
    }

    /** Chamar em onPause(). */
    public void desabilitarLeitura() {
        if (adapter != null) adapter.disableForegroundDispatch(activity);
    }

    /** Chamar em onDestroy(). */
    public void encerrar() {
        executor.shutdown();
    }

    /**
     * Ativa o modo de cadastro: a próxima tag encostada será gravada com o código.
     * Se o código não seguir o padrão (ex.: "PAT-001"), o callback recebe o erro
     * na hora e o modo de cadastro não é ativado.
     *
     * @param travar se true, a tag vira somente leitura depois de gravada (irreversível)
     */
    public void iniciarGravacao(String codigo, boolean travar, GravacaoCallback callback) {
        String limpo = codigo == null ? null : codigo.trim().toUpperCase(Locale.ROOT);
        if (!Projetor.codigoValido(limpo)) {
            limparModoGravacao();
            if (callback != null) {
                callback.onGravado(false, "Código inválido. Use o padrão PAT-001.");
            }
            return;
        }
        this.codigoParaGravar = limpo;
        this.travarAposGravar = travar;
        this.gravacaoCallback = callback;
    }

    /** O próximo toque de tag será uma gravação, e não uma leitura? */
    public boolean isGravando() {
        return codigoParaGravar != null;
    }

    public void cancelarGravacao() {
        limparModoGravacao();
    }

    /** Chamar em onNewIntent(). Decide sozinho entre ler e gravar. */
    public void processarIntent(Intent intent) {
        if (intent == null) return;
        String acao = intent.getAction();
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(acao)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(acao)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(acao)) {
            return;
        }

        Tag tag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag.class)
                : tagLegado(intent);

        if (tag == null) {
            if (isGravando()) {
                if (gravacaoCallback != null) {
                    gravacaoCallback.onGravado(false, "Não foi possível acessar a tag. Tente novamente.");
                }
            } else {
                entregarLeitura(false, null, "Não foi possível ler a tag. Tente novamente.");
            }
            return;
        }

        if (isGravando()) {
            gravar(tag);
        } else {
            ler(tag);
        }
    }

    @SuppressWarnings("deprecation")
    private Tag tagLegado(Intent intent) {
        return intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    }

    // ---------------------------------------------------------------- leitura

    private void ler(Tag tag) {
        String tagId = hex(tag.getId());
        List<String> tecnologias = new ArrayList<>();
        for (String tech : tag.getTechList()) {
            tecnologias.add(tech.substring(tech.lastIndexOf('.') + 1));
        }
        List<String> textos = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            entregarLeitura(false, null, "Tag sem formato NDEF. Ela precisa ser cadastrada antes.",
                    textos, urls, tagId, tecnologias);
            return;
        }

        NdefMessage mensagem = ndef.getCachedNdefMessage();
        if (mensagem == null || mensagem.getRecords().length == 0) {
            entregarLeitura(false, null, "Tag vazia. Ela precisa ser cadastrada antes.",
                    textos, urls, tagId, tecnologias);
            return;
        }

        // Junta tudo o que veio na tag; o código é o primeiro texto no padrão PAT-001.
        String codigo = null;
        String textoInvalido = null;
        for (NdefRecord record : mensagem.getRecords()) {
            String texto = extrairTexto(record);
            if (texto != null && !texto.isEmpty()) {
                textos.add(texto);
                if (codigo == null && Projetor.codigoValido(texto)) {
                    codigo = texto;
                } else if (textoInvalido == null) {
                    textoInvalido = texto;
                }
                continue;
            }
            Uri uri = record.toUri();
            if (uri != null) urls.add(uri.toString());
        }
        if (codigo != null) {
            entregarLeitura(true, codigo, "Projetor " + codigo + " identificado.",
                    textos, urls, tagId, tecnologias);
            return;
        }
        entregarLeitura(false, null, textoInvalido == null
                ? "Esta tag não contém um código de projetor."
                : "Código inválido na tag (\"" + textoInvalido + "\"). Cadastre a tag de novo.",
                textos, urls, tagId, tecnologias);
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02X", b));
        return sb.toString();
    }

    /** Extrai o texto de um record NDEF do tipo Text; devolve null se for de outro tipo. */
    private String extrairTexto(NdefRecord record) {
        if (record.getTnf() != NdefRecord.TNF_WELL_KNOWN
                || !Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) {
            return null;
        }
        byte[] payload = record.getPayload();
        if (payload == null || payload.length == 0) return null;

        // 1º byte: bit 7 = codificação (0 = UTF-8, 1 = UTF-16); bits 0-5 = tamanho do idioma
        int tamanhoIdioma = payload[0] & 0x3F;
        if (payload.length <= 1 + tamanhoIdioma) return null;
        Charset charset = (payload[0] & 0x80) == 0
                ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16;
        return new String(payload, 1 + tamanhoIdioma,
                payload.length - 1 - tamanhoIdioma, charset).trim();
    }

    private void entregarLeitura(boolean sucesso, String codigo, String mensagem) {
        entregarLeitura(sucesso, codigo, mensagem, null, null, null, null);
    }

    private void entregarLeitura(boolean sucesso, String codigo, String mensagem,
                                 List<String> textos, List<String> urls,
                                 String tagId, List<String> tecnologias) {
        if (leituraCallback != null) {
            leituraCallback.onResultado(new ResultadoNfc(
                    sucesso, codigo, mensagem, textos, urls, tagId, tecnologias));
        }
    }

    // --------------------------------------------------------------- gravação

    private void gravar(Tag tag) {
        final String codigo = codigoParaGravar;
        final boolean travar = travarAposGravar;
        final GravacaoCallback callback = gravacaoCallback;

        // A escrita na tag é feita fora da thread principal
        executor.execute(() -> {
            String erro = null;
            boolean travou = !travar;
            try {
                NdefMessage mensagem = new NdefMessage(
                        NdefRecord.createTextRecord("pt", codigo));
                Ndef ndef = Ndef.get(tag);

                if (ndef != null) {
                    ndef.connect();
                    try {
                        if (!ndef.isWritable()) {
                            erro = "Esta tag é somente leitura.";
                        } else if (ndef.getMaxSize() < mensagem.toByteArray().length) {
                            erro = "O código não cabe nesta tag.";
                        } else {
                            ndef.writeNdefMessage(mensagem);
                            if (travar) travou = ndef.makeReadOnly();
                        }
                    } finally {
                        ndef.close();
                    }
                } else {
                    NdefFormatable formatavel = NdefFormatable.get(tag);
                    if (formatavel == null) {
                        erro = "Esta tag não aceita NDEF.";
                    } else {
                        formatavel.connect();
                        try {
                            if (travar) {
                                formatavel.formatReadOnly(mensagem);
                                travou = true;
                            } else {
                                formatavel.format(mensagem);
                            }
                        } finally {
                            formatavel.close();
                        }
                    }
                }
            } catch (IOException | FormatException e) {
                erro = "Falha ao gravar. Mantenha a tag encostada e tente de novo.";
            }

            final String erroFinal = erro;
            final boolean travouFinal = travou;
            main.post(() -> {
                if (erroFinal == null) {
                    limparModoGravacao(); // sucesso: sai do modo de cadastro
                    if (callback != null) {
                        callback.onGravado(true, travouFinal
                                ? "Tag gravada com " + codigo + (travar ? " e travada." : ".")
                                : "Tag gravada com " + codigo + ", mas não foi possível travá-la.");
                    }
                } else if (callback != null) {
                    // em caso de erro o modo continua ativo, para tentar de novo
                    callback.onGravado(false, erroFinal);
                }
            });
        });
    }

    private void limparModoGravacao() {
        codigoParaGravar = null;
        travarAposGravar = false;
        gravacaoCallback = null;
    }
}
