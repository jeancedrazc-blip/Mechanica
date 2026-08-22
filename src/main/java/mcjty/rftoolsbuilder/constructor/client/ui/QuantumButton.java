package mcjty.rftoolsbuilder.constructor.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** Lightweight themed button used by the Quantum Tools UI kit. */
public final class QuantumButton extends AbstractButton {
    private final Runnable action;
    private final BooleanSupplier selected;
    private final int accent;

    public QuantumButton(int x, int y, int width, int height, Component message, Runnable action) {
        this(x, y, width, height, message, action, () -> false, QuantumUiTheme.AMBER);
    }

    public QuantumButton(int x, int y, int width, int height, Component message, Runnable action,
                         BooleanSupplier selected) {
        this(x, y, width, height, message, action, selected, QuantumUiTheme.AMBER);
    }

    public QuantumButton(int x, int y, int width, int height, Component message, Runnable action,
                         BooleanSupplier selected, int accent) {
        super(x, y, width, height, message);
        this.action = action;
        this.selected = selected == null ? () -> false : selected;
        this.accent = accent;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        if (active && action != null) action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean selectedNow = selected.getAsBoolean();
        boolean hovered = isHoveredOrFocused();
        QuantumUiTheme.buttonSurface(graphics, getX(), getY(), getWidth(), getHeight(), active, hovered, selectedNow, accent);

        var font = Minecraft.getInstance().font;
        int textColor = QuantumUiTheme.buttonText(active, hovered, selectedNow);
        int tx = getX() + (getWidth() - font.width(getMessage())) / 2;
        int ty = getY() + (getHeight() - 8) / 2;
        graphics.text(font, getMessage(), tx, ty, textColor, false);
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
