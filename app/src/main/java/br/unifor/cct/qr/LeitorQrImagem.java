package br.unifor.cct.qr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

/**
 * Lê um QR Code de uma imagem da galeria (portado do LeitorQrImagem da PoC de QR Code).
 *
 * Foto de celular costuma vir gravada deitada (metadado EXIF) e com resolução
 * muito acima do necessário. Por isso a imagem é reduzida antes de decodificar e
 * girada conforme o EXIF; sem isso, boa parte das fotos falharia só pela orientação.
 *
 * Usa o ExifInterface do próprio Android (aceita InputStream desde a API 24; o
 * minSdk do app é 26), então não precisa da dependência androidx.exifinterface.
 *
 * Faz leitura de disco e decodificação: chame fora da thread principal.
 */
public final class LeitorQrImagem {

    /**
     * Lado maior máximo usado na decodificação. Uma foto de 4000×3000 em ARGB
     * ocupa 48 MB e derruba aparelhos simples; 1600 px ainda lê o QR com folga.
     */
    static final int DIMENSAO_MAXIMA = 1600;

    private LeitorQrImagem() {
    }

    /**
     * @return o texto do QR Code, ou null se a imagem abriu mas não tem QR Code
     * @throws IOException se a imagem não puder ser aberta ou o formato não for suportado
     */
    public static String ler(Context contexto, Uri uri) throws IOException {
        int graus = lerRotacaoExif(contexto, uri);
        Bitmap bitmap = carregarReduzido(contexto, uri);
        Bitmap orientado = girar(bitmap, graus);
        try {
            int largura = orientado.getWidth();
            int altura = orientado.getHeight();
            int[] pixels = new int[largura * altura];
            orientado.getPixels(pixels, 0, largura, 0, 0, largura, altura);
            return DecodificadorQr.decodificar(pixels, largura, altura);
        } finally {
            if (orientado != bitmap) orientado.recycle();
            bitmap.recycle();
        }
    }

    /** Maior potência de 2 que deixa o lado maior dentro de DIMENSAO_MAXIMA. */
    static int calcularAmostra(int largura, int altura) {
        int maiorLado = Math.max(largura, altura);
        int amostra = 1;
        while (maiorLado / amostra > DIMENSAO_MAXIMA) amostra *= 2;
        return amostra;
    }

    /** Sem EXIF (ou com EXIF ilegível) não é erro: assume 0°. */
    private static int lerRotacaoExif(Context contexto, Uri uri) {
        try (InputStream entrada = contexto.getContentResolver().openInputStream(uri)) {
            if (entrada == null) return 0;
            int orientacao = new ExifInterface(entrada).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientacao) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** Duas passadas: a primeira só mede, a segunda carrega já reduzida. */
    private static Bitmap carregarReduzido(Context contexto, Uri uri) throws IOException {
        BitmapFactory.Options medir = new BitmapFactory.Options();
        medir.inJustDecodeBounds = true;
        try (InputStream entrada = contexto.getContentResolver().openInputStream(uri)) {
            if (entrada == null) throw new IOException("Não foi possível abrir a imagem.");
            BitmapFactory.decodeStream(entrada, null, medir);
        }
        if (medir.outWidth <= 0 || medir.outHeight <= 0) {
            throw new IOException("Formato de imagem não suportado.");
        }

        BitmapFactory.Options carregar = new BitmapFactory.Options();
        carregar.inSampleSize = calcularAmostra(medir.outWidth, medir.outHeight);
        carregar.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream entrada = contexto.getContentResolver().openInputStream(uri)) {
            if (entrada == null) throw new IOException("Não foi possível abrir a imagem.");
            Bitmap bitmap = BitmapFactory.decodeStream(entrada, null, carregar);
            if (bitmap == null) throw new IOException("Não foi possível decodificar a imagem.");
            return bitmap;
        }
    }

    private static Bitmap girar(Bitmap origem, int graus) {
        if (graus == 0) return origem;
        Matrix matriz = new Matrix();
        matriz.postRotate(graus);
        return Bitmap.createBitmap(origem, 0, 0, origem.getWidth(), origem.getHeight(), matriz, true);
    }
}
