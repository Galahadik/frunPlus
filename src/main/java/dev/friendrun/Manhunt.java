package dev.friendrun;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/**
 * Режим «Манхант»: беглецы выживают, охотники их убивают. Нужен общий мир (режим «мир хоста»).
 * Всё считает хост мира: смерти беглецов (scoreboard deathCount), подсказки охотникам (расстояние и направление),
 * наблюдение для погибших беглецов. Итог (кто победил) решает хост лобби по флагам dead и времени.
 */
public final class Manhunt {
    public static final long HEAD_MS = 30_000;   // фора беглецам: охотники ослеплены и стоят на месте

    private static boolean running;
    private static int tickCounter, pollCounter;
    private static final Set<String> dead = new HashSet<>();
    private static final Map<String, String> watching = new HashMap<>();

    private Manhunt() {}

    private static void reset() {
        dead.clear(); watching.clear();
        tickCounter = 0; pollCounter = 0;
    }

    public static void start(Minecraft mc, RunState s) {
        reset();
        if (mc.getSingleplayerServer() == null) { running = false; return; }   // у друзей считает хост мира
        running = true;

        Chaos.run("scoreboard objectives remove frk_d");
        Chaos.run("scoreboard objectives add frk_d deathCount");
        Chaos.run("scoreboard players add @a frk_d 0");

        Chaos.run("time set day");
        Chaos.run("weather clear");

        // фора: охотники ослеплены, не могут двигаться и не получают урон
        for (RunState.PlayerInfo p : s.players) {
            if (s.runners.contains(p.name())) continue;
            Chaos.run("effect give " + p.name() + " minecraft:slowness 30 255 true");
            Chaos.run("effect give " + p.name() + " minecraft:blindness 30 0 true");
            Chaos.run("effect give " + p.name() + " minecraft:resistance 30 4 true");
        }
    }

    public static void tick(Minecraft mc, RunState s) {
        if (!running || mc.getSingleplayerServer() == null) return;
        if (++tickCounter % 40 != 0) return;   // раз в 2 секунды
        pollCounter++;
        pollDeaths(s);
        track(mc, s);
    }

    // ---------- смерти беглецов ----------
    private static void pollDeaths(RunState s) {
        for (String r : new ArrayList<>(s.runners)) {
            if (dead.contains(r)) {
                Chaos.run("gamemode spectator " + r);
                if (pollCounter % 3 == 0) watch(r, s);
                continue;
            }
            CompletableFuture<Integer> f = Cmd.query("scoreboard players get " + r + " frk_d");
            f.thenAccept(deaths -> Minecraft.getInstance().execute(() -> {
                if (!running || dead.contains(r) || deaths == null || deaths <= 0) return;
                dead.add(r);
                long el = s.elapsedMs();
                FriendRunClient.chat(r + " пойман! Беглец выбыл.");
                FriendRunClient.reportPlayer(r, 0, el, true);
                Chaos.run("gamemode spectator " + r);
                watch(r, s);
            }));
        }
    }

    /** Погибший беглец наблюдает за живым беглецом, а если их нет — за охотником. */
    private static void watch(String spectator, RunState s) {
        String target = null;
        for (String r : s.runners) if (!dead.contains(r) && !r.equals(spectator)) { target = r; break; }
        if (target == null) for (RunState.PlayerInfo p : s.players) if (!s.runners.contains(p.name())) { target = p.name(); break; }
        if (target == null) return;
        watching.put(spectator, target);
        Chaos.run("spectate " + target + " " + spectator);
    }

    // ---------- подсказки: расстояние и направление ----------
    private static void track(Minecraft mc, RunState s) {
        Cmd.onServer(Manhunt::snapshot).thenAccept(snap -> mc.execute(() -> {
            if (snap == null || !running) return;
            for (Map.Entry<String, double[]> h : snap.entrySet()) {
                String hunter = h.getKey();
                if (s.runners.contains(hunter)) continue;
                String best = null; double bestD = Double.MAX_VALUE;
                for (String r : s.runners) {
                    double[] rp = snap.get(r);
                    if (rp == null || dead.contains(r)) continue;
                    double d = Math.hypot(rp[0] - h.getValue()[0], rp[2] - h.getValue()[2]);
                    if (d < bestD) { bestD = d; best = r; }
                }
                if (best == null) continue;
                double[] hp = h.getValue(), rp = snap.get(best);
                String arrow = arrow(hp, rp);
                Chaos.run("title " + hunter + " actionbar \"Беглец " + best + ": " + (int) bestD + " м " + arrow + "\"");
            }
            for (String r : s.runners) {
                double[] rp = snap.get(r);
                if (rp == null || dead.contains(r)) continue;
                double nearest = Double.MAX_VALUE;
                for (Map.Entry<String, double[]> h : snap.entrySet()) {
                    if (s.runners.contains(h.getKey())) continue;
                    nearest = Math.min(nearest, Math.hypot(h.getValue()[0] - rp[0], h.getValue()[2] - rp[2]));
                }
                if (nearest < 40) Chaos.run("title " + r + " actionbar \"ОХОТНИК РЯДОМ: " + (int) nearest + " м!\"");
            }
        }));
    }

    /** Стрелка к цели относительно того, куда смотрит игрок (yaw в Minecraft: 0 — на юг, по часовой стрелке). */
    private static String arrow(double[] from, double[] to) {
        double dx = to[0] - from[0], dz = to[2] - from[2];
        double yawTo = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = yawTo - from[3];
        while (rel > 180) rel -= 360;
        while (rel < -180) rel += 360;
        int sector = Math.floorMod((int) Math.round(rel / 45.0), 8);
        return new String[] {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"}[sector];
    }

    /** Позиции и поворот всех игроков мира: ник -> {x, y, z, yaw}. Выполняется на потоке сервера. */
    private static Map<String, double[]> snapshot() {
        Map<String, double[]> out = new HashMap<>();
        try {
            Object srv = Minecraft.getInstance().getSingleplayerServer();
            Object plist = invoke(srv, "getPlayerList");
            Object list = invoke(plist, "getPlayers");
            if (!(list instanceof List<?> players)) return out;
            for (Object p : players) {
                Object nameC = invoke(p, "getName");
                Object name = nameC == null ? null : invoke(nameC, "getString");
                if (!(name instanceof String n)) continue;
                double x = ((Number) invoke(p, "getX")).doubleValue();
                double y = ((Number) invoke(p, "getY")).doubleValue();
                double z = ((Number) invoke(p, "getZ")).doubleValue();
                double yaw = ((Number) invoke(p, "getYRot")).doubleValue();
                out.put(n, new double[] {x, y, z, yaw});
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static Object invoke(Object o, String name) throws Exception {
        if (o == null) return null;
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) { m.setAccessible(true); return m.invoke(o); }
        }
        return null;
    }

    // ---------- конец ----------
    public static void cleanup() {
        if (running) {
            running = false;
            Chaos.run("scoreboard objectives remove frk_d");
        }
        reset();
    }
}
