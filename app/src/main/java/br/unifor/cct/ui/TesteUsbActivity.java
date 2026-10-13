package br.unifor.cct.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import br.unifor.cct.R;

/**
 * Tela isolada para testar se o celular enxerga o leitor Nitgen Hamster DX
 * pelo adaptador OTG, antes de existir o SDK de captura.
 *
 * Testa só a camada USB do Android: listar o aparelho, pedir permissão,
 * abrir a conexão e reservar a interface. Não envia nenhum comando ao
 * leitor: o protocolo é da Nitgen (classe "Vendor Specific") e a captura
 * da digital depende do SDK deles.
 *
 * A tela que abre sozinha ao conectar o leitor agora é a
 * TesteDigitalActivity; esta continua acessível pelo menu da MainActivity.
 */
public class TesteUsbActivity extends Activity {

    private static final int VENDOR_NITGEN = 0x0A86;
    private static final int PRODUTO_HAMSTER_DX = 0x0100;
    private static final String ACAO_PERMISSAO = "br.unifor.cct.PERMISSAO_USB";
    private static final DateTimeFormatter FORMATO_REGISTRO = DateTimeFormatter.ofPattern("HH:mm:ss");

    private UsbManager usbManager;
    private UsbDevice leitor;

    private TextView tvLeitor;
    private Button btnPermissao;
    private Button btnConectar;
    private TextView tvRegistro;

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String acao = intent.getAction();
            if (ACAO_PERMISSAO.equals(acao)) {
                boolean concedida = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                registrar(concedida ? "Permissão concedida." : "Permissão negada.");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(acao)) {
                registrar("Um aparelho USB foi desconectado.");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(acao)) {
                registrar("Um aparelho USB foi conectado.");
            }
            procurar();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teste_usb);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        TextView tvSuporte = findViewById(R.id.tvSuporte);
        tvLeitor = findViewById(R.id.tvLeitor);
        btnPermissao = findViewById(R.id.btnPermissao);
        btnConectar = findViewById(R.id.btnConectar);
        tvRegistro = findViewById(R.id.tvRegistro);

        boolean temHost = getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        tvSuporte.setText(temHost
                ? "Este celular declara suporte a USB Host (OTG)."
                : "Este celular NÃO declara suporte a USB Host (OTG). O leitor pode não funcionar.");

        findViewById(R.id.btnAtualizar).setOnClickListener(v -> procurar());
        btnPermissao.setOnClickListener(v -> pedirPermissao());
        btnConectar.setOnClickListener(v -> testarConexao());

        IntentFilter filtro = new IntentFilter(ACAO_PERMISSAO);
        filtro.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filtro.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, receptor, filtro, ContextCompat.RECEIVER_NOT_EXPORTED);

        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            registrar("Tela aberta pelo Android ao conectar o leitor.");
        }
        procurar();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            registrar("Leitor conectado.");
            procurar();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receptor);
    }

    /** Lista tudo que está no USB e guarda o Hamster DX, se estiver lá. */
    private void procurar() {
        leitor = null;
        if (usbManager.getDeviceList().isEmpty()) {
            registrar("Nenhum aparelho USB encontrado. Confira o adaptador OTG.");
        }
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            registrar("Encontrado: " + descrever(d));
            if (d.getVendorId() == VENDOR_NITGEN && d.getProductId() == PRODUTO_HAMSTER_DX) {
                leitor = d;
            }
        }

        if (leitor == null) {
            tvLeitor.setText("Hamster DX: não encontrado");
            btnPermissao.setEnabled(false);
            btnConectar.setEnabled(false);
            return;
        }
        boolean permitido = usbManager.hasPermission(leitor);
        tvLeitor.setText("Hamster DX: encontrado" + (permitido ? ", com permissão" : ", sem permissão"));
        btnPermissao.setEnabled(!permitido);
        btnConectar.setEnabled(permitido);
    }

    private void pedirPermissao() {
        if (leitor == null) return;
        // Explícita (setPackage) e mutável: o sistema precisa preencher o
        // extra EXTRA_PERMISSION_GRANTED, e o Android 14 recusa intents
        // implícitas mutáveis.
        Intent intent = new Intent(ACAO_PERMISSAO).setPackage(getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, intent, flags);
        registrar("Pedindo permissão...");
        usbManager.requestPermission(leitor, pi);
    }

    /**
     * Abre a conexão e reserva a interface 0, sem enviar nada. Se isso der
     * certo, o Android consegue falar com o leitor e o resto depende do SDK.
     */
    private void testarConexao() {
        if (leitor == null || !usbManager.hasPermission(leitor)) {
            registrar("Erro: leitor ausente ou sem permissão.");
            return;
        }
        UsbDeviceConnection conexao = usbManager.openDevice(leitor);
        if (conexao == null) {
            registrar("Erro: o Android não conseguiu abrir a conexão.");
            return;
        }
        try {
            registrar("Conexão aberta. Serial: " + textoOuTraco(conexao.getSerial()));
            if (leitor.getInterfaceCount() == 0) {
                registrar("Erro: o leitor não informou nenhuma interface.");
                return;
            }
            UsbInterface interfaceUsb = leitor.getInterface(0);
            if (!conexao.claimInterface(interfaceUsb, true)) {
                registrar("Erro: não foi possível reservar a interface 0.");
                return;
            }
            registrar("Interface 0 reservada. Endpoints:\n" + descreverEndpoints(interfaceUsb)
                    + "\nTeste USB concluído: falta o SDK da Nitgen para capturar a digital.");
            conexao.releaseInterface(interfaceUsb);
        } finally {
            conexao.close();
        }
    }

    private static String descrever(UsbDevice d) {
        return String.format(Locale.ROOT, "%04x:%04x %s / %s, %d interface(s)",
                d.getVendorId(), d.getProductId(),
                textoOuTraco(d.getManufacturerName()), textoOuTraco(d.getProductName()),
                d.getInterfaceCount());
    }

    private static String descreverEndpoints(UsbInterface i) {
        StringBuilder sb = new StringBuilder();
        for (int e = 0; e < i.getEndpointCount(); e++) {
            UsbEndpoint ep = i.getEndpoint(e);
            sb.append(String.format(Locale.ROOT, "  0x%02x %s %s, %d bytes\n",
                    ep.getAddress(),
                    ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN " : "OUT",
                    ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "bulk" : "tipo " + ep.getType(),
                    ep.getMaxPacketSize()));
        }
        return sb.toString().replaceAll("\\s+$", "");
    }

    private static String textoOuTraco(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    /** Linha nova no topo do registro, com o horário. */
    private void registrar(String texto) {
        String hora = LocalTime.now().format(FORMATO_REGISTRO);
        CharSequence atual = tvRegistro.getText();
        tvRegistro.setText(hora + "  " + texto + (atual.length() == 0 ? "" : "\n\n" + atual));
    }
}
