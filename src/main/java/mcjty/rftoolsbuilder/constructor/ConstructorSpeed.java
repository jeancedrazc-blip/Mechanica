package mcjty.rftoolsbuilder.constructor;

public enum ConstructorSpeed {
    NORMAL(1, "NORMAL 1x"),
    FAST(2, "FAST 2x"),
    TURBO(4, "TURBO 4x");

    private final int multiplier;
    private final String label;

    ConstructorSpeed(int multiplier, String label) {
        this.multiplier = multiplier;
        this.label = label;
    }

    public int multiplier() { return multiplier; }
    public String label() { return label; }
    public ConstructorSpeed next() { return values()[(ordinal() + 1) % values().length]; }

    public static ConstructorSpeed byOrdinal(int ordinal) {
        ConstructorSpeed[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NORMAL;
    }
}
