package dev.friendrun;

/** Анимация ролла усложнения: названия быстро сменяются, замедляются и останавливаются на результате. */
public final class ChaosRoll {
    public static final long DURATION_MS = 3500;
    private static final int STEPS = 26;

    private static boolean animating;
    private static long startNanos;
    private static int finalIdx, startPos, lastStep = -1;
    private static int[] ring = new int[0];   // по каким усложнениям крутится лента (только включённые)

    private ChaosRoll() {}

    public static boolean running() { return animating; }

    public static void setFinal(int idx) { finalIdx = idx; animating = false; }

    public static void start(int idx, long elapsedMs) {
        finalIdx = idx;
        ring = Chaos.enabledIndices(Settings.effOffChaos());
        int pos = indexIn(ring, idx);
        if (pos < 0) {   // выпавшее усложнение выключено (настройки поменялись) — добавляем его в ленту
            ring = java.util.Arrays.copyOf(ring, ring.length + 1);
            ring[ring.length - 1] = idx;
            pos = ring.length - 1;
        }
        startPos = Math.floorMod(pos - STEPS, ring.length);   // чтобы на последнем шаге выпал именно idx
        animating = true;
        lastStep = -1;
        startNanos = System.nanoTime() - elapsedMs * 1_000_000L;
    }

    private static int indexIn(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    /** Вызывать каждый кадр. Возвращает индекс усложнения, который сейчас показывать. */
    public static int update() {
        if (!animating) return finalIdx;
        double p = (System.nanoTime() - startNanos) / 1_000_000.0 / DURATION_MS;
        if (p >= 1) {
            animating = false;
            Snd.play(Snd.CHAOS_DROP, 1.0f, 1.0f);
            return finalIdx;
        }
        double e = 1 - Math.pow(1 - Math.max(0, p), 3);
        int step = (int) Math.floor(e * STEPS);
        if (step != lastStep) {
            lastStep = step;
            Snd.play(Snd.CHAOS_TICK, 0.8f + step * 0.03f, 0.6f);
        }
        return ring[Math.floorMod(startPos + step, ring.length)];
    }
}
