package dev.friendrun;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** config/friendrun.json: databaseUrl, lastLobby + настройки мода (см. Settings). */
public final class Config {
    private static final Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("friendrun.json");
    /** Вписанный по умолчанию URL базы (у друзей поле будет уже заполнено). */
    public static final String DEFAULT_URL = "https://randommod-default-rtdb.europe-west1.firebasedatabase.app";
    public static String databaseUrl = DEFAULT_URL;
    public static String lastLobby = "";

    private Config() {}

    public static void load() {
        try {
            if (!Files.exists(FILE)) { save(); return; }
            JsonObject o = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            if (o == null) return;
            if (o.has("databaseUrl") && !o.get("databaseUrl").getAsString().isBlank()) databaseUrl = o.get("databaseUrl").getAsString();
            if (o.has("lastLobby")) lastLobby = o.get("lastLobby").getAsString();
            Settings.read(o);
        } catch (Exception e) {
            FriendRunClient.LOG.warn("config load failed", e);
        }
    }

    public static void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("databaseUrl", databaseUrl);
            o.addProperty("lastLobby", lastLobby);
            Settings.write(o);
            Files.writeString(FILE, GSON.toJson(o));
        } catch (Exception e) {
            FriendRunClient.LOG.warn("config save failed", e);
        }
    }
}
