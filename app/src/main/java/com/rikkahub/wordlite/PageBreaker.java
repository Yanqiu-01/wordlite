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
        /**
         * 段末那一行允许垂到版心以下的量（文档 px）。Word 分页看的是基线：最后一行的基线（外加它自己的
         * 上伸）还在版心里，这一行就归这一页，下伸垂过下边距它也不管——桌面 Word 16.0 在 12pt 宋体 /
         * w:line=300 这篇稿子上每页实打实排 33 行（artifacts/word/page_budget.tsv），而 33 × 26.267px
         * = 866.81px 已经超出 865.53px 的版心，按"整行行盒都得装下"只能排 32 行，一页少一行、整篇就多两页。
         * 0 表示这一段按老规则整行行盒算（没量过行高的字体就走这条，行为与改动前一字不差）。
         */
        public float hang;
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
        /** 这一片末尾那一行垂到版心以下的量，见 Item.hang。 */
        public final float hang;
        public Fragment(Item item, int start, int end, float top) {
            this(item, start, end, top, 0f);
        }
        public Fragment(Item item, int start, int end, float top, float hang) {
            this.item = item; startLine = start; endLine = end; this.top = top;
            this.hang = hang;
            height = item.height(start, end);
        }
    }
    public static final class Page {
        public final List<Fragment> fragments = new ArrayList<Fragment>();
        public float usedHeight;
        /** 本页最后那一片用掉的垂下量：判断是否溢出时要把它还给版心，见 Item.hang。 */
        public float hang;
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
            // 整段（或整个 keepNext 组）也要按基线判生死：它末行的下伸同样允许垂出下边距，
            // 否则一篇 33 行的正文段会被这条判断整段推走，白白多占一页。
            if (required > 0 && required + before - item.hang <= capacity + EPS
                    && y + gap + required - item.hang > capacity + EPS && !page.fragments.isEmpty()) {
                page = new Page(); pages.add(page); y = 0; pendingAfter = 0;
            }
            int start = 0;
            while (start < item.lines.length) {
                gap = start == 0 ? Math.max(pendingAfter, before) : 0;
                int end = start;
                float height = 0;
                while (end < item.lines.length
                        && y + gap + height + item.lines[end] - item.hang <= capacity + EPS)
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
                Fragment fragment = new Fragment(item, start, end, y + gap, item.hang);
                page.fragments.add(fragment);
                y = fragment.top + fragment.height;
                page.hang = item.hang;
                page.overflow |= y - page.hang > capacity + EPS;
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
