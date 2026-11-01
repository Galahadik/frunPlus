package dev.friendrun;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

/**
 * Рейтинг для режима «Столбы». Правила и раунды ведёт сама карта; FriendRun узнаёт итог по её сообщению в чате:
 *  - один игрок:   «<ник> победил(а)!»
 *  - команда:      «<Цвет> команда победила! • <ники>»
 *  - никто:        «Никто не победил!»
 * Обрабатывает только хост лобби (у него общий мир). Победители — названные в сообщении игроки лобби,
 * проигравшие — все остальные игроки лобби (карта тоже считает сыгранной игру каждому, кто был в мире).
 */
public final class Pillars {
    private static final String SOLO = "победил(а)!";
    private static final String TEAM = "команда победила!";
    private static final String NOBODY = "Никто не победил!";
    private static long lastNanos;

    private Pillars() {}

    /** Вызывается из миксина на каждое сообщение чата. */
    public static void onChat(String text) {
        if (text == null) return;
        RunState s = FriendRunClient.STATE;
        if (s.gameMode != Game.PILLARS || !s.launched || !s.amHost || !FriendRunClient.inLobby()) return;
        String t = text.strip();
        if (t.startsWith("<")) return;   // реплика игрока в чате, а не сообщение карты

        String part;
        int team = t.indexOf(TEAM), solo = t.indexOf(SOLO);
        if (t.contains(NOBODY)) part = "";
        else if (team >= 0) part = t.substring(team + TEAM.length());
        else if (solo >= 0) part = t.substring(0, solo);
        else return;

        long now = System.nanoTime();
        if (lastNanos != 0 && now - lastNanos < 10_000_000_000L) return;   // одно и то же объявление — один раз
        lastNanos = now;

        final String names = part;
        Minecraft.getInstance().execute(() -> {
            List<String> winners = new ArrayList<>();
            for (RunState.PlayerInfo p : new ArrayList<>(FriendRunClient.STATE.players)) {
                if (Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(p.name()) + "(?![A-Za-z0-9_])").matcher(names).find())
                    winners.add(p.name());
            }
            if (winners.isEmpty() && !names.isEmpty()) {
                FriendRunClient.LOG.warn("FRun: «Столбы» — победитель не найден среди игроков лобби: {}", names.strip());
                return;
            }
            FriendRunClient.pillarsResult(winners);
        });
    }
}
