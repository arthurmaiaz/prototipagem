package br.unifor.cct.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Procura um QR Code numa imagem já carregada (pixels ARGB). Sem Android, para
 * ser testado na JVM; o LeitorQrImagem cuida de abrir e girar a foto.
 */
public final class DecodificadorQr {

    private DecodificadorQr() {
    }

    /** @return o texto do QR Code, ou null se a imagem não tiver nenhum. */
    public static String decodificar(int[] pixels, int largura, int altura) {
        Map<DecodeHintType, Object> dicas = new EnumMap<>(DecodeHintType.class);
        // Só QR Code: o app não lê os formatos 1D (EAN-13, Code 128).
        dicas.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        // Foto de galeria não tem pressa: vale gastar mais tempo para acertar.
        dicas.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        MultiFormatReader leitor = new MultiFormatReader();
        leitor.setHints(dicas);
        try {
            Result resultado = leitor.decodeWithState(new BinaryBitmap(
                    new HybridBinarizer(new RGBLuminanceSource(largura, altura, pixels))));
            return resultado.getText();
        } catch (NotFoundException e) {
            return null;
        } finally {
            leitor.reset();
        }
    }
}
