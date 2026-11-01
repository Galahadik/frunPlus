package dev.friendrun;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * Локализация строк интерфейса. Ключ берётся из assets/friendrun/lang/*.json;
 * если ключа нет (или язык не загружен) — используется русский текст по умолчанию.
 * Аргументы подставляются через %s / %d, как в обычных lang-файлах.
 */
public final class Tr {
    private Tr() {}

    public static String t(String key, String fallback, Object... args) {
        try {
            if (Language.getInstance().has(key)) return Component.translatable(key, args).getString();
        } catch (RuntimeException ignored) {}
        try { return args.length == 0 ? fallback : String.format(fallback, args); }
        catch (RuntimeException e) { return fallback; }
    }
}
