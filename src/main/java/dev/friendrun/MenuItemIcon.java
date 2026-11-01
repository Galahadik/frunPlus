package dev.friendrun;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Item icons for pre-world menus only. Gameplay HUD rendering is deliberately untouched. */
public final class MenuItemIcon {
    private MenuItemIcon() {}

    public static boolean draw(GuiGraphicsExtractor g, Item item, int x, int y, int size) {
        // Once a world is loaded, prefer the real baked item model.
        ItemStack stack = ItemInfo.stack(item);
        if (stack != null) {
            Gfx.item(g, stack, x, y);
            return true;
        }
        // Before a world exists Minecraft cannot safely create ItemStack instances.
        // Use a menu-only texture fallback rather than changing the in-game renderer.
        return Gfx.texture(g, ItemTex.of(item), x, y, size);
    }
}
