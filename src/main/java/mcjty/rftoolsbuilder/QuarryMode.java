package mcjty.rftoolsbuilder;

/** Mining behaviour encoded by the six legacy quarry cards. */
public enum QuarryMode {
    NORMAL(false, false),
    CLEAR(true, false),
    FORTUNE(false, true),
    CLEAR_FORTUNE(true, true),
    SILK(false, false, true),
    CLEAR_SILK(true, false, true);

    private final boolean clear;
    private final boolean fortune;
    private final boolean silk;

    QuarryMode(boolean clear, boolean fortune) {
        this(clear, fortune, false);
    }

    QuarryMode(boolean clear, boolean fortune, boolean silk) {
        this.clear = clear;
        this.fortune = fortune;
        this.silk = silk;
    }

    public boolean isClear() { return clear; }
    public boolean isFortune() { return fortune; }
    public boolean isSilk() { return silk; }
}
