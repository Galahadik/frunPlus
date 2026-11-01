package dev.friendrun;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;

public final class E4mc {
    private static final Pattern P = Pattern.compile("([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.e4mc\\.link)");
    private static final Set<String> KNOWN = new HashSet<>();

    private E4mc() {}

    /** Вызывается из миксина/события на каждое сообщение чата. */
    public static void scan(String text) {
        if (text == null) return;
        if (text.contains("error occurred in e4mc") || text.contains("Hosting will fail") || text.contains("no compatible QUIC"))
            FriendRunClient.onE4mcError();
        Matcher m = P.matcher(text);
        if (m.find() && !KNOWN.contains(m.group(1))) FriendRunClient.onDomain(m.group(1));
    }

    /** Запоминает домены, уже лежащие в истории чата (старые не считаются). */
    public static void snapshot() {
        for (String t : history()) {
            Matcher m = P.matcher(t);
            while (m.find()) KNOWN.add(m.group(1));
        }
    }

    /** Ищет домен в истории чата (на случай, если сообщение прошло мимо миксина). */
    public static void pollChat() {
        for (String t : history()) scan(t);
    }

    /** Тексты сообщений из истории чата: ищем список, элементы которого имеют content(). */
    private static java.util.List<String> history() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Object chat = Mc.chat();
            if (chat == null) return out;
            for (Class<?> c = chat.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!List.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(chat);
                    if (!(v instanceof List<?> l)) continue;
                    for (Object msg : l) {
                        String t = textOf(msg);
                        if (t != null) out.add(t);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String textOf(Object msg) {
        if (msg == null) return null;
        if (msg instanceof Component c) return c.getString();
        try {
            Method m = msg.getClass().getMethod("content");
            Object v = m.invoke(msg);
            if (v instanceof Component c) return c.getString();
        } catch (ReflectiveOperationException ignored) {}
        return null;
    }
}
