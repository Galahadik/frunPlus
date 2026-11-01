package dev.friendrun;

import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Общий стиль интерфейса FRun: тёмные панели, золотые акценты. */
public final class Ui {
    public static final int BG = 0xF0101A2B, EDGE = 0xFF05080F, LINE = 0xFF22324A, SECTION = 0xFF0D1522;
    public static final int ROW = 0xFF121D2F, ROW_HOVER = 0xFF1B2D47, ROW_OFF = 0xFF0F1826;
    public static final int TEXT = 0xFFE8EEF8, MUTED = 0xFF8C9AB0;
    public static final int GOLD = 0xFFFFC82E, GOLD_D = 0xFF8A650A;
    public static final int GREEN = 0xFF2ED158, GREEN_BG = 0xFF0E3320, GREEN_LINE = 0xFF1F7A3E;
    public static final int RED = 0xFFFF5555, RED_BG = 0xFF2A1015, RED_LINE = 0xFF7A2230;
    public static final int ORANGE = 0xFFFFAA33;

    public enum Kind { NORMAL, GOLD, GREEN }

    private Ui() {}

    public record R(int x, int y, int w, int h) {
        public boolean in(int mx, int my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
        public int cy() { return y + h / 2; }
    }

    public static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int line) {
        Gfx.rect(g, x, y, x + w, y + h, line);
        Gfx.rect(g, x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    /** Общий спокойный фон с тонкой сеткой — одинаковый на всех экранах FriendRun. */
    public static void backdrop(GuiGraphicsExtractor g, int width, int height) {
        Gfx.rect(g, 0, 0, width, height, 0xF5070D16);
        // Ненавязчивая сетка добавляет глубину, но не спорит с текстом.
        for (int x = 24; x < width; x += 48) Gfx.rect(g, x, 0, x + 1, height, 0x142A4565);
        for (int y = 24; y < height; y += 48) Gfx.rect(g, 0, y, width, y + 1, 0x142A4565);
        Gfx.rect(g, 0, 0, width, 2, 0xFF17263A);
        Gfx.rect(g, 0, 0, Math.min(width, 96), 2, GOLD);
    }

    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        Gfx.rect(g, x - 2, y - 2, x + w + 2, y + h + 2, EDGE);
        frame(g, x, y, w, h, BG, LINE);
        // Внутренняя верхняя грань и тонкая золотая метка задают визуальную иерархию.
        Gfx.rect(g, x + 2, y + 1, x + w - 2, y + 2, 0xFF31435B);
        Gfx.rect(g, x + 2, y + 1, Math.min(x + w - 2, x + 30), y + 2, GOLD_D);
    }

    public static int w(String s) { return Minecraft.getInstance().font.width(s); }

    public static void bold(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        Gfx.text(g, s, x, y, color);
        Gfx.text(g, s, x + 1, y, color);
    }

    public static void right(GuiGraphicsExtractor g, String s, int xRight, int y, int color) {
        Gfx.text(g, s, xRight - w(s), y, color);
    }

    /** Фон кнопки (рисуется поверх обычной кнопки Minecraft). */
    public static void button(GuiGraphicsExtractor g, R r, boolean hover, boolean active, Kind kind) {
        button(g, r, hover ? 1f : 0f, active, kind);
    }

    /** Кнопка с плавной подсветкой: hover = 0..1 (см. {@link Anim}). */
    public static void button(GuiGraphicsExtractor g, R r, float hover, boolean active, Kind kind) {
        int base, hov, line;
        switch (kind) {
            case GOLD -> { base = 0xFFD9A31A; hov = 0xFFF2B91E; line = active ? 0xFFFFE08A : GOLD_D; if (!active) { base = hov = 0xFF4A3A10; } }
            case GREEN -> { base = GREEN_BG; hov = 0xFF14472B; line = active ? GREEN_LINE : LINE; if (!active) { base = hov = ROW_OFF; } }
            default -> { base = ROW; hov = ROW_HOVER; line = LINE; if (!active) { base = hov = ROW_OFF; } }
        }
        float h = active ? clamp01(hover) : 0f;
        frame(g, r.x(), r.y(), r.w(), r.h(), mix(base, hov, h), line);
        if (h > 0.02f) {
            // Подсветка края, заметная и без свечения.
            int top = kind == Kind.GOLD ? 0xFFFFE7A1 : 0xFF4A6688;
            int edge = kind == Kind.GREEN ? GREEN : (kind == Kind.GOLD ? GOLD : 0xFF4A6688);
            Gfx.rect(g, r.x() + 1, r.y() + 1, r.x() + r.w() - 1, r.y() + 2, mix(base, top, h));
            Gfx.rect(g, r.x() + 1, r.y() + 1, r.x() + 3, r.y() + r.h() - 1, mix(base, edge, h));
        } else if (active && kind == Kind.GREEN) {
            Gfx.rect(g, r.x() + 1, r.y() + 2, r.x() + 2, r.y() + r.h() - 2, GREEN_LINE);
        }
    }

    // ---------- анимации ----------

    public static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }

    /** Линейное смешивание двух ARGB-цветов. */
    public static int mix(int a, int b, float t) {
        t = clamp01(t);
        int aa = (int) (((a >>> 24) & 255) + (((b >>> 24) & 255) - ((a >>> 24) & 255)) * t);
        int ar = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int ag = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int ab = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }

    /** Мягкое замедление к концу (0..1 -> 0..1). */
    public static float easeOut(float t) { t = 1f - clamp01(t); return 1f - t * t * t; }

    /** Набор плавно меняющихся значений 0..1 (наведение, включение). Вызывайте tick() раз в кадр. */
    public static final class Anim {
        private final java.util.HashMap<Object, Float> vals = new java.util.HashMap<>();
        private long last = System.nanoTime();
        private float dt;

        public void tick() {
            long now = System.nanoTime();
            dt = Math.min(0.1f, (now - last) / 1_000_000_000f);
            last = now;
        }

        /** Плавно движет значение по ключу к 1 (on) или к 0 (off) за ~0.15 с. */
        public float to(Object key, boolean on) {
            float v = vals.getOrDefault(key, on ? 1f : 0f);
            float step = dt / 0.15f;
            v = on ? Math.min(1f, v + step) : Math.max(0f, v - step);
            vals.put(key, v);
            return v;
        }

        /** То же, но стартует с 0 (для «появления»). */
        public float appear(Object key, boolean on) {
            vals.putIfAbsent(key, 0f);
            return to(key, on);
        }
    }

    /** Полупрозрачная «шторка» цвета панели: alpha 0..1 — насколько скрыт прямоугольник (для появления). */
    public static void veil(GuiGraphicsExtractor g, int x, int y, int w, int h, float hidden) {
        int a = (int) (clamp01(hidden) * 255);
        if (a <= 2) return;
        Gfx.rect(g, x, y, x + w, y + h, (a << 24) | 0x101A2B);
    }

    /** Обрезает строку по ширине, добавляя «…». */
    public static String trunc(String s, int maxW) {
        if (w(s) <= maxW) return s;
        while (s.length() > 1 && w(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** Вертикальная полоса прокрутки; pos — индекс первого видимого, total — всего, vis — видимых. */
    public static void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int pos, int total, int vis) {
        if (total <= vis) return;
        Gfx.rect(g, x, y, x + 3, y + h, 0xFF0A111C);
        int th = Math.max(10, h * vis / total);
        int ty = y + (h - th) * pos / Math.max(1, total - vis);
        Gfx.rect(g, x, ty, x + 3, ty + th, 0xFF4A6688);
    }

    /** Небольшая «таблетка» статуса: точка + текст. Возвращает ширину. */
    public static int pill(GuiGraphicsExtractor g, int xRight, int y, String text, int color) {
        int tw = w(text);
        Icons.draw(g, Icons.DOT, xRight - tw - 9, y + 1, 1, color);
        Gfx.text(g, text, xRight - tw, y, color);
        return tw + 9;
    }

    /** Цвет из ника (для аватаров). */
    private static int colorOf(String name, int base) {
        int h = name.hashCode();
        int r = base + ((h >> 16) & 0x4F), gr = base + ((h >> 8) & 0x4F), b = base + (h & 0x4F);
        return 0xFF000000 | (Math.min(r, 255) << 16) | (Math.min(gr, 255) << 8) | Math.min(b, 255);
    }

    /** Квадрат с первой буквой (вместо скина). */
    public static void head(GuiGraphicsExtractor g, int x, int y, int size, String name) {
        int c = colorOf(name, 70);
        Gfx.rect(g, x, y, x + size, y + size, 0xFF000000);
        Gfx.rect(g, x + 1, y + 1, x + size - 1, y + size - 1, c);
        String l = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        Gfx.text(g, l, x + (size - w(l)) / 2, y + (size - 8) / 2, 0xFFFFFFFF);
    }

    /** Аватар лобби: две тональные половины и буква. */
    public static void avatar(GuiGraphicsExtractor g, int x, int y, int size, String name) {
        int top = colorOf(name, 90), bot = colorOf(name + "x", 40);
        Gfx.rect(g, x, y, x + size, y + size, 0xFF000000);
        Gfx.rect(g, x + 1, y + 1, x + size - 1, y + size / 2, top);
        Gfx.rect(g, x + 1, y + size / 2, x + size - 1, y + size - 1, bot);
        String l = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        Gfx.text(g, l, x + (size - w(l)) / 2, y + (size - 8) / 2, 0xFFFFFFFF);
    }

    /** Убирает рамку у поля ввода (если метод есть). */
    public static void noBorder(Object editBox) {
        try { editBox.getClass().getMethod("setBordered", boolean.class).invoke(editBox, false); } catch (ReflectiveOperationException ignored) {}
    }

    /** Делает стандартную кнопку прозрачной (если метод есть); сверху всё равно рисуется наш вид. */
    public static void invisible(Object widget) {
        for (Method m : widget.getClass().getMethods()) {
            if (m.getName().equals("setAlpha") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == float.class) {
                try { m.invoke(widget, 0f); } catch (ReflectiveOperationException ignored) {}
                return;
            }
        }
    }
}
