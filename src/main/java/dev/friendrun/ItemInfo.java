package dev.friendrun;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Безопасные название и иконка предмета. В главном меню у предметов ещё не привязаны компоненты
 * (new ItemStack падает с "Components not bound yet"), поэтому имя берём из языкового файла,
 * а иконку создаём только если получилось.
 */
public final class ItemInfo {
    private ItemInfo() {}

    private static final java.util.Map<Item, String> NAMES = new java.util.HashMap<>();
    private static final java.util.Map<Item, ItemStack> STACKS = new java.util.HashMap<>();
    private static final java.util.Map<Item, Long> RETRY_AT = new java.util.HashMap<>();

    public static String name(Item item) {
        String c = NAMES.get(item);
        if (c != null) return c;
        c = resolveName(item);
        NAMES.put(item, c);
        return c;
    }

    private static String resolveName(Item item) {
        var id = BuiltInRegistries.ITEM.getKey(item);
        String path = id.getPath().replace('/', '.');
        for (String kind : new String[] {"item", "block"}) {
            String key = kind + "." + id.getNamespace() + "." + path;
            try {
                if (Language.getInstance().has(key)) return Component.translatable(key).getString();
            } catch (RuntimeException ignored) {}
        }
        return pretty(id.getPath());
    }

    /** null, если стек создать нельзя (например, в главном меню). */
    public static ItemStack stack(Item item) {
        ItemStack cached = STACKS.get(item);
        if (cached != null) return cached;
        long now = System.currentTimeMillis();
        Long retry = RETRY_AT.get(item);
        if (retry != null && now < retry) return null;
        try {
            ItemStack st = new ItemStack(item);
            STACKS.put(item, st);
            RETRY_AT.remove(item);
            return st;
        } catch (RuntimeException e) {
            // Не блокируем создание стеков для остальных предметов: один ранний вызов
            // до привязки компонентов не должен переводить весь интерфейс на плоские PNG.
            RETRY_AT.put(item, now + 2000);
            return null;
        }
    }

    private static String pretty(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.replace('/', ' ').split("[_ ]")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
