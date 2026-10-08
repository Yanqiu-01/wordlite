package com.rikkahub.wordlite;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/**
 * 报告中心（1.0.0）：列表按新的在前、详情从磁盘上那条记录重画、点一条证据请编辑器跳到那段字、
 * 证据行跨报告复用且不重复绑定。顺带把 1.0.6 要断言的两件事先钉住：触控目标不小于 48dp、
 * 图标按钮都带 contentDescription——不留给以后回头补。
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ReportCenterTest {
    /** 面板只会请编辑器做两件事：跳那段字、导出这份 HTML。 */
    private static final class Jumps implements ReportCenterUI.Listener {
        final ArrayList<ReportStore.Record> records = new ArrayList<ReportStore.Record>();
        final ArrayList<ReportStore.Evidence> hits = new ArrayList<ReportStore.Evidence>();
        int exports;
        ReportStore.Record exported;

        public void jumpTo(ReportStore.Record record, ReportStore.Evidence hit) {
            records.add(record);
            hits.add(hit);
        }

        public void export(ReportStore.Record record) {
            exports++;
            exported = record;
        }
    }

    private static File freshDir(String name) {
        return new File(System.getProperty("java.io.tmpdir"),
                "wordlite-ui-" + name + "-" + System.nanoTime());
    }

    private static ReportStore.Record hand(String fileName, long createdAt, int hits) {
        ReportStore.Record record = new ReportStore.Record();
        record.fileName = fileName;
        record.createdAt = createdAt;
        record.detectedAt = "2026-10-08 09:12";
        record.comparedChars = 4000;
        record.duplicateChars = 500;
        record.citedDuplicateChars = 100;
        record.selfWrittenChars = 3500;
        record.overallRate = 12.5d;
        record.excludingCitationsRate = 10d;
        record.selfWrittenRate = 87.5d;
        record.citedDuplicateRate = 2.5d;
        record.aigcVerdict = "复核（可疑 220 字 / 全文 4000 字）";
        record.coverageNote = "检索覆盖 3/6 窗口 · 1200/4000 字 · 相似率是下限";
        record.state = ReportStore.STATE_PARTIAL;
        record.reason = "检索中途停止，只跑了 3/6 个检索窗口，相似率是下限";
        record.sourceChars = 2000;
        for (int i = 0; i < hits; i++) {
            ReportStore.Evidence hit = new ReportStore.Evidence();
            hit.start = 100 + i * 50;
            hit.end = 133 + i * 50;
            hit.score = 0.81d;
            hit.title = "多孔铜连接研究";
            hit.engine = "wanfang";
            hit.snippet = "多孔铜在低温下即可与锡层反应，界面生成稳定的金属间化合物层。";
            record.evidence.add(hit);
        }
        record.evidenceTotal = hits;
        ReportStore.SourceRow row = new ReportStore.SourceRow();
        row.title = "多孔铜连接研究";
        row.engine = "wanfang";
        row.duplicateChars = 500;
        row.hitCount = hits;
        row.firstStart = 100;
        row.share = 12.5d;
        record.sources.add(row);
        record.sourcesTotal = 1;
        ReportStore.EngineRow engine = new ReportStore.EngineRow();
        engine.engine = "wanfang";
        engine.share = 12.5d;
        engine.candidates = 5;
        engine.windows = 3;
        record.engines.add(engine);
        record.notes.add("已检索 3/6 个检索窗口，覆盖 1200/4000 字");
        record.notesTotal = 1;
        return record;
    }

    /** 一次真跑完的比对：详情必须能只靠磁盘上那条记录重画，不再碰比对内核。 */
    private static ReportStore.Record scanned(ReportStore store) {
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = "local:porous";
        source.title = "多孔铜连接研究";
        source.engine = "local";
        corpus.add(source, "多孔铜在低温下即可与锡层反应，界面生成稳定的金属间化合物层。"
                + "实验在三种温度下各重复五次，取样位置固定在接头中心两侧。");
        DocxDocument document = new DocxDocument();
        add(document, paragraph(0, "多孔铜在低温下即可与锡层反应，界面生成稳定的金属间化合物层。"));
        add(document, paragraph(1, "保温时间过长会让反应层增厚，接头强度反而下降。"));
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(document), corpus,
                false, null, null, null, null);
        return store.save(ReportStore.recordFor(report, "论文-多孔铜.docx"));
    }

    private static DocxDocument.ParagraphBlock paragraph(int index, String text) {
        DocxDocument.ParagraphBlock value = new DocxDocument.ParagraphBlock();
        value.index = index;
        value.text = text;
        return value;
    }

    private static void add(DocxDocument document, DocxDocument.ParagraphBlock paragraph) {
        document.blocks.add(paragraph);
        document.paragraphs.add(paragraph);
    }

    private static ReportCenterUI panel(ReportStore store, ReportCenterUI.Listener listener) {
        ReportCenterUI center = new ReportCenterUI(RuntimeEnvironment.getApplication());
        center.bind(store, listener);
        return center;
    }

    private static void collect(View root, String tag, List<View> out) {
        if (tag.equals(root.getTag())) out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), tag, out);
        }
    }

    private static List<View> tagged(View root, String tag) {
        List<View> out = new ArrayList<View>();
        collect(root, tag, out);
        return out;
    }

    private static List<View> shown(List<View> views) {
        List<View> out = new ArrayList<View>();
        for (int i = 0; i < views.size(); i++) if (views.get(i).getVisibility() == View.VISIBLE) out.add(views.get(i));
        return out;
    }

    private static boolean shows(View root, String needle) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(needle)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) if (shows(group.getChildAt(i), needle)) return true;
        }
        return false;
    }

    private static void measure(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    }

    private static int minTouch() {
        return Math.round(48f * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density);
    }

    private static String text(List<View> views) {
        assertEquals(1, views.size());
        return ((TextView) views.get(0)).getText().toString();
    }

    @Test public void historyListsPersistedReportsNewestFirstAndDetailRedrawsFromDisk() {
        ReportStore store = new ReportStore(freshDir("list"));
        Jumps jumps = new Jumps();
        ReportCenterUI center = panel(store, jumps);
        assertEquals(1, tagged(center, "report-empty").size());
        assertTrue(shows(center, "还没有历史报告"));
        assertFalse(center.isShowingDetail());

        store.save(hand("旧论文.docx", 1000L, 2));
        ReportStore.Record saved = store.save(hand("新论文.docx", 2000L, 3));
        center.showHistory();
        List<View> items = shown(tagged(center, "report-item"));
        assertEquals(2, items.size());
        assertTrue("列表新的在前", shows(items.get(0), "新论文.docx"));
        assertTrue("旧的排后面", shows(items.get(1), "旧论文.docx"));
        assertTrue("列表行给出总相似度比", shows(items.get(0), "12.5%"));
        assertTrue("列表行给出命中条数", shows(items.get(0), "命中 3 处"));
        assertTrue("列表行给出三态", shows(items.get(0), "部分完成"));

        // 详情页拿的是从磁盘重读的那条记录：能画出来就证明存档本身够用，不必重跑比对。
        ReportStore.Record fromDisk = store.record(saved.id);
        center.open(fromDisk);
        assertSame(fromDisk, center.opened());
        assertTrue(center.isShowingDetail());
        assertEquals("详情打开时列表让位", View.GONE, tagged(center, "report-list").get(0).getVisibility());
        assertEquals("12.5%", text(tagged(center, "metric-overall")));
        assertEquals("10.0%", text(tagged(center, "metric-uncited")));
        assertEquals("87.5%", text(tagged(center, "metric-self")));
        assertTrue("第四格不是百分比", text(tagged(center, "metric-aigc")).startsWith("复核（可疑 220 字"));
        assertEquals(4, tagged(center, "metric-card").size());
        assertTrue("指标卡脚注写的是账本原数", shows(center, "参与比对 4000 个有效字符"));
        assertTrue(shows(center, "其中引用区间内 100 个"));
        assertTrue("覆盖率那一行与面板同源", shows(center, "检索覆盖 3/6 窗口 · 1200/4000 字 · 相似率是下限"));
        assertTrue("部分完成要把相似率是下限顶到显眼处", shows(tagged(center, "report-banner").get(0), "相似率是下限"));
        assertEquals(1, tagged(center, "report-note").size());
        assertEquals("检索源那一节挂在详情里", View.VISIBLE, tagged(center, "engine-rows").get(0).getVisibility());
        assertEquals(1, tagged(center, "engine-row").size());
        assertTrue(shows(tagged(center, "engine-row").get(0), "候选 5 篇 · 提问 3 个窗口组"));
        assertEquals(1, tagged(center, "source-row").size());
        assertTrue("来源榜写得出这篇重复了多少字", shows(tagged(center, "source-row").get(0), "重复 500 字"));
        assertEquals(3, shown(tagged(center, "evidence-row")).size());
        assertTrue(shows(center, "多孔铜在低温下即可与锡层反应"));
        assertTrue("标题就是那份文档", shows(tagged(center, "report-title").get(0), "新论文.docx"));

        center.showHistory();
        assertFalse(center.isShowingDetail());
        assertEquals("回看一张报告不会多出记录", 2, store.size());
        center.open(fromDisk);
    }

    @Test public void detailRedrawsARealScanWithoutReRunningTheCheck() {
        ReportStore store = new ReportStore(freshDir("scan"));
        ReportStore.Record saved = scanned(store);
        assertNotNull(saved);
        ReportStore.Record fromDisk = store.record(saved.id);
        assertTrue(fromDisk.duplicateChars > 0);
        Jumps jumps = new Jumps();
        ReportCenterUI center = panel(store, jumps);
        center.open(fromDisk);
        assertEquals(String.format(java.util.Locale.US, "%.1f%%", Double.valueOf(fromDisk.overallRate)),
                text(tagged(center, "metric-overall")));
        assertEquals(fromDisk.evidence.size(), shown(tagged(center, "evidence-row")).size());
        assertTrue("来源榜那篇就是比对命中的", shows(tagged(center, "source-row").get(0), "多孔铜连接研究"));
        assertTrue("导出按钮就在详情页头上", !tagged(center, "report-export").isEmpty());
        View export = tagged(center, "report-export").get(0);
        export.performClick();
        assertEquals(1, jumps.exports);
        assertSame(fromDisk, jumps.exported);
    }

    @Test public void tappingAnEvidenceRowAsksTheEditorToJumpToThatCharacterRange() {
        ReportStore store = new ReportStore(freshDir("jump"));
        ReportStore.Record saved = store.save(hand("跳转.docx", 5000L, 3));
        ReportStore.Record fromDisk = store.record(saved.id);
        Jumps jumps = new Jumps();
        ReportCenterUI center = panel(store, jumps);
        center.open(fromDisk);
        List<View> rows = shown(tagged(center, "evidence-row"));
        assertEquals(3, rows.size());
        assertTrue("点之前就说清跳到哪一段", shows(rows.get(1), "正文 150-183 字"));

        rows.get(1).performClick();
        assertEquals(1, jumps.hits.size());
        assertSame(fromDisk, jumps.records.get(0));
        assertSame(fromDisk.evidence.get(1), jumps.hits.get(0));
        assertEquals(150, jumps.hits.get(0).start);
        assertEquals(183, jumps.hits.get(0).end);

        // 行末那个箭头图标是同一次跳转，不是第二个动作。
        List<View> arrows = tagged(rows.get(2), "evidence-jump");
        assertEquals(1, arrows.size());
        arrows.get(0).performClick();
        assertEquals(2, jumps.hits.size());
        assertEquals(200, jumps.hits.get(1).start);
        assertEquals(233, jumps.hits.get(1).end);
        assertSame(fromDisk.evidence.get(2), jumps.hits.get(1));
    }

    @Test public void evidenceRowsAreReusedAcrossReportsAndBoundExactlyOnce() {
        ReportStore store = new ReportStore(freshDir("reuse"));
        ReportStore.Record wide = store.save(hand("五处命中.docx", 7000L, 5));
        ReportStore.Record narrow = store.save(hand("两处命中.docx", 8000L, 2));
        Jumps jumps = new Jumps();
        ReportCenterUI center = panel(store, jumps);

        center.open(store.record(wide.id));
        List<View> firstPass = shown(tagged(center, "evidence-row"));
        assertEquals(5, firstPass.size());
        assertEquals("每行只绑一次", 5, center.evidenceBinds());
        View head = firstPass.get(0);

        center.open(store.record(narrow.id));
        List<View> secondPass = shown(tagged(center, "evidence-row"));
        assertEquals(2, secondPass.size());
        assertSame("视图对象留着复用，没有重新长出来", head, secondPass.get(0));
        assertEquals(5, center.evidenceRowCount());
        assertEquals("换一张报告也只按行数绑定一次", 2, center.evidenceBinds());
        assertTrue("换报告后第一行说的是新报告那段", shows(secondPass.get(0), "第 1/2 处"));
        assertTrue(shows(secondPass.get(1), "第 2/2 处"));

        // 藏起来的行必须把手上的活摘干净：留着上一条报告的区间，点一下就会跳到别的报告去。
        List<View> hidden = new ArrayList<View>();
        for (View row : tagged(center, "evidence-row")) {
            if (row.getVisibility() != View.VISIBLE) hidden.add(row);
        }
        assertEquals(3, hidden.size());
        hidden.get(0).performClick();
        assertTrue("藏起来的行不该还点得动", jumps.hits.isEmpty());
        List<View> deadArrows = tagged(hidden.get(0), "evidence-jump");
        assertEquals(1, deadArrows.size());
        deadArrows.get(0).performClick();
        assertTrue(jumps.hits.isEmpty());

        center.open(store.record(wide.id));
        assertEquals(5, shown(tagged(center, "evidence-row")).size());
        assertEquals(5, center.evidenceBinds());
        assertEquals(5, center.evidenceRowCount());
        assertEquals("三轮重开，视图数没有一轮比上一轮长", 5, center.evidenceRowCount());
        assertSame(head, shown(tagged(center, "evidence-row")).get(0));
    }

    @Test public void unfinishedReportRefusesToPrintRates() {
        ReportStore store = new ReportStore(freshDir("unfinished"));
        ReportStore.Record blank = hand("什么都没查出来.docx", 9000L, 0);
        blank.state = ReportStore.STATE_UNFINISHED;
        blank.reason = "联网检索没有取回可比对的候选文献";
        blank.duplicateChars = 0;
        // 什么都没查出来就没有来源榜：留一条带 12.5% 的来源行，等于让没查出来的报告自己打自己。
        blank.sources.clear();
        blank.sourcesTotal = 0;
        blank.engines.clear();
        blank.evidenceTotal = 0;
        ReportStore.Record saved = store.save(blank);
        Jumps jumps = new Jumps();
        ReportCenterUI center = panel(store, jumps);

        center.open(store.record(saved.id));
        assertTrue("未完成查重一张指标卡都不画", tagged(center, "metric-card").isEmpty());
        assertTrue(tagged(center, "metric-overall").isEmpty());
        assertTrue(shows(center, "未完成查重"));
        assertTrue(shows(center, "联网检索没有取回可比对的候选文献"));
        assertFalse("12.5% 这种数不能从一条没查出来的报告里印出来", shows(center, "12.5%"));
        assertTrue("但要说清未命中不等于全文没有重复", shows(center, "未命中不等于全文没有重复"));

        center.showHistory();
        List<View> items = shown(tagged(center, "report-item"));
        assertEquals(1, items.size());
        assertFalse("列表行同样不许印比率", shows(items.get(0), "12.5%"));
        assertTrue(shows(items.get(0), "未完成查重"));
    }

    @Test public void everyTouchTargetIsFortyEightDipsAndEveryIconButtonIsNamed() {
        ReportStore store = new ReportStore(freshDir("touch"));
        ReportStore.Record saved = store.save(hand("触控.docx", 11000L, 3));
        ReportCenterUI center = panel(store, new Jumps());
        int min = minTouch();
        assertTrue("48dp 在当前密度下应当真的是 48 像素起步", min >= 48);

        for (View row : shown(tagged(center, "report-item"))) {
            measure(row);
            assertTrue("历史列表一行至少 48dp", row.getMeasuredHeight() >= min);
        }
        center.open(store.record(saved.id));
        String[] tags = { "report-back", "report-export", "evidence-row", "source-row", "engine-row", "metric-card" };
        for (int i = 0; i < tags.length; i++) {
            List<View> views = tagged(center, tags[i]);
            assertFalse(tags[i] + " 应当存在", views.isEmpty());
            for (View view : views) {
                measure(view);
                assertTrue(tags[i] + " 的触控目标至少 48dp（实得 " + view.getMeasuredHeight() + "）",
                        view.getMeasuredHeight() >= min);
            }
        }
        List<View> arrows = tagged(center, "evidence-jump");
        assertEquals(3, arrows.size());
        for (int i = 0; i < arrows.size(); i++) {
            View arrow = arrows.get(i);
            measure(arrow);
            assertTrue("跳转图标本身也要够 48dp", arrow.getMeasuredHeight() >= min && arrow.getMeasuredWidth() >= min);
            assertNotNull("图标按钮必须带 contentDescription", arrow.getContentDescription());
            assertTrue(arrow.getContentDescription().length() > 0);
            assertEquals("→", ((TextView) arrow).getText().toString());
        }
        String[] glyphs = { "report-back", "report-export" };
        for (int i = 0; i < glyphs.length; i++) {
            View icon = tagged(center, glyphs[i]).get(0);
            assertNotNull(glyphs[i] + " 必须带 contentDescription", icon.getContentDescription());
            assertTrue(icon.getContentDescription().length() > 0);
        }
    }

    /** 记录文件坏了的时候：留在列表上、说清读不出来，别把半张报告画出来。 */
    @Test public void aRecordThatCannotBeReadStaysOnTheListAndSaysSo() throws Exception {
        File dir = freshDir("broken");
        ReportStore writer = new ReportStore(dir);
        ReportStore.Record good = writer.save(hand("读得动.docx", 12000L, 2));
        ReportStore.Record bad = writer.save(hand("读不动.docx", 13000L, 3));
        File broken = new File(dir, bad.id + ".json");
        java.io.RandomAccessFile cut = new java.io.RandomAccessFile(broken, "rw");
        try {
            cut.setLength(broken.length() / 2);
        } finally {
            cut.close();
        }

        ReportCenterUI center = panel(new ReportStore(dir), new Jumps());
        assertEquals("索引还在，两条都列得出来", 2, shown(tagged(center, "report-item")).size());
        assertTrue(tagged(center, "report-error").isEmpty());

        assertFalse("读不动的那条不报成功", center.open(bad.id));
        assertFalse("读不动就不进详情", center.isShowingDetail());
        assertEquals(1, tagged(center, "report-error").size());
        assertTrue(shows(center, "报告记录已损坏"));

        assertTrue("其余那条照样打得开", center.open(good.id));
        assertTrue(center.isShowingDetail());
        center.showHistory();
        assertTrue("回到列表，顶上那句提示跟着清掉", tagged(center, "report-error").isEmpty());
    }

    /** 入口挂在 ribbon 的引用页上：报告中心不能只能从代码里打开。 */
    @Test public void theRibbonOffersTheReportCenter() {
        final ArrayList<String> fired = new ArrayList<String>();
        RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "论文.docx", new RibbonUI.Listener() {
            public void command(String id) {
                fired.add(id);
            }
        });
        ribbon.select("引用");
        List<View> entry = tagged(ribbon, "command-report-center");
        assertEquals(1, entry.size());
        assertTrue("按钮上写的是人话", shows(entry.get(0), "报告中心"));
        entry.get(0).performClick();
        assertEquals("点的就是报告中心那一条", "report-center", fired.get(0));
        assertEquals(1, fired.size());
    }
}
