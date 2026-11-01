package com.schematicdropper.gui;

import com.schematicdropper.SchematicDropper;
import com.schematicdropper.SchematicDropperSettings;
import com.schematicdropper.material.MaterialEntry;
import com.schematicdropper.material.MaterialManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.utils.render.color.Color;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The material management screen.
 *
 * <p>Shows one row per material with its requirement, confirmed progress and
 * status, and provides the import and run controls. The table is rebuilt on each
 * change rather than edited in place, so it always matches what the module holds.
 */
public class SchematicDropperScreen extends WidgetScreen {
    /** The material list file types the import button accepts. */
    private static final String LIST_FILTER = "*.txt;*.json;*.csv";
    /** The schematic file types the import button accepts. */
    private static final String SCHEMATIC_FILTER = "*.litematic;*.schem";

    private final SchematicDropper module;

    private WTextBox filterBox;
    private WTable materialTable;
    private WLabel statusLabel;

    private String filter = "";
    private MaterialManager.SortMode sortMode = MaterialManager.SortMode.NAME;
    private boolean onlyRemaining;

    public SchematicDropperScreen(GuiTheme theme, SchematicDropper module) {
        super(theme, "Schematic Dropper");
        this.module = module;
    }

    @Override
    public void initWidgets() {
        // ------------------------------------------------------------- CONTROLS
        WSection controls = add(theme.section("Controls")).expandX().widget();
        WTable buttons = controls.add(theme.table()).expandX().widget();

        button(buttons, "Import Schematic", this::pickSchematic);
        button(buttons, "Import List", this::pickMaterialList);
        button(buttons, "Refresh", () -> {
            module.refresh();
            rebuild();
        });
        buttons.row();

        button(buttons, "Start", () -> {
            module.start();
            rebuild();
        });
        button(buttons, "Pause", () -> {
            module.pause();
            rebuild();
        });
        button(buttons, "Resume", () -> {
            module.resume();
            rebuild();
        });
        buttons.row();

        button(buttons, "Stop", () -> {
            module.stop();
            rebuild();
        });
        button(buttons, "Clear List", () -> {
            module.clear();
            rebuild();
        });
        button(buttons, "Reset Progress", () -> {
            module.task().reset();
            module.materials().resetProgress();
            rebuild();
        });
        button(buttons, "Restore Run", () -> {
            module.restoreSavedRun();
            rebuild();
        });

        statusLabel = controls.add(theme.label("")).widget();

        // -------------------------------------------------------------- FILTER
        WSection view = add(theme.section("Materials")).expandX().widget();
        WTable filterRow = view.add(theme.table()).expandX().widget();

        filterRow.add(theme.label("Filter:"));
        filterBox = filterRow.add(theme.textBox("", (CharFilter) (text, c) -> true)).expandX().widget();
        filterBox.set(filter);
        filterBox.action = () -> {
            filter = filterBox.get().trim().toLowerCase(Locale.ROOT);
            rebuild();
        };

        filterRow.add(theme.label("Sort:"));
        WDropdown<MaterialManager.SortMode> sortDropdown =
            filterRow.add(theme.dropdown(MaterialManager.SortMode.values(), sortMode)).widget();
        sortDropdown.action = () -> {
            sortMode = sortDropdown.get();
            rebuild();
        };

        filterRow.add(theme.label("Only remaining:"));
        var remainingBox = filterRow.add(theme.checkbox(onlyRemaining)).widget();
        remainingBox.action = () -> {
            onlyRemaining = remainingBox.checked;
            rebuild();
        };

        WView viewport = view.add(theme.view()).expandX().widget();
        materialTable = viewport.add(theme.table()).expandX().widget();

        rebuild();
    }

    /** Adds a button to a table row and wires its action. */
    private void button(WTable table, String label, Runnable action) {
        table.add(theme.button(label)).widget().action = action;
    }

    /** Rebuilds the material table from the module's current state. */
    private void rebuild() {
        if (materialTable == null) return;

        materialTable.clear();

        List<MaterialEntry> rows = module.materials().view(filter, sortMode, onlyRemaining);

        if (rows.isEmpty()) {
            materialTable.add(theme.label(module.materials().isEmpty()
                ? "No materials loaded. Use Import Schematic or Import List."
                : "No materials match the current filter."));
        } else {
            materialTable.add(theme.label("Material"));
            materialTable.add(theme.label("Required"));
            materialTable.add(theme.label("Dropped"));
            materialTable.add(theme.label("Remaining"));
            materialTable.add(theme.label("Progress"));
            materialTable.add(theme.label("Status"));
            materialTable.row();

            for (MaterialEntry entry : rows) {
                materialTable.add(theme.label(shortName(entry.id())));

                WLabel required = materialTable.add(theme.label(String.valueOf(entry.required()))).widget();
                WLabel dropped = materialTable.add(theme.label(String.valueOf(entry.dropped()))).widget();
                WLabel remaining = materialTable.add(theme.label(String.valueOf(entry.remaining()))).widget();
                materialTable.add(theme.label(percent(entry.progress())));
                WLabel status = materialTable.add(theme.label(entry.status().toString())).widget();

                Color color = statusColor(entry);
                if (color != null) {
                    required.color = color;
                    dropped.color = color;
                    remaining.color = color;
                    status.color = color;
                }

                materialTable.row();
            }
        }

        if (statusLabel != null) statusLabel.set(statusText());

        // The table's contents changed, so the layout must be recomputed.
        invalidate();
    }

    private static Color statusColor(MaterialEntry entry) {
        return switch (entry.status()) {
            case MISSING, STALLED -> new Color(255, 100, 100);
            case BLOCKED -> new Color(255, 190, 90);
            case DONE -> new Color(120, 220, 120);
            case RUNNING -> new Color(120, 180, 255);
            default -> null;
        };
    }

    private String statusText() {
        MaterialManager materials = module.materials();

        if (materials.isEmpty()) {
            return "Status: " + module.task().state() + "   (nothing loaded)";
        }

        StringBuilder text = new StringBuilder()
            .append("Status: ").append(module.task().state())
            .append("   Total: ").append(materials.totalDropped())
            .append(" / ").append(materials.totalRequired())
            .append("   ").append(percent(materials.overallProgress()));

        int missing = materials.missingCount();
        if (missing > 0) text.append("   Missing: ").append(missing);

        int blocked = materials.blockedCount();
        if (blocked > 0) {
            text.append("   Blocked: ").append(blocked)
                .append(" (").append(materials.blockedRemaining()).append(" items)");
        }

        String error = module.task().lastError();
        if (error != null) text.append("   (").append(error).append(')');

        return text.toString();
    }

    // --------------------------------------------------------------- PICKERS

    /**
     * Opens the native file dialog.
     *
     * <p>Runs on a worker thread: the dialog blocks until the user answers, and
     * blocking the client thread would freeze the game. The chosen file is handed
     * back to the client thread.
     */
    private void openFileDialog(String title, String defaultPath, String filter,
                                java.util.function.Consumer<File> onChosen) {
        Thread thread = new Thread(() -> {
            String chosen;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8(filter));
                filters.flip();

                chosen = TinyFileDialogs.tinyfd_openFileDialog(title, defaultPath, filters, null, false);
            } catch (Throwable t) {
                // A missing native library must not take the game down; report it
                // so the user can set the path by hand instead.
                MeteorClient.mc.execute(() -> module.error("Could not open the file dialog: %s", t.getMessage()));
                return;
            }

            if (chosen == null || chosen.isBlank()) return;

            File file = new File(chosen);
            MeteorClient.mc.execute(() -> {
                onChosen.accept(file);
                rebuild();
            });
        }, "SchematicDropper-FileDialog");

        thread.setDaemon(true);
        thread.start();
    }

    private void pickSchematic() {
        openFileDialog("Select a schematic", defaultDirectory(), SCHEMATIC_FILTER, file -> {
            module.options().source.set(SchematicDropperSettings.Source.SCHEMATIC_FILE);
            module.options().schematicPath.set(file.getAbsolutePath());
            module.importSchematicFile(file.toPath(), true);
        });
    }

    private void pickMaterialList() {
        openFileDialog("Select a material list", defaultDirectory(), LIST_FILTER, file -> {
            module.options().source.set(SchematicDropperSettings.Source.MATERIAL_LIST);
            module.options().materialListPath.set(file.getAbsolutePath());
            module.importMaterialList(file.toPath(), true);
        });
    }

    /** Starts the dialog in the folder the module last used, when there is one. */
    private String defaultDirectory() {
        String current = module.options().schematicPath.get();
        if (current != null && !current.isBlank()) {
            File parent = new File(current).getParentFile();
            if (parent != null && parent.isDirectory()) return parent.getAbsolutePath();
        }
        return new File(".").getAbsolutePath();
    }

    // --------------------------------------------------------------- HELPERS

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private static String shortName(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /** Opens this screen. Used by the module's settings screen button. */
    public static void open(GuiTheme theme, SchematicDropper module) {
        MeteorClient.mc.setScreen(new SchematicDropperScreen(theme, module));
    }
}
