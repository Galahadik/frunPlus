package dev.friendrun;

import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Всё, что рисует. Если API рисования опять поменяется — правьте только здесь. */
public final class Gfx {
    private static Method itemMethod;
    private static boolean itemSearched;

    private static Method fillM, scissorOn, scissorOff;
    private static boolean lookedUp;

    private static Method blitM;
    private static Object pipeline;
    private static boolean blitSearched, blitOk = true;

    private Gfx() {}

    /** Рисует PNG-текстуру целиком в квадрат size x size. Возвращает false, если не получилось. */
    public static boolean texture(GuiGraphicsExtractor g, net.minecraft.resources.Identifier id, int x, int y, int size) {
        if (!blitOk || id == null) return false;
        if (!blitSearched) {
            blitSearched = true;
            for (String cn : new String[] {"net.minecraft.client.renderer.RenderPipelines", "com.mojang.blaze3d.pipeline.RenderPipelines"}) {
                try { pipeline = Class.forName(cn).getField("GUI_TEXTURED").get(null); break; } catch (Throwable ignored) {}
            }
            for (Method m : GuiGraphicsExtractor.class.getMethods()) {
                if (!m.getName().equals("blit") || m.getParameterCount() != 10) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t[1] == net.minecraft.resources.Identifier.class && t[2] == int.class && t[3] == int.class
                    && t[4] == float.class && t[5] == float.class && t[6] == int.class && t[7] == int.class
                    && t[8] == int.class && t[9] == int.class && pipeline != null && t[0].isInstance(pipeline)) {
                    blitM = m; break;
                }
            }
            if (blitM == null) FriendRunClient.LOG.warn("FriendRun: подходящий blit() не найден, иконки в меню отключены");
        }
        if (blitM == null) { blitOk = false; return false; }
        try {
            blitM.invoke(g, pipeline, id, x, y, 0f, 0f, size, size, size, size);
            return true;
        } catch (ReflectiveOperationException e) {
            blitOk = false;
            return false;
        }
    }

    /**
     * Legacy fallback intentionally disabled: a raw item/block PNG is not an item model
     * and produces misleading flat icons. Until Minecraft can create an ItemStack, draw no icon.
     */
    public static boolean itemTexture(GuiGraphicsExtractor g, net.minecraft.world.item.Item item, int x, int y, int size) {
        return false;
    }

    private static void lookup() {
        if (lookedUp) return;
        lookedUp = true;
        try { fillM = GuiGraphicsExtractor.class.getMethod("fill", int.class, int.class, int.class, int.class, int.class); } catch (NoSuchMethodException ignored) {}
        try {
            scissorOn = GuiGraphicsExtractor.class.getMethod("enableScissor", int.class, int.class, int.class, int.class);
            scissorOff = GuiGraphicsExtractor.class.getMethod("disableScissor");
        } catch (NoSuchMethodException ignored) {}
    }

    /** Размер экрана в масштабированных пикселях (ищем по именам). */
    public static int guiWidth(GuiGraphicsExtractor g) { return dim(g, "guiWidth", "getGuiScaledWidth", 320); }
    public static int guiHeight(GuiGraphicsExtractor g) { return dim(g, "guiHeight", "getGuiScaledHeight", 240); }

    private static int dim(GuiGraphicsExtractor g, String gName, String wName, int def) {
        try {
            Object r = GuiGraphicsExtractor.class.getMethod(gName).invoke(g);
            if (r instanceof Integer i) return i;
        } catch (ReflectiveOperationException ignored) {}
        try {
            Object win = Minecraft.getInstance().getWindow();
            Object r = win.getClass().getMethod(wName).invoke(win);
            if (r instanceof Integer i) return i;
        } catch (ReflectiveOperationException ignored) {}
        return def;
    }

    /** Закрашенный прямоугольник. */
    public static void rect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        lookup();
        if (fillM == null) return;
        try { fillM.invoke(g, x1, y1, x2, y2, color); } catch (ReflectiveOperationException ignored) {}
    }

    /** Включает обрезку. Возвращает false, если обрезка недоступна. */
    public static boolean pushClip(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2) {
        lookup();
        if (scissorOn == null) return false;
        try { scissorOn.invoke(g, x1, y1, x2, y2); return true; } catch (ReflectiveOperationException e) { return false; }
    }

    public static void popClip(GuiGraphicsExtractor g) {
        if (scissorOff == null) return;
        try { scissorOff.invoke(g); } catch (ReflectiveOperationException ignored) {}
    }

    /** Текст по центру, обрезанный по ширине. */
    public static void fitted(GuiGraphicsExtractor g, String s, int cx, int y, int maxW, int color) {
        Font f = Minecraft.getInstance().font;
        String t = s;
        while (t.length() > 1 && f.width(t) > maxW) t = t.substring(0, t.length() - 1);
        if (!t.equals(s)) t = t.substring(0, Math.max(1, t.length() - 1)) + "…";
        g.text(f, t, cx - f.width(t) / 2, y, color, true);
    }

    public static void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(Minecraft.getInstance().font, s, x, y, color, true);
    }

    public static void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        Font f = Minecraft.getInstance().font;
        g.text(f, s, cx - f.width(s) / 2, y, color, true);
    }

    /** Рисует предмет 16x16. Метод называется item(...) или renderItem(...) — ищем по имени. */
    public static void item(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (!itemSearched) {
            itemSearched = true;
            for (String n : new String[] {"item", "renderItem", "fakeItem"}) {
                try {
                    itemMethod = GuiGraphicsExtractor.class.getMethod(n, ItemStack.class, int.class, int.class);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (itemMethod == null) FriendRunClient.LOG.warn("GuiGraphicsExtractor: метод рисования предмета не найден");
        }
        if (itemMethod == null) return;
        try { itemMethod.invoke(g, stack, x, y); } catch (ReflectiveOperationException e) { itemMethod = null; }
    }
}
