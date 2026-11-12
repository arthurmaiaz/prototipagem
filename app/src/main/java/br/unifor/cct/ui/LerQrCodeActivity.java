package br.unifor.cct.ui;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;

import br.unifor.cct.R;
import br.unifor.cct.qr.CodigoQr;
import br.unifor.cct.qr.GeradorQrCode;
import br.unifor.cct.qr.LeitorQrImagem;
import br.unifor.cct.qr.ResultadoQr;
import br.unifor.cct.rest.PonteQr;

/**
 * Tela institucional de leitura de QR Code para identificação de projetores (CCT - Unifor).
 * Realiza leitura em tempo real via câmera do aparelho, valida conformidade com o padrão
 * de patrimônio institucional ("PAT-001" conforme Projetor.codigoValido) e provê interface
 * responsiva com estados visuais claros para permissão, carregamento, sucesso e código inválido.
 *
 * Também atende a API REST (POST /v1/qr/read): enquanto esta tela está aberta,
 * cada leitura é entregue ao pedido que estiver esperando na PonteQr. Quando é
 * a própria API que abre a tela (EXTRA_PEDIDO_API), ela fecha sozinha depois de
 * entregar a leitura.
 */
public class LerQrCodeActivity extends Activity implements PonteQr.Ouvinte {

    /** true quando a tela foi aberta pelo ServicoApiNfc para atender um pedido. */
    public static final String EXTRA_PEDIDO_API = "br.unifor.cct.extra.PEDIDO_API";
    private static final long FECHAR_APOS_API_MS = 1500;

    private static final int REQUEST_CAMERA_PERMISSION = 2001;
    private static final int REQUEST_IMAGEM = 2002;
    private static final long DEBOUNCE_SCAN_MS = 2500;

    // Elementos da Interface
    private ImageView btnVoltarQr;
    private TextureView textureViewCamera;
    private FrameLayout viewfinderReticle;
    private View viewLaserScan;
    private LinearLayout layoutCarregando;
    private LinearLayout layoutPermissaoNegada;
    private LinearLayout layoutSemCamera;
    private Button btnSolicitarPermissao;
    private LinearLayout layoutResultado;
    private ImageView ivIconeResultado;
    private TextView tvTituloResultado;
    private TextView tvDescricaoResultado;
    private TextView tvCodigoLido;
    private Button btnLerNovamente;
    private Button btnUsarReserva;
    private Button btnDigitarManual;
    private Button btnLerImagem;
    private Button btnGerarQr;

    // Animação do Scanner
    private ObjectAnimator laserAnimator;

    // Gerenciamento da Câmera
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size previewSize;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    // Controle de Leitura
    private final MultiFormatReader qrReader = new MultiFormatReader();
    private boolean isScanning = true;
    private String ultimoCodigoLido = null;
    private long ultimoScanTimestamp = 0;

    // Ponte com a API REST
    private final PonteQr ponteQr = PonteQr.padrao();
    private boolean abertaPelaApi;
    /** O seletor de imagem cobre esta tela, mas o pedido da API deve continuar esperando. */
    private boolean escolhendoImagem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ler_qr_code);
        abertaPelaApi = getIntent().getBooleanExtra(EXTRA_PEDIDO_API, false);

        inicializarViews();
        configurarAnimacaoLaser();
        configurarListeners();
    }

    private void inicializarViews() {
        btnVoltarQr = findViewById(R.id.btnVoltarQr);
        textureViewCamera = findViewById(R.id.textureViewCamera);
        viewfinderReticle = findViewById(R.id.viewfinderReticle);
        viewLaserScan = findViewById(R.id.viewLaserScan);
        layoutCarregando = findViewById(R.id.layoutCarregando);
        layoutPermissaoNegada = findViewById(R.id.layoutPermissaoNegada);
        layoutSemCamera = findViewById(R.id.layoutSemCamera);
        btnSolicitarPermissao = findViewById(R.id.btnSolicitarPermissao);
        layoutResultado = findViewById(R.id.layoutResultado);
        ivIconeResultado = findViewById(R.id.ivIconeResultado);
        tvTituloResultado = findViewById(R.id.tvTituloResultado);
        tvDescricaoResultado = findViewById(R.id.tvDescricaoResultado);
        tvCodigoLido = findViewById(R.id.tvCodigoLido);
        btnLerNovamente = findViewById(R.id.btnLerNovamente);
        btnUsarReserva = findViewById(R.id.btnUsarReserva);
        btnDigitarManual = findViewById(R.id.btnDigitarManual);
        btnLerImagem = findViewById(R.id.btnLerImagem);
        btnGerarQr = findViewById(R.id.btnGerarQr);
    }

    private void configurarAnimacaoLaser() {
        if (viewLaserScan != null) {
            laserAnimator = ObjectAnimator.ofFloat(viewLaserScan, "translationY", 0f, 218f * getResources().getDisplayMetrics().density);
            laserAnimator.setDuration(1800);
            laserAnimator.setRepeatMode(ValueAnimator.REVERSE);
            laserAnimator.setRepeatCount(ValueAnimator.INFINITE);
        }
    }

    private void configurarListeners() {
        btnVoltarQr.setOnClickListener(v -> finish());

        btnSolicitarPermissao.setOnClickListener(v -> solicitarPermissaoCamera());

        btnLerNovamente.setOnClickListener(v -> reiniciarLeitura());

        btnDigitarManual.setOnClickListener(v -> exibirDialogoEntradaManual());

        btnLerImagem.setOnClickListener(v -> escolherImagem());

        btnGerarQr.setOnClickListener(v -> exibirDialogoGerarQr());

        btnUsarReserva.setOnClickListener(v -> avancarParaReserva(tvCodigoLido.getText().toString()));
    }

    /*
     * Como na TesteNfcActivity, a ponte usa onStart/onStop: o pedido de permissão
     * da câmera passa por onPause, e isso não pode cancelar o pedido da API.
     */
    @Override
    protected void onStart() {
        super.onStart();
        ponteQr.setOuvinte(this);
        ponteQr.setTelaAberta(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (escolhendoImagem) return;    // a galeria volta para esta tela
        ponteQr.setTelaAberta(false);
        ponteQr.setOuvinte(null);
    }

    @Override
    public void onPedidoIniciado() {
        // Um pedido novo com o resultado anterior ainda na tela: volta a ler.
        runOnUiThread(() -> {
            reiniciarLeitura();
            Toast.makeText(this, "Pedido do servidor: aponte a câmera para o QR Code.",
                    Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onPedidoEncerrado() {
    }

    @Override
    protected void onResume() {
        super.onResume();
        iniciarBackgroundThread();

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            exibirEstadoSemCamera();
            return;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            exibirEstadoPermissaoNegada();
            solicitarPermissaoCamera();
        } else {
            esconderEstadosDeErro();
            if (textureViewCamera.isAvailable()) {
                abrirCamera();
            } else {
                textureViewCamera.setSurfaceTextureListener(surfaceTextureListener);
            }
        }

        if (laserAnimator != null && !laserAnimator.isRunning()) {
            laserAnimator.start();
        }
    }

    @Override
    protected void onPause() {
        fecharCamera();
        pararBackgroundThread();
        if (laserAnimator != null && laserAnimator.isRunning()) {
            laserAnimator.cancel();
        }
        super.onPause();
    }

    private void solicitarPermissaoCamera() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                esconderEstadosDeErro();
                if (textureViewCamera.isAvailable()) {
                    abrirCamera();
                } else {
                    textureViewCamera.setSurfaceTextureListener(surfaceTextureListener);
                }
            } else {
                exibirEstadoPermissaoNegada();
            }
        }
    }

    // =========================================================================
    // Gerenciamento de Câmera (Camera2 API)
    // =========================================================================

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            abrirCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            if (isScanning) {
                processarFrameParaQrCode();
            }
        }
    };

    private void abrirCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            exibirEstadoSemCamera();
            return;
        }

        try {
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        previewSize = map.getOutputSizes(SurfaceTexture.class)[0];
                    }
                    break;
                }
            }

            if (cameraId == null && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (cameraId == null) {
                exibirEstadoSemCamera();
                return;
            }

            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                layoutCarregando.setVisibility(View.VISIBLE);
                manager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
            }
        } catch (CameraAccessException | SecurityException e) {
            exibirEstadoPermissaoNegada();
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            runOnUiThread(() -> layoutCarregando.setVisibility(View.GONE));
            iniciarPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            runOnUiThread(() -> {
                layoutCarregando.setVisibility(View.GONE);
                Toast.makeText(LerQrCodeActivity.this, "Falha ao inicializar o sensor de câmera.", Toast.LENGTH_SHORT).show();
            });
        }
    };

    private void iniciarPreview() {
        if (cameraDevice == null || !textureViewCamera.isAvailable()) return;

        try {
            SurfaceTexture texture = textureViewCamera.getSurfaceTexture();
            if (texture == null) return;

            if (previewSize != null) {
                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            }

            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (cameraDevice == null) return;
                    cameraCaptureSession = session;
                    try {
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        // ignore
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    runOnUiThread(() -> Toast.makeText(LerQrCodeActivity.this, "Falha na configuração de captura.", Toast.LENGTH_SHORT).show());
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            // ignore
        }
    }

    private void fecharCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void iniciarBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void pararBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // =========================================================================
    // Processamento e Decodificação de QR Code (ZXing)
    // =========================================================================

    private void processarFrameParaQrCode() {
        if (!isScanning) return;

        long agora = SystemClock.elapsedRealtime();
        if (agora - ultimoScanTimestamp < 400) {
            return;
        }
        ultimoScanTimestamp = agora;

        Bitmap bitmap = textureViewCamera.getBitmap();
        if (bitmap == null) return;

        backgroundHandler.post(() -> {
            try {
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

                RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
                BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

                Result result = qrReader.decode(binaryBitmap);
                if (result != null && result.getText() != null) {
                    String texto = result.getText().trim();
                    runOnUiThread(() -> processarResultadoLeitura(texto, ResultadoQr.FORMATO_QR));
                }
            } catch (NotFoundException e) {
                // Nenhum QR Code encontrado no frame atual: comportamento esperado
            } catch (Exception e) {
                // Outro erro de decodificação ignorado no frame individual
            } finally {
                bitmap.recycle();
                qrReader.reset();
            }
        });
    }

    private void processarResultadoLeitura(String conteudo, String formato) {
        long agora = SystemClock.elapsedRealtime();
        if (conteudo.equals(ultimoCodigoLido) && (agora - ultimoScanTimestamp < DEBOUNCE_SCAN_MS)) {
            return;
        }
        ultimoCodigoLido = conteudo;
        isScanning = false;

        ResultadoQr resultado = new ResultadoQr(conteudo, formato);
        boolean valido = resultado.isValido();

        layoutResultado.setVisibility(View.VISIBLE);
        // Válido: mostra só o patrimônio ("projetor:PAT-001" vira "PAT-001"),
        // que é o que o botão "Usar para reserva" repassa.
        tvCodigoLido.setText(valido ? resultado.getPatrimonio() : conteudo);

        if (valido) {
            ivIconeResultado.setImageResource(R.drawable.ic_badge_verified);
            ivIconeResultado.setColorFilter(getColor(R.color.unifor_status_active));
            tvTituloResultado.setText(R.string.qr_sucesso_titulo);
            tvTituloResultado.setTextColor(getColor(R.color.unifor_status_active));
            tvDescricaoResultado.setText(R.string.qr_sucesso_desc);
            btnUsarReserva.setEnabled(true);
            btnUsarReserva.setVisibility(View.VISIBLE);
            tvCodigoLido.setTextColor(getColor(R.color.unifor_primary_brand));
        } else {
            ivIconeResultado.setImageResource(R.drawable.ic_warning_outline);
            ivIconeResultado.setColorFilter(getColor(R.color.unifor_status_pending_text));
            tvTituloResultado.setText(R.string.qr_invalido_titulo);
            tvTituloResultado.setTextColor(getColor(R.color.unifor_status_pending_text));
            tvDescricaoResultado.setText(R.string.qr_invalido_desc);
            btnUsarReserva.setEnabled(false);
            btnUsarReserva.setVisibility(View.GONE);
            tvCodigoLido.setTextColor(getColor(R.color.unifor_status_pending_text));
        }

        if (ponteQr.entregar(resultado)) {
            Toast.makeText(this, "Leitura enviada ao servidor.", Toast.LENGTH_SHORT).show();
            if (abertaPelaApi) {
                tvCodigoLido.postDelayed(this::finish, FECHAR_APOS_API_MS);
            }
        }
    }

    private void reiniciarLeitura() {
        layoutResultado.setVisibility(View.GONE);
        isScanning = true;
        ultimoCodigoLido = null;
    }

    private void avancarParaReserva(String codigoProjetor) {
        Intent intent = new Intent(this, TesteReservaActivity.class);
        intent.putExtra("codigoProjetor", codigoProjetor);
        startActivity(intent);
        finish();
    }

    private void exibirDialogoEntradaManual() {
        EditText input = new EditText(this);
        input.setHint(R.string.qr_digitar_manual_hint);
        input.setText("PAT-001");
        input.setSelectAllOnFocus(true);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(pad, pad / 2, pad, pad / 2);
        input.setLayoutParams(params);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Inserir Código do Projetor")
                .setMessage("Informe o identificador de patrimônio colado no equipamento:")
                .setView(container)
                .setPositiveButton("Validar", (dialog, which) -> {
                    String texto = input.getText().toString().trim().toUpperCase();
                    if (!texto.isEmpty()) {
                        processarResultadoLeitura(texto, ResultadoQr.FORMATO_MANUAL);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // =========================================================================
    // Leitura de imagem da galeria
    // =========================================================================

    private void escolherImagem() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        escolhendoImagem = true;
        try {
            startActivityForResult(intent, REQUEST_IMAGEM);
        } catch (android.content.ActivityNotFoundException e) {
            escolhendoImagem = false;
            Toast.makeText(this, "Nenhum app de galeria encontrado.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGEM) return;
        escolhendoImagem = false;
        Uri uri = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || uri == null) return;

        isScanning = false;    // a câmera não deve atropelar a leitura da imagem
        Toast.makeText(this, "Lendo a imagem...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String texto;
            String erro = null;
            try {
                texto = LeitorQrImagem.ler(this, uri);
            } catch (IOException | RuntimeException e) {
                texto = null;
                erro = e.getMessage();
            }
            String lido = texto;
            String falha = erro;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (lido != null) {
                    ultimoCodigoLido = null;    // a mesma etiqueta pode ser lida de novo pela imagem
                    processarResultadoLeitura(lido.trim(), ResultadoQr.FORMATO_IMAGEM);
                } else {
                    isScanning = layoutResultado.getVisibility() != View.VISIBLE;
                    Toast.makeText(this, falha != null ? "Não foi possível ler a imagem: " + falha
                            : "Nenhum QR Code encontrado na imagem.", Toast.LENGTH_LONG).show();
                }
            });
        }, "LeituraQrImagem").start();
    }

    // =========================================================================
    // Geração de QR Code
    // =========================================================================

    private void exibirDialogoGerarQr() {
        EditText input = new EditText(this);
        input.setHint(R.string.qr_digitar_manual_hint);
        input.setText("PAT-001");
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("Gerar QR Code")
                .setMessage("Informe o patrimônio do projetor:")
                .setView(comMargem(input))
                .setPositiveButton("Gerar", (dialog, which) -> {
                    String patrimonio = CodigoQr.extrairPatrimonio(input.getText().toString());
                    if (patrimonio == null) {
                        Toast.makeText(this, "Código inválido. Use o padrão PAT-001.", Toast.LENGTH_LONG).show();
                    } else {
                        mostrarQrGerado(patrimonio);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void mostrarQrGerado(String patrimonio) {
        Bitmap qr;
        try {
            int lado = GeradorQrCode.TAMANHO_PADRAO;
            qr = Bitmap.createBitmap(GeradorQrCode.gerarPixels(patrimonio, lado), lado, lado,
                    Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível gerar o QR Code: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        ImageView imagem = new ImageView(this);
        imagem.setImageBitmap(qr);
        imagem.setAdjustViewBounds(true);
        imagem.setContentDescription("QR Code do projetor " + patrimonio);

        AlertDialog.Builder dialogo = new AlertDialog.Builder(this)
                .setTitle(patrimonio)
                .setView(comMargem(imagem))
                .setNegativeButton("Fechar", null);
        // Salvar sem pedir permissão de armazenamento só é possível a partir do Android 10.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dialogo.setPositiveButton("Salvar na galeria", (d, w) -> salvarNaGaleria(qr, patrimonio));
        }
        dialogo.show();
    }

    /** Salva em Imagens/CCT, para testar a leitura pela galeria ou imprimir a etiqueta. */
    private void salvarNaGaleria(Bitmap qr, String patrimonio) {
        ContentValues valores = new ContentValues();
        valores.put(MediaStore.Images.Media.DISPLAY_NAME, "qr-" + patrimonio + ".png");
        valores.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        valores.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CCT");
        Uri destino = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores);
        if (destino == null) {
            Toast.makeText(this, "Não foi possível salvar a imagem.", Toast.LENGTH_LONG).show();
            return;
        }
        try (OutputStream saida = getContentResolver().openOutputStream(destino)) {
            if (saida == null || !qr.compress(Bitmap.CompressFormat.PNG, 100, saida)) {
                throw new IOException("falha ao gravar o PNG");
            }
            Toast.makeText(this, "Salvo em Imagens/CCT/qr-" + patrimonio + ".png", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            getContentResolver().delete(destino, null, null);
            Toast.makeText(this, "Não foi possível salvar a imagem: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private View comMargem(View conteudo) {
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(pad, pad / 2, pad, pad / 2);
        conteudo.setLayoutParams(params);
        container.addView(conteudo);
        return container;
    }

    // =========================================================================
    // Estados da Interface
    // =========================================================================

    private void exibirEstadoPermissaoNegada() {
        layoutPermissaoNegada.setVisibility(View.VISIBLE);
        layoutSemCamera.setVisibility(View.GONE);
        layoutCarregando.setVisibility(View.GONE);
    }

    private void exibirEstadoSemCamera() {
        layoutSemCamera.setVisibility(View.VISIBLE);
        layoutPermissaoNegada.setVisibility(View.GONE);
        layoutCarregando.setVisibility(View.GONE);
    }

    private void esconderEstadosDeErro() {
        layoutPermissaoNegada.setVisibility(View.GONE);
        layoutSemCamera.setVisibility(View.GONE);
    }
}
