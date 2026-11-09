package dev.simplex.itm.content.blocks;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.content.BlockDef;
import dev.simplex.itm.content.ToolType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Custom blocks are mushroom blocks / chorus plants for the Java client, which would break almost instantly.
 * While a player mines a custom block we scale their {@code block_break_speed} attribute so the real mining time
 * matches the configured hardness and preferred tool (the client predicts this correctly – no fake animations).
 */
public final class BreakSpeed {

    private final NamespacedKey key;

    public BreakSpeed(SimplexPlugin plugin) {
        this.key = new NamespacedKey(plugin, "break_speed");
    }

    public void apply(Player player, Block block, BlockDef def) {
        clear(player);
        ItemStack tool = player.getInventory().getItemInMainHand();
        float hostHardness = block.getType() == Material.CHORUS_PLANT ? 0.4f : 0.2f;
        float hostSpeed = Math.max(1f, block.getBlockData().getDestroySpeed(tool, true));
        boolean correct = def.tool != ToolType.NONE && def.tool.reference().isPreferredTool(tool);
        float targetSpeed = correct ? Math.max(1f, def.tool.reference().getDestroySpeed(tool, true)) : 1f;
        boolean harvest = !def.requiresTool || correct;
        double wanted = def.hardness <= 0 ? 1000 : targetSpeed / def.hardness / (harvest ? 30.0 : 100.0);
        double current = hostSpeed / hostHardness / 30.0;
        double multiplier = wanted / current;
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null || Math.abs(multiplier - 1) < 0.01) return;
        attr.addTransientModifier(new AttributeModifier(key, multiplier - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    public void clear(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr != null) attr.removeModifier(key);
    }
}
