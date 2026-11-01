package dev.friendrun;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;

/** Выполнение команды на сервере мира с возвратом результата (например, значения счёта scoreboard). */
public final class Cmd {
    private Cmd() {}

    /** Результат команды (целое) или null, если команда не сработала (например, счёт ещё не задан). */
    public static CompletableFuture<Integer> query(String cmd) {
        CompletableFuture<Integer> f = new CompletableFuture<>();
        Object srv = Minecraft.getInstance().getSingleplayerServer();
        if (!(srv instanceof Executor ex)) { f.complete(null); return f; }
        ex.execute(() -> {
            try {
                Object src = call(srv, "createCommandSourceStack");
                Object quiet = call(src, "withSuppressedOutput");
                if (quiet != null) src = quiet;
                Object commands = call(srv, "getCommands");
                Object disp = call(commands, "getDispatcher");
                if (disp == null) { f.complete(null); return; }
                Method exec = disp.getClass().getMethod("execute", String.class, Object.class);
                Object r = exec.invoke(disp, cmd, src);
                f.complete(r instanceof Integer i ? i : null);
            } catch (Throwable t) {
                f.complete(null);
            }
        });
        return f;
    }

    /** Выполняет код на потоке сервера мира и возвращает результат (null при ошибке). */
    public static <T> CompletableFuture<T> onServer(java.util.function.Supplier<T> sup) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Object srv = Minecraft.getInstance().getSingleplayerServer();
        if (!(srv instanceof Executor ex)) { f.complete(null); return f; }
        ex.execute(() -> {
            try { f.complete(sup.get()); } catch (Throwable t) { f.complete(null); }
        });
        return f;
    }

    private static Object call(Object o, String name) throws Exception {
        if (o == null) return null;
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) { m.setAccessible(true); return m.invoke(o); }
        }
        return null;
    }
}
