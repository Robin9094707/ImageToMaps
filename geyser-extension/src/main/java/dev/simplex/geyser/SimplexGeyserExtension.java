package dev.simplex.geyser;

import dev.simplex.common.SlotLayout;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.block.custom.CustomBlockData;
import org.geysermc.geyser.api.block.custom.component.BoxComponent;
import org.geysermc.geyser.api.block.custom.component.CustomBlockComponents;
import org.geysermc.geyser.api.block.custom.component.GeometryComponent;
import org.geysermc.geyser.api.block.custom.component.MaterialInstance;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.PriorityOption;
import org.geysermc.geyser.api.util.CreativeCategory;
import org.geysermc.geyser.api.util.Identifier;

import java.util.Locale;

/**
 * Bedrock side of Simplex ImageToMap.
 *
 * <ul>
 *   <li>Registers one Bedrock custom block per {@link SlotLayout} block slot and overrides the matching Java
 *       mushroom / chorus plant state, so custom blocks are real blocks for Bedrock players (correct textures,
 *       sounds, names, geometry, breaking particles).</li>
 *   <li>Registers one Bedrock custom item per item slot, mapped through the Java {@code item_model} component.</li>
 *   <li>Sends the <b>latest</b> generated Bedrock pack to every Bedrock player while they join – so newly
 *       added blocks, models and sounds appear after a simple reconnect, no Geyser restart required.</li>
 *   <li>Injects the block/item translations (English + German) into Geyser's locale tables so Bedrock players
 *       see custom names in their own language.</li>
 * </ul>
 */
public final class SimplexGeyserExtension implements Extension {

    private ExtensionConfig config;
    private PackSource packSource;

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        config = ExtensionConfig.load(this);
        packSource = new PackSource(this, config);
        packSource.refreshNow();
        logger().info("Simplex Geyser extension loaded – " + SlotLayout.blockSlots().size() + " block slots, "
                + SlotLayout.ITEM_SLOTS + " item slots. Pack source: " + packSource.describe());
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        packSource.startPolling();
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (packSource != null) packSource.stop();
    }

    @Subscribe
    public void onDefineCustomBlocks(GeyserDefineCustomBlocksEvent event) {
        if (!config.customBlocks()) {
            logger().info("Custom blocks are disabled in the Simplex extension config.");
            return;
        }
        int registered = 0;
        for (SlotLayout.BlockSlot slot : SlotLayout.blockSlots()) {
            boolean transparent = slot.kind() == SlotLayout.Kind.TRANSPARENT;
            String renderMethod = transparent ? "alpha_test" : "opaque";

            CustomBlockComponents.Builder components = CustomBlockComponents.builder()
                    .displayName(slot.nameKey())
                    .destructibleByMining(transparent ? 0.6f : config.defaultBreakSeconds())
                    .friction(0.4f)
                    .lightDampening(transparent ? 0 : 15)
                    .collisionBox(BoxComponent.fullBox())
                    .selectionBox(BoxComponent.fullBox());
            for (String face : SlotLayout.FACES) {
                components.materialInstance(face, MaterialInstance.builder()
                        .texture(slot.textureKey(face))
                        .renderMethod(renderMethod)
                        .faceDimming(true)
                        .ambientOcclusion(!transparent)
                        .build());
            }
            components.materialInstance("*", MaterialInstance.builder()
                    .texture(slot.textureKey("north"))
                    .renderMethod(renderMethod)
                    .faceDimming(true)
                    .ambientOcclusion(!transparent)
                    .build());
            if (transparent) {
                // Geometry is defined by the generated pack, so glass panes, grates or furniture-style
                // block models can be swapped at runtime.
                components.geometry(GeometryComponent.builder().identifier(slot.geometryIdentifier()).build());
            }

            CustomBlockData data = CustomBlockData.builder()
                    .name(slot.bedrockName())
                    .includedInCreativeInventory(false)
                    .creativeCategory(CreativeCategory.CONSTRUCTION)
                    .components(components.build())
                    .build();
            try {
                event.register(data);
                event.registerOverride(slot.javaState(), data.defaultBlockState());
                registered++;
            } catch (Exception e) {
                logger().warning("Could not register custom block slot " + slot.index() + ": " + e.getMessage());
            }
        }
        logger().info("Registered " + registered + " Simplex custom block slots for Bedrock.");
    }

    @Subscribe
    public void onDefineCustomItems(GeyserDefineCustomItemsEvent event) {
        Identifier base = Identifier.of(SlotLayout.BASE_ITEM);
        int registered = 0;
        for (int i = 0; i < SlotLayout.ITEM_SLOTS; i++) {
            try {
                CustomItemDefinition definition = CustomItemDefinition.builder(
                                Identifier.of("geyser_custom", SlotLayout.bedrockItemName(i)),
                                Identifier.of(SlotLayout.NAMESPACE, SlotLayout.itemModelPath(i)))
                        .displayName("simplex.item.i" + i)
                        .bedrockOptions(CustomItemBedrockOptions.builder()
                                .icon(SlotLayout.itemIconKey(i))
                                .allowOffhand(true)
                                .displayHandheld(false)
                                .creativeCategory(CreativeCategory.CONSTRUCTION)
                                .creativeGroup("itemGroup.name.simplex"))
                        .build();
                event.register(base, definition);
                registered++;
            } catch (Exception e) {
                logger().warning("Could not register custom item slot " + i + ": " + e.getMessage());
                break;
            }
        }
        logger().info("Registered " + registered + " Simplex custom item slots for Bedrock.");
    }

    @Subscribe
    public void onLoadResourcePacks(SessionLoadResourcePacksEvent event) {
        ResourcePack pack = packSource.currentPack();
        if (pack == null) return;
        try {
            event.register(pack, PriorityOption.HIGH);
        } catch (Exception e) {
            logger().warning("Could not send Simplex pack to " + event.connection().bedrockUsername() + ": " + e.getMessage());
        }
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        String code = event.connection().languageCode();
        String locale = (code == null ? "en_us" : code).toLowerCase(Locale.ROOT);
        packSource.translations().inject(locale);
        // Geyser may still be downloading the vanilla locale for this language – inject again shortly after.
        packSource.scheduleLater(() -> packSource.translations().inject(locale), 5);
    }
}
