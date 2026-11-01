package dev.friendrun;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Пиксельные иконки (рисуются прямоугольниками, без картинок-ресурсов). '#' — закрашенный пиксель. */
public final class Icons {
    private Icons() {}

    public static final String[] CROWN = {"#...#...#", "##.###.##", "#########", "#########", "#########"};
    public static final String[] SEARCH = {".###...", "#...#..", "#...#..", "#...#..", ".###...", "....##.", ".....##"};
    public static final String[] CHECK = {"......#", ".....##", "#...##.", "##.##..", ".###...", "..#...."};
    public static final String[] DOT = {".###.", "#####", "#####", "#####", ".###."};
    public static final String[] PERSON = {"..###..", ".#####.", "..###..", "#######", "#######", "#######"};
    public static final String[] LOCK = {".#####.", "#.....#", "#.....#", "#######", "#######", "###.###", "#######", "#######"};
    public static final String[] CHEV_R = {"#...", ".#..", "..#.", "...#", "..#.", ".#..", "#..."};
    public static final String[] CHEV_L = {"...#", "..#.", ".#..", "#...", ".#..", "..#.", "...#"};
    public static final String[] WORLD_ONE = {".#####.", "#.....#", "#.###.#", "#.###.#", "#.###.#", "#.....#", ".#####."};
    public static final String[] WORLD_MANY = {"###.###", "#.#.#.#", "###.###"};
    public static final String[] GEAR = {"#.###.#", ".#####.", "###.###", "##...##", "###.###", ".#####.", "#.###.#"};
    public static final String[] DICE = {"#######", "#.#.#.#", "#.....#", "#..#..#", "#.....#", "#.#.#.#", "#######"};
    public static final String[] PLUS = {"...#...", "...#...", "...#...", "#######", "...#...", "...#...", "...#..."};
    public static final String[] PLAY = {"#....", "##...", "###..", "####.", "###..", "##...", "#...."};
    public static final String[] CROSS = {"#...#", ".#.#.", "..#..", ".#.#.", "#...#"};
    public static final String[] TRI_UP = {"...#...", "..###..", ".#####.", "#######"};
    public static final String[] TRI_DOWN = {"#######", ".#####.", "..###..", "...#..."};

    // Иконки режимов: 7×7 пикселей, масштабируются отдельно в меню выбора.
    public static final String[] MODE_SPEEDRUN = {"...#...", "..##...", ".#####.", "...##..", "..##...", ".##....", "##....."};
    public static final String[] MODE_CONVEYOR = {".......", "#..#..#", "##.##.#", "#######", "#.##.##", "#..#..#", "......."};
    public static final String[] MODE_CHAIN = {"..###..", ".#...#.", "#.....#", "#.....#", ".#...#.", "..###..", "......."};
    public static final String[] MODE_BINGO = {"#######", "#.#.#.#", "#######", "#.#.#.#", "#######", "#.#.#.#", "#######"};
    public static final String[] MODE_WAVES = {"..###..", ".#####.", "##.#.##", "#######", ".#.#.#.", "..#.#..", ".#...#."};
    public static final String[] MODE_MANHUNT = {"...#...", "..###..", ".#####.", "###.###", "..###..", "..#.#..", ".#...#."};
    public static final String[] MODE_PILLARS = {"..#...#", "..#...#", "..#...#", "#######", "#..#..#", "#..#..#", "#######"};
    public static final String[] MODE_DRAFT_PLUS = {"###.###", "#.#.#.#", "###.###", "...#...", "###.###", "#.#.#.#", "###.###"};
    public static final String[] MODE_DRAFT = {"#######", "#.#.#.#", "#######", "#.#.#.#", "#######", "#.#.#.#", "#######"};

    public static String[] mode(int mode) {
        return switch (mode) {
            case Game.CONVEYOR -> MODE_CONVEYOR;
            case Game.CHAIN -> MODE_CHAIN;
            case Game.BINGO -> MODE_BINGO;
            case Game.WAVES -> MODE_WAVES;
            case Game.MANHUNT -> MODE_MANHUNT;
            case Game.PILLARS -> MODE_PILLARS;
            case Game.DRAFT -> MODE_DRAFT;
            case Game.DRAFT_PLUS -> MODE_DRAFT_PLUS;
            case Game.SINGLE_SPEEDRUN -> MODE_SPEEDRUN;
            default -> MODE_SPEEDRUN;
        };
    }

    public static int w(String[] b) { return b[0].length(); }
    public static int h(String[] b) { return b.length; }

    public static void draw(GuiGraphicsExtractor g, String[] bmp, int x, int y, int scale, int color) {
        for (int r = 0; r < bmp.length; r++) {
            String row = bmp[r];
            int c = 0;
            while (c < row.length()) {
                if (row.charAt(c) != '#') { c++; continue; }
                int s = c;
                while (c < row.length() && row.charAt(c) == '#') c++;
                Gfx.rect(g, x + s * scale, y + r * scale, x + c * scale, y + (r + 1) * scale, color);
            }
        }
    }
}
