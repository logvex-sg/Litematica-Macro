package com.schematicdropper.hud;

import com.schematicdropper.drop.DropTask;
import com.schematicdropper.material.MaterialEntry;
import com.schematicdropper.material.MaterialManager;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.List;

/**
 * Progress readout for the current drop.
 *
 * <p>The element is a singleton whose contents are refreshed by the module each
 * tick, rather than the element reaching into the module. That keeps the drawing
 * path free of game state and makes the element safe to render when no drop has
 * ever run.
 */
public class SchematicDropperHud extends HudElement {
    /** Registers the element type with Meteor's HUD system. */
    public static final HudElementInfo<SchematicDropperHud> INFO = new HudElementInfo<>(
        Hud.GROUP, "schematic-dropper", "Schematic Dropper",
        "Shows drop progress for the Schematic Dropper module.",
        SchematicDropperHud::new);

    /** The live element, or {@code null} when the HUD has no instance. */
    private static SchematicDropperHud instance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Text scale.")
        .defaultValue(1.0)
        .range(0.5, 2.0)
        .sliderRange(0.5, 2.0)
        .decimalPlaces(2)
        .build());

    private final Setting<Integer> backgroundOpacity = sgGeneral.add(new IntSetting.Builder()
        .name("background-opacity")
        .description("Opacity of the background, from 0 to 255.")
        .defaultValue(120)
        .range(0, 255)
        .sliderRange(0, 255)
        .build());

    private final Setting<Boolean> compact = sgGeneral.add(new BoolSetting.Builder()
        .name("compact")
        .description("Show a single summary line instead of the material table.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> onlyRemaining = sgGeneral.add(new BoolSetting.Builder()
        .name("only-remaining")
        .description("Hide materials that are already complete.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> showTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("show-target")
        .description("Show where drops are being sent.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxLines = sgGeneral.add(new IntSetting.Builder()
        .name("max-lines")
        .description("Largest number of materials listed before the rest are summarised.")
        .defaultValue(12)
        .range(1, 40)
        .sliderRange(1, 30)
        .build());

    // ----------------------------------------------------------- REFRESH STATE

    private String stateLabel = "Idle";
    private String targetLabel = "Feet";
    private double progress;
    private int totalDropped;
    private int totalRequired;
    private int blockedCount;
    private List<MaterialEntry> rows = List.of();
    private int hiddenRows;

    public SchematicDropperHud() {
        super(INFO);
    }

    /** Makes sure the element exists and is active. */
    public static void ensureElement() {
        if (instance != null) return;

        Hud hud = Hud.get();

        // The user may already have added the element; reuse it rather than
        // creating a second copy.
        for (HudElement element : hud) {
            if (element instanceof SchematicDropperHud existing) {
                instance = existing;
                return;
            }
        }

        hud.add(INFO, 10, 10);

        for (HudElement element : hud) {
            if (element instanceof SchematicDropperHud added) {
                instance = added;
                return;
            }
        }
    }

    /** Removes the element, used when the module is switched off. */
    public static void removeElement() {
        if (instance != null) instance.remove();
        instance = null;
    }

    /** Pushes a fresh snapshot into the element. Called from the module's tick. */
    public static void refresh(DropTask task, MaterialManager materials, String targetLabel) {
        if (instance == null) return;
        instance.apply(task, materials, targetLabel);
    }

    /**
     * Copies the module's HUD settings into the element.
     *
     * <p>The module owns these so they appear in one place alongside the rest of
     * the options, and so they are saved with the module. The element's own
     * settings stay as the fallback for when it is used standalone.
     */
    public static void applyOptions(double scale, int backgroundOpacity, boolean compact,
                                    boolean onlyRemaining, boolean showTarget) {
        if (instance == null) return;

        instance.scale.set(scale);
        instance.backgroundOpacity.set(backgroundOpacity);
        instance.compact.set(compact);
        instance.onlyRemaining.set(onlyRemaining);
        instance.showTarget.set(showTarget);
    }

    private void apply(DropTask task, MaterialManager materials, String targetLabel) {
        this.stateLabel = task.state().toString();
        this.targetLabel = targetLabel;
        this.progress = materials.overallProgress();
        this.totalDropped = materials.totalDropped();
        this.totalRequired = materials.totalRequired();
        this.blockedCount = materials.blockedCount();

        boolean remainingOnly = onlyRemaining.get();
        List<MaterialEntry> all = materials.all();

        java.util.List<MaterialEntry> shown = new java.util.ArrayList<>();
        for (MaterialEntry entry : all) {
            if (remainingOnly && entry.isComplete()) continue;
            shown.add(entry);
        }

        int limit = compact.get() ? 0 : Math.max(1, maxLines.get());
        if (limit > 0 && shown.size() > limit) {
            hiddenRows = shown.size() - limit;
            shown = shown.subList(0, limit);
        } else {
            hiddenRows = 0;
        }

        this.rows = List.copyOf(shown);
    }

    // --------------------------------------------------------------- RENDERING

    @Override
    public void tick(HudRenderer renderer) {
        double s = scale.get();
        double lineHeight = renderer.textHeight(false, s) + 2 * s;

        if (compact.get()) {
            setSize(renderer.textWidth(summaryLine(), false, s), lineHeight);
            return;
        }

        double width = renderer.textWidth("SchematicDropper", false, s);
        width = Math.max(width, renderer.textWidth(summaryLine(), false, s));
        width = Math.max(width, renderer.textWidth(stateLine(), false, s));

        for (MaterialEntry row : rows) {
            width = Math.max(width, renderer.textWidth(materialLine(row), false, s));
        }

        int lines = 3 + rows.size() + (hiddenRows > 0 ? 1 : 0) + (showTarget.get() ? 1 : 0);
        setSize(width, lineHeight * lines);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();
        double x = getX();
        double y = getY();

        int alpha = backgroundOpacity.get();
        if (alpha > 0) {
            renderer.quad(x, y, getWidth(), getHeight(),
                new Color(0, 0, 0, Math.min(255, alpha)));
        }

        Color text = Color.WHITE;
        Color heading = new Color(90, 160, 255);
        Color done = new Color(120, 220, 120);
        Color missing = new Color(255, 120, 120);
        Color blocked = new Color(255, 190, 90);

        if (compact.get()) {
            renderer.text(summaryLine(), x, y, text, false, s);
            return;
        }

        renderer.text("SchematicDropper", x, y, heading, false, s);
        y += renderer.textHeight(false, s) + 2 * s;

        for (MaterialEntry row : rows) {
            Color color = row.status() == MaterialEntry.Status.MISSING ? missing
                : row.isBlocked() ? blocked
                : row.isComplete() ? done
                : text;
            renderer.text(materialLine(row), x, y, color, false, s);
            y += renderer.textHeight(false, s) + 2 * s;
        }

        if (hiddenRows > 0) {
            renderer.text("... " + hiddenRows + " more", x, y, text, false, s);
            y += renderer.textHeight(false, s) + 2 * s;
        }

        renderer.text(summaryLine(), x, y, text, false, s);
        y += renderer.textHeight(false, s) + 2 * s;

        renderer.text(stateLine(), x, y, text, false, s);
    }

    private String materialLine(MaterialEntry row) {
        String name = shortName(row.id());
        if (row.status() == MaterialEntry.Status.MISSING) {
            return pad(name) + " missing";
        }
        if (row.isBlocked()) {
            String reason = row.blockedReason() == null ? "blocked" : row.blockedReason().name().toLowerCase(java.util.Locale.ROOT);
            return pad(name) + padLeft(row.dropped() + " / " + row.required()) + " " + reason;
        }
        return pad(name) + padLeft(row.dropped() + " / " + row.required())
            + " " + percent(row.progress());
    }

    private String summaryLine() {
        String base = "Total: " + totalDropped + " / " + totalRequired
            + "  " + percent(progress);
        return blockedCount > 0 ? base + "  blocked: " + blockedCount : base;
    }

    private String stateLine() {
        String base = "Status: " + stateLabel;
        if (!showTarget.get()) return base;
        return base + "  Target: " + targetLabel;
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0);
    }

    /** Strips the namespace so rows stay readable. */
    private static String shortName(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static String pad(String value) {
        return value.length() >= 18 ? value + " " : value + " ".repeat(18 - value.length());
    }

    private static String padLeft(String value) {
        return value.length() >= 11 ? value + " " : " ".repeat(11 - value.length()) + value;
    }
}
