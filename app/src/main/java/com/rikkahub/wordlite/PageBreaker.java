package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.List;

/** Pure pagination over already shaped lines. No Android objects, no font guesses. */
public final class PageBreaker {
    private static final float EPS = 0.01f;
    public static final class Item {
        public int blockIndex;
        public float[] lines;
        public float before, after;
        public boolean pageBreakBefore, keepNext, keepLines, widowControl = true;
        /** Section-local pagination group. */
        public int sectionIndex;
        /** Section-specific content height; <=0 uses paginate's default capacity. */
        public float capacity;
        public Item(int index, float... heights) {
            blockIndex = index;
            lines = heights.length == 0 ? new float[]{1} : heights;
            for (float h : lines) if (!(h > 0) || Float.isInfinite(h))
                throw new IllegalArgumentException("Line height must be finite and positive");
        }
        public float height(int start, int end) {
            float h = 0;
            for (int i = start; i < end; i++) h += lines[i];
            return h;
        }
    }
    public static final class Fragment {
        public final Item item;
        public final int startLine, endLine;
        public final float top, height;
        public Fragment(Item item, int start, int end, float top) {
            this.item = item; startLine = start; endLine = end; this.top = top;
            height = item.height(start, end);
        }
    }
    public static final class Page {
        public final List<Fragment> fragments = new ArrayList<Fragment>();
        public float usedHeight;
        public boolean overflow;
    }
    public static List<Page> paginate(List<Item> items, float capacity) {
        if (!(capacity > 0) || Float.isInfinite(capacity)) throw new IllegalArgumentException("Invalid page height");
        List<Page> pages = new ArrayList<Page>();
        Page page = new Page();
        pages.add(page);
        float y = 0, pendingAfter = 0;
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            if (item.pageBreakBefore && !page.fragments.isEmpty()) {
                page = new Page(); pages.add(page); y = 0; pendingAfter = 0;
            }
            // Word treats pageBreakBefore as the top edge of a fresh page:
            // paragraph-before spacing is not painted above that paragraph.
            // This also covers the first item in a section, where no physical
            // break is needed because the section already starts on a page.
            float before = item.pageBreakBefore ? 0f : Math.max(0, item.before);
            float gap = Math.max(pendingAfter, before);
            float full = item.height(0, item.lines.length);
            // Keep an entire paragraph only when it can fit on an empty page.
            float required = item.keepLines ? full : 0;
            if (item.keepNext) required = Math.max(required, keepGroupHeight(items, index));
            if (required > 0 && required + before <= capacity + EPS
                    && y + gap + required > capacity + EPS && !page.fragments.isEmpty()) {
                page = new Page(); pages.add(page); y = 0; pendingAfter = 0;
            }
            int start = 0;
            while (start < item.lines.length) {
                gap = start == 0 ? Math.max(pendingAfter, before) : 0;
                int end = start;
                float height = 0;
                while (end < item.lines.length && y + gap + height + item.lines[end] <= capacity + EPS)
                    height += item.lines[end++];
                if (end < item.lines.length && item.widowControl) {
                    // Do not leave a single final line on the following page.
                    if (item.lines.length - end == 1 && end - start > 2) end--;
                    if ((end - start < 2 || item.lines.length - end < 2) && !page.fragments.isEmpty()) {
                        page = new Page(); pages.add(page); y = 0; pendingAfter = 0; continue;
                    }
                    // A page shorter than two lines cannot satisfy the rule; make progress.
                }
                if (end == start) {
                    if (!page.fragments.isEmpty()) {
                        page = new Page(); pages.add(page); y = 0; pendingAfter = 0; continue;
                    }
                    // One oversize object/line: show it, report overflow, never loop forever.
                    end = start + 1;
                }
                Fragment fragment = new Fragment(item, start, end, y + gap);
                page.fragments.add(fragment);
                y = fragment.top + fragment.height;
                page.overflow |= y > capacity + EPS;
                page.usedHeight = y;
                start = end;
                pendingAfter = 0;
                if (start < item.lines.length) {
                    page = new Page(); pages.add(page); y = 0;
                }
            }
            pendingAfter = Math.max(0, item.after);
            page.usedHeight = Math.max(y, Math.min(capacity, y + pendingAfter));
        }
        return pages;
    }
    private static float keepGroupHeight(List<Item> items, int index) {
        Item previous = items.get(index);
        float height = previous.height(0, previous.lines.length);
        for (int i = index + 1; i < items.size() && previous.keepNext; i++) {
            Item next = items.get(i);
            if (next.pageBreakBefore) break;
            height += Math.max(Math.max(0, previous.after), Math.max(0, next.before));
            int lines = next.keepNext || next.keepLines ? next.lines.length
                    : Math.min(next.lines.length, next.widowControl ? 2 : 1);
            height += next.height(0, lines);
            previous = next;
        }
        return height;
    }
    private PageBreaker() { }
}
