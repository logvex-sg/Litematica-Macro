package com.schematicdropper;

import com.schematicdropper.drop.CreativeInventoryHandler;
import com.schematicdropper.drop.DropTargetManager;
import com.schematicdropper.drop.DropTask;
import com.schematicdropper.material.ImportFormat;
import com.schematicdropper.material.MaterialManager;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * Every user-facing option, in one place.
 *
 * <p>Holds the {@link Setting} fields and exposes them as plain values. The
 * module reads these each tick rather than caching them, so a change in the GUI
 * takes effect on the next tick without any invalidation logic.
 */
public final class SchematicDropperSettings {
    /** Where the material list comes from. */
    public enum Source {
        LITEMATICA("Litematica placement"),
        SCHEMATIC_FILE("Schematic file"),
        MATERIAL_LIST("Imported material list");

        private final String title;

        Source(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final SettingGroup general;
    private final SettingGroup speed;
    private final SettingGroup target;
    private final SettingGroup hud;

    public final Setting<Source> source;
    public final Setting<String> schematicPath;
    public final Setting<String> materialListPath;
    public final Setting<Boolean> useSelectedPlacement;
    public final Setting<Boolean> ignoreAir;

    public final Setting<Integer> itemsPerSecond;
    public final Setting<Integer> maxDropsPerTick;
    public final Setting<Integer> minDelayMillis;
    public final Setting<Integer> randomDelayMillis;
    public final Setting<Integer> maxPerDrop;
    public final Setting<Integer> confirmTimeoutTicks;
    public final Setting<Integer> maxAttempts;

    public final Setting<DropTargetManager.Mode> targetMode;
    public final Setting<Double> targetRange;
    public final Setting<CreativeInventoryHandler.Strategy> inventoryStrategy;
    public final Setting<Boolean> lockTarget;
    public final Setting<Boolean> notifyTargetChange;

    public final Setting<Boolean> hudEnabled;
    public final Setting<Double> hudScale;
    public final Setting<Integer> hudBackgroundOpacity;
    public final Setting<Boolean> hudCompact;
    public final Setting<Boolean> hudOnlyRemaining;
    public final Setting<Boolean> hudShowTarget;

    public SchematicDropperSettings(Module module) {
        general = module.settings.getDefaultGroup();
        speed = module.settings.createGroup("Speed");
        target = module.settings.createGroup("Targeting");
        hud = module.settings.createGroup("HUD");

        source = general.add(new EnumSetting.Builder<Source>()
            .name("source")
            .description("Where the required materials are read from.")
            .defaultValue(Source.LITEMATICA)
            .build());

        useSelectedPlacement = general.add(new BoolSetting.Builder()
            .name("use-selected-placement")
            .description("Read the placement currently selected in Litematica, rather than a file.")
            .defaultValue(true)
            .visible(() -> source.get() == Source.LITEMATICA)
            .build());

        schematicPath = general.add(new StringSetting.Builder()
            .name("schematic-file")
            .description("Path to a .litematic or .schem file. Set with the Import button in the module screen.")
            .defaultValue("")
            .wide()
            .visible(() -> source.get() == Source.SCHEMATIC_FILE)
            .build());

        materialListPath = general.add(new StringSetting.Builder()
            .name("material-list")
            .description("Path to a .txt, .json or .csv material list. Set with the Import button.")
            .defaultValue("")
            .wide()
            .visible(() -> source.get() == Source.MATERIAL_LIST)
            .build());

        ignoreAir = general.add(new BoolSetting.Builder()
            .name("ignore-air")
            .description("Skip air and other blocks that have no item form.")
            .defaultValue(true)
            .build());

        itemsPerSecond = speed.add(new IntSetting.Builder()
            .name("items-per-second")
            .description("Upper bound on items sent per second. The server refuses more than 200 drops per second.")
            .defaultValue(128)
            .range(1, 200)
            .sliderRange(1, 200)
            .build());

        maxDropsPerTick = speed.add(new IntSetting.Builder()
            .name("max-drops-per-tick")
            .description("Upper bound on drops per tick. The server accepts at most 10.")
            .defaultValue(4)
            .range(1, 10)
            .sliderRange(1, 10)
            .build());

        minDelayMillis = speed.add(new IntSetting.Builder()
            .name("delay")
            .description("Minimum delay between drops, in milliseconds.")
            .defaultValue(0)
            .range(0, 1000)
            .sliderRange(0, 500)
            .build());

        randomDelayMillis = speed.add(new IntSetting.Builder()
            .name("random-delay")
            .description("Extra delay added at random, from 0 up to this many milliseconds.")
            .defaultValue(0)
            .range(0, 1000)
            .sliderRange(0, 500)
            .build());

        maxPerDrop = speed.add(new IntSetting.Builder()
            .name("max-per-drop")
            .description("Largest number of items in a single drop, before the stack limit applies.")
            .defaultValue(64)
            .range(1, 64)
            .sliderRange(1, 64)
            .build());

        confirmTimeoutTicks = speed.add(new IntSetting.Builder()
            .name("confirm-timeout")
            .description("Ticks to wait for the server to confirm a drop before retrying it.")
            .defaultValue(20)
            .range(1, 200)
            .sliderRange(1, 100)
            .build());

        maxAttempts = speed.add(new IntSetting.Builder()
            .name("max-attempts")
            .description("Sends allowed per drop. Leave at 1 so an unconfirmed drop stops the run; a higher value can duplicate items if the server was only slow.")
            .defaultValue(1)
            .range(1, 5)
            .sliderRange(1, 5)
            .build());

        inventoryStrategy = general.add(new EnumSetting.Builder<CreativeInventoryHandler.Strategy>()
            .name("inventory-strategy")
            .description("How items are taken from the creative inventory: a direct drop packet, or a hotbar give followed by a throw.")
            .defaultValue(CreativeInventoryHandler.Strategy.DIRECT)
            .build());

        targetMode = target.add(new EnumSetting.Builder<DropTargetManager.Mode>()
            .name("target-mode")
            .description("Drop at the player's feet, or throw toward the entity being looked at.")
            .defaultValue(DropTargetManager.Mode.FEET)
            .build());

        targetRange = target.add(new DoubleSetting.Builder()
            .name("target-range")
            .description("How far to look for a target entity, in blocks.")
            .defaultValue(4.5)
            .range(1, 64)
            .sliderRange(1, 32)
            .decimalPlaces(1)
            .build());

        lockTarget = target.add(new BoolSetting.Builder()
            .name("lock-target")
            .description("Keep using the entity that was targeted when the run started.")
            .defaultValue(false)
            .visible(() -> targetMode.get() == DropTargetManager.Mode.LOOKED_AT_ENTITY)
            .build());

        notifyTargetChange = target.add(new BoolSetting.Builder()
            .name("notify-target-change")
            .description("Report in chat when the targeted entity changes.")
            .defaultValue(true)
            .visible(() -> targetMode.get() == DropTargetManager.Mode.LOOKED_AT_ENTITY)
            .build());

        hudEnabled = hud.add(new BoolSetting.Builder()
            .name("hud-enabled")
            .description("Show the progress HUD.")
            .defaultValue(true)
            .build());

        hudScale = hud.add(new DoubleSetting.Builder()
            .name("hud-scale")
            .description("Text scale of the HUD.")
            .defaultValue(1.0)
            .range(0.5, 2.0)
            .sliderRange(0.5, 2.0)
            .decimalPlaces(2)
            .build());

        hudBackgroundOpacity = hud.add(new IntSetting.Builder()
            .name("hud-background-opacity")
            .description("Opacity of the HUD background, from 0 to 255.")
            .defaultValue(120)
            .range(0, 255)
            .sliderRange(0, 255)
            .build());

        hudCompact = hud.add(new BoolSetting.Builder()
            .name("hud-compact")
            .description("Show a one-line summary instead of the full material table.")
            .defaultValue(false)
            .build());

        hudOnlyRemaining = hud.add(new BoolSetting.Builder()
            .name("hud-only-remaining")
            .description("Hide materials that are already complete.")
            .defaultValue(false)
            .build());

        hudShowTarget = hud.add(new BoolSetting.Builder()
            .name("hud-show-target")
            .description("Show the current drop target and mode.")
            .defaultValue(true)
            .build());
    }

    /** Copies the settings into the scheduler's plain config object. */
    public void applyTo(DropTask.Config config, MaterialManager materials) {
        config.itemsPerSecond = itemsPerSecond.get();
        config.maxDropsPerTick = maxDropsPerTick.get();
        config.minDelayMillis = minDelayMillis.get();
        config.randomDelayMillis = randomDelayMillis.get();
        config.maxPerDrop = maxPerDrop.get();
        config.confirmTimeoutTicks = confirmTimeoutTicks.get();
        config.maxAttempts = maxAttempts.get();
        config.targetMode = targetMode.get();
    }

    /** The format of the configured material list file, or {@code null}. */
    public ImportFormat materialListFormat() {
        return ImportFormat.fromFileName(materialListPath.get());
    }
}
