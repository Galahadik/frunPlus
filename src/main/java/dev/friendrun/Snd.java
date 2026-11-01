package dev.friendrun;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Интерфейсные звуки. Сигнатуры SimpleSoundInstance.forUI меняются между версиями,
 * поэтому всё ищется по именам. names — список запасных вариантов SoundEvents.* (берётся первый найденный).
 */
public final class Snd {
    public static final String[] TICK = {"NOTE_BLOCK_HAT", "UI_BUTTON_CLICK"};
    public static final String[] CHAOS_TICK = {"NOTE_BLOCK_BIT", "NOTE_BLOCK_HAT", "UI_BUTTON_CLICK"};
    public static final String[] ITEM_DROP = {"UI_TOAST_CHALLENGE_COMPLETE", "PLAYER_LEVELUP", "EXPERIENCE_ORB_PICKUP"};
    public static final String[] CHAOS_DROP = {"ENCHANTMENT_TABLE_USE", "PLAYER_LEVELUP", "EXPERIENCE_ORB_PICKUP"};

    private static final Map<String, Object> CACHE = new HashMap<>();
    private static boolean warned;

    private Snd() {}

    public static void play(String[] names, float pitch, float volume) {
        if (!Settings.sounds) return;
        try {
            Object ev = event(names);
            if (ev == null) return;
            Class<?> sis = Class.forName("net.minecraft.client.resources.sounds.SimpleSoundInstance");
            Object inst = null;
            Method two = null;
            for (Method m : sis.getMethods()) {
                if (!m.getName().equals("forUI") || !Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 0 || !t[0].isInstance(ev)) continue;
                if (t.length == 3 && t[1] == float.class && t[2] == float.class) { inst = m.invoke(null, ev, pitch, volume); break; }
                if (t.length == 2 && t[1] == float.class) two = m;
            }
            if (inst == null && two != null) inst = two.invoke(null, ev, pitch);
            if (inst == null) { warnOnce("forUI не найден"); return; }

            Object sm = Minecraft.getInstance().getSoundManager();
            for (Method m : sm.getClass().getMethods()) {
                if (m.getName().equals("play") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(inst)) {
                    m.invoke(sm, inst);
                    return;
                }
            }
            warnOnce("SoundManager.play не найден");
        } catch (Throwable t) {
            warnOnce("ошибка звука: " + t);
        }
    }

    private static Object event(String[] names) throws Exception {
        String key = String.join("|", names);
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Class<?> se = Class.forName("net.minecraft.sounds.SoundEvents");
        Object found = null;
        for (String n : names) {
            try {
                Field f = se.getField(n);
                found = f.get(null);
                if (found != null) break;
            } catch (NoSuchFieldException ignored) {}
        }
        CACHE.put(key, found);
        if (found == null) warnOnce("звук не найден: " + key);
        return found;
    }

    private static void warnOnce(String msg) {
        if (warned) return;
        warned = true;
        FriendRunClient.LOG.warn("FriendRun: " + msg);
    }
}
