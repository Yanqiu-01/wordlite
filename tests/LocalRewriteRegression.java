package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalRewriteRegression {
    private static int count;
    private static final List<String> TERMS = Arrays.asList("界面反应层", "钎料");
    private static final Pattern MARKER = Pattern.compile("\u27e6WL_[^\u27e7\\n]*\u27e7");

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    private static ArrayList<LocalRewriter.Option> rewrite(String text) {
        return LocalRewriter.rewrite(text, TERMS);
    }

    private static ArrayList<String> markers(String text) {
        ArrayList<String> markers = new ArrayList<String>();
        Matcher matcher = MARKER.matcher(text);
        while (matcher.find()) markers.add(matcher.group());
        return markers;
    }

    /** Same semantics the caller uses: one occurrence per island, original order, no stray marker left. */
    private static boolean islandsIntact(String input, String candidate) {
        ArrayList<TextProtection.Island> islands = new ArrayList<TextProtection.Island>();
        for (String marker : markers(input))
            islands.add(new TextProtection.Island(0, 0, marker, marker));
        try {
            new TextProtection.Mask(input, input, islands).segments(candidate);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int occurrences(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) found++;
        return found;
    }

    private static int punctuation(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++)
            if ("\u3002\uFF0C\uFF1B\uFF01\uFF1F\u3001\uFF1A".indexOf(text.charAt(i)) >= 0) total++;
        return total;
    }

    private static int brackets(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == ']' || c == '{' || c == '}') total++;
        }
        return total;
    }

    private static String digits(String text) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        return digits.toString();
    }

    /** Shape every candidate must keep: changed literal, markers, numbers, terms, punctuation, sane count. */
    private static void checkShape(String label, String input, ArrayList<LocalRewriter.Option> options,
                                   String... mustKeep) {
        check(!options.isEmpty(), label + ": at least one candidate");
        check(options.size() <= 3, label + ": never more than three candidates");
        ArrayList<String> seen = new ArrayList<String>();
        for (int i = 0; i < options.size(); i++) {
            LocalRewriter.Option option = options.get(i);
            String tag = label + " #" + (i + 1);
            check(option != null && !option.text.isEmpty(), tag + ": candidate text is not empty");
            check(!option.strategy.isEmpty(), tag + ": strategy label is not empty");
            check(!option.text.equals(input), tag + ": candidate differs from the input");
            check(!seen.contains(option.text), tag + ": candidates are distinct");
            seen.add(option.text);
            check(islandsIntact(input, option.text), tag + ": mask markers survive in order");
            for (String marker : markers(input))
                check(occurrences(option.text, marker) == 1, tag + ": marker appears exactly once");
            for (String keep : mustKeep)
                check(occurrences(option.text, keep) == occurrences(input, keep), tag + ": keeps " + keep);
            check(punctuation(option.text) == punctuation(input), tag + ": no punctuation lost or half-width");
            check(brackets(option.text) <= brackets(input), tag + ": no bracket placeholder inserted");
            check(digits(option.text).equals(digits(input)), tag + ": digit sequence unchanged");
        }
    }

    private static void checkNoCandidate(String label, String input) {
        boolean threw = false;
        ArrayList<LocalRewriter.Option> options = null;
        try {
            options = LocalRewriter.rewrite(input, TERMS);
        } catch (RuntimeException e) {
            threw = true;
        }
        check(!threw, label + ": rewrite never throws");
        check(options != null && options.isEmpty(), label + ": no candidate returned");
        check(!LocalRewriter.applicable(input), label + ": applicable agrees with rewrite");
    }

    public static void main(String[] args) throws Exception {
        // 1. mask markers
        String many = "由于⟦WL_9ac_0⟧的含量为 0.65 MPa，因此⟦WL_9ac_1⟧在⟦WL_9ac_2⟧后被加热至 300 ℃，⟦WL_9ac_3⟧需要复检。";
        checkShape("标记多于三个", many, rewrite(many), "0.65 MPa", "300 ℃");
        String atStart = "⟦WL_9ac_0⟧含量最高，同时该工艺需要延长保温时间。";
        checkShape("标记在句首", atStart, rewrite(atStart));
        String atEnd = "由于温度升高，界面反应层主要为⟦WL_9ac_0⟧。";
        checkShape("标记在句尾", atEnd, rewrite(atEnd));
        String nextToNumber = "峰值温度为 260 ℃，⟦WL_9ac_0⟧对应 12.5%⟦WL_9ac_1⟧，因此需要复核。";
        checkShape("标记紧邻数字", nextToNumber, rewrite(nextToNumber), "260 ℃", "12.5%");

        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.text = "由于界面反应层被加热至 300 ℃，因此钎料的接头强度需要重新评估。";
        TextProtection.Mask mask = TextProtection.mask(paragraph, 0, paragraph.text.length(), TERMS);
        check(!mask.submitted.equals(paragraph.text), "真实掩码产物被改写规则读取");
        ArrayList<LocalRewriter.Option> masked = LocalRewriter.rewrite(mask.submitted, TERMS);
        checkShape("TextProtection 掩码", mask.submitted, masked);
        for (int i = 0; i < masked.size(); i++) {
            String restored = mask.restore(masked.get(i).text);
            check(restored.contains("300 ℃"), "还原后数值仍在 #" + (i + 1));
            check(restored.contains("界面反应层") && restored.contains("钎料"), "还原后术语仍在 #" + (i + 1));
            check(!restored.contains("\u27e6WL_"), "还原后不留掩码残留 #" + (i + 1));
        }

        // 2. numbers and units
        String numeric = "试样被加热至 300 ℃，压强为 0.65 MPa，采用 SAC305 钎料，Cu6Sn5 相占比 12.5%。";
        checkShape("数字与单位", numeric, rewrite(numeric), "300 ℃", "0.65 MPa", "SAC305", "Cu6Sn5", "12.5%");

        // 3. registered terms
        String termText = "界面反应层较厚时，需要降低连接温度。";
        ArrayList<LocalRewriter.Option> termOptions = rewrite(termText);
        checkShape("自定义术语", termText, termOptions, "界面反应层");
        check(occurrences(termText, "界面反应层") == 1, "术语在原文出现一次");
        for (LocalRewriter.Option option : termOptions)
            check(option.text.contains("界面反应层"), "改写后术语原样保留");

        // 4. one case per rule family
        checkShape("结构-对…进行", "对接头进行了加热，保温 2 h。", rewrite("对接头进行了加热，保温 2 h。"), "2 h");
        check(rewrite("对接头进行了加热，保温 2 h。").get(0).text.equals("加热了接头，保温 2 h。"),
                "对…进行X 还原为动词");
        check(rewrite("加热了接头，保温 2 h。").get(0).text.equals("对接头进行了加热，保温 2 h。"),
                "动词还原为对…进行X");
        check(rewrite("试样被加热至 300 ℃，保温 30 min。").get(0).text.equals("将试样加热至 300 ℃，保温 30 min。"),
                "无施事被字句改将字句");
        check(rewrite("先把钎料放入丙酮中清洗 10 min。").get(0).text.contains("将钎料放入"), "把字句换成将字句");
        check(rewrite("该合金被广泛用于航空结构件。").get(0).text.equals("该合金广泛用于航空结构件。"),
                "被+副词时可省被");
        check(rewrite("通过延长保温时间来优化工艺参数。").get(0).text.equals("借助延长保温时间优化工艺参数。"),
                "通过…来…句式改写");
        check(rewrite("通过延长保温时间，可以显著提高强度。").get(0).text.startsWith("借助延长保温时间"),
                "通过…可以句式改写");
        check(rewrite("缓慢地冷却可以减少裂纹。").get(0).text.startsWith("缓慢冷却"), "地的结构可省地");
        checkShape("连接词", "由于温度升高，接头过早失效。", rewrite("由于温度升高，接头过早失效。"));
        check(rewrite("由于温度升高，接头过早失效。").get(0).text.startsWith("因为温度升高"), "由于→因为");
        check(rewrite("因此接头过早失效。").get(0).text.startsWith("所以接头"), "因此→所以");
        check(rewrite("并且需要延长保温时间。").get(0).text.startsWith("同时"), "并且→同时");
        checkShape("语气", "该工艺可以降低缺陷率。", rewrite("该工艺可以降低缺陷率。"));
        check(rewrite("该工艺可以降低缺陷率。").get(0).text.equals("该工艺能够降低缺陷率。"), "可以→能够");
        check(rewrite("保温时间需要控制在 30 min。").get(0).text.contains("需控制"), "需要→需");
        checkShape("词序", "剪切强度高于母材，断口呈韧性。", rewrite("剪切强度高于母材，断口呈韧性。"));
        check(rewrite("剪切强度高于母材，断口呈韧性。").get(0).text.equals("母材低于剪切强度，断口呈韧性。"),
                "比较句两侧互换");
        checkShape("词汇", "该工艺采用钎料 A。", rewrite("该工艺采用钎料 A。"), "钎料");
        check(rewrite("该工艺采用钎料 A。").get(0).text.equals("该工艺使用钎料 A。"), "采用→使用");

        // 5. candidate set shape on a busy paragraph
        String busy = "由于母材导热系数较高，因此对接头进行了预热。同时，钎料熔点约为 217 ℃，保温时间需要控制在 30 min 以内，"
                + "接头的组织明显细化，该方法可以推广。";
        ArrayList<LocalRewriter.Option> busyOptions = rewrite(busy);
        checkShape("整段多规则", busy, busyOptions, "217 ℃", "30 min", "钎料");
        check(busyOptions.size() == 3, "多规则命中时给出三个候选");

        // 6. determinism
        ArrayList<LocalRewriter.Option> again = rewrite(busy);
        check(again.size() == busyOptions.size(), "两次调用候选数量相同");
        for (int i = 0; i < again.size(); i++) {
            check(again.get(i).text.equals(busyOptions.get(i).text), "两次调用文本一致 #" + (i + 1));
            check(again.get(i).strategy.equals(busyOptions.get(i).strategy), "两次调用策略一致 #" + (i + 1));
        }

        // 7. English rules
        String english = "The joint strength was improved by the addition of Ti, and the layer was significantly "
                + "thinner at 300 ℃.";
        ArrayList<LocalRewriter.Option> englishOptions = LocalRewriter.rewrite(english, Arrays.<String>asList());
        checkShape("英文段落", english, englishOptions, "300 ℃", "Ti");
        boolean passive = false;
        boolean adverb = false;
        for (LocalRewriter.Option option : englishOptions) {
            if (option.text.contains("The addition of Ti improved the joint strength")) passive = true;
            if (option.text.contains("considerably thinner")) adverb = true;
        }
        check(passive, "英文被动改主动");
        check(adverb, "英文副词替换");

        // 8. nothing to rewrite
        checkNoCandidate("空串", "");
        checkNoCandidate("纯标点", "！！！。？，");
        checkNoCandidate("单个掩码标记", "⟦WL_9ac_0⟧");
        checkNoCandidate("无规则命中", "本文研究了工艺参数对接头质量的影响。");
        boolean nullThrew = false;
        try {
            check(LocalRewriter.rewrite(null, TERMS).isEmpty(), "null 输入返回空列表");
        } catch (RuntimeException e) {
            nullThrew = true;
        }
        check(!nullThrew, "null 输入不抛异常");
        check(LocalRewriter.rewrite("由于温度升高，接头失效。", null).size() == 1, "terms 传 null 仍可改写");

        // 9. applicable agreement
        String[] probe = { "", "。", "⟦WL_9ac_0⟧", "本文研究了工艺参数对接头质量的影响。", "由于温度升高，接头失效。",
                "试样被加热至 300 ℃。", "The layer was significantly thinner.", busy };
        for (int i = 0; i < probe.length; i++) {
            ArrayList<LocalRewriter.Option> options = LocalRewriter.rewrite(probe[i], TERMS);
            if (!LocalRewriter.applicable(probe[i]))
                check(options.isEmpty(), "applicable=false 时候选为空 #" + i);
            else check(!options.isEmpty(), "applicable=true 时候选非空 #" + i);
        }
        System.out.println("SUMMARY " + count + " assertions passed");
    }
}
