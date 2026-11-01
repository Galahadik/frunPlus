package dev.friendrun;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;

/** Состояние лобби и забега на клиенте. Пишется только из главного потока клиента. */
public final class RunState {
    public enum Phase { IDLE, COUNTDOWN, RUNNING, WAITING_RESULT }
    public enum Mode { SEPARATE_WORLDS, HOST_WORLD }

    // лобби
    public Mode mode = Mode.SEPARATE_WORLDS;
    public long itemSeed;
    // настройки режимов (выбираются в лобби)
    public int convTotalMin = 20, convSlotMin = 3, chainLen = 5, bingoSize = 3, draftSize = 5, cfgDraftSize = 5, draftType = 3, cfgDraftType = 3, waveSec = 45, waveDiff = 1, manRunners = 1, manMin = 15;
    public boolean bingoFull;
    // настройки текущего раунда (хост копирует их на старте)
    public long cfgConvTotalMs = Game.CONVEYOR_TOTAL_MS, cfgConvSlotMs = Game.SLOT_MS;
    public int cfgChainLen = 5, cfgBingoSize = 3, cfgWaveSec = 45, cfgWaveDiff = 1, cfgManMin = 15;
    public boolean cfgBingoFull;
    public int gameMode;               // режим, выбранный в лобби
    public int chaosMod;               // выпавшее в лобби усложнение (индекс)
    public boolean chaos;              // включено «случайное усложнение»
    public int roundMod = -1;          // номер усложнения текущего раунда
    public boolean launched;           // хост нажал «Старт» -> миры создаются / созданы
    public boolean amHost;
    public String lobbyName = "";
    public String lanAddress = "";     // локальный адрес мира хоста (для друзей в одной Wi-Fi)
    public String address = "";        // домен e4mc мира хоста
    public String status = "";         // строка состояния для экрана лобби
    public long roundId;
    public record PlayerInfo(String name, boolean host, boolean ready, int score, boolean dead) {}
    public final List<PlayerInfo> players = new ArrayList<>();

    public String playersLine() {
        if (players.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (PlayerInfo p : players) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.name()).append(p.host() ? " (хост)" : "").append(p.ready() ? " ✔" : "");
        }
        return sb.toString();
    }

    // забег
    public Phase phase = Phase.IDLE;
    public Item target;
    // режимы «конвейер» и «цепочка»
    public int roundGame;              // режим текущего раунда
    public long roundSeed;
    public java.util.List<Item> chain = new ArrayList<>();
    public java.util.List<DraftGoal> draftGoals = new ArrayList<>();
    public int chainIdx;
    public int baseline;               // сколько штук цели уже было в инвентаре (засчитываем только новые)
    public int myScore;
    public long slot = -1;
    public boolean slotDone;
    public java.util.Set<String> runners = new java.util.HashSet<>();   // манхант: ники беглецов
    public boolean[] bingoDone = new boolean[25];   // бинго: какие клетки закрыты
    // Драфт: общая карточка, выбор целей и владелец каждой клетки.
    public boolean draftActive, draftComplete, draftChoiceSent;
    public int draftPickCount;
    public String draftTurn = "", draftOfferA = "", draftOfferB = "", draftBoardIds = "", draftOwnersIds = "";
    public String[] draftOwners = new String[49];
    public int[] draftBase = new int[49];
    public boolean[] draftClaimPending = new boolean[49];
    // Драфт+: фазы (баны -> роли -> драфт -> матч), роли игроков и модификатор матча.
    public String dpPhase = "", dpBanTurn = "", dpModId = "", dpBannedCsv = "", dpLastModAnnounced = "";
    public int dpBans = 2, dpBanTotal;
    public long dpBanSentIndex = -1;
    public boolean dpRoleSent, dpMatch;
    /** Роли игроков (ник -> id роли). Читается и серверным потоком (бонусы ролей), поэтому заменяется целиком. */
    public volatile java.util.Map<String, String> dpRoles = java.util.Map.of();
    // Драфт+: настройка матча (хост меняет в лобби) и команды.
    public boolean dpTeam, dpBanOn = true, dpRoleOn = true, dpModOn = true, dpCoinOn = true;
    public int dpTeamSize = 2, dpCoinFace = -1;
    public String dpTeamNameA = "Красные", dpTeamNameB = "Синие", dpFirst = "", dpHover = "";
    public java.util.Set<String> dpOffMods = java.util.Set.of(), dpOffRoles = java.util.Set.of();
    /** Состав: ник -> {команда 0/1/2, время вступления}. */
    public java.util.Map<String, long[]> dpRoster = java.util.Map.of();
    public int[] bingoBase = new int[25];           // бинго: сколько штук было в инвентаре на старте
    public long countdownEndNanos;
    public long startNanos;
    public boolean myReady;
    public boolean singleSpeedrunRecordSaved;
    public long singleSpeedrunFinalMs;

    // итог последнего раунда (для панели на экране)
    public String resultName = "", resultItem = "", resultMmr = "", resultMmr2 = "";
    public long resultMs;
    public int resultMode;
    public long resultScore;
    public long resultUntilNanos;

    public void resetRound() {
        phase = Phase.IDLE;
        target = null;
        myReady = false;
        singleSpeedrunRecordSaved = false;
        singleSpeedrunFinalMs = 0;
        roundMod = -1;
        roundGame = 0;
        chain = new ArrayList<>();
        draftGoals = new ArrayList<>();
        chainIdx = 0;
        baseline = 0;
        myScore = 0;
        slot = -1;
        slotDone = false;
        runners = new java.util.HashSet<>();
        bingoDone = new boolean[25];
        bingoBase = new int[25];
        draftActive = draftComplete = draftChoiceSent = false;
        draftPickCount = 0;
        draftTurn = draftOfferA = draftOfferB = draftBoardIds = draftOwnersIds = "";
        draftOwners = new String[49];
        draftBase = new int[49];
        draftClaimPending = new boolean[49];
        dpPhase = dpBanTurn = dpModId = dpBannedCsv = dpLastModAnnounced = "";
        dpBanTotal = 0;
        dpBanSentIndex = -1;
        dpRoleSent = dpMatch = false;
        dpRoles = java.util.Map.of();
        dpFirst = dpHover = "";
        dpCoinFace = -1;
    }

    public void resetAll() {
        resetRound();
        players.clear();
        launched = false;
        amHost = false;
        chaos = false;
        gameMode = 0;
        convTotalMin = 20; convSlotMin = 3; chainLen = 5; bingoSize = 3; draftSize = cfgDraftSize = 5; draftType = cfgDraftType = 3; dpBans = 2; bingoFull = false; waveSec = 45; waveDiff = 1; manRunners = 1; manMin = 15;
        resultUntilNanos = 0;
        mode = Mode.SEPARATE_WORLDS;
        lobbyName = "";
        address = "";
        lanAddress = "";
        status = "";
        roundId = 0;
    }

    public static String diffName(int d) { return d == 0 ? "лёгкая" : d == 2 ? "тяжёлая" : "обычная"; }

    /** Краткое описание выбранного режима с его настройками. */
    public String gameSummary() {
        return switch (gameMode) {
            case Game.CONVEYOR -> convTotalMin + " мин, смена каждые " + convSlotMin + " мин";
            case Game.CHAIN -> chainLen + " предметов по порядку";
            case Game.MANHUNT -> "беглецов " + manRunners + ", " + manMin + " мин, общий мир";
            case Game.WAVES -> "хардкор, волна каждые " + waveSec + " сек, " + diffName(waveDiff);
            case Game.BINGO -> bingoSize + "×" + bingoSize + (bingoFull ? ", вся карточка" : ", нужна линия");
            case Game.PILLARS -> "готовая карта, общий мир";
            case Game.SINGLE_SPEEDRUN -> "один игрок, выживание, полный Minecraft-спидран";
            case Game.DRAFT -> "драфт 5×5, первый до 13 целей";
            case Game.DRAFT_PLUS -> "драфт+ 5×5: баны, роли, хаос-событие";
            default -> "первый получивший побеждает";
        };
    }

    public long elapsedMs() { return (System.nanoTime() - startNanos) / 1_000_000L; }

    public static String formatTime(long ms) {
        long m = ms / 60000, s = (ms / 1000) % 60, r = ms % 1000;
        return String.format("%02d:%02d.%03d", m, s, r);
    }

    /** Компактный RTA-таймер с точностью до сотых секунды, как в спидран-инструментах. */
    public static String formatSpeedrunTime(long ms) {
        long safeMs = Math.max(0, ms);
        long totalSeconds = safeMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds / 60) % 60;
        long seconds = totalSeconds % 60;
        long centiseconds = (safeMs % 1000) / 10;
        return hours > 0
            ? String.format("%02d:%02d:%02d.%02d", hours, minutes, seconds, centiseconds)
            : String.format("%02d:%02d.%02d", totalSeconds / 60, seconds, centiseconds);
    }
}
