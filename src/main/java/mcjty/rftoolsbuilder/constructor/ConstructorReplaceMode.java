package mcjty.rftoolsbuilder.constructor;

/** Placement policy inspired by Create's Schematicannon options. */
public enum ConstructorReplaceMode {
    DONT_REPLACE,
    REPLACE_SOLID,
    REPLACE_ANY,
    REPLACE_EMPTY;

    public ConstructorReplaceMode next() {
        ConstructorReplaceMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
