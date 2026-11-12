package br.unifor.cct.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * Gera o QR Code de um patrimônio (portado do GeradorQrCode da PoC de QR Code).
 *
 * Devolve os pixels em ARGB, sem depender do Android: a tela monta o Bitmap com
 * Bitmap.createBitmap(pixels, lado, lado, ARGB_8888), e os testes conferem a
 * ida e volta (gerar e ler de novo) na JVM.
 */
public final class GeradorQrCode {

    /** Resolução do QR gerado. Maior que a ImageView para não borrar ao ampliar. */
    public static final int TAMANHO_PADRAO = 600;

    private static final int PRETO = 0xFF000000;
    private static final int BRANCO = 0xFFFFFFFF;

    private GeradorQrCode() {
    }

    /**
     * @param conteudo texto a codificar (ex.: "PAT-001")
     * @param tamanho  lado da imagem, em pixels
     * @return os pixels ARGB, linha a linha (tamanho × tamanho)
     * @throws WriterException se o conteúdo não couber num QR Code
     */
    public static int[] gerarPixels(String conteudo, int tamanho) throws WriterException {
        if (tamanho <= 0) throw new IllegalArgumentException("tamanho inválido: " + tamanho);
        if (conteudo == null || conteudo.isEmpty()) throw new IllegalArgumentException("conteúdo vazio");

        Map<EncodeHintType, Object> dicas = new EnumMap<>(EncodeHintType.class);
        // H (recupera ~30%): etiqueta de projetor vive com poeira, risco e reflexo.
        dicas.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        // Margem 1 em vez das 4 do ZXing: a etiqueta impressa já tem margem própria.
        dicas.put(EncodeHintType.MARGIN, 1);
        dicas.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix matriz = new QRCodeWriter().encode(conteudo, BarcodeFormat.QR_CODE, tamanho, tamanho, dicas);
        int[] pixels = new int[tamanho * tamanho];
        for (int y = 0; y < tamanho; y++) {
            int linha = y * tamanho;
            for (int x = 0; x < tamanho; x++) {
                pixels[linha + x] = matriz.get(x, y) ? PRETO : BRANCO;
            }
        }
        return pixels;
    }

    public static int[] gerarPixels(String conteudo) throws WriterException {
        return gerarPixels(conteudo, TAMANHO_PADRAO);
    }
}
