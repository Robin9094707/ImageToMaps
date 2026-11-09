package dev.simplex.itm.ui;

/**
 * Platform independent menu description. A menu is rendered as
 * <ul>
 *   <li>a chest inventory (Java, {@link ChoiceMenu}) or a native Paper dialog with text fields, toggles,
 *       dropdowns and sliders (Java, {@link InputMenu} / {@link ConfirmMenu}),</li>
 *   <li>a native Bedrock form via Floodgate/Geyser (SimpleForm / CustomForm / ModalForm).</li>
 * </ul>
 * Menus are always built for one specific viewer, so all texts are already in the viewer's language.
 */
public sealed interface Menu permits ChoiceMenu, InputMenu, ConfirmMenu {
}
