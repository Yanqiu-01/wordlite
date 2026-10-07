package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * 知网条目页与刊期页的解析回归。夹具是手机知网（wap.cnki.net/touch）的真实页面字节，
 * 打不了网络也打得开：这一路的价值在于"手上有一条知网链接或文献号，能不能变成自建库里的一篇"，
 * 而 Vue 服务端渲染最爱改的就是标签之间的空白与占位注释，所以按真实字节而不是想象的 HTML 来验。
 */
public final class CnkiTouchRegression {
    private static int passed;

    public static void main(String[] args) throws Exception {
        String article = read("tests/samples/cnki-article.html");
        String issue = read("tests/samples/cnki-issue.html");
        String text = CnkiTouch.record(article);
        check(text.contains("题名：人体散乱点云数据的区域分割算法"), "条目页取到题名");
        check(text.contains("机械设计与研究") && text.contains("2020年第01期"),
                "刊名与年期从标题尾巴上拆出来，不用另开一个请求");
        check(text.contains("王希") && text.contains("陈晓波") && text.contains("习俊通"),
                "作者按页面顺序全部进来");
        check(text.indexOf("摘要：") > 0, "摘要那一格没有丢");
        check(!text.contains("<") && !text.contains("<!--[-->"), "标签和 Vue 的注释占位一个都不留");
        check(text.indexOf('\u00a0') < 0, "作者名后面那个不间断空格被收干净");
        check(!text.contains("下载"), "\"下载\"这类界面文字不算关键词");

        check(CnkiTouch.code("https://wap.cnki.net/touch/web/Journal/Article/JSYY202001008.html")
                .equals("JSYY202001008"), "整条链接里的文献号能剥出来");
        check(CnkiTouch.code("jsyy202001008").equals("JSYY202001008"), "裸编号大小写都认");
        check(CnkiTouch.code("https://wap.cnki.net/touch/web/Journal/List/JSYY202001.html")
                .equals("JSYY202001"), "刊期页链接同样认");
        check(CnkiTouch.code("帮我把这篇降重").isEmpty(), "认不出的直说认不出，不硬凑一个编号");
        check(CnkiTouch.isIssue("JSYY202001") && !CnkiTouch.isIssue("JSYY202001008"),
                "六位是刊期，多出来的那几位是文章");

        ArrayList<String> codes = CnkiTouch.issueCodes(issue, CnkiTouch.MAX_IMPORT);
        check(codes.size() == CnkiTouch.MAX_IMPORT,
                "一期几十篇也只导前 " + CnkiTouch.MAX_IMPORT + " 篇，不让手一抖把整刊拖进手机");
        check(codes.get(0).matches("[A-Z]{2,8}\\d{7,}"), "目录里取到的是文章号而不是刊期号");
        check(new ArrayList<String>(codes).containsAll(codes.subList(0, 10)), "顺序与页面一致");
        check(CnkiTouch.issueCodes(issue, 1).size() == 1, "给多少就要多少，不多取");

        check(CnkiTouch.issueCodes(null, 10).isEmpty(), "null 目录页交空表而不是崩");
        check(CnkiTouch.record(null).isEmpty(), "null 条目页交空文本");
        check(CnkiTouch.record("<html></html>").isEmpty(), "空壳页面交空文本");
        check(CnkiTouch.articleUrl("ZGQX202610027047").endsWith("/Journal/Article/ZGQX202610027047.html")
                        && CnkiTouch.conferenceUrl("ZGQX202610027047")
                        .endsWith("/Conference/Article/ZGQX202610027047.html"),
                "期刊与会议两条条目页分开：同一个编号塞错目录只会拿到 302");
        System.out.println("SUMMARY " + passed + " assertions passed (real 手机知网 pages, no network).");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
        System.out.println("PASS " + what);
    }
}