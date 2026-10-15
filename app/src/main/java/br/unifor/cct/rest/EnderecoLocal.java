package br.unifor.cct.rest;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** Descobre o IPv4 do celular na rede local, para mostrar a URL da API. */
public final class EnderecoLocal {

    private EnderecoLocal() {
    }

    /** @return o IPv4 da Wi-Fi (ou de outra interface local), ou null se não houver rede. */
    public static String ipv4() {
        String qualquer = null;
        try {
            for (NetworkInterface rede : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!rede.isUp() || rede.isLoopback()) continue;
                for (InetAddress endereco : Collections.list(rede.getInetAddresses())) {
                    if (!(endereco instanceof Inet4Address) || !endereco.isSiteLocalAddress()) continue;
                    if (rede.getName().startsWith("wlan")) return endereco.getHostAddress();
                    if (qualquer == null) qualquer = endereco.getHostAddress();
                }
            }
        } catch (SocketException | NullPointerException e) {
            return qualquer;
        }
        return qualquer;
    }
}
