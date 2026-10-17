package br.unifor.cct.ui;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
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

import java.util.Collections;

import br.unifor.cct.R;
import br.unifor.cct.model.Projetor;

/**
 * Tela institucional de leitura de QR Code para identificação de projetores (CCT - Unifor).
 * Realiza leitura em tempo real via câmera do aparelho, valida conformidade com o padrão
 * de patrimônio institucional ("PAT-001" conforme Projetor.codigoValido) e provê interface
 * responsiva com estados visuais claros para permissão, carregamento, sucesso e código inválido.
 */
public class LerQrCodeActivity extends Activity {

    private static final int REQUEST_CAMERA_PERMISSION = 2001;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ler_qr_code);

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

        btnUsarReserva.setOnClickListener(v -> avancarParaReserva(tvCodigoLido.getText().toString()));
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
                    runOnUiThread(() -> processarResultadoLeitura(texto));
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

    private void processarResultadoLeitura(String conteudo) {
        long agora = SystemClock.elapsedRealtime();
        if (conteudo.equals(ultimoCodigoLido) && (agora - ultimoScanTimestamp < DEBOUNCE_SCAN_MS)) {
            return;
        }
        ultimoCodigoLido = conteudo;
        isScanning = false;

        boolean valido = Projetor.codigoValido(conteudo);

        layoutResultado.setVisibility(View.VISIBLE);
        tvCodigoLido.setText(conteudo);

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
                        processarResultadoLeitura(texto);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
