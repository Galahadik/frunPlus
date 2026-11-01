package dev.friendrun;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Отдельные личные рекорды полного одиночного Minecraft-спидрана; не связаны с MMR. */
public final class SpeedrunRecords {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private SpeedrunRecords() {}

    public record Row(String name, long ms) {}

    public static List<Row> fetch(String dbUrl) throws IOException {
        String root = dbUrl.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + "/friendrun/_speedrun_records.json"))
            .timeout(Duration.ofSeconds(8)).GET().build();
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) throw new IOException("Firebase отклонил чтение таблицы");
            if (res.statusCode() / 100 != 2) throw new IOException("HTTP " + res.statusCode());
            String body = res.body();
            if (body == null || body.isBlank() || body.equals("null")) return List.of();
            JsonElement parsed = com.google.gson.JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return List.of();
            List<Row> rows = new ArrayList<>();
            for (var e : parsed.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject v = e.getValue().getAsJsonObject();
                String name = Lobby.str(v, "name");
                long ms = Lobby.num(v, "ms");
                if (!name.isBlank() && ms > 0) rows.add(new Row(name, ms));
            }
            rows.sort(Comparator.comparingLong(Row::ms).thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(rows);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка прервана");
        }
    }

    /** Сохраняет только лучший результат игрока. Вызывается исключительно после экрана финальных титров. */
    public static void submitAsync(String dbUrl, String player, long ms) {
        if (dbUrl == null || dbUrl.isBlank() || player == null || player.isBlank() || ms <= 0) return;
        Thread t = new Thread(() -> {
            try {
                String root = dbUrl.trim();
                while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
                String key = player.replaceAll("[^A-Za-z0-9_-]", "_");
                String url = root + "/friendrun/_speedrun_records/" + key + ".json";
                HttpRequest get = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
                HttpResponse<String> old = HTTP.send(get, HttpResponse.BodyHandlers.ofString());
                if (old.statusCode() == 401 || old.statusCode() == 403) throw new IOException("Firebase отклонил запись рекорда");
                if (old.statusCode() / 100 != 2) throw new IOException("HTTP " + old.statusCode());
                if (old.body() != null && !old.body().isBlank() && !old.body().equals("null")) {
                    JsonElement e = com.google.gson.JsonParser.parseString(old.body());
                    if (e.isJsonObject() && Lobby.num(e.getAsJsonObject(), "ms") > 0 && Lobby.num(e.getAsJsonObject(), "ms") <= ms) return;
                }
                JsonObject value = new JsonObject();
                value.addProperty("name", player);
                value.addProperty("ms", ms);
                HttpRequest put = HttpRequest.newBuilder(URI.create(url + "?print=silent"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(value.toString())).build();
                HttpResponse<String> saved = HTTP.send(put, HttpResponse.BodyHandlers.ofString());
                if (saved.statusCode() / 100 != 2) throw new IOException("HTTP " + saved.statusCode());
            } catch (Exception e) {
                FriendRunClient.LOG.warn("FRun: не удалось сохранить личный рекорд спидрана", e);
                FriendRunClient.chat("Не удалось сохранить рекорд спидрана: " + e.getMessage());
            }
        }, "FriendRun-Speedrun-Record");
        t.setDaemon(true);
        t.start();
    }
}
