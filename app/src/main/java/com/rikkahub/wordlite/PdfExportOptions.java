package com.rikkahub.wordlite;

/** Export choices, independent of Android UI and persistence. */
public final class PdfExportOptions {
    public enum Scope { ALL, CURRENT_PAGE, SELECTION }
    public Scope scope = Scope.ALL;
    public int currentPage;
    public int paragraphIndex = -1;
    public int selectionStart, selectionEnd;
    public boolean includeComments;
    public boolean includeRevisions;
    public boolean bookmarks = true;

    public int[] pages(int total) {
        if (total < 1) throw new IllegalArgumentException("没有可导出的页面");
        if (scope == Scope.CURRENT_PAGE) {
            if (currentPage < 0 || currentPage >= total) throw new IllegalArgumentException("页码无效");
            return new int[]{currentPage};
        }
        int[] result = new int[total]; for (int i = 0; i < total; i++) result[i] = i;
        return result;
    }
    public void validateSelection(DocxDocument.ParagraphBlock paragraph) {
        if (scope != Scope.SELECTION) return;
        if (paragraph == null || selectionStart < 0 || selectionEnd <= selectionStart
                || selectionEnd > paragraph.text.length()) throw new IllegalArgumentException("没有选中内容");
    }
}
