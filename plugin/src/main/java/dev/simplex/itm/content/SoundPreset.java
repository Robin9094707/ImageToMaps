package dev.simplex.itm.content;

import java.util.Locale;

/** Block sound sets. Java keys are vanilla sound events, {@code bedrock} is the Bedrock blocks.json sound type. */
public enum SoundPreset {
    STONE("block.stone", "stone"),
    WOOD("block.wood", "wood"),
    CHERRY_WOOD("block.cherry_wood", "cherry_wood"),
    BAMBOO_WOOD("block.bamboo_wood", "bamboo_wood"),
    NETHER_WOOD("block.nether_wood", "nether_wood"),
    GLASS("block.glass", "glass"),
    METAL("block.metal", "metal"),
    COPPER("block.copper", "copper"),
    DEEPSLATE("block.deepslate", "deepslate"),
    DEEPSLATE_BRICKS("block.deepslate_bricks", "deepslate_bricks"),
    POLISHED_DEEPSLATE("block.polished_deepslate", "polished_deepslate"),
    TUFF("block.tuff", "tuff"),
    CALCITE("block.calcite", "calcite"),
    AMETHYST("block.amethyst_block", "amethyst_block"),
    MUD_BRICKS("block.mud_bricks", "mud_bricks"),
    NETHER_BRICKS("block.nether_bricks", "nether_brick"),
    BASALT("block.basalt", "basalt"),
    WOOL("block.wool", "cloth"),
    GRAVEL("block.gravel", "gravel"),
    SAND("block.sand", "sand"),
    GRASS("block.grass", "grass"),
    MOSS("block.moss", "moss_block"),
    LODESTONE("block.lodestone", "lodestone"),
    NETHERITE("block.netherite_block", "netherite");

    public final String java;
    public final String bedrock;

    SoundPreset(String java, String bedrock) {
        this.java = java;
        this.bedrock = bedrock;
    }

    public String place() { return "minecraft:" + java + ".place"; }
    public String breakSound() { return "minecraft:" + java + ".break"; }
    public String hit() { return "minecraft:" + java + ".hit"; }
    public String step() { return "minecraft:" + java + ".step"; }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static SoundPreset parse(String s) {
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return STONE; }
    }
}
