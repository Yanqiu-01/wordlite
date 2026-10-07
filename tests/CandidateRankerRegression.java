package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Collections;

/**
 * CandidateRanker 回归：BM25 排序、长度项、万能句压制、跨源合并、配额挑选、确定性与空值。
 * 全程不联网、不碰 Android，语料全是手写的，跑起来只依赖 host-classes 里的 TextCorpus 与 PaperSources。
 */
public final class CandidateRankerRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /** 每篇论文都会写的一句套话，谁都不该靠它排到前面。 */
    private static final String BOILER =
            "综上所述，本文作者独立完成了全部实验与数据分析工作，所得结论均由作者本人负责，未尽之处有待后续研究继续补充。";

    private static final String QUERY = "深度学习在医学影像分割中的应用";

    /** 一篇对题摘要，九百字量级，"深度学习"出现在两句里。 */
    private static final String[] TOPIC = {
            "深度学习在医学影像分割中的应用近年进展很快，但肺结节体积与密度的精确测量仍依赖人工勾画，"
                    + "一例胸部CT平均需要十二分钟，读片量的增长远快于放射科医师的补充速度。",
            "本文以卷积神经网络为骨架，设计了一条从肺野提取、候选区检出到结节边界回归的三级流水线，各级之间用可微的掩码传递衔接起来。",
            "第一级用阈值与血管追踪相结合的方法把肺野从胸腔影像中分离出来，避免肋骨与纵隔在后续分类里制造大量假阳。",
            "第二级在降采样后的三维块上滑动检出候选区，再用一个浅层网络把明显属于血管断端的假候选剔除，命中率提高到百分之九十六。",
            "第三级是带注意力门控的编码器解码器结构，编码器负责吸收上下文，门控负责把细枝末状的血管纹理挡在掩码之外。",
            "训练数据来自公开数据集与本地医院合作采集的两批影像，两者在层厚与重建核上差异明显，因此统一重采样到一毫米立方体素再入库。",
            "标注由两名放射科医师背靠背完成，意见不一致的层面交给第三名高年资医师裁定，最终保留一千二百例、共两千三百余个结节。",
            "损失函数把体素交叉熵与软化的Dice项加权求和，前者照顾类别不平衡，后者直接优化我们要报告的几何重叠指标。",
            "在留出的一百四十例测试集上，本方法与医生勾画的体积误差中位数为百分之八点二，Dice系数中位数为零点九一。",
            "与阈值分割、区域生长、随机森林三条基线相比，本方法在磨玻璃型结节上的优势最大，这类结节边界对比度最低，传统方法几乎失效。",
            "消融实验显示，去掉注意力门控后召回率下降四个百分点，把三级流水线退化为端到端一级网络时体积误差中位数翻了一倍，"
                    + "说明深度学习在这里的价值主要来自分工而不是堆层数。",
            "为检验跨中心泛化，模型未经微调直接跑在另一家医院的三百例影像上，体积误差中位数从八点二升到十一，仍低于人工复核改判的阈值。",
            "失败案例集中在两类：与胸膜粘连的结节会被整片并入胸膜，炎性渗出与结节难以从形态上区分，"
                    + "前者靠后处理的曲率约束纠正，后者仍需医师复核。",
            "结论是网络没有替代读片，而是把可重复的测量环节压缩到十几秒，医师的时间留给真正需要判断的疑难层面，"
                    + "这套流程已在合作医院的随访队列里稳定运行半年。",
    };

    private static String longSummary() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < TOPIC.length; i++) out.append(TOPIC[i]);
        return out.toString();
    }

    private static final String SHARED_TERM_ABSTRACT = "深度学习在医学影像分割里的应用，效果良好";
    private static final String OFF_TOPIC = "讨论了档案信息化管理的三条路径：完善制度、建设平台、培养人才，与影像无关。";

    private static PaperSources.Candidate candidate(String engine, String id, String title, String summary) {
        return candidate(engine, id, title, summary, "", "");
    }

    private static PaperSources.Candidate candidate(String engine, String id, String title, String summary,
                                                   String locator, String fullTextUrl) {
        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = engine;
        candidate.source.id = id;
        candidate.source.title = title;
        candidate.source.locator = locator;
        candidate.source.year = "2023";
        candidate.abstractText = summary;
        candidate.fullTextUrl = fullTextUrl;
        return candidate;
    }

    private static ArrayList<PaperSources.Candidate> poolOf(PaperSources.Candidate... items) {
        ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
        for (int i = 0; i < items.length; i++) pool.add(items[i]);
        return pool;
    }

    private static int validChars(String text) {
        return TextCorpus.validCount(TextCorpus.normalize(text), 0, text.length());
    }

    /** 名次指纹：候选身份 + 分数的原始位型，两次运行必须逐字符相同。 */
    private static String signature(ArrayList<CandidateRanker.Scored> ranked) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ranked.size(); i++) {
            CandidateRanker.Scored scored = ranked.get(i);
            out.append(scored.candidate.source.engine).append('/').append(scored.candidate.source.id)
                    .append('@').append(Double.doubleToLongBits(scored.score)).append(';');
        }
        return out.toString();
    }

    private static String planSignature(ArrayList<CandidateRanker.Selection> plan) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) {
            CandidateRanker.Selection pick = plan.get(i);
            out.append(pick.candidate.source.id).append('#').append(pick.rank)
                    .append(pick.fetchFullText ? 'F' : '-').append(';');
        }
        return out.toString();
    }

    public static void main(String[] args) {
        scoring();
        length();
        boilerplate();
        merging();
        quota();
        determinism();
        edges();
        System.out.println("SUMMARY " + count + " assertions passed"
                + " (打分与合并只在 host JVM 上跑，取数与 UI 不在本用例范围内).");
    }

    private static void scoring() {
        PaperSources.Candidate onTopic = candidate("openalex", "W1", "基于深度学习的医学影像分割方法",
                longSummary(), "https://doi.org/10.1007/s11760-020-01789-x", "https://example.org/a.pdf");
        PaperSources.Candidate offTopic = candidate("crossref", "C9", "高校档案信息化管理的路径探析",
                OFF_TOPIC, "10.5555/2020.0001", "");
        ArrayList<PaperSources.Candidate> pool = poolOf(offTopic, onTopic);
        ArrayList<CandidateRanker.Scored> ranked = CandidateRanker.rank(QUERY, pool);
        check(ranked.size() == 2, "两条候选都被打分，零分的候选不会被悄悄丢掉");
        check(ranked.get(0).candidate == onTopic, "BM25 把对题摘要排在离题摘要之前");
        check(ranked.get(0).score > ranked.get(1).score * 3, "两者的分差是数量级的，不是并列线上的抖动");
        check(ranked.get(0).matchedTerms > ranked.get(1).matchedTerms,
                "绝对命中量一起交出来：比例之外还要看共享了多少个三元组");
        check(ranked.get(1).score < 1d, "离题那条几乎没有可记的证据");
        check(ranked.get(0).documentLength > 600 && ranked.get(1).documentLength < 60,
                "dl 就是这条候选标题加摘要的三元组数，长摘要不会因为长而被压成小 dl");
        // 归一化必须复用 TextCorpus 的那一套：全半角、大小写的差异不该带来任何分数差
        ArrayList<PaperSources.Candidate> widths = poolOf(
                candidate("cnki", "N1", "Deep Learning图像分割", ""),
                candidate("cqvip", "Q1", "ＤＥＥＰ　ＬＥＡＲＮＩＮＧ图像分割", ""));
        ArrayList<CandidateRanker.Scored> folded = CandidateRanker.rank("deep learning 图像分割", widths);
        check(folded.size() == 2 && folded.get(0).score > 0 && folded.get(0).score == folded.get(1).score,
                "全角标题与半角标题得分相同，说明用的是 TextCorpus.normalize 而不是自己另写一套");
        check(folded.get(0).candidate.source.id.equals("N1") && folded.get(1).candidate.source.id.equals("Q1"),
                "得分相同的两条按 source id 定先后，不看引擎回来的顺序");
        check(CandidateRanker.rank(QUERY, poolOf()).isEmpty(), "空池返回空表而不是抛异常");
    }

    private static void length() {
        PaperSources.Candidate stub = candidate("crossref", "S1", "深度学习应用研究", SHARED_TERM_ABSTRACT);
        PaperSources.Candidate longOne = candidate("openalex", "L1", "深度学习在医学影像分割中的应用", longSummary());
        check(validChars(SHARED_TERM_ABSTRACT) <= 25, "样例里的短摘要确实在二十字量级");
        int chars = validChars(longSummary());
        check(chars >= 800 && chars <= 1000, "样例里的长摘要在九百字量级，实测 " + chars + " 字");
        ArrayList<CandidateRanker.Scored> ranked = CandidateRanker.rank(QUERY, poolOf(stub, longOne));
        check(ranked.get(0).candidate == longOne,
                "同一个术语下，九百字的对题摘要压过二十字的空壳：长度项只罚短壳的白送加分");
        check(ranked.get(1).candidate == stub && ranked.get(1).matchedTerms >= 1,
                "空壳确实命中了同一个术语，这场比较不是靠它零分蒙对");
        ArrayList<CandidateRanker.Scored> flipped = CandidateRanker.rank(QUERY, poolOf(longOne, stub));
        check(signature(flipped).equals(signature(ranked)), "长短对照与输入顺序无关");
        // 长度项本身要能被直接盯住：短文档一分便宜都不许占，长文档照 Lucene 原式挨罚
        check(CandidateRanker.lengthFactor(18, 300) == 1d && CandidateRanker.lengthFactor(300, 300) == 1d,
                "短于均长的文档拿不到长度加分，norm 下限就是 1");
        check(Math.abs(CandidateRanker.lengthFactor(600, 300) - 1.75d) < 1e-12,
                "两倍均长的惩罚是 1-0.75+0.75x2，惩罚侧没被偷偷放松");
        check(Math.abs(CandidateRanker.lengthFactor(1200, 300) - 3.25d) < 1e-12, "四倍均长惩罚 3.25，b=0.75 确实生效");
        boolean neverBelowOne = true;
        for (int dl = 1; dl <= 300; dl++) if (CandidateRanker.lengthFactor(dl, 300) < 1d) neverBelowOne = false;
        check(neverBelowOne, "从 1 到均长逐个试一遍，没有任何长度能拿到小于 1 的 norm");
        check(CandidateRanker.lengthFactor(5, 48) == 1d,
                "五个字的残骸被 MIN_DOC_GRAMS 抬到下限，买不到更短的优势");
        // tf 的饱和：命中术语的句数要顶用，而且不能被长度摊薄
        // 削掉末尾那处提及：开头那句里还有同一个术语，两条的命中集合才会一模一样，差别纯粹在句数上
        String fullText = longSummary();
        int lastMention = fullText.lastIndexOf("深度学习");
        String mentionedOnce = fullText.substring(0, lastMention) + "另一种方法"
                + fullText.substring(lastMention + "深度学习".length());
        int full = occurrences(fullText, "深度学习"), single = occurrences(mentionedOnce, "深度学习");
        check(full == 2 && single == 1,
                "对照样例把两处提及削成一处：" + full + " 处对 " + single + " 处，且没有削掉任何别的命中");
        ArrayList<CandidateRanker.Scored> tf = CandidateRanker.rank(QUERY, poolOf(
                candidate("arxiv", "T1", "一处提及", mentionedOnce),
                candidate("arxiv", "T2", "两处提及", longSummary())));
        check(tf.size() == 2 && tf.get(0).candidate.source.id.equals("T2") && tf.get(0).score > tf.get(1).score,
                "命中术语的句数更多的那条赢：k1 的饱和让长摘要不必按密度被摊薄");
        check(tf.get(0).matchedTerms == tf.get(1).matchedTerms,
                "两条命中的术语集合相同，差别只在句数，这条断言测的就是 tf 而不是命中数");
        ArrayList<CandidateRanker.Scored> tfFlipped = CandidateRanker.rank(QUERY, poolOf(
                candidate("arxiv", "T2", "两处提及", longSummary()),
                candidate("arxiv", "T1", "一处提及", mentionedOnce)));
        check(tfFlipped.get(0).candidate.source.id.equals("T2") && tfFlipped.get(0).score == tf.get(0).score,
                "这对对照也和输入顺序无关，分数逐位相同");
    }
    private static void boilerplate() {
        String query = BOILER + QUERY;
        PaperSources.Candidate boilerOnly = candidate("cnki", "U1", "某篇本科毕业论文", BOILER);
        PaperSources.Candidate topical = candidate("openalex", "T1", "深度学习在医学影像分割中的应用",
                BOILER + longSummary());
        ArrayList<PaperSources.Candidate> pool = poolOf(boilerOnly, topical,
                candidate("cqvip", "P2", "题目二", BOILER + "本文讨论了高年级数学课堂的提问策略。"),
                candidate("cqvip", "P3", "题目三", BOILER + "本文研究了城市地下管网的巡检排班。"),
                candidate("wanfang", "P4", "题目四", BOILER + "本文设计了图书馆座位预约的小程序。"),
                candidate("wanfang", "P5", "题目五", BOILER + "本文分析了校园快递柜的点位布局。"));
        ArrayList<CandidateRanker.Scored> ranked = CandidateRanker.rank(query, pool);
        check(ranked.get(0).candidate == topical, "整池都带同一句万能句时，靠内容命中的那条才排第一");
        double boiler = scoreOf(ranked, "U1");
        check(find(ranked, "U1").matchedTerms > 0, "万能句那条确实命中了查询里的套话，不是零分摆在这里");
        check(boiler < ranked.get(0).score, "只命中万能句的候选压不过命中内容词的候选");
        double universal = CandidateRanker.rawIdf(pool.size(), pool.size());
        double rare = CandidateRanker.rawIdf(1, pool.size());
        check(universal > 0d && universal * 10 < rare,
                "人人都有的三元组被压到稀有词的十分之一以下：这就是万能句在数学上的下场");
        check(CandidateRanker.rawIdf(2, pool.size()) < rare
                        && CandidateRanker.rawIdf(3, pool.size()) > universal,
                "idf 随 df 单调下降：df=6 的万能句在底下，df=1 的稀有词在顶上");
        double[] idf = CandidateRanker.idfFor(new int[]{6, 3, 2, 1}, 6);
        check(idf[0] < idf[1] && idf[1] < idf[2] && idf[2] < idf[3], "六条候选的池子里，权重按 df 逐级递减");
        check(CandidateRanker.rawIdf(1, 2) > 0d,
                "两条候选的小池子里 df=1 的权重仍是正数：idf 用 Lucene 的 log(1+..) 写法就是为了这个格");
        check(CandidateRanker.idfFor(new int[]{6}, 6)[0] > 0d, "整池只有同一个三元组时权重也大于零，只是小到几乎没有");
        ArrayList<PaperSources.Candidate> flat = poolOf(
                candidate("cnki", "S3", "同题三", BOILER),
                candidate("cnki", "S1", "同题一", BOILER),
                candidate("cnki", "S2", "同题二", BOILER));
        ArrayList<CandidateRanker.Scored> allSame = CandidateRanker.rank(BOILER, flat);
        boolean sameScore = true;
        for (int i = 1; i < allSame.size(); i++) if (allSame.get(i).score != allSame.get(0).score) sameScore = false;
        check(sameScore && allSame.get(0).score > 0d,
                "查询本身就是一句万能句时三条分数一模一样：套话给不出区分度，就不假装分得出高下");
        check(allSame.get(0).candidate.source.id.equals("S1")
                        && allSame.get(1).candidate.source.id.equals("S2")
                        && allSame.get(2).candidate.source.id.equals("S3"),
                "分数全零时名次退到确定性的 source id 序，重跑不洗牌");
    }

    private static void merging() {
        PaperSources.Candidate withAbstract = candidate("openalex", "W1", "基于深度学习的图像分割方法研究",
                longSummary(), "https://doi.org/10.1007/S11760-020-01789-X", "");
        PaperSources.Candidate recordOnly = candidate("crossref", "C1", "基于深度学习的图像分割方法", "",
                "doi:10.1007/s11760-020-01789-x.", "https://example.org/oa.pdf");
        CandidateRanker.Dedup byDoi = CandidateRanker.dedup(poolOf(withAbstract, recordOnly));
        check(byDoi.kept.size() == 1 && byDoi.merges.size() == 1, "同一个 DOI 从两个引擎回来只留一条");
        check(byDoi.kept.get(0) == withAbstract, "留下的是有摘要的那条");
        check(byDoi.merges.get(0).keyKind.equals("doi"), "账目记下用的是 DOI 键");
        check(byDoi.merges.get(0).key.equals("10.1007/s11760-020-01789-x"), "账目里的键是规范后的 DOI 本身");
        check(byDoi.merges.get(0).reason.contains("摘要"), "账目写清凭什么留它：先比有没有摘要，再比长短");
        check(byDoi.merges.get(0).dropped == recordOnly, "账目认得出被并掉的是哪一条");
        check(withAbstract.fullTextUrl.equals("https://example.org/oa.pdf"),
                "败者独有的开放获取链接补给胜者，去重不该白丢一次全文");
        check(withAbstract.source.title.endsWith("研究"), "剥尾缀只作用于键，显示用的标题一个字都不动");

        PaperSources.Candidate shortOne = candidate("cnki", "N7", "基于深度学习的图像分割方法研究", "提出一种两阶段方法。",
                "ncpssd:6789", "");
        PaperSources.Candidate longOne = candidate("cqvip", "Q7", "基于深度学习的图像分割方法", longSummary(),
                "https://www.cqvip.com/doc/93742010", "https://example.org/q.pdf");
        CandidateRanker.Dedup byTitle = CandidateRanker.dedup(poolOf(shortOne, longOne));
        check(byTitle.kept.size() == 1, "一边压根没有 DOI 时，靠标题指纹合并成一条");
        check(byTitle.merges.get(0).keyKind.equals("title"), "这种合并的账目记下用的是标题键");
        check(byTitle.kept.get(0) == longOne, "两条都没有可用 DOI 时按摘要长短定胜者");
        check(byTitle.kept.get(0).source.engine.equals("cqvip"), "胜者的来源身份不被改写，来源分布统计才不会漂移");

        CandidateRanker.Dedup sameTitle = CandidateRanker.dedup(poolOf(
                candidate("crossref", "D1", "肺结节CT分割的比较研究", "第一条摘要内容。", "10.1000/a.111", ""),
                candidate("crossref", "D2", "肺结节CT分割的比较研究", "第二条摘要内容。", "10.1000/b.222", "")));
        check(sameTitle.kept.size() == 2 && sameTitle.merges.isEmpty(),
                "标题一模一样但 DOI 不同：这是同名不同文，不合并");

        CandidateRanker.Dedup similarTitles = CandidateRanker.dedup(poolOf(
                candidate("wanfang", "F1", "基于深度学习的图像分割方法研究", "图像分割的两阶段流水线。", "", ""),
                candidate("wanfang", "F2", "基于深度学习的目标检测方法研究", "目标检测的两阶段流水线。", "", "")));
        check(similarTitles.kept.size() == 2 && similarTitles.merges.isEmpty(),
                "同句式不同对象的两个标题不被合并：剥的是尾缀，不是信息词");

        CandidateRanker.Dedup untitled = CandidateRanker.dedup(poolOf(
                candidate("cnki", "X1", "图像分割", "甲的摘要内容完全不同。", "", ""),
                candidate("cqvip", "X2", "图像分割", "乙的摘要内容也完全不同。", "", "")));
        check(untitled.kept.size() == 2, "标题短到只剩通用词时不凭它合并，够不到 MIN_TITLE_KEY 就不算键");

        PaperSources.Candidate keepUrl = candidate("openalex", "A1", "同一个 DOI 的两种记录甲", "甲的摘要。",
                "10.2000/same.1", "https://example.org/keep.pdf");
        PaperSources.Candidate longerAbstract = candidate("crossref", "A2", "同一个 DOI 的两种记录乙",
                "乙的摘要内容明显更长也更详细。", "10.2000/same.1", "https://example.org/other.pdf");
        CandidateRanker.Dedup adopt = CandidateRanker.dedup(poolOf(keepUrl, longerAbstract));
        check(adopt.kept.size() == 1 && adopt.kept.get(0) == longerAbstract, "两条都有摘要时留下更长的那条");
        check(longerAbstract.fullTextUrl.equals("https://example.org/other.pdf")
                        && keepUrl.fullTextUrl.equals("https://example.org/keep.pdf"),
                "补字段只补空位，已有的全文链接一律不覆盖");

        check(CandidateRanker.titleKey("深度學習圖像分割的研究").equals(CandidateRanker.titleKey("深度学习图像分割的研究")),
                "繁简写法在键上折成一回事，用的是 normalize 自带的常用繁简表");
        check(CandidateRanker.titleKey("《深度学习图像分割》研究").equals(CandidateRanker.titleKey("深度学习图像分割的研究")),
                "书名号与「的」这类标点虚词不进键，同一篇的两种写法能对上");
        check(CandidateRanker.titleKey("深度学习图像分割的研究综述")
                        .equals(CandidateRanker.titleKey("深度学习图像分割的研究")),
                "「研究综述」与「研究」剥到同一个键");
        check(CandidateRanker.titleKey("Ｐａｐｅｒ　ＡＢＣ研究").equals(CandidateRanker.titleKey("Paper ABC研究")),
                "全角标题与半角标题的键相同");
        check(CandidateRanker.doiKey("PMID:12345").isEmpty() && CandidateRanker.doiKey("ncpssd:6789").isEmpty()
                        && CandidateRanker.doiKey("https://www.cqvip.com/doc/93742010").isEmpty(),
                "PMID、哲社号、维普号都不是 DOI，不许拿来当键");
        check(CandidateRanker.doiKey("10.1007").isEmpty(), "少了斜杠的 10. 前缀不算 DOI");
        check(CandidateRanker.doiKey(null).isEmpty() && CandidateRanker.doiKey("").isEmpty(), "DOI 键遇到空值不抛");
        check(CandidateRanker.doiKey("HTTPS://DOI.ORG/10.1001/JAMA.2020.1234.").equals("10.1001/jama.2020.1234"),
                "DOI 键忽略大小写、doi.org 前缀和句尾那个点");
    }

    private static ArrayList<PaperSources.Candidate> quotaPool() {
        return poolOf(
                candidate("cnki", "N1", "深度学习在医学影像分割中的应用", longSummary(), "ncpssd:1", "https://example.org/n1.pdf"),
                candidate("cnki", "N2", "深度学习与医学影像分割", TOPIC[0], "ncpssd:2", ""),
                candidate("openalex", "O1", "深度学习医学影像分割方法", longSummary(), "10.3000/o1", "https://example.org/o1.pdf"),
                candidate("openalex", "O2", "医学影像的分割方法", TOPIC[1], "10.3000/o2", "https://example.org/o2.pdf"),
                candidate("openalex", "O3", "分割方法的比较", TOPIC[8], "10.3000/o3", "https://example.org/o3.pdf"),
                candidate("openalex", "O4", "档案信息化管理", OFF_TOPIC, "10.3000/o4", "https://example.org/o4.pdf"),
                candidate("openalex", "O5", "无线传感器网络", "综述了无线传感器网络的能耗均衡协议。",
                        "10.3000/o5", "https://example.org/o5.pdf"),
                candidate("crossref", "C1", "深度学习影像分割的评测", TOPIC[9], "10.3000/c1", "https://example.org/c1.pdf"),
                candidate("crossref", "C2", "影像分割的体积测量", TOPIC[0], "10.3000/c2", "https://example.org/c2.pdf"),
                candidate("crossref", "C3", "影像分割的体积测量", TOPIC[0], "10.3000/c3", ""));
    }

    private static void quota() {
        ArrayList<PaperSources.Candidate> pool = quotaPool();
        ArrayList<CandidateRanker.Selection> plan = CandidateRanker.plan(QUERY, pool, 3, 2);
        check(plan.size() == 6, "单源上限为 2 时，五个候选的 openalex 只能占两个位置，总名额 6 条");
        check(perSource(plan, "openalex") == 2 && perSource(plan, "crossref") == 2 && perSource(plan, "cnki") == 2,
                "每个检索源各占两条，谁也不许吃满预算");
        int fetches = fetches(plan);
        check(fetches == 3, "三次全文额度正好花完");
        check(!plan.get(0).fetchFullText && plan.get(0).candidate.fullTextUrl.trim().isEmpty(),
                "第一名自己没有全文链接就不替它花额度，预算顺延给后面真正有全文的");
        /* 瓶颈要看整池：每源上限先把 openalex 的五个候选砍到两个，计划里带链接的恰好只剩三条，
           与预算持平，这时计划内部看不出富余。真正该断言的是"还带着全文链接却没轮到抓的候选"不止一条。 */
        int waiting = 0;
        for (int i = 0; i < pool.size(); i++) {
            String url = pool.get(i).fullTextUrl == null ? "" : pool.get(i).fullTextUrl.trim();
            if (url.isEmpty()) continue;
            boolean grabbed = false;
            for (int k = 0; k < plan.size(); k++) {
                if (plan.get(k).candidate == pool.get(i) && plan.get(k).fetchFullText) grabbed = true;
            }
            if (!grabbed) waiting++;
        }
        check(waiting > fetches(pool, plan),
                "预算确实构成瓶颈：带着全文链接却排不上抓取的候选比花掉的额度多");
        boolean fetchedHaveUrl = true, ordered = true;
        double lowestFetched = Double.MAX_VALUE, highestUnfetchedWithUrl = -1;
        for (int i = 0; i < plan.size(); i++) {
            CandidateRanker.Selection pick = plan.get(i);
            if (pick.fetchFullText) {
                if (pick.candidate.fullTextUrl.trim().isEmpty()) fetchedHaveUrl = false;
                lowestFetched = Math.min(lowestFetched, pick.score);
            } else if (!pick.candidate.fullTextUrl.trim().isEmpty()) {
                highestUnfetchedWithUrl = Math.max(highestUnfetchedWithUrl, pick.score);
            }
        }
        check(fetchedHaveUrl, "没挂全文链接的候选一律不吃额度");
        check(lowestFetched >= highestUnfetchedWithUrl,
                "全文抓取按分数从高到低发，高分的没链接就让位给次高的");
        ArrayList<CandidateRanker.Selection> nothing = CandidateRanker.plan(QUERY, pool, 0, 2);
        check(fetches(nothing) == 0 && nothing.size() == 6, "预算为零时排序照给，一次全文也不抓");
        ArrayList<CandidateRanker.Selection> generous = CandidateRanker.plan(QUERY, pool, 99, 2);
        check(fetches(generous) == urlBearing(generous), "预算宽裕时恰好等于计划里带全文链接的条数");
        ArrayList<CandidateRanker.Selection> uncapped = CandidateRanker.plan(QUERY, pool, 3, 0);
        check(uncapped.size() == pool.size(), "单源上限给零或负数表示不限额，池子整份交回");
        ArrayList<PaperSources.Candidate> doubled = new ArrayList<PaperSources.Candidate>(pool);
        doubled.add(candidate("semantic-scholar", "S1", "深度学习医学影像分割方法", "",
                "https://doi.org/10.3000/o1", "https://example.org/o1-duplicate.pdf"));
        ArrayList<CandidateRanker.Selection> deduped = CandidateRanker.plan(QUERY, doubled, 3, 2);
        check(deduped.size() == plan.size() && fetches(deduped) == fetches(plan),
                "挑选先去重：同一篇从第四个引擎再回来一次，也不会多花一次全文额度");
    }

    private static void determinism() {
        ArrayList<PaperSources.Candidate> pool = quotaPool();
        String first = signature(CandidateRanker.rank(QUERY, pool));
        String again = signature(CandidateRanker.rank(QUERY, pool));
        check(!first.isEmpty() && first.equals(again), "同一份输入跑两次，名次与分数逐位相同");
        ArrayList<PaperSources.Candidate> reversed = new ArrayList<PaperSources.Candidate>(pool);
        Collections.reverse(reversed);
        check(signature(CandidateRanker.rank(QUERY, reversed)).equals(first),
                "把池子倒过来喂，名次一律不变：平局由 source id 和标题定死");
        String planOne = planSignature(CandidateRanker.plan(QUERY, pool, 3, 2));
        String planTwo = planSignature(CandidateRanker.plan(QUERY, pool, 3, 2));
        check(planOne.equals(planTwo), "配额挑选两次运行逐条一致");
        check(planSignature(CandidateRanker.plan(QUERY, reversed, 3, 2)).equals(planOne),
                "挑选也不受输入顺序影响");
    }

    private static void edges() {
        ArrayList<PaperSources.Candidate> pool = quotaPool();
        check(CandidateRanker.rank(null, pool).isEmpty(), "查询为 null 时返回空表而不是抛异常");
        check(CandidateRanker.rank("", pool).isEmpty() && CandidateRanker.rank("    ", pool).isEmpty(),
                "空查询与纯空白查询都返回空表");
        check(CandidateRanker.rank("深", pool).isEmpty() && CandidateRanker.rank("AB", pool).isEmpty(),
                "短到凑不出三元组的查询返回空表：三元组是本项目的最小术语单位");
        check(CandidateRanker.rank(QUERY, null).isEmpty(), "池为 null 时返回空表");
        check(CandidateRanker.plan(null, pool, 3, 2).isEmpty() && CandidateRanker.plan(QUERY, null, 3, 2).isEmpty(),
                "查询或池缺失时挑选返回空表");
        check(CandidateRanker.dedup(null).kept.isEmpty() && CandidateRanker.unique(null).isEmpty(),
                "去重遇到 null 池返回空结果");
        ArrayList<PaperSources.Candidate> dirty = new ArrayList<PaperSources.Candidate>();
        dirty.add(null);
        dirty.add(candidate("cnki", "Z1", "深度学习影像分割", TOPIC[0]));
        PaperSources.Candidate headless = candidate("cnki", "Z2", "标题也不要", "正文也不要");
        headless.source = null;
        dirty.add(headless);
        dirty.add(null);
        ArrayList<CandidateRanker.Scored> ranked = CandidateRanker.rank(QUERY, dirty);
        check(ranked.size() == 1 && ranked.get(0).score > 0d, "池里的 null 与缺 source 的候选被跳过，不抛");
        check(CandidateRanker.dedup(dirty).kept.size() == 1, "去重同样跳过 null 与缺 source 的候选");
        check(ranked.get(0).documentLength > 0 && ranked.get(0).matchedTerms > 0,
                "打分把 dl 与命中量一起交出来，报告要能解释名次是怎么来的");
        ArrayList<CandidateRanker.Selection> plan = CandidateRanker.plan(QUERY, pool, 3, 2);
        boolean numbered = true;
        for (int i = 0; i < plan.size(); i++) if (plan.get(i).rank != i + 1) numbered = false;
        check(numbered, "挑出来的名次是连续的 1..n，检索循环照名次走");
    }

    private static int occurrences(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) found++;
        return found;
    }

    private static CandidateRanker.Scored find(ArrayList<CandidateRanker.Scored> ranked, String id) {
        for (int i = 0; i < ranked.size(); i++)
            if (ranked.get(i).candidate.source.id.equals(id)) return ranked.get(i);
        throw new AssertionError("结果里没有 " + id);
    }

    private static double scoreOf(ArrayList<CandidateRanker.Scored> ranked, String id) {
        return find(ranked, id).score;
    }

    private static int perSource(ArrayList<CandidateRanker.Selection> plan, String engine) {
        int found = 0;
        for (int i = 0; i < plan.size(); i++) if (plan.get(i).candidate.source.engine.equals(engine)) found++;
        return found;
    }

    private static int fetches(ArrayList<CandidateRanker.Selection> plan) {
        int found = 0;
        for (int i = 0; i < plan.size(); i++) if (plan.get(i).fetchFullText) found++;
        return found;
    }

    private static int urlBearing(ArrayList<CandidateRanker.Selection> plan) {
        int found = 0;
        for (int i = 0; i < plan.size(); i++) if (!plan.get(i).candidate.fullTextUrl.trim().isEmpty()) found++;
        return found;
    }

    /** 池子里带全文链接、又在计划里真被抓取的那几条。 */
    private static int fetches(ArrayList<PaperSources.Candidate> pool,
                               ArrayList<CandidateRanker.Selection> plan) {
        int hits = 0;
        for (int i = 0; i < plan.size(); i++) {
            CandidateRanker.Selection pick = plan.get(i);
            if (!pick.fetchFullText) continue;
            for (int k = 0; k < pool.size(); k++) if (pool.get(k) == pick.candidate) hits++;
        }
        return hits;
    }
}
