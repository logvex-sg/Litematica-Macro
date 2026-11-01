package com.schematicdropper;

import com.schematicdropper.hud.SchematicDropperHud;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

/** Registers the module and the HUD element with Meteor. */
public class SchematicDropperAddon extends MeteorAddon {
    /**
     * The Meteor category this addon's modules appear under.
     *
     * <p>Assigned in {@link #onRegisterCategories()}, which Meteor runs before
     * {@link #onInitialize()}, so it is never null by the time a module reads it.
     */
    public static Category CATEGORY;

    /**
     * Creates and registers the addon's category.
     *
     * <p>Meteor only accepts category registrations during this callback (see
     * {@code Modules.registerCategory}), and it runs the callback before any
     * module is added. Registering the category in {@code onInitialize} instead
     * is what caused {@code Modules.add} to reject the module.
     */
    @Override
    public void onRegisterCategories() {
        CATEGORY = new Category("Schematic Dropper", Items.DROPPER.getDefaultStack());
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new SchematicDropper());
        Hud.get().register(SchematicDropperHud.INFO);
    }

    @Override
    public String getPackage() {
        return "com.schematicdropper";
    }
}
