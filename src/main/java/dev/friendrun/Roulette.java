package dev.friendrun;

import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Рулетка предмета как кейс в КС: лента карточек прокручивается с замедлением и останавливается на выпавшем предмете.
 * Лента строится из seed, поэтому у всех игроков выглядит одинаково. Со звуками: щелчок на каждой карточке и сигнал выпадения.
 */
public final class Roulette {
    public static final long DURATION_MS = 5000;
    private static final int N = 50, WIN = 42, CARD = 62, PITCH = 66, H = 44;
    private static final int[] STRIPE = {0xFF4C9AFF, 0xFFB24CFF, 0xFFFF4C8A, 0xFFFFA64C, 0xFF4CFFA0, 0xFF9AA5B5};

    private static Item[] strip;
    private static int jitter;
    private static boolean animating;
    private static long startNanos;
    private static int lastIdx = -1;

    private Roulette() {}

    public static boolean running() { return animating; }

    public static double remainingSec() {
        if (!animating) return 0;
        double el = (System.nanoTime() - startNanos) / 1e9;
        return Math.max(0, DURATION_MS / 1000.0 - el);
    }

    public static void setFinal(long seed) { build(seed); animating = false; }

    public static void start(long seed, long elapsedMs) {
        build(seed);
        animating = true;
        lastIdx = -1;
        startNanos = System.nanoTime() - elapsedMs * 1_000_000L;
    }

    private static void build(long seed) {
        Random r = new Random(seed * 31 + 7);
        strip = new Item[N];
        for (int i = 0; i < N; i++) strip[i] = ItemPicker.random(r);
        strip[WIN] = ItemPicker.pick(seed);
        jitter = r.nextInt(CARD / 2) - CARD / 4;
    }

    /** Рисует ленту; y — верх карточек. */
    public static void draw(GuiGraphicsExtractor g, int cx, int y, int maxWidth) {
        if (strip == null) return;
        int boxW = Math.min(340, maxWidth);
        int x1 = cx - boxW / 2, x2 = cx + boxW / 2;

        double target = WIN * PITCH + jitter;
        double scroll = target;
        if (animating) {
            double p = (System.nanoTime() - startNanos) / 1_000_000.0 / DURATION_MS;
            if (p >= 1) {
                animating = false;
                Snd.play(Snd.ITEM_DROP, 1.0f, 1.0f);   // звук выпадения
            } else {
                double e = 1 - Math.pow(1 - Math.max(0, p), 3);   // плавное замедление
                scroll = e * target;
                int idx = (int) Math.floor((scroll + PITCH / 2.0) / PITCH);   // карточка под указателем
                if (idx != lastIdx) {
                    lastIdx = idx;
                    Snd.play(Snd.TICK, 1.1f, 0.7f);   // щелчок на каждой карточке
                }
            }
        }

        Ui.frame(g, x1 - 2, y - 3, boxW + 4, H + 6, 0xFF0B121E, Ui.LINE);
        boolean clipped = Gfx.pushClip(g, x1, y - 2, x2, y + H + 2);

        for (int i = 0; i < N; i++) {
            int left = (int) Math.round(cx - CARD / 2.0 + i * PITCH - scroll);
            int right = left + CARD;
            if (right < x1 || left > x2) continue;
            if (!clipped && (left < x1 || right > x2)) continue;   // без обрезки рисуем только целые карточки

            Item it = strip[i];
            String name = ItemInfo.name(it);
            boolean sel = Math.abs(left + CARD / 2.0 - cx) < PITCH / 2.0;   // карточка под указателем
            boolean win = !animating && i == WIN;
            boolean hi = sel || win;

            Ui.frame(g, left, y, CARD, H, hi ? 0xFF222B3D : 0xFF141F30, hi ? Ui.GOLD : 0xFF2A3B55);
            Gfx.rect(g, left + 1, y + H - 3, right - 1, y + H - 1, STRIPE[Math.abs(name.hashCode()) % STRIPE.length]);

            int textColor = hi ? Ui.GOLD : 0xFFD0D8E8;
            if (MenuItemIcon.draw(g, it, left + CARD / 2 - 8, y + 4, 16)) {
                Gfx.fitted(g, name, left + CARD / 2, y + 26, CARD - 4, textColor);
            } else {
                Gfx.fitted(g, name, left + CARD / 2, y + H / 2 - 4, CARD - 4, textColor);
            }
        }
        if (clipped) Gfx.popClip(g);

        // затемнение по краям и стрелки
        Gfx.rect(g, x1, y - 2, x1 + 12, y + H + 2, 0xDD0B121E);
        Gfx.rect(g, x2 - 12, y - 2, x2, y + H + 2, 0xDD0B121E);
        Icons.draw(g, Icons.CHEV_L, x1 + 3, y + H / 2 - 3, 1, Ui.GOLD);
        Icons.draw(g, Icons.CHEV_R, x2 - 7, y + H / 2 - 3, 1, Ui.GOLD);

        // указатель
        Icons.draw(g, Icons.TRI_DOWN, cx - 3, y - 9, 1, Ui.GOLD);
    }
}
