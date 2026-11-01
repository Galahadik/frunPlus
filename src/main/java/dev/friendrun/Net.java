package dev.friendrun;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Сетевые мелочи: локальный адрес устройства и определение Android. */
public final class Net {
    private Net() {}

    public static boolean isAndroid() {
        return System.getProperty("java.runtime.name", "").toLowerCase().contains("android");
    }

    /** Локальный IPv4 (192.168.x.x и т.п.) или null. */
    public static String localIPv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Совпадают ли первые три числа адресов (одна домашняя сеть). */
    public static boolean sameSubnet(String ipA, String ipB) {
        if (ipA == null || ipB == null) return false;
        String[] a = ipA.split("\\.");
        String[] b = ipB.split("\\.");
        if (a.length != 4 || b.length != 4) return false;
        return a[0].equals(b[0]) && a[1].equals(b[1]) && a[2].equals(b[2]);
    }
}
