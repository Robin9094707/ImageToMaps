package dev.simplex.itm.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A form with input fields. Java: Paper dialog, Bedrock: CustomForm. */
public final class InputMenu implements Menu {

    public sealed interface Field permits Text, Toggle, Dropdown, Slider {
        String key();
        Component label();
    }

    public record Text(String key, Component label, String initial, String placeholder, int maxLength) implements Field {}
    public record Toggle(String key, Component label, boolean initial) implements Field {}
    /** @param ids values returned for the options, @param labels visible option texts */
    public record Dropdown(String key, Component label, List<String> ids, List<Component> labels, int initial) implements Field {}
    public record Slider(String key, Component label, float min, float max, float step, float initial) implements Field {}

    /** Submitted values keyed by field key. Dropdowns return the option id. */
    public static final class Values {
        private final Map<String, Object> values = new HashMap<>();

        public void put(String key, Object value) { values.put(key, value); }

        public String text(String key) {
            Object o = values.get(key);
            return o == null ? "" : String.valueOf(o).trim();
        }

        public boolean bool(String key) {
            Object o = values.get(key);
            return o instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(o));
        }

        public float number(String key, float def) {
            Object o = values.get(key);
            if (o instanceof Number n) return n.floatValue();
            try { return Float.parseFloat(String.valueOf(o)); } catch (Exception e) { return def; }
        }
    }

    private final Component title;
    private Component body = Component.empty();
    private final List<Field> fields = new ArrayList<>();
    private Component submitLabel;
    private Component cancelLabel;
    private BiConsumer<Player, Values> onSubmit = (p, v) -> {};
    private Consumer<Player> onCancel = p -> {};

    public InputMenu(Component title) {
        this.title = title;
    }

    public InputMenu body(Component body) { this.body = body; return this; }
    public InputMenu field(Field field) { fields.add(field); return this; }
    public InputMenu buttons(Component submit, Component cancel) { this.submitLabel = submit; this.cancelLabel = cancel; return this; }
    public InputMenu onSubmit(BiConsumer<Player, Values> handler) { this.onSubmit = handler; return this; }
    public InputMenu onCancel(Consumer<Player> handler) { this.onCancel = handler; return this; }

    public Component title() { return title; }
    public Component body() { return body; }
    public List<Field> fields() { return fields; }
    public Component submitLabel() { return submitLabel; }
    public Component cancelLabel() { return cancelLabel; }
    public BiConsumer<Player, Values> submit() { return onSubmit; }
    public Consumer<Player> cancel() { return onCancel; }
}
