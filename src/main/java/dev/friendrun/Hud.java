package dev.friendrun;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.Screen;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class Hud {
    private static final Set<Screen> SCREEN_HOOKED = Collections.newSetFromMap(new WeakHashMap<>());

    private Hud() {}

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("friendrun", "hud"), Hud::render);
        // ScreenEvents.afterExtract runs after the inventory UI has been extracted,
        // so the grids are drawn above the vanilla dim overlay instead of being dimmed with the world.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof InventoryScreen && SCREEN_HOOKED.add(screen)) {
                ScreenEvents.afterExtract(screen).register((currentScreen, graphics, mouseX, mouseY, tickProgress) ->
                    renderGridOverInventory(graphics, mouseX, mouseY));
            }
        });
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    /** Панель итога: кто победил (10 секунд после конца раунда). */
    private static void drawResult(GuiGraphicsExtractor g, Minecraft mc, RunState s) {
        if (s.resultUntilNanos == 0 || System.nanoTime() - s.resultUntilNanos >= 0) return;
        boolean mmr = !s.resultMmr.isEmpty();
        int w = Math.min(260, Gfx.guiWidth(g) - 20), h = mmr ? 88 : 64;
        int cx = Gfx.guiWidth(g) / 2, top = Gfx.guiHeight(g) / 5;
        int x1 = cx - w / 2;
        boolean pillars = s.resultMode == Game.PILLARS;
        boolean conveyor = s.resultMode == Game.CONVEYOR;
        boolean draw = conveyor && s.resultName.equals("—");
        Gfx.rect(g, x1 - 2, top - 2, x1 + w + 2, top + h + 2, 0xFFFFD700);
        Gfx.rect(g, x1, top, x1 + w, top + h, 0xEE101010);
        Gfx.centered(g, conveyor ? "КОНВЕЙЕР ЗАВЕРШЁН" : s.resultMode == Game.BINGO ? "БИНГО!" : s.resultMode == Game.DRAFT ? "ДРАФТ ЗАВЕРШЁН" : s.resultMode == Game.DRAFT_PLUS ? "ДРАФТ+ ЗАВЕРШЁН" : s.resultMode == Game.WAVES ? "ВОЛНЫ ЗАВЕРШЕНЫ" : s.resultMode == Game.MANHUNT ? "МАНХАНТ ЗАВЕРШЁН" : pillars ? "СТОЛБЫ: РАУНД ЗАВЕРШЁН" : "ЗАБЕГ ЗАВЕРШЁН", cx, top + 6, 0xFFFFD700);
        Gfx.centered(g, draw ? "Ничья: никто ничего не сдал" : (s.resultMode == Game.MANHUNT || pillars ? "Победили: " : s.resultMode == Game.DRAFT_PLUS && s.resultName.startsWith("Команда ") ? "Побеждает " : "Победитель: ") + s.resultName, cx, top + 20, 0xFFFFFFFF);
        if (pillars) {
            Gfx.centered(g, s.resultMs > 0 ? "Длительность: " + RunState.formatTime(s.resultMs) : "", cx, top + 32, 0xFFAAAAAA);
        } else if (conveyor) {
            Gfx.centered(g, draw ? "" : "Предметов сдано: " + s.resultScore, cx, top + 32, 0xFF55FF55);
        } else if (s.resultMode == Game.WAVES || s.resultMode == Game.DRAFT || s.resultMode == Game.DRAFT_PLUS) {
            Gfx.centered(g, (s.resultMode == Game.DRAFT || s.resultMode == Game.DRAFT_PLUS ? "Целей захвачено: " : "Очков: ") + s.resultScore, cx, top + 32, 0xFF55FF55);
            Gfx.centered(g, "Время: " + RunState.formatTime(s.resultMs), cx, top + 44, 0xFFAAAAAA);
        } else {
            Gfx.centered(g, "Время: " + RunState.formatTime(s.resultMs), cx, top + 32, 0xFF55FF55);
            Gfx.centered(g, s.resultItem.isEmpty() ? "" : "Предмет: " + s.resultItem, cx, top + 44, 0xFFAAAAAA);
        }
        if (mmr) {   // изменение рейтинга: режим и общий
            Gfx.centered(g, s.resultMmr, cx, top + 58, 0xFFFFD700);
            Gfx.centered(g, s.resultMmr2, cx, top + 70, 0xFFAAAAAA);
        }
    }

    /** Экран режима «Манхант»: роль, время, сколько беглецов осталось. */
    private static void drawManhunt(GuiGraphicsExtractor g, RunState s, int x, int y) {
        String me = FriendRunClient.myName();
        boolean runner = s.runners.contains(me);
        if (s.phase == RunState.Phase.COUNTDOWN) {
            long left = Math.max(0, (s.countdownEndNanos - System.nanoTime()) / 1_000_000L);
            Gfx.text(g, "Манхант — старт через " + (left / 1000 + 1), x, y, 0xFFFFDD55);
            Gfx.text(g, "Вы: " + (runner ? "БЕГЛЕЦ" : "ОХОТНИК"), x, y + 11, runner ? 0xFF55FF55 : 0xFFFF7777);
            return;
        }
        long el = s.elapsedMs();
        Gfx.text(g, "Вы: " + (runner ? "БЕГЛЕЦ" : "ОХОТНИК") + "  ·  осталось " + mmss(s.cfgManMin * 60_000L - el), x, y,
            runner ? 0xFF55FF55 : 0xFFFF7777);
        int ly = y + 11;
        int alive = 0, total = s.runners.size();
        for (RunState.PlayerInfo p : s.players) if (s.runners.contains(p.name()) && !p.dead()) alive++;
        Gfx.text(g, "Беглецов в живых: " + alive + "/" + total + "  (" + String.join(", ", s.runners) + ")", x, ly, 0xFFFFDD55);
        ly += 11;
        if (el < Manhunt.HEAD_MS)
            Gfx.text(g, runner ? "Бегите! Фора: " + mmss(Manhunt.HEAD_MS - el) : "Вы ослеплены, фора беглецов: " + mmss(Manhunt.HEAD_MS - el), x, ly, 0xFFFF7777);
        else {
            boolean dead = false;
            for (RunState.PlayerInfo p : s.players) if (p.name().equals(me) && p.dead()) dead = true;
            if (dead) Gfx.text(g, "Вы пойманы — наблюдаете", x, ly, 0xFF8C9AB0);
        }
        if (s.roundMod >= 0) Gfx.text(g, "Усложнение: " + Chaos.nameOf(s.roundMod), x, ly + 11, 0xFFFF7777);
    }

    /** Экран режима «Волны мобов»: волна, таймер, очки всех игроков. */
    private static void drawWaves(GuiGraphicsExtractor g, RunState s, int x, int y) {
        if (s.phase == RunState.Phase.COUNTDOWN) {
            long left = Math.max(0, (s.countdownEndNanos - System.nanoTime()) / 1_000_000L);
            Gfx.text(g, "Волны мобов — старт через " + (left / 1000 + 1), x, y, 0xFFFFDD55);
            Gfx.text(g, "Хардкор: одна жизнь", x, y + 11, 0xFFFF7777);
            return;
        }
        long el = s.elapsedMs();
        int wave = Waves.waveNo(el, s.cfgWaveSec);
        long next = Waves.untilNext(el, s.cfgWaveSec);
        Gfx.text(g, wave == 0 ? "Подготовка: " + mmss(next) : "Волна " + wave + "  ·  следующая через " + mmss(next), x, y, 0xFF55FF55);
        int ly = y + 11;
        String me = FriendRunClient.myName();
        for (RunState.PlayerInfo p : s.players) {
            String line = p.name() + ": " + p.score() + (p.dead() ? "  (погиб)" : "");
            Gfx.text(g, line, x, ly, p.dead() ? 0xFFFF7777 : p.name().equals(me) ? 0xFFFFDD55 : 0xFFFFFFFF);
            ly += 11;
        }
        for (RunState.PlayerInfo p : s.players)
            if (p.name().equals(me) && p.dead()) { Gfx.text(g, "Вы погибли — наблюдаете за живым игроком", x, ly, 0xFF8C9AB0); ly += 11; }
        if (s.roundMod >= 0) Gfx.text(g, "Усложнение: " + Chaos.nameOf(s.roundMod), x, ly, 0xFFFF7777);
    }


    /** Показывает название предмета из сетки Бинго/Драфта при наведении мышью. */
    private static void drawBingoTooltip(GuiGraphicsExtractor g, Minecraft mc, RunState s,
                                         int gridX, int gridY, int n, int cell, int pad) {
        double scale = mc.getWindow().getGuiScale();
        if (scale <= 0) return;
        int mx = (int) (mc.mouseHandler.xpos() / scale);
        int my = (int) (mc.mouseHandler.ypos() / scale);
        drawBingoTooltipAt(g, s, gridX, gridY, n, cell, mx, my);
    }

    private static void drawBingoTooltipAt(GuiGraphicsExtractor g, RunState s,
                                           int gridX, int gridY, int n, int cell, int mx, int my) {
        int goalCount = s.roundGame == Game.DRAFT ? s.draftGoals.size() : s.chain.size();
        for (int i = 0; i < n * n && i < goalCount; i++) {
            int cx = gridX + (i % n) * (cell + 2);
            int cy = gridY + (i / n) * (cell + 2);
            if (mx >= cx && mx < cx + cell && my >= cy && my < cy + cell) {
                String name = s.roundGame == Game.DRAFT && i < s.draftGoals.size()
                    ? s.draftGoals.get(i).displayName() : ItemInfo.name(s.chain.get(i));
                int width = Math.min(Gfx.guiWidth(g) - 8, Minecraft.getInstance().font.width(name) + 8);
                int tx = Math.min(mx + 8, Gfx.guiWidth(g) - width - 4);
                int ty = Math.min(my + 8, Gfx.guiHeight(g) - 16);
                Gfx.rect(g, tx, ty, tx + width, ty + 13, 0xF0100010);
                Gfx.rect(g, tx, ty, tx + width, ty + 1, 0x505000FF);
                Gfx.text(g, name, tx + 4, ty + 3, 0xFFFFFFFF);
                return;
            }
        }
    }

    /** Draw Bingo/Draft grids after the inventory and its dim layer. */
    private static void renderGridOverInventory(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        RunState s = FriendRunClient.STATE;
        if (!FriendRunClient.inLobby() || mc.player == null || s.phase == RunState.Phase.IDLE) return;
        if (s.roundGame != Game.BINGO && s.roundGame != Game.DRAFT) return;

        // Force a fresh top stratum so inventory widgets and their dimmed background stay underneath.
        g.nextStratum();
        int x = 6, ly = 6;
        if (s.phase == RunState.Phase.COUNTDOWN) ly += 11;
        if (s.roundGame == Game.BINGO) {
            ly += 12; // timer line
            int n = s.cfgBingoSize, cell = n == 3 ? 22 : 18, pad = (cell - 16) / 2;
            for (int i = 0; i < n * n && i < s.chain.size(); i++) {
                int cx = x + (i % n) * (cell + 2), cy = ly + (i / n) * (cell + 2);
                boolean done = s.bingoDone[i];
                Ui.frame(g, cx, cy, cell, cell, done ? Ui.GREEN_BG : 0xCC101A2B, done ? Ui.GREEN : Ui.LINE);
                ItemStack stack = ItemInfo.stack(s.chain.get(i));
                if (stack != null) Gfx.item(g, stack, cx + pad, cy + pad);
                else Gfx.itemTexture(g, s.chain.get(i), cx + pad, cy + pad, 16);
                if (done) Icons.draw(g, Icons.CHECK, cx + cell - 8, cy + 1, 1, Ui.GREEN);
            }
            drawBingoTooltipAt(g, s, x, ly, n, cell, mouseX, mouseY);
        } else {
            ly += 12; // timer only; claimed cells already show who completed each goal
            int n = s.cfgDraftSize, cell = n == 5 ? 18 : n == 6 ? 15 : 13, pad = n == 5 ? 1 : 0;
            String me = FriendRunClient.myName();
            for (int i = 0; i < n * n && i < s.draftGoals.size(); i++) {
                int cx = x + (i % n) * (cell + 2), cy = ly + (i / n) * (cell + 2);
                String owner = s.draftOwners[i];
                boolean mine = owner != null && owner.equals(me);
                boolean claimed = owner != null && !owner.equals("-") && !owner.isEmpty();
                int fill = mine ? Ui.GREEN_BG : claimed ? 0xCC3A2028 : 0xCC101A2B;
                int border = mine ? Ui.GREEN : claimed ? 0xFFCE6B72 : Ui.LINE;
                Ui.frame(g, cx, cy, cell, cell, fill, border);
                var icon = s.draftGoals.get(i).icon();
                if (icon != null) {
                    ItemStack stack = ItemInfo.stack(icon);
                    if (stack != null) Gfx.item(g, stack, cx + pad, cy + pad);
                    else Gfx.itemTexture(g, icon, cx + pad, cy + pad, 16);
                }
                if (claimed) Icons.draw(g, Icons.CHECK, cx + cell - 7, cy + 1, 1, mine ? Ui.GREEN : 0xFFFF7777);
            }
            drawBingoTooltipAt(g, s, x, ly, n, cell, mouseX, mouseY);
        }
    }

    /** Отдельный HUD одиночного Minecraft-спидрана: предстартовый отсчёт и RTA-таймер. */
    private static void drawSingleSpeedrun(GuiGraphicsExtractor g, RunState s, int x, int y) {
        if (s.phase == RunState.Phase.COUNTDOWN) {
            long left = Math.max(0, s.countdownEndNanos - System.nanoTime());
            long seconds = (left + 999_999_999L) / 1_000_000_000L;
            Gfx.rect(g, x - 3, y - 3, x + 154, y + 35, 0xCC101A2B);
            Gfx.text(g, "ОДИНОЧНЫЙ СПИДРАН", x + 4, y + 2, 0xFFFFD45A);
            Gfx.text(g, "СТАРТ ЧЕРЕЗ", x + 4, y + 15, 0xFFFFFFFF);
            Gfx.text(g, Long.toString(seconds), x + 112, y + 10, 0xFF55FF55);
            return;
        }

        if (s.phase == RunState.Phase.RUNNING || s.phase == RunState.Phase.WAITING_RESULT) {
            long elapsed = s.phase == RunState.Phase.WAITING_RESULT
                ? s.singleSpeedrunFinalMs : s.elapsedMs();
            String timer = RunState.formatSpeedrunTime(elapsed);
            int width = Math.max(126, timer.length() * 10 + 18);
            Gfx.rect(g, x - 3, y - 3, x + width, y + 25, 0xCC101A2B);
            Gfx.text(g, "SPEEDRUN  ·  RTA", x + 5, y + 1, 0xFFAAAAAA);
            Gfx.text(g, timer, x + 5, y + 12,
                s.phase == RunState.Phase.WAITING_RESULT ? 0xFFFFD45A : 0xFF55FF55);
            if (s.phase == RunState.Phase.WAITING_RESULT)
                Gfx.text(g, "ЗАБЕГ ЗАВЕРШЁН", x + width + 7, y + 12, 0xFFFFD45A);
        }
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        RunState s = FriendRunClient.STATE;
        if (!FriendRunClient.inLobby() || mc.player == null) return;
        // «Столбы» использует правила встроенной карты и не участвует в системе /go FriendRun.
        if (s.gameMode == Game.PILLARS) return;

        int x = 6, y = 6;
        drawResult(g, mc, s);
        if (s.gameMode == Game.DRAFT_PLUS && s.launched) DraftPlus.drawHud(g, s);   // роль и событие матча
        if (s.phase == RunState.Phase.IDLE) {
            if (s.launched) {
                boolean draftFinished = ((s.gameMode == Game.DRAFT && s.resultMode == Game.DRAFT) || (s.gameMode == Game.DRAFT_PLUS && s.resultMode == Game.DRAFT_PLUS))
                    && !s.resultName.isBlank();
                String status = s.myReady ? "Ожидаем остальных…"
                    : draftFinished ? "Драфт завершён" : "Напишите /go когда готовы";
                Gfx.text(g, status, x, y, 0xFFFFDD55);
                Gfx.text(g, "Игроки: " + s.playersLine(), x, y + 11, 0xFFFFFFFF);
                Gfx.text(g, "Игра: " + Game.name(s.gameMode), x, y + 22, 0xFF8C9AB0);
            }
            return;
        }
        if (s.roundGame == Game.WAVES) { drawWaves(g, s, x, y); return; }
        if (s.roundGame == Game.MANHUNT) { drawManhunt(g, s, x, y); return; }
        // У одиночного спидрана нет предметной цели: не пропускаем его HUD из-за target == null.
        if (s.roundGame == Game.SINGLE_SPEEDRUN) {
            drawSingleSpeedrun(g, s, x, y);
            return;
        }
        boolean gridMode = s.roundGame == Game.BINGO || s.roundGame == Game.DRAFT;
        if (s.target == null && !gridMode) return;

        // В режимах с сеткой сама сетка показывает цели — отдельная цель сверху лишняя.
        if (!gridMode) {
            ItemStack icon = ItemInfo.stack(s.target);
            if (icon != null) Gfx.item(g, icon, x, y);
            else Gfx.itemTexture(g, s.target, x, y, 16);
            Gfx.text(g, ItemInfo.name(s.target), x + 20, y + 4, 0xFFFFFFFF);
        }

        int ly = gridMode ? y : y + 22;
        if (s.phase == RunState.Phase.COUNTDOWN) {
            long left = Math.max(0, (s.countdownEndNanos - System.nanoTime()) / 1_000_000L);
            Gfx.text(g, "Старт через " + (left / 1000 + 1), x, ly, 0xFFFFDD55);
            ly += 11;
        } else if (s.roundGame == Game.CONVEYOR) {
            long el = s.elapsedMs();
            Gfx.text(g, "Осталось " + mmss(s.cfgConvTotalMs - el) + "  ·  смена через "
                + mmss(s.cfgConvSlotMs - el % s.cfgConvSlotMs), x, ly, 0xFF55FF55);
            ly += 11;
            String me = FriendRunClient.myName();
            StringBuilder sb = new StringBuilder("Счёт: ");
            for (RunState.PlayerInfo p : s.players)
                sb.append(p.name()).append(' ').append(p.name().equals(me) ? s.myScore : p.score()).append("   ");
            Gfx.text(g, sb.toString(), x, ly, 0xFFFFDD55);
            ly += 11;
            if (s.slotDone) { Gfx.text(g, "Предмет сдан — ждите следующий", x, ly, 0xFF8C9AB0); ly += 11; }
        } else if (s.roundGame == Game.BINGO) {
            Gfx.text(g, RunState.formatTime(s.elapsedMs()), x, ly, 0xFF55FF55);
            ly += 12;
            int n = s.cfgBingoSize, cell = n == 3 ? 22 : 18, pad = (cell - 16) / 2;
            if (!(Mc.currentScreen() instanceof InventoryScreen)) {
                for (int i = 0; i < n * n && i < s.chain.size(); i++) {
                    int cx0 = x + (i % n) * (cell + 2), cy0 = ly + (i / n) * (cell + 2);
                    boolean done = s.bingoDone[i];
                    Ui.frame(g, cx0, cy0, cell, cell, done ? Ui.GREEN_BG : 0xCC101A2B, done ? Ui.GREEN : Ui.LINE);
                    ItemStack st = ItemInfo.stack(s.chain.get(i));
                    if (st != null) Gfx.item(g, st, cx0 + pad, cy0 + pad);
                    else Gfx.itemTexture(g, s.chain.get(i), cx0 + pad, cy0 + pad, 16);
                    if (done) Icons.draw(g, Icons.CHECK, cx0 + cell - 8, cy0 + 1, 1, Ui.GREEN);
                }
            }
            ly += n * (cell + 2) + 2;
        } else if (s.roundGame == Game.DRAFT) {
            Gfx.text(g, RunState.formatTime(s.elapsedMs()), x, ly, 0xFF55FF55);
            ly += 12;
            String me = FriendRunClient.myName();
            int n = s.cfgDraftSize, cell = n == 5 ? 18 : n == 6 ? 15 : 13, pad = n == 5 ? 1 : 0;
            if (!(Mc.currentScreen() instanceof InventoryScreen)) {
                for (int i = 0; i < n * n && i < s.draftGoals.size(); i++) {
                    int cx0 = x + (i % n) * (cell + 2), cy0 = ly + (i / n) * (cell + 2);
                    String owner = s.draftOwners[i];
                    boolean mine = owner != null && owner.equals(me);
                    boolean claimed = owner != null && !owner.equals("-") && !owner.isEmpty();
                    int fill = mine ? Ui.GREEN_BG : claimed ? 0xCC3A2028 : 0xCC101A2B;
                    int border = mine ? Ui.GREEN : claimed ? 0xFFCE6B72 : Ui.LINE;
                    Ui.frame(g, cx0, cy0, cell, cell, fill, border);
                    var icon = s.draftGoals.get(i).icon();
                    if (icon != null) {
                        ItemStack stack = ItemInfo.stack(icon);
                        if (stack != null) Gfx.item(g, stack, cx0 + pad, cy0 + pad);
                        else Gfx.itemTexture(g, icon, cx0 + pad, cy0 + pad, 16);
                    }
                    if (claimed) Icons.draw(g, Icons.CHECK, cx0 + cell - 7, cy0 + 1, 1, mine ? Ui.GREEN : 0xFFFF7777);
                }
            }
            ly += n * (cell + 2) + 2;
        } else if (s.roundGame == Game.CHAIN) {
            Gfx.text(g, RunState.formatTime(s.elapsedMs()), x, ly, 0xFF55FF55);
            ly += 11;
            for (int i = 0; i < s.chain.size(); i++) {
                boolean done = i < s.chainIdx, cur = i == s.chainIdx;
                if (done) Icons.draw(g, Icons.CHECK, x, ly + 1, 1, Ui.GREEN);
                else if (cur) Icons.draw(g, Icons.PLAY, x, ly + 1, 1, Ui.GOLD);
                else Icons.draw(g, Icons.DOT, x, ly + 2, 1, Ui.MUTED);
                Gfx.text(g, (i + 1) + ". " + ItemInfo.name(s.chain.get(i)), x + 10, ly,
                    cur ? 0xFFFFFFFF : done ? 0xFF55FF55 : 0xFF8C9AB0);
                ly += 11;
            }
            String me = FriendRunClient.myName();
            StringBuilder sb = new StringBuilder();
            for (RunState.PlayerInfo p : s.players)
                sb.append(p.name()).append(' ').append(p.name().equals(me) ? s.chainIdx : p.score()).append('/').append(s.chain.size()).append("   ");
            Gfx.text(g, sb.toString(), x, ly, 0xFFFFDD55);
            ly += 11;
        } else {
            Gfx.text(g, RunState.formatTime(s.elapsedMs()), x, ly, 0xFF55FF55);
            ly += 11;
        }

        if (s.roundMod >= 0) Gfx.text(g, "Усложнение: " + Chaos.nameOf(s.roundMod), x, ly, 0xFFFF7777);
    }
}
