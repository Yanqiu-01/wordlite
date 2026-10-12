package com.rikkahub.wordlite;

/**
 * AIGC 读数的置信档。它回答的只有一件事：**这个数是哪个仪器量出来的，那个仪器本机实测到什么精度**。
 *
 * 0.7.2 之前只有两档——"标定够了就印数"与"标定不够就整格不画"。后者在实践中等于把功能关掉：
 * 用户看到的是一句"判据未标定"加一个空白格，读不出"这台仪器量到了什么、错在哪一侧"。
 * 现在改成三档披露：数照量、照印，但每一格都带着仪器版本号、实测 AUC、误报率与这一档。
 *
 * <p>这一档的值只许从**当前真正参与打分那件仪器**的实测读数算出来（{@link #of}），
 * 不许在任何地方写死成 HIGH。仪器没跑过、样本不够、或者根本没量过 AUC，一律 LOW：
 * LOW 的意思是"这个数只能用来排复核顺序，不是判决"。
 */
public enum AigcConfidence {
    /** 实测精度足够当结论：留出 AUC ≥ 0.95 且误报在预算内。 */
    HIGH("高"),
    /** 达到出厂门槛（AUC ≥ 0.85 且误报在预算内）：可以给数，仍要人复核。 */
    MEDIUM("中"),
    /** 仪器没有达标的实测读数，或者精度只够排序。 */
    LOW("低");

    /** 出厂门槛与 {@link AigcNgramModel} 同一套数，不许这一处另立一条更松的线。 */
    public static final double GATE_AUC = 0.85d;
    public static final double HIGH_AUC = 0.95d;
    public static final double GATE_FALSE_POSITIVES_PER_MILLE = 2.0d;

    private final String label;

    AigcConfidence(String label) {
        this.label = label;
    }

    /** 界面上那一个字。 */
    public String label() {
        return label;
    }

    /** 够不够格被当成结论读。LOW 一律只许当排序线索。 */
    public boolean conclusive() {
        return this != LOW;
    }

    /** 界面上那一行口径：结论 / 排序线索。 */
    public String use() {
        return conclusive() ? "可作复核结论" : "只作复核排序线索，不是判决";
    }

    /**
     * 由仪器自己的实测读数定档。
     *
     * @param auc          仪器在留出集上的 AUC（机器 &gt; 人）；没量过传 {@code Double.NaN}
     * @param perMille     同一批留出集上每千个单元的误报数；没量过传 {@code Double.NaN}
     * @param measured     这台仪器本机是否真有这两个读数（没跑过 / 样本不够时传 false）
     */
    public static AigcConfidence of(double auc, double perMille, boolean measured) {
        return of(auc, perMille, GATE_FALSE_POSITIVES_PER_MILLE, measured);
    }

    /**
     * 同一件事，但误报预算由仪器自己带。这一条是给节奏判据那件仪器用的：它按真人段落分 P99 划线，
     * 每千段本来就要误报十段上下（实测 1.02%），拿 {@link #GATE_FALSE_POSITIVES_PER_MILLE}
     * 那一个数去卡它，等于用另一件仪器的尺量它。
     *
     * @param budgetPerMille 这件仪器自己选定工作点时允许的每千个单元误报数
     */
    public static AigcConfidence of(double auc, double perMille, double budgetPerMille, boolean measured) {
        if (!measured || Double.isNaN(auc) || Double.isNaN(perMille)) return LOW;
        if (Double.isNaN(budgetPerMille)) return LOW;
        if (auc >= HIGH_AUC && perMille <= budgetPerMille) return HIGH;
        if (auc >= GATE_AUC && perMille <= budgetPerMille) return MEDIUM;
        return LOW;
    }

    /** 一次检测里参与打分的仪器可能不止一件，整份报告的置信档取最弱那件。 */
    public static AigcConfidence weakest(AigcConfidence left, AigcConfidence right) {
        if (left == null) return right == null ? LOW : right;
        if (right == null) return left;
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
