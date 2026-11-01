package dev.friendrun;

import java.lang.reflect.Method;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Подсчёт предметов в инвентаре (ищем методы по именам — безопасно к изменениям API). */
public final class Inv {
    private static Method size, get;
    private static boolean looked;

    private Inv() {}

    /** Количество каждого из предметов за один проход по инвентарю. */
    public static int[] counts(Player p, java.util.List<Item> items) {
        int[] out = new int[items.size()];
        try {
            Object inv = p.getInventory();
            if (!looked) {
                looked = true;
                size = inv.getClass().getMethod("getContainerSize");
                get = inv.getClass().getMethod("getItem", int.class);
            }
            if (size == null || get == null) throw new NoSuchMethodException();
            int n = (Integer) size.invoke(inv);
            for (int i = 0; i < n; i++) {
                Object st = get.invoke(inv, i);
                if (!(st instanceof ItemStack s) || s.isEmpty()) continue;
                for (int k = 0; k < out.length; k++) if (s.is(items.get(k))) out[k] += s.getCount();
            }
        } catch (ReflectiveOperationException e) {
            for (int k = 0; k < out.length; k++) out[k] = count(p, items.get(k));
        }
        return out;
    }

    public static int count(Player p, Item item) {
        try {
            Object inv = p.getInventory();
            if (!looked) {
                looked = true;
                size = inv.getClass().getMethod("getContainerSize");
                get = inv.getClass().getMethod("getItem", int.class);
            }
            if (size == null || get == null) throw new NoSuchMethodException();
            int n = (Integer) size.invoke(inv);
            int total = 0;
            for (int i = 0; i < n; i++) {
                Object st = get.invoke(inv, i);
                if (st instanceof ItemStack s && !s.isEmpty() && s.is(item)) total += s.getCount();
            }
            return total;
        } catch (ReflectiveOperationException e) {
            return p.getInventory().contains(st -> st.is(item)) ? 1 : 0;
        }
    }
}
