package dev.friendrun;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Доступ к экрану и чату. В 26.2+ они переехали из Minecraft в Minecraft.gui (и gui.hud),
 * поэтому всё ищется по именам — работает и на старой, и на новой раскладке.
 */
public final class Mc {
    private Mc() {}

    public static void setScreen(Screen s) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Object gui = field(mc, "gui");
            if (gui != null) {
                Method m = method(gui.getClass(), "setScreen", Screen.class);
                if (m != null) { m.invoke(gui, s); return; }
            }
            for (String n : new String[] {"setScreen", "setScreenAndShow"}) {
                Method m = method(Minecraft.class, n, Screen.class);
                if (m != null) { m.invoke(mc, s); return; }
            }
            FriendRunClient.LOG.error("FriendRun: не найден метод setScreen");
        } catch (Throwable t) {
            FriendRunClient.LOG.error("FriendRun: setScreen failed", t);
        }
    }

    public static Screen currentScreen() {
        Minecraft mc = Minecraft.getInstance();
        Object gui = field(mc, "gui");
        Object v = gui != null ? field(gui, "screen") : null;
        if (v instanceof Screen s) return s;
        v = field(mc, "screen");
        return v instanceof Screen s ? s : null;
    }

    /** Объект чата (ChatComponent) или null. */
    public static Object chat() {
        Minecraft mc = Minecraft.getInstance();
        try {
            Object gui = field(mc, "gui");
            if (gui == null) return null;
            Method g = method(gui.getClass(), "getChat");
            Object chat = g != null ? g.invoke(gui) : null;
            if (chat == null) {
                Object hud = field(gui, "hud");
                if (hud != null) {
                    Method hg = method(hud.getClass(), "getChat");
                    if (hg != null) chat = hg.invoke(hud);
                }
            }
            return chat;
        } catch (Throwable t) { return null; }
    }

    public static void chatLine(Component c) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Object gui = field(mc, "gui");
            Object chat = null;
            if (gui != null) {
                Method g = method(gui.getClass(), "getChat");
                if (g != null) chat = g.invoke(gui);
                if (chat == null) {
                    Object hud = field(gui, "hud");
                    if (hud != null) {
                        Method hg = method(hud.getClass(), "getChat");
                        if (hg != null) chat = hg.invoke(hud);
                    }
                }
            }
            if (chat != null) {
                Method add = method(chat.getClass(), "addMessage", Component.class);
                if (add != null) { add.invoke(chat, c); return; }
            }
            FriendRunClient.LOG.info("[FriendRun] " + c.getString());
        } catch (Throwable t) {
            FriendRunClient.LOG.warn("chatLine failed", t);
        }
    }

    // ---------- рефлексия ----------
    private static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException e) { return null; }
        }
        return null;
    }

    private static Method method(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != params.length) continue;
                Class<?>[] pt = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < pt.length; i++) if (!pt[i].isAssignableFrom(params[i])) { ok = false; break; }
                if (ok) { m.setAccessible(true); return m; }
            }
        }
        return null;
    }
}
