package dev.friendrun;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FriendRunClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("friendrun");
    public static final RunState STATE = new RunState();

    private static Lobby lobby;

    // состояние обработки документа лобби (только главный поток)
    private static boolean pendingPublish;
    private static long domainDeadline;          // хост: до какого момента ждём домен e4mc
    private static boolean addressSent;
    private static boolean hostGameStateSent;
    // Автоподключение к миру хоста разрешено только до первого старта раунда в этом лобби.
    private static boolean hostWorldJoinLocked;
    private static boolean lastHostGameState;
    private static boolean joinedWorld;
    private static long lastStartAt;
    private static long lastPreparedAckRound;
    private static long lastHostSeenValue = Long.MIN_VALUE;
    private static long hostClockOffsetMs;
    private static long lastWinnerRound;
    private static String lastPlayersLabel = "";
    private static int reapplyTicks, freezeTicks, pollTicks, dpTicks;
    private static long lastJoinTry;
    private static int joinAttempts;
    private static boolean rouletteInit, chaosInit;
    private static long lastChaosRollAt;
    private static long lastRollAt;
    private static int lastSubmittedDraftPick = -1;

    @Override
    public void onInitializeClient() {
        Config.load();
        Worlds.markForDelete();   // остатки одноразовых миров после прошлой сессии
        Commands.register();
        Hud.register();
        DraftPlusBonus.register();   // бонусы ролей и запреты модификаторов Драфта+ (серверные события)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> E4mc.scan(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(FriendRunClient::tick);
    }

    public static void chat(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) Mc.chatLine(Component.literal("[FRun] " + text));
        });
    }

    public static boolean inLobby() { return lobby != null; }

    /** Строка состояния: видна на экране лобби (даже когда игрок в меню и чата нет) и в чате. */
    public static void status(String text) {
        Minecraft.getInstance().execute(() -> STATE.status = text);
        chat(text);
    }

    /** Ручной ввод домена e4mc хостом: /rr domain <адрес>. */
    public static void setDomainManual(String raw) {
        if (lobby == null || !STATE.amHost) { chat("Только хост лобби может задать домен."); return; }
        String d = raw.trim().replaceAll("^[a-zA-Z]+://", "").replaceAll("/.*$", "");
        if (d.isEmpty()) { chat("Укажите домен, например: /rr domain abc-123.e4mc.link"); return; }
        addressSent = true;
        domainDeadline = 0;
        lobby.setAddress(d);
        status("Домен задан вручную: " + d);
    }

    // ---------- создание / вход по названию ----------
    public interface Result { void done(String errorOrNull); }

    public static void createLobby(String dbUrl, String name, Result cb) { connect(true, dbUrl, name, cb); }
    public static void joinLobby(String dbUrl, String name, Result cb) { connect(false, dbUrl, name, cb); }

    private static void connect(boolean host, String dbUrl, String name, Result cb) {
        if (inLobby()) { cb.done("Вы уже в лобби"); return; }
        if (dbUrl.isBlank()) { cb.done("Укажите URL базы данных (см. README)"); return; }
        if (!Lobby.validName(name)) { cb.done("Название: латиница, цифры, _ и - (до 24 символов)"); return; }
        Config.databaseUrl = dbUrl.trim();
        Config.lastLobby = name;
        Config.save();
        String me = Minecraft.getInstance().getUser().getName().replaceAll("[^A-Za-z0-9_]", "_");
        final JsonObject hostSettings = host ? Settings.toDoc() : null;   // настройки хоста уходят в новое лобби
        final boolean chaosOn = host && Settings.chaosDefault && Chaos.enabledIndices(Settings.localOffChaos()).length > 0;
        Thread t = new Thread(() -> {
            String err = null;
            Lobby lb = null;
            try {
                Lobby.Listener l = new Lobby.Listener() {
                    public void onDoc(JsonObject doc) { Minecraft.getInstance().execute(() -> apply(doc)); }
                    public void onClosed(String reason) {
                        Minecraft.getInstance().execute(() -> {
                            if (lobby != null) { chat(reason); cleanup(); }
                        });
                    }
                };
                lb = host ? Lobby.create(dbUrl, name, me, hostSettings, chaosOn, l) : Lobby.join(dbUrl, name, me, l);
            } catch (IOException e) { err = e.getMessage(); }
            Lobby fl = lb; String fe = err;
            Minecraft.getInstance().execute(() -> {
                if (fe == null) {
                    resetLocal();
                    lobby = fl;
                    STATE.amHost = host;
                    STATE.lobbyName = name;
                    Ranking.refreshAsync(Config.databaseUrl);   // MMR игроков рядом с именами в комнате
                }
                cb.done(fe);
            });
        }, "FriendRun-Connect");
        t.setDaemon(true);
        t.start();
    }

    // ---------- применение документа лобби ----------
    private static void apply(JsonObject doc) {
        if (lobby == null) return;
        RunState s = STATE;

        // настройки пула предметов/усложнений от хоста — первыми: от них зависит выбор предмета ниже
        Settings.applyLobby(doc);

        // игроки
        JsonObject players = Lobby.obj(doc, "players");
        String hostName = Lobby.str(doc, "host");
        // Estimate host-clock offset from fresh host heartbeat timestamps. Use a monotonic
        // clock after sampling so an old heartbeat does not make the offset drift over time.
        long hostSeen = Lobby.num(doc, "hostSeen");
        if (hostSeen > 0 && hostSeen != lastHostSeenValue) {
            lastHostSeenValue = hostSeen;
            hostClockOffsetMs = System.currentTimeMillis() - hostSeen;
            // Refine the deadline if a fresher host heartbeat arrives during countdown.
            if (!STATE.amHost && STATE.phase == RunState.Phase.COUNTDOWN && lastStartAt > 0) {
                long hostNow = System.currentTimeMillis() - hostClockOffsetMs;
                STATE.countdownEndNanos = System.nanoTime() + Math.max(0L, lastStartAt - hostNow) * 1_000_000L;
            }
        }
        s.players.clear();
        for (String p : players.keySet()) {
            s.players.add(new RunState.PlayerInfo(p, p.equals(hostName), Lobby.bool(Lobby.obj(players, p), "ready"),
                (int) Lobby.num(Lobby.obj(players, p), "score"), Lobby.bool(Lobby.obj(players, p), "dead")));
        }
        if (s.gameMode == Game.DRAFT || s.gameMode == Game.DRAFT_PLUS || s.roundGame == Game.DRAFT)
            s.myScore = (int) Lobby.num(Lobby.obj(players, myName()), "score");

        // настройки
        s.mode = Lobby.num(doc, "mode") == 1 ? RunState.Mode.HOST_WORLD : RunState.Mode.SEPARATE_WORLDS;
        s.gameMode = (int) Lobby.num(doc, "gameMode");
        s.draftActive = Lobby.bool(doc, "draftActive");
        s.draftComplete = Lobby.bool(doc, "draftComplete");
        s.draftPickCount = (int) Lobby.num(doc, "draftPickCount");
        s.draftTurn = Lobby.str(doc, "draftTurn");
        s.draftOfferA = Lobby.str(doc, "draftOfferA");
        s.draftOfferB = Lobby.str(doc, "draftOfferB");
        s.draftBoardIds = Lobby.str(doc, "draftBoardIds");
        s.draftOwnersIds = Lobby.str(doc, "draftOwnersIds");
        if (lastSubmittedDraftPick < s.draftPickCount) lastSubmittedDraftPick = -1;
        s.draftChoiceSent = lastSubmittedDraftPick == s.draftPickCount;
        s.draftOwners = parseDraftOwners(s.draftOwnersIds);
        for (int i = 0; i < s.draftOwners.length; i++) if (!"-".equals(s.draftOwners[i])) s.draftClaimPending[i] = false;
        if ((s.gameMode == Game.DRAFT || s.gameMode == Game.DRAFT_PLUS) && !s.draftBoardIds.isBlank()) s.draftGoals = parseDraftGoals(s.draftBoardIds);
        s.convTotalMin = (int) Lobby.orDef(Lobby.num(doc, "convTotal"), 20);
        s.convSlotMin = (int) Lobby.orDef(Lobby.num(doc, "convSlot"), 3);
        s.chainLen = (int) Lobby.orDef(Lobby.num(doc, "chainLen"), 5);
        s.bingoSize = (int) Lobby.orDef(Lobby.num(doc, "bingoSize"), 3);
        s.draftSize = (int) Math.max(5, Math.min(7, Lobby.orDef(Lobby.num(doc, "draftSize"), 5)));
        s.draftType = (int) Math.max(0, Math.min(3, Lobby.num(doc, "draftType")));
        DraftPlus.applyDoc(s, doc, players);   // Драфт+: фаза, роли, модификатор
        s.bingoFull = Lobby.bool(doc, "bingoFull");
        s.waveSec = (int) Lobby.orDef(Lobby.num(doc, "waveSec"), 45);
        s.waveDiff = (int) Lobby.num(doc, "waveDiff");
        s.manRunners = (int) Lobby.orDef(Lobby.num(doc, "manRunners"), 1);
        s.manMin = (int) Lobby.orDef(Lobby.num(doc, "manMin"), 15);
        long newSeed = s.itemSeed;
        try { newSeed = Long.parseLong(Lobby.str(doc, "itemSeed")); } catch (NumberFormatException ignored) {}
        long rollAt = Lobby.num(doc, "rollAt");
        if (!rouletteInit) {
            rouletteInit = true; lastRollAt = rollAt;
            Roulette.setFinal(newSeed);
        } else if (rollAt != lastRollAt) {
            lastRollAt = rollAt;
            long elapsed = System.currentTimeMillis() - rollAt;
            if (elapsed < Roulette.DURATION_MS) Roulette.start(newSeed, Math.max(0, elapsed));
            else Roulette.setFinal(newSeed);
        } else if (newSeed != s.itemSeed) {
            Roulette.setFinal(newSeed);   // сид сменился без ролла (новый раунд)
        }
        s.itemSeed = newSeed;
        int newMod = (int) Lobby.num(doc, "chaosMod");
        long cr = Lobby.num(doc, "chaosRollAt");
        if (!chaosInit) {
            chaosInit = true; lastChaosRollAt = cr;
            ChaosRoll.setFinal(newMod);
        } else if (cr != lastChaosRollAt) {
            lastChaosRollAt = cr;
            long el = System.currentTimeMillis() - cr;
            if (el < ChaosRoll.DURATION_MS) ChaosRoll.start(newMod, Math.max(0, el));
            else ChaosRoll.setFinal(newMod);
        } else if (newMod != s.chaosMod) {
            ChaosRoll.setFinal(newMod);   // усложнение сменилось без ролла (новый раунд)
        }
        s.chaosMod = newMod;
        s.roundId = Lobby.num(doc, "roundId");
        s.chaos = Lobby.bool(doc, "chaos");

        // победитель прошлого раунда
        JsonObject w = Lobby.obj(doc, "winner");
        long wr = Lobby.num(w, "round");
        if (wr > lastWinnerRound) {
            lastWinnerRound = wr;
            int wm = (int) Lobby.num(w, "mode");
            String wn = Lobby.str(w, "name");
            if (wm == Game.PILLARS) {
                chat(wn.isEmpty() || wn.equals("—") ? "Столбы: раунд завершён без победителя." : "Столбы: раунд завершён. Победили: " + wn);
            } else if (wm == Game.MANHUNT) {
                chat("Манхант завершён. Победили: " + wn + " (время " + RunState.formatTime(Lobby.num(w, "ms")) + ")");
            } else if (wm == Game.WAVES) {
                chat("Волны завершены. Победитель: " + wn + " — очков: " + Lobby.num(w, "score") + ", продержался " + RunState.formatTime(Lobby.num(w, "ms")));
            } else if (wm == Game.CONVEYOR) {
                chat(wn.equals("—") ? "Конвейер завершён: никто ничего не сдал, ничья."
                    : "Конвейер завершён. Победитель: " + wn + " — предметов: " + Lobby.num(w, "score"));
            } else if (wm == Game.DRAFT_PLUS) {
                String teamWin = Lobby.str(w, "team");
                if (!teamWin.isEmpty()) chat("Побеждает " + teamWin + "! Захвачено целей командой: " + Lobby.num(w, "score"));
                else chat("Драфт+ завершён. Победитель: " + wn + " — захвачено целей: " + Lobby.num(w, "score"));
                for (String line : DraftPlus.contribLines(Lobby.str(w, "contrib"))) chat("Вклад — " + line);
            } else if (wm == Game.DRAFT) {
                chat("Драфт завершён. Победитель: " + wn + " — захвачено целей: " + Lobby.num(w, "score"));
            } else {
                chat("Победитель: " + wn + " — " + RunState.formatTime(Lobby.num(w, "ms")));
            }
            s.resultName = wn;
            s.resultMs = Lobby.num(w, "ms");
            s.resultMode = wm;
            s.resultScore = Lobby.num(w, "score");
            s.resultItem = (s.target != null && wm == Game.SPEEDRUN) ? ItemInfo.name(s.target) : "";
            s.resultMmr = ""; s.resultMmr2 = "";
            java.util.List<Ranking.Delta> deltas = Ranking.parseDeltas(Lobby.str(w, "deltas"));
            if (!deltas.isEmpty()) {
                String modeName = Ranking.shortName(wm);
                StringBuilder sb = new StringBuilder();
                for (Ranking.Delta d : deltas) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(d.name()).append(' ').append(Ranking.signed(d.delta())).append(" (").append(d.mmr()).append(')');
                    if (d.name().equals(myName())) {
                        s.resultMmr = modeName + ": " + d.mmr() + " (" + Ranking.signed(d.delta()) + ") · " + Ranking.tier(d.mmr()).name();
                        s.resultMmr2 = "Общий: " + d.oMmr() + " (" + Ranking.signed(d.oDelta()) + ") · " + Ranking.tier(d.oMmr()).name();
                    }
                }
                chat("Рейтинг (" + modeName + "): " + sb);
                for (Ranking.Delta d : deltas) {   // смена ранга в этом режиме
                    Ranking.Tier before = Ranking.tier(d.mmr() - d.delta()), after = Ranking.tier(d.mmr());
                    if (!before.name().equals(after.name()))
                        chat(d.name() + (d.delta() > 0 ? ": новый ранг — " : ": ранг понижен — ") + after.name() + " (" + modeName + ")");
                }
                // Не обновляем рейтинг автоматически после каждого раунда:
                // свежие данные загружаются при входе в лобби/таблицу или вручную.
            }
            s.resultUntilNanos = System.nanoTime() + 10_000_000_000L;
            Snd.play(Snd.ITEM_DROP, 1.0f, 1.0f);
            DraftPlus.clearEffects();   // эффекты ролей/модификатора Драфта+ (если были)
            if (wm != Game.PILLARS) {   // «Столбы» ведёт раунды сама карта: сбрасывать нечего и /go не нужен
                Waves.cleanup();
                Manhunt.cleanup();
                Chaos.clear();
                s.resetRound();
                // Draft is a complete match, not a normal round. Do not immediately
                // prompt everyone to /go again after its winner is announced.
                if (wm != Game.DRAFT && wm != Game.DRAFT_PLUS) {
                    chat("Напишите /go, когда готовы к следующему раунду.");
                }
            }
        }

        // Хост завершил игру (например, вышел из своего мира). Сбрасываем только состояние
        // раунда/запущенной игры, но оставляем соединение с лобби и список игроков.
        if (!Lobby.bool(doc, "launched") && s.launched) {
            s.launched = false;
            s.resetRound();
            lastStartAt = 0;
            lastPreparedAckRound = 0;
            hostWorldJoinLocked = false;
            pendingPublish = false;
            domainDeadline = 0;
            addressSent = false;
            Freeze.release();
            Waves.cleanup();
            Manhunt.cleanup();
            Chaos.clear();
            hostGameStateSent = false;
            lastHostGameState = false;
            chat("Хост вышел из мира. Игра остановлена; лобби сохранено. Хост может запустить игру снова.");
        }

        // запуск игры
        if (Lobby.bool(doc, "launched") && !s.launched) {
            s.launched = true;
            s.myReady = false;
            handleLaunch(Lobby.str(doc, "worldSeed"));
        }

        // мир хоста: адрес e4mc пришёл -> заходим
        s.address = Lobby.str(doc, "address");
        s.lanAddress = Lobby.str(doc, "lanAddress");

        // Two-phase start: prepare identical round data first, then acknowledge it to host.
        // Host will publish the actual start deadline only after all ready players prepared.
        long startAt = Lobby.num(doc, "startAt");
        long prepareRound = Lobby.num(doc, "prepareRound");
        if (prepareRound > 0 && startAt == 0 && s.phase == RunState.Phase.IDLE
            && Lobby.num(Lobby.obj(Lobby.obj(doc, "players"), myName()), "preparedRound") != prepareRound
            && lastPreparedAckRound != prepareRound) {
            lastPreparedAckRound = prepareRound;
            JsonObject ack = new JsonObject();
            ack.addProperty("preparedRound", prepareRound);
            lobby.patchPlayer(myName(), ack);
        }
        if (startAt > 0 && startAt != lastStartAt) {
            lastStartAt = startAt;
            long hostNow = System.currentTimeMillis() - (s.amHost ? 0 : hostClockOffsetMs);
            if (hostNow - startAt < 10_000 && s.phase == RunState.Phase.IDLE) {
                String rs = Lobby.str(doc, "roundSeed");
                try {
                    long seed = Long.parseLong(rs);
                    s.roundSeed = seed;
                    s.roundGame = (int) Lobby.num(doc, "roundGame");
                    s.dpMatch = Lobby.bool(doc, "rDraftPlus") && s.roundGame == Game.DRAFT;
                    s.myScore = 0;
                    s.slot = -1;
                    s.slotDone = false;
                    s.chain = new java.util.ArrayList<>();
                    s.chainIdx = 0;
                    s.cfgConvTotalMs = Lobby.orDef(Lobby.num(doc, "rConvTotal"), 20) * 60_000L;
                    s.cfgConvSlotMs = Lobby.orDef(Lobby.num(doc, "rConvSlot"), 3) * 60_000L;
                    s.cfgChainLen = (int) Lobby.orDef(Lobby.num(doc, "rChainLen"), 5);
                    s.cfgBingoSize = (int) Lobby.orDef(Lobby.num(doc, "rBingoSize"), 3);
        s.cfgDraftSize = (int) Math.max(5, Math.min(7, Lobby.orDef(Lobby.num(doc, "rDraftSize"), 5)));
                    s.cfgDraftType = (int) Math.max(0, Math.min(3, Lobby.num(doc, "rDraftType")));
                    s.cfgBingoFull = Lobby.bool(doc, "rBingoFull");
                    s.cfgWaveSec = (int) Lobby.orDef(Lobby.num(doc, "rWaveSec"), 45);
                    s.cfgWaveDiff = (int) Lobby.num(doc, "rWaveDiff");
                    s.cfgManMin = (int) Lobby.orDef(Lobby.num(doc, "rManMin"), 15);
                    s.runners = new java.util.HashSet<>();
                    for (String r : Lobby.str(doc, "runners").split(",")) if (!r.isEmpty()) s.runners.add(r);
                    if (s.roundGame == Game.CHAIN) {
                        s.chain = ItemPicker.chain(seed, s.cfgChainLen);
                        s.target = s.chain.get(0);
                    } else if (s.roundGame == Game.BINGO) {
                        s.chain = ItemPicker.chain(seed, s.cfgBingoSize * s.cfgBingoSize);
                        s.target = s.chain.get(0);
                        s.bingoDone = new boolean[25];
                        s.bingoBase = new int[25];
                    } else if (s.roundGame == Game.DRAFT) {
                        s.draftGoals = parseDraftGoals(s.draftBoardIds);
                        s.target = s.chain.isEmpty() ? ItemPicker.pick(seed) : s.chain.get(0);
                        s.draftBase = new int[49];
                        s.draftClaimPending = new boolean[49];
                    } else if (s.roundGame == Game.WAVES || s.roundGame == Game.MANHUNT) {
                        s.target = ItemPicker.pick(seed);   // для волн цель не нужна, но поле должно быть заполнено
                    } else if (s.roundGame == Game.CONVEYOR) {
                        s.target = ItemPicker.itemFor(seed, 0);
                    } else if (s.roundGame == Game.SINGLE_SPEEDRUN) {
                        s.target = null;
                    } else {
                        s.target = ItemPicker.pick(seed);
                    }
                    s.countdownEndNanos = System.nanoTime() + Math.max(0L, startAt - hostNow) * 1_000_000L;
                    s.phase = RunState.Phase.COUNTDOWN;
                    s.roundMod = doc.has("roundMod") ? (int) Lobby.num(doc, "roundMod") : -1;
                    if (s.roundGame == Game.CHAIN) {
                        StringBuilder sb = new StringBuilder();
                        for (Item it : s.chain) { if (sb.length() > 0) sb.append(" → "); sb.append(ItemInfo.name(it)); }
                        chat("Цепочка начинается! По порядку: " + sb);
                    } else if (s.roundGame == Game.MANHUNT) {
                        boolean runner = s.runners.contains(myName());
                        chat("Манхант! Беглецы: " + String.join(", ", s.runners) + ". Вы — " + (runner
                            ? "БЕГЛЕЦ: выживите " + s.cfgManMin + " мин. У вас фора 30 секунд."
                            : "ОХОТНИК: найдите и убейте всех беглецов за " + s.cfgManMin + " мин. Подсказка с направлением — над хотбаром."));
                    } else if (s.roundGame == Game.WAVES) {
                        chat("Волны мобов (хардкор)! Выживайте, убивайте мобов и проходите волны — за всё начисляются очки. Побеждает больше очков.");
                    } else if (s.roundGame == Game.BINGO) {
                        int n = s.cfgBingoSize;
                        chat("Бинго " + n + "×" + n + " начинается! " + (s.cfgBingoFull ? "Нужно закрыть всю карточку." : "Нужна линия из " + n + " предметов.")
                            + " Карточка на экране, список: /rr card");
                    } else if (s.roundGame == Game.CONVEYOR) {
                        chat("Конвейер начинается! " + s.cfgConvTotalMs / 60_000L + " мин, предмет меняется каждые "
                            + s.cfgConvSlotMs / 60_000L + " мин. Первый: " + ItemInfo.name(s.target));
                    } else if (s.roundGame == Game.SINGLE_SPEEDRUN) {
                        chat("Одиночный спидран: предстартовый отсчёт. После GO таймер запустится автоматически. Играйте до финальных титров; незавершённые попытки не записываются.");
                    } else {
                        chat("Забег начинается! Ищите: " + ItemInfo.name(s.target));
                    }
                    if (s.roundMod >= 0) chat("Усложнение раунда: " + Chaos.nameOf(s.roundMod));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // ---------- настройки лобби (только хост) ----------
    public static void setMode(RunState.Mode m) {
        if (STATE.gameMode == Game.SINGLE_SPEEDRUN) { chat("В одиночном спидране используется отдельный мир хоста; смена сетевого режима отключена."); return; }
        if (STATE.gameMode == Game.MANHUNT && m == RunState.Mode.SEPARATE_WORLDS) { chat("Манхант возможен только в режиме «мир хоста»."); return; }
        if (lobby != null && STATE.amHost && !STATE.launched)
            lobby.setSettings(m == RunState.Mode.HOST_WORLD ? 1 : 0, Long.toString(STATE.itemSeed), false);
    }

    public static void rollItem() {
        if (lobby != null && STATE.amHost && !STATE.launched && STATE.gameMode == Game.SPEEDRUN)
            lobby.setSettings(STATE.mode == RunState.Mode.HOST_WORLD ? 1 : 0, Lobby.newSeed(), true);
    }

    private static final int[] OPT_DRAFT = {5, 6, 7};
    private static final int[] OPT_WAVE = {30, 45, 60, 90}, OPT_RUN = {1, 2, 3}, OPT_MAN = {10, 15, 20, 30};
    private static final int[] OPT_TOTAL = {10, 15, 20, 30}, OPT_SLOT = {1, 2, 3, 5}, OPT_CHAIN = {3, 5, 7, 10}, OPT_BINGO = {3, 5};

    private static int nextOpt(int[] opts, int cur) {
        for (int i = 0; i < opts.length; i++) if (opts[i] == cur) return opts[(i + 1) % opts.length];
        return opts[0];
    }

    /** Первая настройка выбранного режима (меняет только хост, пока нет раунда). */
    public static void settingA() {
        if (lobby == null || !STATE.amHost || STATE.phase != RunState.Phase.IDLE) return;
        switch (STATE.gameMode) {
            case Game.CONVEYOR -> lobby.setNum("convTotal", nextOpt(OPT_TOTAL, STATE.convTotalMin));
            case Game.CHAIN -> lobby.setNum("chainLen", nextOpt(OPT_CHAIN, STATE.chainLen));
            case Game.BINGO -> lobby.setNum("bingoSize", nextOpt(OPT_BINGO, STATE.bingoSize));
            case Game.DRAFT, Game.DRAFT_PLUS -> lobby.setNum("draftType", (STATE.draftType + 1) % 4);
            case Game.WAVES -> lobby.setNum("waveSec", nextOpt(OPT_WAVE, STATE.waveSec));
            case Game.MANHUNT -> lobby.setNum("manRunners", nextOpt(OPT_RUN, STATE.manRunners));
            default -> { }
        }
    }

    /** Вторая настройка выбранного режима. */
    public static void settingB() {
        if (lobby == null || !STATE.amHost || STATE.phase != RunState.Phase.IDLE) return;
        switch (STATE.gameMode) {
            case Game.DRAFT -> lobby.setNum("draftSize", nextOpt(OPT_DRAFT, STATE.draftSize));
            case Game.DRAFT_PLUS -> lobby.setNum("dpBans", STATE.dpBans % DraftPlus.BANS_MAX + 1);   // 1 -> 2 -> 3 -> 1
            case Game.CONVEYOR -> lobby.setNum("convSlot", nextOpt(OPT_SLOT, STATE.convSlotMin));
            case Game.BINGO -> lobby.setFlag("bingoFull", !STATE.bingoFull);
            case Game.WAVES -> lobby.setNum("waveDiff", (STATE.waveDiff + 1) % 3);
            case Game.MANHUNT -> lobby.setNum("manMin", nextOpt(OPT_MAN, STATE.manMin));
            default -> { }
        }
    }

    public static void toggleChaos() {
        if (lobby == null || !STATE.amHost) return;
        if (!STATE.chaos && Chaos.enabledIndices(Settings.localOffChaos()).length == 0) {
            status("Все усложнения выключены в настройках мода (кнопка «Настройки»).");
            return;
        }
        lobby.setChaos(!STATE.chaos);
    }

    /** Хост изменил пул предметов: передать лобби (только до старта игры — иначе у игроков разойдутся предметы). */
    public static void pushPool() {
        if (lobby == null || !STATE.amHost || STATE.launched) return;
        lobby.setPool(Settings.encodeItems(Settings.viewOffItems()), Settings.viewSkipHard(), Settings.viewFamilyWeighted());
    }

    /** Хост изменил список усложнений. Если выключены все — случайное усложнение отключается. */
    public static void pushChaos() {
        if (lobby == null || !STATE.amHost) return;
        Set<String> off = Settings.localOffChaos();
        lobby.setOffChaos(Settings.encodeChaos(off));
        if (STATE.chaos && Chaos.enabledIndices(off).length == 0) lobby.setChaos(false);
    }

    /** Записать очки/смерть игрока в лобби (хост общего мира пишет за всех игроков). */
    public static void reportPlayer(String name, int score, long ms, boolean dead) {
        if (lobby == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("score", score);
        o.addProperty("lastMs", ms);
        o.addProperty("dead", dead);
        lobby.patchPlayer(name, o);
    }

    /** Ник вашего аккаунта в том виде, в каком он попадает в лобби и в таблицу рейтинга. */
    public static String accountName() {
        return Minecraft.getInstance().getUser().getName().replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** /rr top [режим] — топ рейтинга в чат. scope: 0 — общий, 1.. — режим. */
    public static void printTop(int scope) {
        final String url = Config.databaseUrl;
        Thread t = new Thread(() -> {
            String msg;
            try {
                java.util.List<Ranking.Row> rows = Ranking.sorted(Lobby.fetchRatings(url), scope);
                String title = scope == 0 ? "Общий" : Ranking.shortName(scope - 1);
                StringBuilder sb = new StringBuilder("Таблица лидеров — " + title + ":");
                if (rows.isEmpty()) sb.append(" пока пусто (рейтинг идёт в лобби от 2 игроков).");
                for (int i = 0; i < Math.min(10, rows.size()); i++) {
                    Ranking.Row r = rows.get(i);
                    sb.append("\n").append(i + 1).append(". ").append(r.name()).append(" — ").append(r.rec().mmr())
                      .append(" (").append(Ranking.tier(r.rec().mmr()).name()).append("), побед ").append(r.rec().wins()).append('/').append(r.rec().games());
                }
                msg = sb.toString();
            } catch (Exception e) { msg = "Не удалось загрузить таблицу: " + e.getMessage(); }
            final String m = msg;
            Minecraft.getInstance().execute(() -> chat(m));
        }, "FRun-top");
        t.setDaemon(true);
        t.start();
    }

    /** «Столбы»: карта объявила победителей (из чата). Рейтинг считает и записывает только хост лобби. */
    public static void pillarsResult(java.util.List<String> winners) {
        if (lobby != null && STATE.amHost) lobby.reportPillars(winners);
    }

    public static String myName() { return lobby != null ? lobby.me : ""; }

    private static String[] parseDraftOwners(String raw) {
        String[] out = new String[49];
        java.util.Arrays.fill(out, "-");
        if (raw != null && !raw.isBlank()) {
            String[] parts = raw.split(",", -1);
            for (int i = 0; i < Math.min(parts.length, out.length); i++) out[i] = parts[i].isBlank() ? "-" : parts[i];
        }
        return out;
    }

    private static java.util.List<Item> parseDraftItems(String raw) {
        java.util.List<Item> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String id : raw.split(",")) {
            Item item = ItemPicker.byId(id);
            if (item != null) out.add(item);
        }
        return out;
    }

    private static java.util.List<DraftGoal> parseDraftGoals(String raw) {
        java.util.List<DraftGoal> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String encoded : raw.split(",")) {
            DraftGoal goal = DraftGoal.parse(encoded);
            if (goal != null) out.add(goal);
        }
        return out;
    }

    // ---------- Драфт+: настройка матча, команды, подсказки ----------
    private static boolean pendingSetup;

    /** Хост может менять настройки матча, пока не идёт подготовка/матч. */
    public static boolean dpCanConfigure() {
        return lobby != null && STATE.amHost && STATE.phase == RunState.Phase.IDLE && STATE.dpPhase.isEmpty();
    }

    public static boolean dpCanJoinTeam() {
        return lobby != null && STATE.phase == RunState.Phase.IDLE && STATE.dpPhase.isEmpty();
    }

    public static void dpSetNum(String key, long v) { if (dpCanConfigure()) lobby.setNum(key, v); }
    public static void dpSetFlag(String key, boolean v) { if (dpCanConfigure()) lobby.setFlag(key, v); }
    public static void dpSetStr(String key, String v) { if (dpCanConfigure()) lobby.setStr(key, v); }

    public static void dpJoinTeam(int team) { if (dpCanJoinTeam()) lobby.joinTeam(team); }

    /** Капитан переименовывает свою команду (не больше 16 символов). */
    public static void dpRenameTeam(int team, String name) {
        String clean = name == null ? "" : name.replaceAll("[\\r\\n\"]", "").trim();
        if (clean.length() > 16) clean = clean.substring(0, 16).trim();
        if (clean.isEmpty() || lobby == null || !dpCanJoinTeam()) return;
        if (!myName().equals(DraftPlus.captain(STATE.dpRoster, team))) return;
        lobby.setStr(team == 1 ? "dpTeamNameA" : "dpTeamNameB", clean);
    }

    /** Хост: «Случайные команды» — перемешать всех игроков и разделить поровну. */
    public static void dpRandomTeams() {
        if (!dpCanConfigure() || !STATE.dpTeam) return;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (RunState.PlayerInfo p : STATE.players) names.add(p.name());
        if (names.size() > 2 * STATE.dpTeamSize) { chat("Драфт+: игроков больше, чем мест в командах (" + 2 * STATE.dpTeamSize + ")."); return; }
        java.util.Collections.shuffle(names);
        java.util.Map<String, Integer> assign = new java.util.LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) assign.put(names.get(i), i < (names.size() + 1) / 2 ? 1 : 2);
        lobby.setTeams(assign);
    }

    public static void dpHover(String value) { if (lobby != null) lobby.setHover(value); }

    /** /rr setup — открыть настройку матча (в том числе из мира, между матчами). */
    public static void openSetup() { pendingSetup = true; }

    /** Драфт+: забанить цель (только в свой ход, один раз на ход). */
    public static void chooseBan(String encodedGoal) {
        RunState s = STATE;
        int idx = DraftPlus.bannedCount(s);
        if (lobby == null || !s.dpPhase.equals("BAN") || !myName().equals(s.dpBanTurn) || s.dpBanSentIndex == idx) return;
        s.dpBanSentIndex = idx;
        lobby.chooseBan(encodedGoal, idx);
    }

    /** Драфт+: выбрать роль (один раз за матч). */
    public static void chooseRole(String roleId) {
        if (lobby == null || !STATE.dpPhase.equals("ROLE") || STATE.dpRoleSent || DraftPlus.roleById(roleId) == null) return;
        STATE.dpRoleSent = true;
        lobby.chooseRole(roleId);
    }

    public static void chooseDraft(int choice) {
        if (lobby == null || !STATE.draftActive || !myName().equals(STATE.draftTurn)
            || STATE.draftChoiceSent || STATE.draftPickCount < 0) return;
        lastSubmittedDraftPick = STATE.draftPickCount;
        STATE.draftChoiceSent = true;
        lobby.chooseDraft(choice, STATE.draftPickCount);
    }

    public static void setGameMode(int g) {
        if (lobby == null || !STATE.amHost) { chat("Режим игры выбирает только хост лобби."); return; }
        if (STATE.phase != RunState.Phase.IDLE) { chat("Дождитесь конца раунда."); return; }
        int gm = Math.floorMod(g, Game.COUNT);
        if (gm == Game.SINGLE_SPEEDRUN && STATE.players.size() > 1) chat("Остальные участники будут удалены только из этого лобби.");
        lobby.setGameMode(gm);
        if (gm == Game.SINGLE_SPEEDRUN) {
            STATE.mode = RunState.Mode.SEPARATE_WORLDS;
            chat("Одиночный спидран: вход в это лобби закрыт, участники удаляются, MMR не начисляется.");
        }
        if ((gm == Game.MANHUNT || gm == Game.PILLARS) && !STATE.launched) {
            lobby.setSettings(1, Long.toString(STATE.itemSeed), false);   // этим режимам нужен общий мир
            chat(gm == Game.PILLARS ? "Столбы: включён общий мир хоста, чтобы все игроки попали на одну карту."
                : "Манхант: включён режим «мир хоста» (нужен общий мир).");
        }
        chat("Режим игры: " + Game.name(Math.floorMod(g, Game.COUNT)));
    }

    public static void cycleGame() { setGameMode(STATE.gameMode + 1); }

    public static void rollChaos() {
        if (lobby != null && STATE.amHost && STATE.chaos) lobby.rollChaos();
    }

    public static void launch() {
        if (STATE.gameMode == Game.SINGLE_SPEEDRUN && STATE.players.size() != 1) { chat("Одиночный спидран требует ровно одного игрока в этом лобби."); return; }
        if (STATE.gameMode == Game.PILLARS && (STATE.mode != RunState.Mode.HOST_WORLD || STATE.players.size() < 2)) {
            chat("Столбы: для запуска нужен общий мир хоста и минимум 2 игрока в лобби.");
            return;
        }
        if (STATE.gameMode == Game.DRAFT_PLUS && DraftPlus.startError(STATE) != null) { chat("Драфт+: " + DraftPlus.startError(STATE)); return; }
        if (STATE.gameMode == Game.MANHUNT && (STATE.mode != RunState.Mode.HOST_WORLD || STATE.players.size() < 2)) {
            chat("Манхант: нужен режим «мир хоста» и минимум 2 игрока в лобби.");
            return;
        }
        if (lobby != null && STATE.amHost && !STATE.launched) lobby.launch();
    }

    private static void handleLaunch(String worldSeedStr) {
        if (STATE.gameMode == Game.PILLARS) {
            if (STATE.amHost) {
                pendingPublish = true;
                status("Подготавливаем карту «Столбики»…");
                WorldLauncher.createPillarsWorld();
            } else {
                status("Хост загружает карту «Столбики», подождите подключения…");
            }
            return;
        }
        long seed;
        try { seed = Long.parseLong(worldSeedStr); } catch (NumberFormatException e) { seed = System.nanoTime(); }
        if (STATE.mode == RunState.Mode.SEPARATE_WORLDS) {
            status("Создаём ваш мир…");
            WorldLauncher.createWorld(seed);
        } else if (STATE.amHost) {
            pendingPublish = true;
            status("Создаём мир…");
            WorldLauncher.createWorld(seed);
        } else {
            status("Хост создаёт мир, подождите…");
        }
    }

    /** Из E4mc.scan: e4mc сообщил об ошибке — ждать домен бесполезно. */
    public static void onE4mcError() {
        Minecraft.getInstance().execute(() -> {
            if (lobby != null && STATE.amHost && domainDeadline > 0 && !addressSent) {
                domainDeadline = 0;
                status("e4mc не смог запуститься (на Android он хостить не умеет). Друзья из вашей Wi-Fi зайдут по LAN, остальным — режим «отдельные миры».");
            }
        });
    }

    /** Из миксина: в чат пришёл домен e4mc. Актуально только для хоста мира. */
    public static void onDomain(String domain) {
        Minecraft.getInstance().execute(() -> {
            if (lobby != null && STATE.amHost && STATE.mode == RunState.Mode.HOST_WORLD && !addressSent
                && domainDeadline > 0) {
                addressSent = true;
                lobby.setAddress(domain);
                status("Домен e4mc: " + domain + " — друзья заходят автоматически.");
            }
        });
    }

    // ---------- в мире ----------
    public static void go() {
        if (STATE.gameMode == Game.PILLARS) { chat("Режим «Столбы» работает по правилам встроенной карты: /go не нужен, рейтинг начисляется автоматически по итогу раунда карты."); return; }
        if (!inLobby()) { chat("Вы не в лобби (главное меню -> FriendRun)"); return; }
        if (!STATE.launched) { chat("Хост ещё не запустил игру."); return; }
        if (STATE.phase != RunState.Phase.IDLE) { chat("Раунд уже идёт."); return; }
        if (STATE.myReady) { chat("Вы уже подтвердили готовность."); return; }
        if (STATE.gameMode == Game.MANHUNT && STATE.players.size() < 2) { chat("Манхант: нужно минимум 2 игрока."); return; }
        if (STATE.gameMode == Game.DRAFT_PLUS && DraftPlus.startError(STATE) != null) { chat("Драфт+: " + DraftPlus.startError(STATE) + " (/rr setup — настройка матча)."); return; }
        STATE.myReady = true;
        lobby.setReady();
    }

    public static void leave() {
        if (lobby != null) lobby.close();
        cleanup();
    }

    private static void resetLocal() {
        pendingPublish = false; domainDeadline = 0; addressSent = false;
        hostGameStateSent = false; lastHostGameState = false; hostWorldJoinLocked = false;
        lastJoinTry = 0; joinAttempts = 0; rouletteInit = false; chaosInit = false; lastChaosRollAt = 0; lastRollAt = 0; lastStartAt = 0; lastPreparedAckRound = 0; lastHostSeenValue = Long.MIN_VALUE; hostClockOffsetMs = 0; lastWinnerRound = Long.MAX_VALUE;
        STATE.resetAll();
        DraftPlus.resetClientMemory();
        Settings.clearLobby();
    }

    private static void cleanup() {
        Waves.cleanup();
        Manhunt.cleanup();
        Freeze.release();
        Chaos.clear();
        DraftPlus.clearEffects();
        // Перед resetLocal сохраняем адрес текущего FriendRun-сервера для точечной очистки Xaero.
        Worlds.markForDelete(STATE.address);
        if (Minecraft.getInstance().player != null) chat("Мир FRun будет удалён, когда вы выйдете из него.");
        lobby = null;
        resetLocal();
    }

    /** Сейчас идёт подключение/загрузка мира — повторно не заходим. */
    private static boolean connecting() {
        var scr = Mc.currentScreen();
        if (scr == null) return false;
        String n = scr.getClass().getSimpleName();
        return (n.contains("Connect") && !n.contains("Disconnect")) || n.contains("Loading") || n.contains("Receiving");
    }

    // ---------- тик ----------
    private static void tick(Minecraft mc) {
        Worlds.tick(mc);
        WorldLauncher.tick(mc);
        RunState s = STATE;
        if (lobby == null) return;
        if (s.launched && s.draftActive && !(Mc.currentScreen() instanceof DraftScreen)) Mc.setScreen(new DraftScreen());
        else if ((!s.launched || !s.draftActive) && Mc.currentScreen() instanceof DraftScreen) Mc.setScreen(null);
        DraftPlus.tickScreens(s);   // Драфт+: экраны банов и выбора роли
        if (pendingSetup && Mc.currentScreen() == null) { pendingSetup = false; Mc.setScreen(new DraftPlusSetupScreen(null)); }

        // Передаём состояние мира только при изменении, а не каждый тик.
        // Пока хост не в игровом мире, остальные клиенты опрашивают Firebase редко.
        if (s.amHost) {
            boolean inGame = s.launched && mc.player != null
                && (s.mode != RunState.Mode.HOST_WORLD || mc.getSingleplayerServer() != null);
            if (hostGameStateSent && lastHostGameState && !inGame) {
                // Host left the Minecraft world: terminate the running game in Firebase.
                // One combined patch prevents clients seeing a half-reset lobby state.
                hostGameStateSent = true;
                lastHostGameState = false;
                lobby.endBecauseHostLeftWorld();
            } else if (!hostGameStateSent || inGame != lastHostGameState) {
                hostGameStateSent = true;
                lastHostGameState = inGame;
                lobby.setHostInGame(inGame);
            }
        }

        // lastWinnerRound при входе = текущий раунд, чтобы не показывать старых победителей
        if (lastWinnerRound == Long.MAX_VALUE) lastWinnerRound = s.roundId;

        // хост-мир: мир загрузился -> открыть для сети (e4mc сам поднимет туннель и напишет домен)
        if (pendingPublish && mc.player != null && mc.getSingleplayerServer() != null) {
            pendingPublish = false;
            E4mc.snapshot();   // старые домены из чата не считаются
            int port = WorldLauncher.publish();
            if (port > 0) {
                String ip = Net.localIPv4();
                if (ip != null) lobby.setLanAddress(ip + ":" + port);   // друзья из той же Wi-Fi зайдут по LAN
                domainDeadline = System.currentTimeMillis() + 90_000;
                status("Мир открыт" + (ip != null ? " (LAN " + ip + ":" + port + ")" : "") + ". Ждём домен от e4mc…");
            } else {
                status("Не удалось открыть мир для сети.");
            }
        }
        if (domainDeadline > 0 && !addressSent) {
            if (++pollTicks % 20 == 0) E4mc.pollChat();   // ищем домен в истории чата
            if (System.currentTimeMillis() > domainDeadline) {
                domainDeadline = 0;
                status("Домен e4mc не найден. Установлен ли e4mc? Можно ввести вручную: /rr domain <адрес>");
            }
        }

        // Друг в режиме «мир хоста»: автоподключение разрешено только до первого запуска раунда.
        // Как только раунд реально начался, больше не пытаемся подключаться повторно — в том числе
        // после завершения раунда, пока это же лобби используется для следующих режимов/раундов.
        if (s.phase == RunState.Phase.RUNNING || s.phase == RunState.Phase.WAITING_RESULT) {
            hostWorldJoinLocked = true;
        }
        boolean lanOk = !s.lanAddress.isEmpty() && Net.sameSubnet(Net.localIPv4(), s.lanAddress.split(":")[0]);
        String target = (lanOk && (s.address.isEmpty() || joinAttempts < 2)) ? s.lanAddress : s.address;
        if (!hostWorldJoinLocked && !s.amHost && s.launched && s.mode == RunState.Mode.HOST_WORLD && !target.isEmpty()
            && mc.player == null && System.currentTimeMillis() - lastJoinTry > 8000 && !connecting()) {
            lastJoinTry = System.currentTimeMillis();
            joinAttempts++;
            status("Заходим в мир хоста: " + target);
            Worlds.trackServerAddress(target);
            WorldLauncher.joinServer(target);
        }

        // до старта раунда игроки заморожены (мир открыт, но /go ещё не подтверждён всеми)
        boolean shouldFreeze = s.gameMode != Game.PILLARS && s.launched
            && (s.phase == RunState.Phase.IDLE || s.phase == RunState.Phase.COUNTDOWN);
        if (shouldFreeze && mc.player != null) {
            // Серверные эффекты замедляют, но прыжок всё ещё мог давать небольшой сдвиг.
            // Обнуляем скорость локального игрока каждый тик, пока раунд не начался.
            mc.player.setDeltaMovement(0.0, 0.0, 0.0);
            if (mc.getSingleplayerServer() != null && freezeTicks++ % 40 == 0) Freeze.apply();
        } else {
            freezeTicks = 0;
        }

        if (s.phase == RunState.Phase.COUNTDOWN && System.nanoTime() >= s.countdownEndNanos) {
            s.startNanos = s.countdownEndNanos;
            s.phase = RunState.Phase.RUNNING;
            chat("GO!");
            Freeze.release();
            if (mc.player != null && s.target != null) s.baseline = Inv.count(mc.player, s.target);
            if (mc.player != null && s.roundGame == Game.BINGO && s.chain.size() == s.cfgBingoSize * s.cfgBingoSize) s.bingoBase = Inv.counts(mc.player, s.chain);
            if (mc.player != null && s.roundGame == Game.DRAFT) {
                s.draftBase = new int[49];
                for (int i = 0; i < Math.min(s.cfgDraftSize * s.cfgDraftSize, s.draftGoals.size()); i++)
                    s.draftBase[i] = s.draftGoals.get(i).progressValue(mc);
            }
            if (s.roundGame == Game.BINGO) printCard();
            if (s.roundGame == Game.DRAFT && s.dpMatch) { DraftPlus.announceMatch(s); DraftPlus.applyEffects(s); }
            else if (s.roundGame == Game.DRAFT) chat("Драфт начался: " + DraftGoal.draftTypeName(s.cfgDraftType) + ". Карточка " + s.cfgDraftSize + "×" + s.cfgDraftSize + "; для победы нужно " + (s.cfgDraftSize * s.cfgDraftSize / 2 + 1) + " целей.");
            if (s.roundGame == Game.WAVES) Waves.start(mc, s);
            if (s.roundGame == Game.MANHUNT) Manhunt.start(mc, s);
            Chaos.apply(s.roundMod);
        }
        if (s.phase == RunState.Phase.RUNNING && s.roundGame == Game.SINGLE_SPEEDRUN && mc.player != null
            && !s.singleSpeedrunRecordSaved && Mc.currentScreen() != null
            && Mc.currentScreen().getClass().getSimpleName().contains("WinScreen")) {
            long elapsed = s.elapsedMs();
            s.singleSpeedrunFinalMs = elapsed;
            s.singleSpeedrunRecordSaved = true;
            s.phase = RunState.Phase.WAITING_RESULT;
            SpeedrunRecords.submitAsync(Config.databaseUrl, accountName(), elapsed);
            chat("Спидран завершён! Время: " + RunState.formatTime(elapsed) + ". Результат отправлен в отдельный топ; MMR не изменён.");
        }
        if (s.phase == RunState.Phase.RUNNING && s.roundMod >= 0 && ++reapplyTicks % 100 == 0) Chaos.apply(s.roundMod);
        if (s.phase == RunState.Phase.RUNNING && s.dpMatch && s.roundGame == Game.DRAFT && ++dpTicks % 100 == 0) DraftPlus.applyEffects(s);
        if (s.phase == RunState.Phase.RUNNING && mc.player != null && s.roundGame != Game.SINGLE_SPEEDRUN && s.target != null) roundTick(mc, s);
    }

    /** Собрана ли линия (строка, столбец или диагональ) на карточке n×n; в режиме full — вся карточка. */
    public static boolean bingoLine(boolean[] d, int n, boolean full) {
        if (full) {
            for (int i = 0; i < n * n; i++) if (!d[i]) return false;
            return true;
        }
        for (int r = 0; r < n; r++) {
            boolean ok = true;
            for (int c = 0; c < n; c++) if (!d[r * n + c]) { ok = false; break; }
            if (ok) return true;
        }
        for (int c = 0; c < n; c++) {
            boolean ok = true;
            for (int r = 0; r < n; r++) if (!d[r * n + c]) { ok = false; break; }
            if (ok) return true;
        }
        boolean d1 = true, d2 = true;
        for (int i = 0; i < n; i++) { if (!d[i * n + i]) d1 = false; if (!d[i * n + (n - 1 - i)]) d2 = false; }
        return d1 || d2;
    }

    /** Печатает карточку бинго в чат (/rr card). */
    public static void printCard() {
        RunState s = STATE;
        int n = s.cfgBingoSize;
        if (s.roundGame != Game.BINGO || s.chain.size() < n * n) { chat("Карточка есть только в раунде «Бинго»."); return; }
        for (int r = 0; r < n; r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < n; c++) {
                int i = r * n + c;
                sb.append(s.bingoDone[i] ? "[x] " : "[ ] ").append(ItemInfo.name(s.chain.get(i)));
                if (c < n - 1) sb.append("  |  ");
            }
            chat(sb.toString());
        }
    }

    /** Логика текущего раунда: спидран, конвейер, цепочка или бинго. */
    private static void roundTick(Minecraft mc, RunState s) {
        switch (s.roundGame) {
            case Game.CONVEYOR -> {
                long lastSlot = Math.max(0, s.cfgConvTotalMs / s.cfgConvSlotMs - 1);
                long slot = Math.min(s.elapsedMs() / s.cfgConvSlotMs, lastSlot);
                if (slot != s.slot) {
                    s.slot = slot;
                    s.slotDone = false;
                    s.target = ItemPicker.itemFor(s.roundSeed, (int) slot);
                    s.baseline = Inv.count(mc.player, s.target);
                    if (slot > 0) {
                        chat("Новый предмет: " + ItemInfo.name(s.target));
                        Snd.play(Snd.CHAOS_TICK, 1.3f, 0.9f);
                    }
                }
                if (s.slotDone) return;
                int c = Inv.count(mc.player, s.target);
                if (c < s.baseline) s.baseline = c;
                else if (c > s.baseline) {
                    s.slotDone = true;
                    s.myScore++;
                    lobby.reportScore(s.myScore, s.elapsedMs());
                    chat("Засчитано: " + ItemInfo.name(s.target) + ". Счёт: " + s.myScore + ". Ждите следующий предмет.");
                    Snd.play(Snd.CHAOS_DROP, 1.0f, 0.9f);
                }
            }
            case Game.WAVES -> Waves.tick(mc, s);
            case Game.MANHUNT -> Manhunt.tick(mc, s);
            case Game.CHAIN -> {
                int c = Inv.count(mc.player, s.target);
                if (c < s.baseline) { s.baseline = c; return; }
                if (c == s.baseline) return;
                s.chainIdx++;
                lobby.reportScore(s.chainIdx, s.elapsedMs());
                if (s.chainIdx >= s.chain.size()) {
                    s.phase = RunState.Phase.WAITING_RESULT;
                    lobby.reportWin(s.roundId, s.elapsedMs());
                } else {
                    s.target = s.chain.get(s.chainIdx);
                    s.baseline = Inv.count(mc.player, s.target);
                    chat("Цепочка " + s.chainIdx + "/" + s.chain.size() + ". Дальше: " + ItemInfo.name(s.target));
                    Snd.play(Snd.CHAOS_DROP, 1.0f, 0.9f);
                }
            }
            case Game.BINGO -> {
                int n = s.cfgBingoSize, cells = n * n;
                if (s.chain.size() < cells) return;
                int[] cnt = Inv.counts(mc.player, s.chain);
                boolean changed = false;
                for (int i = 0; i < cells; i++) {
                    if (s.bingoDone[i]) continue;
                    if (cnt[i] < s.bingoBase[i]) s.bingoBase[i] = cnt[i];
                    else if (cnt[i] > s.bingoBase[i]) {
                        s.bingoDone[i] = true;
                        changed = true;
                        chat("Бинго: есть " + ItemInfo.name(s.chain.get(i)));
                    }
                }
                if (changed) {
                    int done = 0;
                    for (int i = 0; i < cells; i++) if (s.bingoDone[i]) done++;
                    lobby.reportScore(done, s.elapsedMs());
                    Snd.play(Snd.CHAOS_DROP, 1.1f, 0.9f);
                    if (bingoLine(s.bingoDone, n, s.cfgBingoFull)) {
                        s.phase = RunState.Phase.WAITING_RESULT;
                        lobby.reportWin(s.roundId, s.elapsedMs());
                    }
                }
            }
            case Game.DRAFT -> {
                if (s.draftGoals.size() != s.cfgDraftSize * s.cfgDraftSize) return;
                for (int i = 0; i < s.cfgDraftSize * s.cfgDraftSize; i++) {
                    if (!"-".equals(s.draftOwners[i]) || s.draftClaimPending[i]) continue;
                    int progress = s.draftGoals.get(i).progressValue(mc);
                    if (progress < s.draftBase[i]) s.draftBase[i] = progress;
                    else if (progress > s.draftBase[i]) {
                        s.draftClaimPending[i] = true;
                        lobby.claimDraftGoal(i, s.roundId, System.currentTimeMillis() - hostClockOffsetMs);
                    }
                }
            }
            default -> {
                Item t = s.target;
                if (mc.player.getInventory().contains(stack -> stack.is(t))) {
                    s.phase = RunState.Phase.WAITING_RESULT;
                    lobby.reportWin(s.roundId, s.elapsedMs());
                }
            }
        }
    }
}
