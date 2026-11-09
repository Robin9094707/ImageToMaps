package dev.simplex.itm.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/** Yes / no question. Java: Paper confirmation dialog, Bedrock: ModalForm. */
public record ConfirmMenu(Component title, Component body, Component yes, Component no,
                          Consumer<Player> onYes, Consumer<Player> onNo) implements Menu {
}
