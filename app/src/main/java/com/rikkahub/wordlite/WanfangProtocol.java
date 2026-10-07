package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 万方数据的检索协议。万方只肯用 gRPC-web 说这门生意：POST 一个帧化的 protobuf，回来的也是帧化的
 * protobuf，JSON 入口一概不给匿名调用者。请求侧不足四十字节，响应侧只用得到著录项，所以配着
 * ProtoWire 自己读写，比引一个 protobuf 运行时划算。
 * 字段号来自它前端打包出来的 JS，并对着真实返回逐条核对；这里只取题名、作者、来源、年份与摘要，
 * 全文从来不在请求里，也不进这台设备之外的任何地方。
 */
final class WanfangProtocol {
    static final String CONTENT_TYPE = "application/grpc-web+proto";
    private static final int MAX_MESSAGE = 240;

    private WanfangProtocol() { }

    /**
     * SearchRequest{1 CommonRequest, 2 interfaceType}，
     * CommonRequest{1 searchType, 2 searchWord, 5 currentPage, 6 pageSize}。
     * searchType 用聚合的 paper：期刊和学位论文混在一起，而查重最常撞上的就是后者。
     */
    static byte[] request(String phrase, int page, int perPage) {
        ByteArrayOutputStream common = new ByteArrayOutputStream();
        ProtoWire.writeString(common, 1, "paper");
        ProtoWire.writeString(common, 2, phrase == null ? "" : phrase.trim());
        ProtoWire.writeInt32(common, 5, Math.max(1, page));
        ProtoWire.writeInt32(common, 6, Math.max(1, perPage));
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        ProtoWire.writeMessage(message, 1, common.toByteArray());
        ProtoWire.writeInt32(message, 2, 1);
        return frame(message.toByteArray(), false);
    }

    /** gRPC-web 帧：1 字节标志 + 4 字节大端长度 + 消息体。 */
    private static byte[] frame(byte[] message, boolean compressed) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(compressed ? 1 : 0);
        out.write((message.length >>> 24) & 0xFF);
        out.write((message.length >>> 16) & 0xFF);
        out.write((message.length >>> 8) & 0xFF);
        out.write(message.length & 0xFF);
        out.write(message, 0, message.length);
        return out.toByteArray();
    }

    /** 解出帧里每条记录。 trailer 帧（标志位最高位为 1）只带 grpc-status，不是数据，跳过。 */
    static ArrayList<PaperSources.Candidate> parse(byte[] response, int limit) throws IOException {
        ArrayList<PaperSources.Candidate> out = new ArrayList<PaperSources.Candidate>();
        if (response == null) return out;
        int at = 0;
        while (at + 5 <= response.length) {
            int flags = response[at] & 0xFF;
            long declared = ((long) (response[at + 1] & 0xFF) << 24) | ((response[at + 2] & 0xFF) << 16)
                    | ((long) (response[at + 3] & 0xFF) << 8) | (long) (response[at + 4] & 0xFF);
            at += 5;
            if (declared < 0 || at + declared > response.length) throw new IOException("万方检索响应帧长无效");
            int length = (int) declared;
            byte[] payload = new byte[length];
            System.arraycopy(response, at, payload, 0, length);
            at += length;
            if ((flags & 0x80) != 0) continue;
            if ((flags & 0x01) != 0) throw new IOException("万方检索响应使用了不支持的压缩");
            readSearch(payload, out, limit);
        }
        return out;
    }

    /** SearchResponse{1 status, 2 message, 3 count, 4 repeated Resource}。 */
    private static void readSearch(byte[] payload, ArrayList<PaperSources.Candidate> out, int limit)
            throws IOException {
        ArrayList<ProtoWire.Field> fields = ProtoWire.read(payload);
        for (ProtoWire.Field field : fields) {
            if (field.number != 1 || field.bytes != null) continue;
            if (field.varint == 0) {
                String reason = ProtoWire.text(fields, 2);
                throw new IOException(reason.isEmpty() ? "万方检索未通过" : cut(reason));
            }
        }
        for (byte[] resource : ProtoWire.nested(fields, 4)) {
            ArrayList<ProtoWire.Field> item = ProtoWire.read(resource);
            String type = ProtoWire.text(item, 1).toLowerCase(Locale.ROOT);
            /* 会议（104）、图书（107）这些载荷的字段号没有公开出处，编不出来的东西就不编：
               期刊与学位论文已经覆盖查重要的绝大部分来源，其余类型安静跳过。 */
            /* 载荷是 oneof：101 期刊、102 学位论文，各自一套字段号，得先把它解出来再谈著录项。 */
            ArrayList<byte[]> journals = ProtoWire.nested(item, 101);
            if (!journals.isEmpty() && (type.isEmpty() || type.contains("periodical") || type.contains("journal"))) {
                addJournal(ProtoWire.read(journals.get(0)), out, limit);
                continue;
            }
            ArrayList<byte[]> theses = ProtoWire.nested(item, 102);
            if (!theses.isEmpty() && (type.isEmpty() || type.contains("thesis") || type.contains("degree")))
                addThesis(ProtoWire.read(theses.get(0)), out, limit);
        }
    }

    /** Periodical{1 id, 2 titleList, 3 creatorList, 20 abstractList, 24 periodicalTitleList, 33 publishYear}。 */
    private static void addJournal(ArrayList<ProtoWire.Field> item, ArrayList<PaperSources.Candidate> out,
                                   int limit) {
        String id = ProtoWire.text(item, 1);
        PaperSources.Candidate candidate = record("wanfang", id, "periodical");
        candidate.source.title = preferred(ProtoWire.texts(item, 2));
        candidate.source.authors = PaperSources.join(keep(ProtoWire.texts(item, 3)));
        candidate.source.year = yearIn(item, 33);
        String journal = preferred(ProtoWire.texts(item, 24));
        candidate.abstractText = PaperSources.clip(dehighlight(joinChinese(ProtoWire.texts(item, 20))));
        if (!journal.isEmpty()) candidate.source.title = withVenue(candidate.source.title, journal);
        PaperSources.add(out, candidate, limit);
    }

    /** Thesis{1 id, 3 titleList, 4 creatorList, 8 originalOrganizationList, 18 abstractList, 26 publishYear}。 */
    private static void addThesis(ArrayList<ProtoWire.Field> item, ArrayList<PaperSources.Candidate> out,
                                  int limit) {
        String id = ProtoWire.text(item, 1);
        PaperSources.Candidate candidate = record("wanfang", id, "thesis");
        candidate.source.title = preferred(ProtoWire.texts(item, 3));
        candidate.source.authors = PaperSources.join(keep(ProtoWire.texts(item, 4)));
        candidate.source.year = yearIn(item, 26);
        String school = preferred(ProtoWire.texts(item, 8));
        candidate.abstractText = PaperSources.clip(dehighlight(joinChinese(ProtoWire.texts(item, 18))));
        if (!school.isEmpty()) candidate.source.title = withVenue(candidate.source.title, school);
        PaperSources.add(out, candidate, limit);
    }

    private static PaperSources.Candidate record(String engine, String id, String path) {
        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = engine;
        candidate.source.id = id;
        candidate.source.locator = id.isEmpty() ? "wanfang"
                : "https://d.wanfangdata.com.cn/" + path + "/" + id;
        return candidate;
    }

    /** 题录里只有"题名"，读者看不出它登在哪儿，所以把出处并到题名后面，和维普那一路保持一致。 */
    private static String withVenue(String title, String venue) {
        if (title.isEmpty()) return venue;
        return title.contains(venue) ? title : title + "《" + venue + "》";
    }

    /** titleList 是[中文，英文]两段，比对和报告都更该用中文那条；只有英文时也不能空着。 */
    private static String preferred(ArrayList<String> values) {
        for (String value : values) if (dehighlight(value).matches("(?s).*[\\u4e00-\\u9fa5].*"))
            return dehighlight(value).trim();
        for (String value : values) if (!value.trim().isEmpty()) return dehighlight(value).trim();
        return "";
    }

    private static String joinChinese(ArrayList<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            String clean = dehighlight(value).trim();
            if (clean.isEmpty() || !clean.matches("(?s).*[\\u4e00-\\u9fa5].*")) continue;
            if (out.length() > 0) out.append(' ');
            out.append(clean);
        }
        if (out.length() > 0) return out.toString();
        for (String value : values) {
            String clean = dehighlight(value).trim();
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(clean);
        }
        return out.toString();
    }

    private static ArrayList<String> keep(ArrayList<String> values) {
        ArrayList<String> out = new ArrayList<String>();
        for (String value : values) {
            String name = dehighlight(value).trim();
            if (name.isEmpty() || out.contains(name)) continue;
            out.add(name);
            if (out.size() >= PaperSources.MAX_AUTHORS) break;
        }
        return out;
    }

    private static String yearIn(ArrayList<ProtoWire.Field> item, int number) {
        long value = ProtoWire.number(item, number);
        if (value >= 1500 && value <= 2200) return String.valueOf(value);
        return PaperSources.year(ProtoWire.text(item, number));
    }

    /**
     * 万方把命中的词包在 <span class='highlight'> 里。标签一定要去掉，否则比对文本里全是尖括号；
     * 但也不能按通用的去标签办法在断开处补一个空格——中文词之间多一个空格，查重比分就会被白白稀释。
     */
    private static String dehighlight(String value) {
        String raw = value == null ? "" : value.replace("\u0000", "");
        return PaperSources.Xml.stripTags(raw.replaceAll("(?i)</?span[^>]*>", ""));
    }

    /** 万方把拒绝原因写在 message 里（"检索参数为空"之类），照原话报出来，但别把整段响应搬进报告。 */
    private static String cut(String reason) {
        String text = new String(reason.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE);
    }
}
