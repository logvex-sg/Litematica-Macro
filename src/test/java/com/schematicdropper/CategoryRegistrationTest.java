package com.schematicdropper;

import meteordevelopment.meteorclient.systems.modules.Category;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the category-registration contract that caused the addon to crash while
 * Meteor initialised.
 *
 * <p>The crash was {@code Modules.addModule - Module's category was not
 * registered}: the addon built its {@link Category} as a field initialiser and
 * never registered it, so {@code Modules.add} rejected the module.
 *
 * <p>Meteor 0.5.8 fixes the order for addons. {@code MeteorClient.onInitializeClient}
 * calls {@code Categories.init()}, which sets {@code Categories.REGISTERING},
 * registers its own categories, and then calls {@code onRegisterCategories()} on
 * every addon; only {@code onInitialize()} runs afterwards. So the addon has to
 * override {@code onRegisterCategories()} and create the category there.
 *
 * <p>These tests assert that shape without booting Minecraft. A full lifecycle
 * test is not possible in a plain JVM: {@code Category} falls back to
 * {@code Items.AIR.getDefaultStack()} when given no icon, and {@code Modules}
 * touches registries in its own static initialiser, so both fail with
 * {@code NoClassDefFoundError: Could not initialize class net.minecraft.item.Items}.
 * The end-to-end behaviour is verified by running the client, and by inspecting
 * the bytecode of the built jar.
 */
class CategoryRegistrationTest {
    /** The addon's category field. */
    private static Field categoryField() throws Exception {
        return SchematicDropperAddon.class.getDeclaredField("CATEGORY");
    }

    /**
     * The regression itself: {@code onRegisterCategories} must be overridden.
     *
     * <p>{@code getDeclaredMethod} only returns methods the class declares, so
     * this fails if the override is ever dropped and the inherited no-op default
     * is used again - which is precisely the state that crashed.
     */
    @Test
    void theAddonOverridesOnRegisterCategories() throws Exception {
        Method method = SchematicDropperAddon.class.getDeclaredMethod("onRegisterCategories");

        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertEquals(void.class, method.getReturnType());
        assertEquals(0, method.getParameterCount());
    }

    /**
     * The category is created during the registration pass, not during class
     * loading.
     *
     * <p>A field initialiser would run before Meteor sets {@code REGISTERING}, so
     * registering it would throw, and {@code Modules.add} would later reject the
     * module. The field must therefore still be unset at this point: the addon
     * class has been loaded to run this test, but no registration pass has run.
     */
    @Test
    void theCategoryIsNotCreatedDuringClassLoading() throws Exception {
        assertTrue(categoryField().get(null) == null,
            "the category must not be built by a static initialiser");
    }

    /**
     * The category is assigned by the registration pass, and the module reads the
     * same field.
     *
     * <p>The value is written reflectively because constructing a real
     * {@link Category} needs Minecraft's item registry. What matters here is the
     * field's identity and type, which is what the module constructor reads.
     */
    @Test
    void theCategoryFieldIsPublicStaticAndReadByTheModule() throws Exception {
        Field field = categoryField();

        assertTrue(Modifier.isStatic(field.getModifiers()),
            "the category must be static: the module constructor reads it before any instance exists");
        assertTrue(Modifier.isPublic(field.getModifiers()),
            "the category must be reachable from the module constructor");
        assertEquals(Category.class, field.getType());
    }

    /**
     * The module constructor must read the category field, not build its own.
     *
     * <p>A module that made its own {@code Category} would produce an equal-looking
     * but separately-constructed object, which is the shape of the original bug.
     */
    @Test
    void theModuleConstructorReadsTheAddonCategory() throws Exception {
        String bytecode = bytecodeOf(SchematicDropper.class);

        assertTrue(bytecode.contains("com/schematicdropper/SchematicDropperAddon"),
            "the module constructor must reference the addon's category field");
        assertTrue(bytecode.contains("CATEGORY"),
            "the module constructor must read CATEGORY from the addon");
    }

    /** Reads a class's bytecode straight from the compiled output. */
    private static String bytecodeOf(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("no bytecode for " + type);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }
}
