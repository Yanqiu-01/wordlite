package com.rikkahub.wordlite;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 报告中心（1.0.0）：历史报告列表 + 详情页（指标卡 / 来源分布 / 证据表），点一条证据请编辑器跳到那段字。
 *
 * 面板不自己算任何一个数，所有数字都从 {@link ReportStore.Record} 里读：
 * 一旦这里开始现算，就会出现面板说 12%、导出的报告说 11.4% 那种对不上的事，
 * 而这是全应用最容易让用户抓出来的自相矛盾。
 *
 * 与 {@link RibbonUI}、{@link ReviewPanel} 同一套做法：纯标准控件、没有布局文件、颜色自己按深浅色取。
 * 1.0.6 要把触控目标与 contentDescription 写成断言，这两件事在这里一次做齐——
 * 每个能点的东西最低 48dp，每个图标按钮都带 contentDescription，不留给以后回头补。
 */
public final class ReportCenterUI extends LinearLayout {
    /** 详情页要请编辑器做的事。只报字符区间：怎么映射回段落，由拿着文档的那一侧说了算。 */
    public interface Listener {
        void jumpTo(ReportStore.Record record, ReportStore.Evidence hit);

        void export(ReportStore.Record record);
    }

    private final LinearLayout header;
    private final LinearLayout listPane;
    private final LinearLayout detailPane;
    private final LinearLayout metricRow;
    private final LinearLayout metricStack;
    private final LinearLayout noteBox;
    private final LinearLayout engineBox;
    private final LinearLayout sourceBox;
    private final LinearLayout evidenceBox;
    private final LinearLayout hitMapBox;
    private final HitMapView hitMap;
    private final TextView hitMapLegend;
    private final TextView title;
    private final TextView coverage;
    private final TextView footLine;
    private final TextView evidenceTitle;
    private final TextView banner;
    private final TextView back;
    private final TextView export;
    private final Typeface medium;

    private ReportStore store;
    private ReportStore.Record opened;
    private Listener listener;

    /** 证据行的复用池：重开一张报告只改文本，不重新长出一屏视图（1.0.2 要断言这件事）。 */
    private final ArrayList<EvidenceRow> evidenceRows = new ArrayList<EvidenceRow>();
    private int evidenceBinds;
    private int evidencePasses;

    private final int surface;
    private final int card;
    private final int ink;
    private final int muted;
    private final int accent;
    private final int hairline;

    public ReportCenterUI(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setTag("report-center");
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        surface = dark ? 0xFF1E1E1E : 0xFFFFFFFF;
        card = dark ? 0xFF262626 : 0xFFF4F6F8;
        ink = dark ? 0xFFE8E8E8 : 0xFF1A1A1A;
        muted = dark ? 0xFF9A9A9A : 0xFF6B7178;
        accent = 0xFF2B579A;
        hairline = dark ? 0xFF3A3A3A : 0xFFD6D6D6;
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        setBackgroundColor(surface);

        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, dp(4), 0);
        back = icon("←", "返回历史列表", "report-back");
        back.setOnClickListener(view -> showHistory());
        header.addView(back, touch(-2));
        title = text("报告中心", 15, ink);
        title.setTypeface(medium);
        title.setTag("report-title");
        header.addView(title, new LayoutParams(0, dp(48), 1));
        export = icon("↓", "导出这份报告的 HTML", "report-export");
        export.setVisibility(GONE);
        export.setOnClickListener(view -> {
            if (listener != null && opened != null) listener.export(opened);
        });
        header.addView(export, touch(-2));
        addView(header, new LayoutParams(-1, dp(48)));

        View divider = new View(context);
        divider.setBackgroundColor(hairline);
        addView(divider, new LayoutParams(-1, 1));

        ScrollView scroll = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setPadding(dp(12), dp(6), dp(12), dp(20));
        scroll.addView(body);
        addView(scroll, new LayoutParams(-1, 0, 1));

        listPane = new LinearLayout(context);
        listPane.setOrientation(VERTICAL);
        listPane.setTag("report-list");
        body.addView(listPane, new LayoutParams(-1, -2));

        detailPane = new LinearLayout(context);
        detailPane.setOrientation(VERTICAL);
        detailPane.setVisibility(GONE);
        detailPane.setTag("report-detail");
        body.addView(detailPane, new LayoutParams(-1, -2));

        banner = text("", 14, accent);
        banner.setTag("report-banner");
        banner.setVisibility(GONE);
        detailPane.addView(banner, new LayoutParams(-1, -2));

        metricRow = new LinearLayout(context);
        metricRow.setOrientation(HORIZONTAL);
        metricRow.setTag("metric-cards");
        detailPane.addView(metricRow, new LayoutParams(-1, -2));
        metricStack = new LinearLayout(context);
        metricStack.setOrientation(VERTICAL);
        detailPane.addView(metricStack, new LayoutParams(-1, -2));

        footLine = text("", 12, muted);
        footLine.setTag("metric-foot");
        detailPane.addView(footLine, new LayoutParams(-1, -2));
        coverage = text("", 12, muted);
        coverage.setTag("coverage-line");
        detailPane.addView(coverage, new LayoutParams(-1, -2));

        // 命中地图排在指标卡与注记之间：它回答"重复在哪几处"，是看完比率之后自然要问的第二件事。
        hitMapBox = new LinearLayout(context);
        hitMapBox.setOrientation(VERTICAL);
        hitMapBox.setTag("hit-map");
        TextView mapTitle = section("命中地图");
        mapTitle.setTag("hit-map-title");
        hitMapBox.addView(mapTitle, new LayoutParams(-1, -2));
        hitMap = new HitMapView(context);
        hitMap.setTag("hit-map-band");
        hitMap.setPalette(dark ? 0xFF3A3A3A : 0xFFE3E6E8,
                0xFFC0392B, 0xFFE08A1E, 0xFF1F7A6C, dark ? 0xFF6E8A83 : 0xFF9FBFB8);
        hitMap.setOnPick(band -> jumpToBand(band));
        hitMapBox.addView(hitMap, new LayoutParams(-1, dp(48)));
        hitMapLegend = text("", 11, muted);
        hitMapLegend.setTag("hit-map-legend");
        hitMapBox.addView(hitMapLegend, new LayoutParams(-1, -2));
        detailPane.addView(hitMapBox, new LayoutParams(-1, -2));

        noteBox = new LinearLayout(context);
        noteBox.setOrientation(VERTICAL);
        noteBox.setTag("report-notes");
        detailPane.addView(noteBox, new LayoutParams(-1, -2));
        engineBox = new LinearLayout(context);
        engineBox.setOrientation(VERTICAL);
        engineBox.setTag("engine-rows");
        detailPane.addView(engineBox, new LayoutParams(-1, -2));
        sourceBox = new LinearLayout(context);
        sourceBox.setOrientation(VERTICAL);
        sourceBox.setTag("source-rows");
        detailPane.addView(sourceBox, new LayoutParams(-1, -2));

        evidenceTitle = section("证据表");
        evidenceTitle.setTag("evidence-title");
        detailPane.addView(evidenceTitle, new LayoutParams(-1, -2));
        evidenceBox = new LinearLayout(context);
        evidenceBox.setOrientation(VERTICAL);
        evidenceBox.setTag("evidence-rows");
        detailPane.addView(evidenceBox, new LayoutParams(-1, -2));
    }

    /* ================= 对外的三个动作 ================= */

    /** 接上数据与回调；面板不自己打开库，目录由调用方决定，和自建库一个路子。 */
    public void bind(ReportStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
        showHistory();
    }

    /** 历史列表，新的在前。列表只读 index.json 的摘要，不把证据表整份读进内存。 */
    public void showHistory() {
        opened = null;
        title.setText("报告中心");
        back.setVisibility(GONE);
        export.setVisibility(GONE);
        detailPane.setVisibility(GONE);
        listPane.setVisibility(VISIBLE);
        listPane.removeAllViews();
        ArrayList<ReportStore.Summary> history = store == null
                ? new ArrayList<ReportStore.Summary>() : store.history();
        if (history.isEmpty()) {
            TextView empty = text("还没有历史报告。查重完成后，这里会留下可以回看的那一份。", 13, muted);
            empty.setTag("report-empty");
            empty.setMinHeight(dp(48));
            empty.setGravity(Gravity.CENTER_VERTICAL);
            listPane.addView(empty, new LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < history.size(); i++) listPane.addView(historyRow(history.get(i)));
    }

    /** 按编号打开详情：列表那一层只握着摘要，进详情才读那一个文件。 */
    public boolean open(String id) {
        ReportStore.Record record = store == null ? null : store.record(id);
        if (record != null) {
            open(record);
            return true;
        }
        // 读不动就留在列表上，并把原因写在列表顶上：点一条没反应是最让人怀疑界面死了的错法。
        showHistory();
        TextView error = text(store == null ? "还没有接上报告库。" : "这条报告已经读不出来了：" + store.lastError(), 13, accent);
        error.setTag("report-error");
        error.setMinHeight(dp(48));
        error.setGravity(Gravity.CENTER_VERTICAL);
        listPane.addView(error, 0, new LayoutParams(-1, -2));
        return false;
    }

    /** 打开详情。record 为 null 时留在列表上，不画一张空报告。 */
    public void open(ReportStore.Record record) {
        if (record == null) return;
        opened = record;
        listPane.setVisibility(GONE);
        detailPane.setVisibility(VISIBLE);
        back.setVisibility(VISIBLE);
        export.setVisibility(VISIBLE);
        title.setText(record.fileName);
        renderMetrics(record);
        renderHitMap(record);
        renderNotes(record);
        renderSources(record);
        renderEvidence(record);
    }

    public boolean isShowingDetail() {
        return detailPane.getVisibility() == VISIBLE;
    }

    /** 当前详情用的是哪条记录，测试与回调都靠它确认没串到别条报告上。 */
    public ReportStore.Record opened() {
        return opened;
    }

    public int evidenceRowCount() {
        return evidenceRows.size();
    }

    public int evidenceBinds() {
        return evidenceBinds;
    }

    public int evidencePasses() {
        return evidencePasses;
    }

    /* ================= 历史列表 ================= */

    private LinearLayout historyRow(ReportStore.Summary summary) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(VERTICAL);
        row.setTag("report-item");
        row.setPadding(dp(4), dp(9), dp(4), dp(9));
        row.setMinimumHeight(dp(56));
        row.setBackgroundResource(selectable());
        final String id = summary.id;
        row.setOnClickListener(view -> open(id));
        boolean unfinished = ReportStore.STATE_UNFINISHED.equals(summary.state);
        // 未完成查重不印比率：什么都没查出来时，0.00% 会被读成"全文没有重复"，那是全应用最贵的一个误读。
        String head = unfinished ? summary.fileName : summary.fileName + "   " + percent(summary.overallRate);
        TextView name = text(head, 14, ink);
        name.setTypeface(medium);
        name.setMaxLines(2);
        row.addView(name, new LayoutParams(-1, -2));
        StringBuilder meta = new StringBuilder();
        meta.append(summary.detectedAt).append(" · ").append(summary.state)
                .append(" · 命中 ").append(summary.evidenceTotal).append(" 处");
        if (summary.truncated) meta.append(" · 已截断");
        TextView tail = text(meta.toString(), 12, muted);
        tail.setMaxLines(2);
        row.addView(tail, new LayoutParams(-1, -2));
        return row;
    }

    /* ================= 详情：指标卡 ================= */

    private void renderMetrics(ReportStore.Record record) {
        metricRow.removeAllViews();
        metricStack.removeAllViews();
        LinearLayout second = new LinearLayout(getContext());
        second.setOrientation(HORIZONTAL);
        metricStack.addView(second, new LayoutParams(-1, -2));
        if (!record.metricsValid()) {
            metricRow.setVisibility(GONE);
            metricStack.setVisibility(GONE);
            footLine.setText("");
            banner.setVisibility(VISIBLE);
            banner.setText("未完成查重：" + reason(record));
            coverage.setText(record.coverageNote);
            return;
        }
        banner.setVisibility(GONE);
        metricRow.setVisibility(VISIBLE);
        metricStack.setVisibility(VISIBLE);
        metricRow.addView(metric("metric-overall", "总相似度比", percent(record.overallRate)), cardCell());
        metricRow.addView(metric("metric-uncited", "去除引用重复比", percent(record.excludingCitationsRate)), cardCell());
        second.addView(metric("metric-self", "自编率", percent(record.selfWrittenRate)), cardCell());
        // 第四格刻意不是百分比：机器生成倾向是档位 + 字数，挂上百分号就成了第四个"比率"，与 0.7.1 那条口径冲突。
        second.addView(metric("metric-aigc", "机器生成倾向", record.aigcVerdict), cardCell());
        StringBuilder foot = new StringBuilder();
        foot.append("参与比对 ").append(record.comparedChars).append(" 个有效字符，命中 ")
                .append(record.duplicateChars).append(" 个，其中引用区间内 ").append(record.citedDuplicateChars)
                .append(" 个；自编 ").append(record.selfWrittenChars).append(" 个");
        // 未量到的那一轮不印可疑字数：这个数来自同一条没验正过的门槛，留着它等于换个名字继续报数。
        if (record.machineChars > 0 && !record.aigcUnmeasured)
            foot.append("；机器腔可疑 ").append(record.machineChars).append(" 个");
        if (record.excludedChars > 0) foot.append("；另有结构性文本 ").append(record.excludedChars).append(" 个未参与比对");
        footLine.setText(foot.toString());
        coverage.setText(record.coverageNote);
        if (ReportStore.STATE_PARTIAL.equals(record.state)) {
            // 部分完成必须把"相似率是下限"顶到显眼处，否则读者会把 12% 读成上限。
            banner.setVisibility(VISIBLE);
            banner.setText(reason(record));
        }
    }

    private LinearLayout metric(String tag, String label, String value) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setMinimumHeight(dp(72));
        card.setBackground(cardBackground());
        card.setTag("metric-card");
        TextView name = text(label, 11, muted);
        card.addView(name, new LayoutParams(-1, -2));
        TextView number = text(value, 15, ink);
        number.setTypeface(medium);
        number.setTag(tag);
        card.addView(number, new LayoutParams(-1, -2));
        return card;
    }

    /* ================= 详情：注记 / 来源分布 ================= */

    private void renderNotes(ReportStore.Record record) {
        noteBox.removeAllViews();
        if (record.notes.isEmpty() && record.notesTotal == 0) return;
        noteBox.addView(section("检测说明"), new LayoutParams(-1, -2));
        for (int i = 0; i < record.notes.size(); i++) {
            TextView line = text("提示：" + record.notes.get(i), 12, muted);
            line.setTag("report-note");
            line.setMinHeight(dp(32));
            noteBox.addView(line, new LayoutParams(-1, -2));
        }
        int hidden = record.notesTotal - record.notes.size();
        if (hidden > 0) {
            TextView tail = text("另有 " + hidden + " 条注记未随报告保存。", 12, muted);
            tail.setTag("report-note-tail");
            noteBox.addView(tail, new LayoutParams(-1, -2));
        }
    }

    private void renderSources(ReportStore.Record record) {
        engineBox.removeAllViews();
        sourceBox.removeAllViews();
        if (!record.engines.isEmpty()) {
            engineBox.addView(section("按检索源分布"), new LayoutParams(-1, -2));
            for (int i = 0; i < record.engines.size(); i++) {
                ReportStore.EngineRow row = record.engines.get(i);
                TextView line = text(PaperSources.label(row.engine) + "  " + percent(row.share)
                        + " · 候选 " + row.candidates + " 篇 · 提问 " + row.windows + " 个窗口组", 13, ink);
                line.setTag("engine-row");
                line.setMinHeight(dp(48));
                line.setGravity(Gravity.CENTER_VERTICAL);
                engineBox.addView(line, new LayoutParams(-1, -2));
            }
        }
        sourceBox.addView(section(record.sourcesTotal > record.sources.size()
                ? "来源榜 " + record.sourcesTotal + " 篇（列 " + record.sources.size() + " 行）"
                : "来源榜 " + record.sources.size() + " 篇"), new LayoutParams(-1, -2));
        if (record.sources.isEmpty()) {
            TextView none = text("本次没有文献命中相似片段。未命中不等于全文没有重复。", 13, muted);
            none.setTag("source-empty");
            none.setMinHeight(dp(48));
            sourceBox.addView(none, new LayoutParams(-1, -2));
            return;
        }
        for (int i = 0; i < record.sources.size(); i++) {
            ReportStore.SourceRow row = record.sources.get(i);
            String who = row.others ? "其余 " + row.othersCount + " 篇合计"
                    : (row.title.length() == 0 ? "未署名文献" : row.title);
            String meta = "重复 " + row.duplicateChars + " 字 · " + percent(row.share)
                    + " · 命中 " + row.hitCount + " 处 · " + PaperSources.label(row.engine);
            LinearLayout line = twoLine(who, meta);
            line.setTag("source-row");
            sourceBox.addView(line, new LayoutParams(-1, -2));
        }
    }

    /* ================= 详情：证据表（视图复用 + 点一下跳正文） ================= */

    /**
     * 证据表重绑：视图池里已有的行只改文本，不够才长新的，多出来的藏起来下次接着用。
     * 每开一张报告，每行恰好被绑一次（evidenceBinds == 行数）——绑定两次就会在长列表滚动时
     * 出现同一行被两个命中先后占用，那是比重画慢一点严重得多的错。
     */
    /**
     * 命中地图：段宽、四色、能不能跳，全部来自 HitMapModel.build，这里只做显示与派发。
     * 分母用 sourceChars——那是证据偏移所在的那把尺；相似率的分母是可比字数，两者不是一把尺，
     * 所以图例只印字数，不印百分比，免得被读成"色带涂掉的百分比 == 相似率"。
     */
    private void renderHitMap(ReportStore.Record record) {
        HitMapModel.Map map = HitMapModel.build(record.sourceChars, record.evidence, record.aiSegments,
                record.evidenceTruncated || record.aiSegmentsTruncated, record.evidence.size(),
                record.evidenceTotal, record.aiSegments.size(), record.aiSegmentsTotal);
        hitMap.setMap(map);
        if (map.isEmpty()) {
            hitMapBox.setVisibility(record.evidenceTotal > 0 || record.aiSegmentsTotal > 0 ? VISIBLE : GONE);
            hitMap.setVisibility(GONE);
            hitMapLegend.setText(record.evidenceTotal > 0
                    ? "命中的区间都越出了这篇正文的范围，画不出地图" : "");
            return;
        }
        hitMapBox.setVisibility(VISIBLE);
        hitMap.setVisibility(VISIBLE);
        int[] chars = new int[4];
        for (int i = 0; i < map.bands.size(); i++) chars[map.bands.get(i).kind] += map.bands.get(i).chars();
        StringBuilder legend = new StringBuilder();
        legend.append("重复 ").append(chars[HitMapModel.KIND_DUPLICATE]).append(" 字 · 改写 ")
                .append(chars[HitMapModel.KIND_REWRITTEN]).append(" 字 · AI 可疑 ")
                .append(chars[HitMapModel.KIND_AI]).append(" 字 · 疑似 ")
                .append(chars[HitMapModel.KIND_SUSPECTED]).append(" 字（段宽 = 字数占比，全文 ")
                .append(map.totalChars).append(" 字）");
        if (map.incomplete) {
            legend.append("· 只画了本机保留的 ").append(map.keptEvidence).append('/').append(map.totalEvidence)
                    .append(" 处命中与 ").append(map.keptAi).append('/').append(map.totalAi).append(" 段 AI");
        }
        hitMapLegend.setText(legend.toString());
    }

    /** 色带上的点：证据段跳回那一条证据（与证据表同一把尺），AI 段按它自己的区间跳。 */
    private void jumpToBand(HitMapModel.Band band) {
        if (opened == null || band == null) return;
        ReportStore.Evidence hit = null;
        if (band.fromEvidence && band.sourceIndex >= 0 && band.sourceIndex < opened.evidence.size()) {
            hit = opened.evidence.get(band.sourceIndex);
        }
        if (hit == null) {
            hit = new ReportStore.Evidence();
            hit.start = band.start;
            hit.end = band.end;
        }
        if (!opened.canJump(hit)) return;
        if (listener != null) listener.jumpTo(opened, hit);
    }

    /** 测试与外部确认色带状态用：现在画了几段、图例说了什么。 */
    public int hitMapBandCount() {
        return hitMap.getBandCount();
    }

    public String hitMapLegendText() {
        return hitMapLegend.getText().toString();
    }

    private void renderEvidence(ReportStore.Record record) {
        evidenceBinds = 0;
        evidencePasses++;
        int wanted = record.evidence.size();
        while (evidenceRows.size() < wanted) {
            EvidenceRow row = new EvidenceRow(getContext());
            evidenceBox.addView(row, new LayoutParams(-1, -2));
            evidenceRows.add(row);
        }
        for (int i = 0; i < evidenceRows.size(); i++) {
            EvidenceRow row = evidenceRows.get(i);
            if (i < wanted) {
                row.bind(record, i + 1, wanted);
                evidenceBinds++;
            } else {
                row.recycle();
            }
        }
        String head = "证据表 " + wanted + " 处";
        if (record.evidenceTotal > wanted) head = head + "（共 " + record.evidenceTotal + " 处，只保留可跳转的前 " + wanted + " 处）";
        if (wanted == 0) head = head + (record.evidenceTotal > 0 ? "（区间越界，没有可跳转的证据）" : "（本次没有命中）");
        evidenceTitle.setText(head);
    }

    /** 证据表的一行：整行可点，末尾再给一个带 contentDescription 的跳转图标。视图对象留着复用，只换文本。 */
    private final class EvidenceRow extends LinearLayout {
        private final TextView primary;
        private final TextView secondary;
        private final TextView jump;
        private ReportStore.Record record;
        private ReportStore.Evidence hit;

        EvidenceRow(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(64));
            setPadding(dp(4), dp(8), dp(4), dp(8));
            setBackgroundResource(selectable());
            setTag("evidence-row");
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            primary = text("", 14, ink);
            primary.setMaxLines(3);
            column.addView(primary, new LayoutParams(-1, -2));
            secondary = text("", 11, muted);
            column.addView(secondary, new LayoutParams(-1, -2));
            addView(column, new LayoutParams(0, -2, 1));
            jump = icon("→", "跳到正文这段文字", "evidence-jump");
            addView(jump, touch(-2));
        }

        void bind(ReportStore.Record record, int index, int total) {
            // 复用回来的第一句话就是把自己亮出来：藏起来的行如果只改文本不亮回来，
            // 先开两处命中再开五处命中，屏幕上就只有两行。
            setVisibility(VISIBLE);
            this.record = record;
            this.hit = record.evidence.get(index - 1);
            primary.setText(hit.snippet.length() == 0 ? "（这段没有可显示的原文）" : hit.snippet);
            secondary.setText("第 " + index + "/" + total + " 处 · 正文 " + hit.start + "-" + hit.end
                    + " 字 · 相似度 " + percent(hit.score * 100d) + " · " + PaperSources.label(hit.engine)
                    + (hit.year.length() == 0 ? "" : "（" + hit.year + "）")
                    + (hit.title.length() == 0 ? "" : " 《" + hit.title + "》"));
            setEnabled(true);
            setOnClickListener(view -> go());
            jump.setOnClickListener(view -> go());
        }

        /** 复用的前提是把手上的活摘干净：留着上一条的闭包，下一次点就会跳到别人的位置去。 */
        void recycle() {
            setVisibility(GONE);
            setOnClickListener(null);
            jump.setOnClickListener(null);
            setEnabled(false);
            record = null;
            hit = null;
        }

        ReportStore.Evidence bound() {
            return hit;
        }

        private void go() {
            if (listener != null && record != null && hit != null) listener.jumpTo(record, hit);
        }
    }

    /* ================= 小工具 ================= */

    private TextView section(String value) {
        TextView out = text(value, 13, ink);
        out.setTypeface(medium);
        out.setPadding(0, dp(14), 0, dp(4));
        return out;
    }

    private LinearLayout twoLine(String top, String bottom) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(0, dp(6), 0, dp(6));
        TextView first = text(top, 13, ink);
        first.setMaxLines(2);
        row.addView(first, new LayoutParams(-1, -2));
        TextView second = text(bottom, 11, muted);
        second.setMaxLines(2);
        row.addView(second, new LayoutParams(-1, -2));
        return row;
    }

    private TextView text(String value, int size, int color) {
        TextView out = new TextView(getContext());
        out.setText(value);
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        out.setTextColor(color);
        return out;
    }

    /**
     * 图标按钮：字面是一个字形，含义写进 contentDescription。
     * 1.0.6 会按"每个图标按钮都得有 contentDescription"下断言，这里一次做齐，不给以后回头补的机会。
     */
    private TextView icon(String glyph, String description, String tag) {
        TextView out = text(glyph, 17, ink);
        out.setGravity(Gravity.CENTER);
        out.setContentDescription(description);
        out.setBackgroundResource(selectable());
        out.setTag(tag);
        out.setMinWidth(dp(48));
        out.setMinHeight(dp(48));
        return out;
    }

    /** 触控目标下限 48dp：这是手指的最小可点尺寸，不是审美选择。 */
    private LayoutParams touch(int width) {
        return new LayoutParams(width, dp(48));
    }

    private LayoutParams cardCell() {
        LayoutParams out = new LayoutParams(0, dp(72), 1);
        out.setMargins(dp(3), dp(3), dp(3), dp(3));
        return out;
    }

    private android.graphics.drawable.GradientDrawable cardBackground() {
        android.graphics.drawable.GradientDrawable out = new android.graphics.drawable.GradientDrawable();
        out.setColor(card);
        out.setCornerRadius(dp(8));
        return out;
    }

    private static String percent(double value) {
        return String.format(Locale.US, "%.1f%%", Double.valueOf(value));
    }

    private static String reason(ReportStore.Record record) {
        return record.reason.length() == 0 ? "本次没有可比对的文献来源" : record.reason;
    }

    private int selectable() {
        TypedValue value = new TypedValue();
        return getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                ? value.resourceId : 0;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
