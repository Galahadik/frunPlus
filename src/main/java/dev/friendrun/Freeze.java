package dev.friendrun;

/**
 * Заморозка игроков до старта раунда: приключенческий режим (нельзя ломать/ставить блоки), замедление 255
 * (нельзя ходить), прыжок 0 и сопротивление урону. Выполняется командами на сервере мира, поэтому
 * в мире хоста замораживаются все игроки мира.
 */
public final class Freeze {
    private static boolean frozen;

    private Freeze() {}

    /** Идемпотентно: вызывается повторно, чтобы заморозить зашедших позже и вернуть эффекты после смерти. */
    public static void apply() {
        frozen = true;
        Chaos.run("gamemode adventure @a");
        Chaos.run("effect give @a minecraft:slowness infinite 255 true");
        Chaos.run("effect give @a minecraft:resistance infinite 4 true");
        Chaos.run(Chaos.attr("jump_strength", "0"));
    }

    public static void release() {
        if (!frozen) return;
        frozen = false;
        Chaos.run("effect clear @a");
        Chaos.run(Chaos.attr("jump_strength", "0.42"));
        Chaos.run("gamemode survival @a");
    }
}
