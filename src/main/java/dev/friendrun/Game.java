package dev.friendrun;

/** Режимы FriendRun, включая запуск встроенной карты «Столбы». */
public final class Game {
    public static final int SPEEDRUN = 0, CONVEYOR = 1, CHAIN = 2, BINGO = 3, WAVES = 4, MANHUNT = 5, PILLARS = 6, DRAFT = 7, DRAFT_PLUS = 8, SINGLE_SPEEDRUN = 9, COUNT = 10;   // id режимов = индекс в Ranking.MODE_KEYS (SINGLE_SPEEDRUN — без рейтинга, всегда последний)
    public static final long WAVES_CAP_MS = 30 * 60_000L;       // волны: максимум 30 минут
    public static final long SLOT_MS = 3 * 60_000L;              // конвейер: смена предмета каждые 3 минуты
    public static final long CONVEYOR_TOTAL_MS = 20 * 60_000L;   // конвейер: длительность раунда
    public static final int CHAIN_LEN = 5;                       // цепочка: сколько предметов

    private Game() {}

    public static String name(int g) {
        return switch (g) { case CONVEYOR -> "Конвейер"; case CHAIN -> "Цепочка"; case BINGO -> "Бинго"; case WAVES -> "Волны мобов"; case MANHUNT -> "Манхант"; case PILLARS -> "Столбы"; case DRAFT -> "Драфт"; case DRAFT_PLUS -> "Драфт+"; case SINGLE_SPEEDRUN -> "Одиночный спидран"; default -> "Спидран"; };
    }

    public static String desc(int g) {
        return switch (g) {
            case CONVEYOR -> "смена каждые 3 мин, 20 мин";
            case CHAIN -> "5 предметов по порядку";
            case BINGO -> "карточка 3×3, нужна линия";
            case WAVES -> "хардкор, очки за мобов и волны";
            case MANHUNT -> "беглецы против охотников";
            case PILLARS -> "готовая карта «Столбики», рейтинговая";
            case DRAFT -> "выбор целей и захват большинства";
            case DRAFT_PLUS -> "баны, роли, хаос-событие, MMR за вклад";
            case SINGLE_SPEEDRUN -> "Minecraft до титров; без рейтинга, только личный рекорд";
            default -> "первый получивший побеждает";
        };
    }
}
