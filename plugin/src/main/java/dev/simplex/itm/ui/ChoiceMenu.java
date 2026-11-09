package dev.simplex.itm.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A list of buttons. Java: paginated chest GUI, Bedrock: SimpleForm. */
public final class ChoiceMenu implements Menu {

    /**
     * @param icon         item shown in the chest GUI
     * @param bedrockImage Bedrock form image: a resource pack path ({@code textures/...}) or an https URL, may be null
     */
    public record Option(Component label, List<Component> lore, ItemStack icon, String bedrockImage, Consumer<Player> action) {}

    private final Component title;
    private Component body = Component.empty();
    private final List<Option> options = new ArrayList<>();
    private final List<Option> toolbar = new ArrayList<>();
    private Consumer<Player> back;
    private int rows = 6;

    public ChoiceMenu(Component title) {
        this.title = title;
    }

    public ChoiceMenu body(Component body) { this.body = body; return this; }
    public ChoiceMenu option(Option option) { options.add(option); return this; }
    /** Extra buttons shown in the bottom row of the chest GUI (and appended on Bedrock). */
    public ChoiceMenu tool(Option option) { toolbar.add(option); return this; }
    public ChoiceMenu back(Consumer<Player> back) { this.back = back; return this; }
    public ChoiceMenu rows(int rows) { this.rows = Math.max(2, Math.min(6, rows)); return this; }

    public Component title() { return title; }
    public Component body() { return body; }
    public List<Option> options() { return options; }
    public List<Option> toolbar() { return toolbar; }
    public Consumer<Player> back() { return back; }
    public int rows() { return rows; }
}
