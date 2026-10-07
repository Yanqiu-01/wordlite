package com.rikkahub.wordlite;

/** UTF-16 anchor adjustment for an edit replacing [at, at+removed). */
public final class EditOffsets {
    public static int adjust(int value, int at, int removed, int inserted, boolean endAnchor) {
        if (value < at) return value;
        if (value > at + removed) return value + inserted - removed;
        if (removed == 0 && value == at) return endAnchor ? value + inserted : value;
        if (value == at + removed) return at + inserted;
        return endAnchor ? at + inserted : at;
    }
    private EditOffsets() { }
}
