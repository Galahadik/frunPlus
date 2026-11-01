package dev.friendrun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

/**
 * Находит текстуру предмета в ресурсах игры. Нужна для главного меню, где new ItemStack(...) ещё нельзя
 * (компоненты не привязаны), поэтому иконку берём прямо из textures/item/*.png и textures/block/*.png.
 */
public final class ItemTex {
    private static final Map<Item, Optional<Identifier>> CACHE = new HashMap<>();
    private static final String[] SUFFIXES = {"_stairs", "_slab", "_fence_gate", "_fence", "_button", "_pressure_plate", "_wall"};

    private ItemTex() {}

    public static Identifier of(Item item) {
        return CACHE.computeIfAbsent(item, ItemTex::find).orElse(null);
    }

    private static Optional<Identifier> find(Item item) {
        var key = BuiltInRegistries.ITEM.getKey(item);
        String ns = key.getNamespace(), p = key.getPath();
        List<String> c = new ArrayList<>();
        c.add("item/" + p);
        c.add("block/" + p);
        for (String suf : SUFFIXES) {
            if (p.endsWith(suf)) {
                String base = p.substring(0, p.length() - suf.length());
                c.add("block/" + base + "_planks");
                c.add("block/" + base);
                c.add("block/" + base + "_block");
                c.add("block/" + base + "s");
                break;
            }
        }
        c.add("block/" + p + "_top");
        c.add("block/" + p + "_side");
        c.add("block/" + p + "_front");
        for (String s : c) {
            try {
                Identifier id = Identifier.fromNamespaceAndPath(ns, "textures/" + s + ".png");
                if (Minecraft.getInstance().getResourceManager().getResource(id).isPresent()) return Optional.of(id);
            } catch (RuntimeException ignored) {}
        }
        return Optional.empty();
    }
}
