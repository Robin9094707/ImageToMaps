package dev.simplex.itm.content;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.Locale;

/** Preferred tool of a custom block. Each tool has a vanilla reference block used to compute mining speed. */
public enum ToolType {
    NONE(Material.GLASS),
    PICKAXE(Material.STONE),
    AXE(Material.OAK_PLANKS),
    SHOVEL(Material.DIRT),
    HOE(Material.HAY_BLOCK),
    SHEARS(Material.WHITE_WOOL);

    private final Material reference;
    private BlockData data;

    ToolType(Material reference) {
        this.reference = reference;
    }

    public BlockData reference() {
        if (data == null) data = reference.createBlockData();
        return data;
    }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static ToolType parse(String s) {
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return NONE; }
    }
}
