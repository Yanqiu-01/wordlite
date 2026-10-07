package com.rikkahub.wordlite;

/** Bounded comparison range for the standard original/rewrite view. */
public final class TextDiff {
    public final int start, originalEnd, changedEnd;
    public TextDiff(String original, String changed) {
        int prefix = 0;
        while (prefix < original.length() && prefix < changed.length() && original.charAt(prefix) == changed.charAt(prefix)) prefix++;
        if (prefix > 0 && prefix < original.length() && Character.isLowSurrogate(original.charAt(prefix))) prefix--;
        int suffix = 0;
        while (suffix < original.length() - prefix && suffix < changed.length() - prefix
                && original.charAt(original.length() - suffix - 1) == changed.charAt(changed.length() - suffix - 1)) suffix++;
        if (suffix > 0 && suffix < original.length() && Character.isLowSurrogate(original.charAt(original.length() - suffix))) suffix--;
        start = prefix; originalEnd = original.length() - suffix; changedEnd = changed.length() - suffix;
    }
}
