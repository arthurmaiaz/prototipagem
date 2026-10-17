package br.unifor.cct.rest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

import br.unifor.cct.BuildConfig;
import br.unifor.cct.R;
import br.unifor.cct.ui.TesteNfcActivity;

/**
 * Serviço em primeiro plano que mantém a API REST do NFC no ar
 * (http://&lt;ip do celular&gt;:8787), mesmo com o app em segundo plano.
 *
 * O serviço atende os pedidos, mas quem lê a tag é a TesteNfcActivity
 * (o Android só entrega tags para a Activity em primeiro plano): sem a tela
 * aberta, POST /v1/nfc/read responde 503. A notificação abre a tela.
 */
public class ServicoApiNfc extends Service {

    public static final int PORTA = 8787;

    private static final String TAG = "ServicoApiNfc";
    private static final String CANAL = "api_nfc";
    private static final int ID_NOTIFICACAO = 8787;

    /** Mantida entre reinícios do serviço, enquanto o processo viver. */
    private static final Auditoria AUDITORIA = new Auditoria();

    private static volatile boolean rodando;
    private static volatile String ultimoErro;

    private ServidorHttp servidor;

    public static void iniciar(Context context) {
        context.startForegroundService(new Intent(context, ServicoApiNfc.class));
    }

    public static void parar(Context context) {
        context.stopService(new Intent(context, ServicoApiNfc.class));
    }

    public static boolean isRodando() {
        return rodando;
    }

    /** Motivo da última falha ao abrir a porta, ou null. */
    public static String getUltimoErro() {
        return ultimoErro;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        criarCanal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForeground tem que vir logo, mesmo que a porta falhe depois.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_NOTIFICACAO, notificacao(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_NOTIFICACAO, notificacao());
        }
        if (servidor == null) {
            ServidorHttp novo = new ServidorHttp(PORTA, new NfcRestApi(PonteNfc.padrao(), AUDITORIA,
                    () -> TokenApi.obter(this), BuildConfig.VERSION_NAME));
            try {
                novo.iniciar();
                servidor = novo;
                rodando = true;
                ultimoErro = null;
                Log.d(TAG, "API no ar na porta " + PORTA);
            } catch (IOException e) {
                ultimoErro = "Não foi possível abrir a porta " + PORTA + ": " + e.getMessage();
                Log.w(TAG, ultimoErro, e);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (servidor != null) {
            servidor.parar();
            servidor = null;
        }
        rodando = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void criarCanal() {
        NotificationChannel canal = new NotificationChannel(
                CANAL, "API NFC", NotificationManager.IMPORTANCE_LOW);
        canal.setDescription("Aviso de que a API REST do NFC está no ar.");
        getSystemService(NotificationManager.class).createNotificationChannel(canal);
    }

    private Notification notificacao() {
        PendingIntent abrirTela = PendingIntent.getActivity(this, 0,
                new Intent(this, TesteNfcActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String ip = EnderecoLocal.ipv4();
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_nfc_tag)
                .setContentTitle("API NFC no ar")
                .setContentText((ip == null ? "sem rede" : "http://" + ip + ":" + PORTA)
                        + " · toque para abrir a leitura")
                .setContentIntent(abrirTela)
                .setOngoing(true)
                .build();
    }
}
