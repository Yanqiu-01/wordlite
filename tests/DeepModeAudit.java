package com.rikkahub.wordlite;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 深模式（一条规则在同一段里改多个匹配点，只给 RewriteLoop 用）的文本健全性断言：
 * 把每条规则单独拿出来，在真稿句子上按 depth 2..6 各跑一遍，改坏文本的一条都不许有。
 * "坏"= 正文被吞掉（缩水超 4 字）、句子边界断了（以标点开头或以标点结尾）、标点叠了、
 * 掩码占位符没还原。变长不算坏：结构规则本来就要补虚词。
 *
 * 2026-10-09 修掉的两个 bug 都是这里抓的：①跳过某个匹配点时光标推过头，跳过的正文没抄回输出，
 * 一句 57 字的句子被改成以"，"开头的 40 字残句；②比较句互换把句首的致使动词一起搬进主语，
 * "引起实测值高于仿真值"变成"仿真值低于导致测量值"。
 *
 * 用法：java -cp <classes> com.rikkahub.wordlite.DeepModeAudit [句子文件...]
 */
public final class DeepModeAudit {

    private static final String HEAD_BAD = "，。、；：！？）］】》”’";
    private static final String TAIL_BAD = "，、；：！？（［【《“‘";
    private static final String[] DOUBLES = {
        "，，", "。。", "；；", "，。", "。，", "；。", "。；", "、、", "？？", "！！", "，、", "、，",
    };
    private static final int MAX_SENTENCES = 200;

    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
    }

    private static String defect(String in, String out) {
        if (out == null) return null;
        if (out.length() < in.length() - 4) return "缩水超过 4 字";
        if (HEAD_BAD.indexOf(out.charAt(0)) >= 0 && HEAD_BAD.indexOf(in.charAt(0)) < 0) return "以标点开头";
        if (TAIL_BAD.indexOf(out.charAt(out.length() - 1)) >= 0
                && TAIL_BAD.indexOf(in.charAt(in.length() - 1)) < 0) return "以标点结尾";
        for (String pair : DOUBLES) if (out.contains(pair)) return "连续标点 " + pair;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c >= 0xE000 && c <= 0xF8FF) return "留下掩码占位符";
        }
        return null;
    }

    /** 整段太长比不出细节，按句号级标点切开，取 16-80 字的那一段。 */
    private static List<String> sentences(String file) throws Exception {
        List<String> out = new ArrayList<String>();
        String raw = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        for (String line : raw.split("\n")) {
            if (line.trim().startsWith("#")) continue;
            for (String piece : line.split("[。；！？]")) {
                String s = piece.trim();
                if (s.length() < 16 || s.length() > 80) continue;
                out.add(s);
                if (out.size() >= MAX_SENTENCES) return out;
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        List<String> files = args.length > 0 ? Arrays.asList(args)
                : Arrays.asList("tests/corpus/real-prose.txt", "tests/corpus/oa-planted-cjmenet.txt");
        List<String> pool = new ArrayList<String>();
        for (String file : files) {
            List<String> got = sentences(file);
            System.out.println("SOURCE " + file + " 句子 " + got.size() + " 条");
            pool.addAll(got);
        }

        Field rulesField = LocalRewriter.class.getDeclaredField("RULES");
        rulesField.setAccessible(true);
        Object[] rules = (Object[]) rulesField.get(null);
        Field ruleField = rules[0].getClass().getDeclaredField("rule");
        ruleField.setAccessible(true);
        Field strategyField = rules[0].getClass().getDeclaredField("strategy");
        strategyField.setAccessible(true);
        Method deep = null;
        for (Method m : ruleField.getType().getMethods())
            if (m.getParameterCount() == 2 && m.getName().equals("apply")) deep = m;
        deep.setAccessible(true);

        for (int depth = 2; depth <= 6; depth++) {
            int fired = 0, bad = 0;
            for (String in : pool) {
                for (Object entry : rules) {
                    String out;
                    try {
                        Object value = deep.invoke(ruleField.get(entry), in, depth);
                        out = value == null ? null : value.toString();
                    } catch (Exception error) {
                        bad++;
                        System.out.println("BAD depth=" + depth + " 抛异常 rule="
                                + strategyField.get(entry) + " " + error.getCause() + "｜" + in);
                        continue;
                    }
                    if (out == null || out.equals(in)) continue;
                    fired++;
                    String flaw = defect(in, out);
                    if (flaw == null) continue;
                    bad++;
                    System.out.println("BAD depth=" + depth + " " + flaw + " rule="
                            + strategyField.get(entry) + " 字数 " + in.length() + "->" + out.length()
                            + "｜" + out);
                }
            }
            System.out.println("DEPTH " + depth + " 规则 " + rules.length + " 条 × 句子 " + pool.size()
                    + " 条：出手 " + fired + " 次，改坏 " + bad + " 次");
            check(bad == 0, "depth " + depth + " 没有一条规则改坏文本（出手 " + fired + " 次）");
        }

        int options = 0, broken = 0;
        for (String in : pool) {
            for (LocalRewriter.Option o : LocalRewriter.rewrite(in, new ArrayList<String>(), 8, 8)) {
                options++;
                if (defect(in, o.text) == null) continue;
                broken++;
                System.out.println("BAD 整条链路 " + defect(in, o.text) + "｜" + o.text);
            }
        }
        check(broken == 0, "整条改写链路在深模式下出的候选也不改坏句子（" + options + " 个）");
        System.out.println("SUMMARY " + count + " assertions passed；规则 " + rules.length
                + " 条 × 句子 " + pool.size() + " 条 × depth 2..6 全部过线；整条链路另出候选 "
                + options + " 个，逐个过健全性断言");
    }
}
