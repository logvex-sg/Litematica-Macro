package com.schematicdropper;

import com.schematicdropper.hud.SchematicDropperHud;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

/** Registers the module and the HUD element with Meteor. */
public class SchematicDropperAddon extends MeteorAddon {
    /** The Meteor category this addon's modules appear under. */
    public static final Category CATEGORY = new Category("Schematic Dropper",
        Items.DROPPER.getDefaultStack());

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
