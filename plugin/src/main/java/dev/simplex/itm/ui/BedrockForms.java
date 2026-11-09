package dev.simplex.itm.ui;

import dev.simplex.itm.SimplexPlugin;
import dev.simplex.itm.util.Lang;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native Bedrock forms (Floodgate / Geyser). Only loaded when one of them is installed. */
final class BedrockForms {

    private BedrockForms() {}

    // ------------------------------------------------------------------------------------------------------
    // Bedrock: Floodgate / Geyser forms
    // ------------------------------------------------------------------------------------------------------

    static boolean open(SimplexPlugin plugin, Player player, Menu menu) {
        return switch (menu) {
            case ChoiceMenu c -> bedrockChoice(plugin, player, c);
            case InputMenu i -> bedrockInput(plugin, player, i);
            case ConfirmMenu c -> bedrockConfirm(plugin, player, c);
        };
    }

    private static boolean bedrockChoice(SimplexPlugin plugin, Player player, ChoiceMenu menu) {
        List<Consumer<Player>> actions = new ArrayList<>();
        SimpleForm.Builder form = SimpleForm.builder().title(Lang.legacy(menu.title())).content(Lang.legacy(menu.body()));
        List<ChoiceMenu.Option> all = new ArrayList<>(menu.options());
        all.addAll(menu.toolbar());
        for (ChoiceMenu.Option o : all) {
            String text = Lang.legacy(o.label());
            if (o.lore() != null && !o.lore().isEmpty()) text += "\n§8" + Lang.plain(o.lore().getFirst());
            String image = o.bedrockImage();
            if (image != null && !image.isBlank()) {
                form.button(text, image.startsWith("http") ? FormImage.Type.URL : FormImage.Type.PATH, image);
            } else {
                form.button(text);
            }
            actions.add(o.action());
        }
        if (menu.back() != null) {
            form.button(Lang.legacy(plugin.lang().get(player, "menu.back")), FormImage.Type.PATH, "textures/ui/arrow_dark_left_stretch");
            actions.add(p -> menu.back().accept(p));
        }
        form.validResultHandler(response -> {
            int id = response.clickedButtonId();
            if (id >= 0 && id < actions.size() && actions.get(id) != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.sfx().play(player, "click");
                    actions.get(id).accept(player);
                });
            }
        });
        return plugin.platform().sendForm(player, form);
    }

    private static boolean bedrockInput(SimplexPlugin plugin, Player player, InputMenu menu) {
        CustomForm.Builder form = CustomForm.builder().title(Lang.legacy(menu.title()));
        if (!menu.body().equals(Component.empty())) form.label(Lang.legacy(menu.body()));
        for (InputMenu.Field field : menu.fields()) {
            switch (field) {
                case InputMenu.Text t -> form.input(Lang.legacy(t.label()), t.placeholder() == null ? "" : t.placeholder(), t.initial() == null ? "" : t.initial());
                case InputMenu.Toggle t -> form.toggle(Lang.legacy(t.label()), t.initial());
                case InputMenu.Dropdown d -> {
                    List<String> labels = new ArrayList<>();
                    for (Component c : d.labels()) labels.add(Lang.legacy(c));
                    if (labels.isEmpty()) labels.add("-");
                    form.dropdown(Lang.legacy(d.label()), labels, Math.max(0, Math.min(d.initial(), labels.size() - 1)));
                }
                case InputMenu.Slider s -> form.slider(Lang.legacy(s.label()), s.min(), s.max(), s.step(), s.initial());
            }
        }
        form.validResultHandler(response -> {
            InputMenu.Values values = new InputMenu.Values();
            for (InputMenu.Field field : menu.fields()) {
                switch (field) {
                    case InputMenu.Text t -> values.put(t.key(), response.next());
                    case InputMenu.Toggle t -> values.put(t.key(), response.next());
                    case InputMenu.Dropdown d -> {
                        Object idx = response.next();
                        int i = idx instanceof Number n ? n.intValue() : 0;
                        values.put(d.key(), i >= 0 && i < d.ids().size() ? d.ids().get(i) : "");
                    }
                    case InputMenu.Slider s -> values.put(s.key(), response.next());
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.sfx().play(player, "click");
                menu.submit().accept(player, values);
            });
        });
        form.closedOrInvalidResultHandler(() -> Bukkit.getScheduler().runTask(plugin, () -> menu.cancel().accept(player)));
        return plugin.platform().sendForm(player, form);
    }

    private static boolean bedrockConfirm(SimplexPlugin plugin, Player player, ConfirmMenu menu) {
        ModalForm.Builder form = ModalForm.builder()
                .title(Lang.legacy(menu.title()))
                .content(Lang.legacy(menu.body()))
                .button1(Lang.legacy(menu.yes()))
                .button2(Lang.legacy(menu.no()))
                .validResultHandler(response -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (response.clickedFirst()) menu.onYes().accept(player);
                    else menu.onNo().accept(player);
                }));
        return plugin.platform().sendForm(player, form);
    }
}
