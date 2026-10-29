package net.monacraft.spellforge.fabric;

import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;

import java.lang.reflect.Field;

/** Mapping-independent access to Minecraft's multiline editing model. */
final class EditorTextAccess {
    private static final Field TEXT_FIELD = findTextField();

    private EditorTextAccess() { }

    static MultilineTextField get(MultiLineEditBox editor) {
        try {
            return (MultilineTextField) TEXT_FIELD.get(editor);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("ArcSys editor text model is unavailable", error);
        }
    }

    private static Field findTextField() {
        for (Field field : MultiLineEditBox.class.getDeclaredFields()) {
            if (field.getType() != MultilineTextField.class) continue;
            if (!field.trySetAccessible()) throw new IllegalStateException("ArcSys editor text model is inaccessible");
            return field;
        }
        throw new IllegalStateException("ArcSys editor text model was not found");
    }
}
