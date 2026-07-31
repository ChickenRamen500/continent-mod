package com.continentgen.client;

import com.continentgen.world.ContinentBiomeSource;
import com.continentgen.world.ContinentChunkGenerator;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.world.GeneratorOptionsHolder;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.biome.Biome;

/**
 * The "Customize" screen for the Continent world preset.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHAT IT DOES:
 *   Shows a NUMERIC TEXT FIELD where the user types the desired world size
 *   in blocks (default 300000). This replaces fixed presets, so any size
 *   can be entered — e.g. 500000, 100000, 30000, 3000...
 *
 *   The PNG maps are ALWAYS 3000×3000 pixels (a fixed constant of this mod).
 *   The chosen worldSize determines how those pixels scale to blocks:
 *       blocksPerPixel = worldSize / 3000
 *       300000 → 100 b/px,  30000 → 10 b/px,  3000 → 1 b/px, ...
 *
 *   On "Done", a NEW ContinentBiomeSource + ContinentChunkGenerator are built
 *   with the chosen size and applied via worldCreator.applyModifier(...),
 *   which replaces the overworld's chunk generator.
 *
 * API NOTES (Minecraft 1.20.1):
 *   • DrawableHelper was REMOVED in 1.20. All rendering goes through
 *     DrawContext: context.drawCenteredTextWithShadow(...), etc.
 *   • Screen.render(...) now receives a DrawContext (NOT a MatrixStack).
 *   • Screen.renderBackground(DrawContext) — single-arg overload (1.20.1).
 *   • TextFieldWidget is in net.minecraft.client.gui.widget. It handles its
 *     own key input via Screen's charTyped/keyPressed routing — no manual
 *     repeat-event toggling is needed in 1.20.1.
 * ─────────────────────────────────────────────────────────────────────────
 */
public class ContinentCustomizeScreen extends Screen {

    /** The PNG maps are always 3000×3000 pixels — a constant of this mod. */
    public static final int IMAGE_SIZE = 3000;

    /** Default world size shown when the screen opens. */
    public static final int DEFAULT_WORLD_SIZE = 300_000;

    /** Minimum allowed world size (smaller would be degenerate). */
    public static final int MIN_WORLD_SIZE = 30;

    /** Maximum allowed world size (sanity cap). */
    public static final int MAX_WORLD_SIZE = 9_999_999;

    /** Max digit count for the text field (matches MAX_WORLD_SIZE). */
    private static final int FIELD_MAX_LENGTH = 7;

    private static final int FIELD_WIDTH = 200;
    private static final int FIELD_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 320;
    private static final int BUTTON_HEIGHT = 20;

    private final CreateWorldScreen parent;
    private final GeneratorOptionsHolder generatorOptionsHolder;

    /** Numeric text field for entering the world size. */
    private TextFieldWidget sizeField;

    /** Raw text of the field, preserved across init() re-calls (window resize). */
    private String sizeFieldText;

    /** Last valid parsed value (used for the live blocks/pixel display). */
    private int selectedWorldSize;

    /** Error message to display below the field (empty when input is valid). */
    private Text errorText = Text.literal("");

    public ContinentCustomizeScreen(CreateWorldScreen parent,
                                    GeneratorOptionsHolder holder,
                                    int currentWorldSize) {
        super(Text.translatable("continentgen.customize.title"));
        this.parent = parent;
        this.generatorOptionsHolder = holder;
        this.selectedWorldSize = currentWorldSize;
    }

    // ═════════════════════════════════════════════════════════════════════
    // INIT
    // ═════════════════════════════════════════════════════════════════════

    @Override
    protected void init() {
        int centerX = this.width / 2;

        // ─── Numeric text field for world size ─────────────────────────
        int fieldY = this.height / 2 - 30;
        sizeField = new TextFieldWidget(
            this.textRenderer,
            centerX - FIELD_WIDTH / 2, fieldY,
            FIELD_WIDTH, FIELD_HEIGHT,
            Text.translatable("continentgen.customize.map_size")
        );
        sizeField.setMaxLength(FIELD_MAX_LENGTH);
        // Accept only digits (or empty, while the user is typing).
        sizeField.setTextPredicate(s -> s == null || s.isEmpty() || s.matches("\\d+"));
        // Restore text if we were re-initialised (e.g. window resized).
        if (sizeFieldText != null) {
            sizeField.setText(sizeFieldText);
        } else {
            sizeField.setText(String.valueOf(selectedWorldSize));
        }
        // Live-parse on every keystroke.
        sizeField.setChangedListener(text -> {
            sizeFieldText = text;
            onSizeChanged(text);
        });
        this.addDrawableChild(sizeField);

        // Give the field initial focus so the user can type immediately.
        this.setFocused(sizeField);

        // ─── Done button ───────────────────────────────────────────────
        int actionY = this.height / 2 + 50;
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("continentgen.customize.done"),
                button -> onDone())
            .dimensions(centerX - BUTTON_WIDTH / 2 - 1, actionY,
                        BUTTON_WIDTH / 2 - 1, BUTTON_HEIGHT)
            .build());

        // ─── Cancel button ──────────────────────────────────────────────
        this.addDrawableChild(ButtonWidget.builder(
                Text.translatable("continentgen.customize.cancel"),
                button -> onCancel())
            .dimensions(centerX + 1, actionY,
                        BUTTON_WIDTH / 2 - 1, BUTTON_HEIGHT)
            .build());
    }

    @Override
    public void removed() {
        // Nothing to clean up — TextFieldWidget handles its own state.
    }

    // ═════════════════════════════════════════════════════════════════════
    // INPUT VALIDATION
    // ═════════════════════════════════════════════════════════════════════

    private void onSizeChanged(String text) {
        if (text == null || text.isEmpty()) {
            errorText = Text.translatable("continentgen.customize.error.empty")
                .formatted(Formatting.RED);
            return;
        }
        try {
            int val = Integer.parseInt(text);
            if (val < MIN_WORLD_SIZE) {
                errorText = Text.translatable("continentgen.customize.error.too_small",
                    MIN_WORLD_SIZE).formatted(Formatting.RED);
                return;
            }
            if (val > MAX_WORLD_SIZE) {
                errorText = Text.translatable("continentgen.customize.error.too_big",
                    MAX_WORLD_SIZE).formatted(Formatting.RED);
                return;
            }
            errorText = Text.literal("");
            selectedWorldSize = val;
        } catch (NumberFormatException e) {
            errorText = Text.translatable("continentgen.customize.error.invalid")
                .formatted(Formatting.RED);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ACTIONS
    // ═════════════════════════════════════════════════════════════════════

    private void onDone() {
        // Re-validate strictly before applying.
        String text = sizeField.getText();
        if (text == null || text.isEmpty()) {
            errorText = Text.translatable("continentgen.customize.error.empty")
                .formatted(Formatting.RED);
            return;
        }
        int val;
        try {
            val = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            errorText = Text.translatable("continentgen.customize.error.invalid")
                .formatted(Formatting.RED);
            return;
        }
        if (val < MIN_WORLD_SIZE) {
            errorText = Text.translatable("continentgen.customize.error.too_small",
                MIN_WORLD_SIZE).formatted(Formatting.RED);
            return;
        }
        if (val > MAX_WORLD_SIZE) {
            errorText = Text.translatable("continentgen.customize.error.too_big",
                MAX_WORLD_SIZE).formatted(Formatting.RED);
            return;
        }
        selectedWorldSize = val;

        try {
            final int newWorldSize = this.selectedWorldSize;

            // Dynamic biome registry lookup from the holder's combined registries.
            RegistryWrapper.Impl<Biome> biomeLookup = generatorOptionsHolder
                .getCombinedRegistryManager()
                .getWrapperOrThrow(RegistryKeys.BIOME);

            // Default seed — vanilla overrides this with the real world seed
            // at creation time. The codec default is 0L.
            long seed = 0L;

            // Build new ContinentBiomeSource + ContinentChunkGenerator with the
            // chosen worldSize.
            final ContinentBiomeSource newBs = new ContinentBiomeSource(
                biomeLookup, seed, newWorldSize);
            final ContinentChunkGenerator newGen = new ContinentChunkGenerator(newBs, seed);

            // Apply via the parent's WorldCreator: replace the overworld's
            // chunk generator with the new one.
            parent.getWorldCreator().applyModifier(
                (dynamicRegistryManager, dimensionsHolder) ->
                    dimensionsHolder.with(dynamicRegistryManager, newGen)
            );

            this.client.setScreen(parent);
        } catch (Throwable t) {
            System.err.println("[ContinentCustomizeScreen] Failed to apply changes: " + t);
            t.printStackTrace();
            this.client.setScreen(parent);
        }
    }

    private void onCancel() {
        this.client.setScreen(parent);
    }

    @Override
    public void close() {
        // ESC key — behave like Cancel (return to parent screen).
        this.client.setScreen(parent);
    }

    // ═════════════════════════════════════════════════════════════════════
    // RENDERING (DrawContext — 1.20.1 API)
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 1.20.1: renderBackground takes a single DrawContext argument.
        this.renderBackground(context);

        // Draw children (text field + buttons).
        super.render(context, mouseX, mouseY, delta);

        // ─── Title ─────────────────────────────────────────────────────
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
            this.width / 2, 24, 0xFFFFFF);

        // ─── Description (wrapped) ─────────────────────────────────────
        Text description = Text.translatable("continentgen.customize.description")
            .formatted(Formatting.GRAY);
        int descY = 44;
        for (OrderedText line : this.textRenderer.wrapLines(description, this.width - 80)) {
            int lineWidth = this.textRenderer.getWidth(line);
            context.drawText(this.textRenderer, line,
                (this.width - lineWidth) / 2, descY, 0xA0A0A0, false);
            descY += this.textRenderer.fontHeight + 1;
        }

        // ─── Label above the text field ───────────────────────────────
        Text label = Text.translatable("continentgen.customize.map_size_label")
            .formatted(Formatting.WHITE);
        context.drawCenteredTextWithShadow(this.textRenderer, label,
            this.width / 2, this.height / 2 - 50, 0xFFFFFF);

        // ─── Blocks/pixel info line (below the field) ─────────────────
        double bpp = selectedWorldSize / (double) IMAGE_SIZE;
        String bppText = String.format(
            "Map: %dx%d px  ->  World: %dx%d blocks  (1 px ~ %.4g blocks)",
            IMAGE_SIZE, IMAGE_SIZE, selectedWorldSize, selectedWorldSize, bpp);
        Text bppLine = Text.literal(bppText).formatted(Formatting.AQUA);
        context.drawCenteredTextWithShadow(this.textRenderer, bppLine,
            this.width / 2, this.height / 2 - 4, 0xFFFFFF);

        // ─── Hint (default / min / max) ───────────────────────────────
        Text hint = Text.translatable("continentgen.customize.map_size_hint",
            DEFAULT_WORLD_SIZE, MIN_WORLD_SIZE, MAX_WORLD_SIZE)
            .formatted(Formatting.DARK_GRAY);
        context.drawCenteredTextWithShadow(this.textRenderer, hint,
            this.width / 2, this.height / 2 + 12, 0xFFFFFF);

        // ─── Error message (if any) ───────────────────────────────────
        String errStr = errorText.getString();
        if (errStr != null && !errStr.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, errorText,
                this.width / 2, this.height / 2 + 28, 0xFF5555);
        }
    }
}
