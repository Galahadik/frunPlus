package dev.friendrun;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

public final class Commands {
    private Commands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("go").executes(ctx -> { FriendRunClient.go(); return 1; }));
            // /rr top — общий рейтинг; /rr top speedrun|conveyor|chain|bingo|waves|manhunt|pillars — рейтинг режима
            var top = ClientCommands.literal("top").executes(ctx -> { FriendRunClient.printTop(0); return 1; });
            for (int i = 0; i < Ranking.modeCount(); i++) {
                final int scope = i + 1;
                top = top.then(ClientCommands.literal(Ranking.key(i)).executes(ctx -> { FriendRunClient.printTop(scope); return 1; }));
            }
            dispatcher.register(ClientCommands.literal("rr")
                .then(ClientCommands.literal("leave").executes(ctx -> {
                    FriendRunClient.leave(); FriendRunClient.chat("Вы вышли из лобби."); return 1; }))
                .then(ClientCommands.literal("domain")
                    .then(ClientCommands.argument("address", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> {
                            FriendRunClient.setDomainManual(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "address"));
                            return 1; })))
                .then(ClientCommands.literal("game")
                    .then(ClientCommands.literal("speedrun").executes(ctx -> { FriendRunClient.setGameMode(Game.SPEEDRUN); return 1; }))
                    .then(ClientCommands.literal("conveyor").executes(ctx -> { FriendRunClient.setGameMode(Game.CONVEYOR); return 1; }))
                    .then(ClientCommands.literal("chain").executes(ctx -> { FriendRunClient.setGameMode(Game.CHAIN); return 1; }))
                    .then(ClientCommands.literal("bingo").executes(ctx -> { FriendRunClient.setGameMode(Game.BINGO); return 1; }))
                    .then(ClientCommands.literal("waves").executes(ctx -> { FriendRunClient.setGameMode(Game.WAVES); return 1; }))
                    .then(ClientCommands.literal("manhunt").executes(ctx -> { FriendRunClient.setGameMode(Game.MANHUNT); return 1; })))
                .then(ClientCommands.literal("card").executes(ctx -> { FriendRunClient.printCard(); return 1; }))
                .then(ClientCommands.literal("chaos")
                    .then(ClientCommands.literal("list").executes(ctx -> {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Chaos.COUNT; i++) {
                            sb.append(i + 1).append(") ").append(Chaos.nameOf(i)).append("; ");
                            if ((i + 1) % 6 == 0 || i == Chaos.COUNT - 1) { FriendRunClient.chat(sb.toString()); sb.setLength(0); }
                        }
                        return 1; }))
                    .then(ClientCommands.literal("off").executes(ctx -> { Chaos.clear(); FriendRunClient.chat("Усложнение снято."); return 1; }))
                    .then(ClientCommands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 99))
                        .executes(ctx -> { Chaos.test(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n")); return 1; })))
                .then(ClientCommands.literal("setup").executes(ctx -> { FriendRunClient.openSetup(); return 1; }))   // настройка матча Драфта+
                .then(top)
                .then(ClientCommands.literal("status").executes(ctx -> {
                    var st = FriendRunClient.STATE;
                    FriendRunClient.chat("Фаза: " + st.phase + ", игроки: "
                        + st.playersLine());
                    return 1; })));
        });
    }
}
