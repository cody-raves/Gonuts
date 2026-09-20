package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Keeps vanilla focus, keyboard activation, and narration with a quieter skin. */
final class CockpitButton extends Button {
    private boolean selected;
    private boolean navigation;

    private CockpitButton(int x, int y, int width, int height, Component label, OnPress action) {
        super(x, y, width, height, label, action, DEFAULT_NARRATION);
    }

    public static Button.Builder builder(Component label, OnPress action) {
        return new Button.Builder(label, action) {
            @Override public Button build() {
                Button geometry = super.build();
                return new CockpitButton(geometry.getX(), geometry.getY(), geometry.getWidth(),
                        geometry.getHeight(), label, action);
            }
        };
    }

    void navigation(boolean selected) {
        this.navigation = true;
        this.selected = selected;
    }

    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        var font = Minecraft.getInstance().font;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean highlight = active && (isHovered() || isFocused());
        int background = selected ? 0xFF354034 : highlight ? UiTheme.HOVER : UiTheme.SURFACE;
        boolean border = !navigation || highlight;
        UiTheme.rounded(g, x, y, w, h, 4, border ? (isFocused() ? UiTheme.GOLD : UiTheme.EDGE)
                : active ? background : UiTheme.INSET);
        if (border) UiTheme.rounded(g, x + 1, y + 1, w - 2, h - 2, 3, active ? background : UiTheme.INSET);
        if (selected) g.fill(x, y + 3, x + 2, y + h - 3, UiTheme.GOLD);
        String label = UiTheme.fit(font, getMessage().getString(), w - 12);
        int tx = navigation ? x + 8 : x + (w - font.width(label)) / 2;
        g.text(font, label, tx, y + (h - font.lineHeight) / 2,
                !active ? UiTheme.MUTED : selected ? UiTheme.GOLD : UiTheme.TEXT, false);
        if (isHovered() && font.width(getMessage()) > w - 12) {
            g.setTooltipForNextFrame(font, getMessage(), mouseX, mouseY);
        }
    }
}
