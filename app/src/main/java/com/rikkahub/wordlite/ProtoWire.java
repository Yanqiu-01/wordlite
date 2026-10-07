package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * protobuf 线格式里我们真正用到的那一小块：varint、定长字段和带长度前缀的嵌套消息。
 * 万方的检索接口只说 gRPC-web，官方没有匿名可用的 JSON 入口，而它一次检索请求总共三十来字节，
 * 所以这里手写编解码，而不是把 protobuf-java（连同它的运行时）塞进一个手机端的查词应用。
 * 读侧一律先校验再取用：越界的长度前缀按格式无效处理，不给对端一个分配大数组的机会。
 */
final class ProtoWire {
    /** 一个已解码字段。varint 型放 varint，其余把原始字节留着，由调用方决定怎么解释。 */
    static final class Field {
        final int number, wireType;
        final long varint;
        final byte[] bytes;
        Field(int number, int wireType, long varint, byte[] bytes) {
            this.number = number; this.wireType = wireType; this.varint = varint; this.bytes = bytes;
        }
        String text() { return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8); }
    }

    private ProtoWire() { }

    static void writeTag(ByteArrayOutputStream out, int number, int wireType) {
        writeVarint(out, ((long) number << 3) | (long) wireType);
    }

    static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7FL) | 0x80L));
            value >>>= 7;
        }
        out.write((int) value);
    }

    /** 空串按 protobuf 的默认值处理：不写字段，省下来的都是流量。 */
    static void writeString(ByteArrayOutputStream out, int number, String value) {
        if (value == null || value.isEmpty()) return;
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        writeTag(out, number, 2);
        writeVarint(out, raw.length);
        out.write(raw, 0, raw.length);
    }

    static void writeInt32(ByteArrayOutputStream out, int number, int value) {
        if (value == 0) return;
        writeTag(out, number, 0);
        writeVarint(out, value);
    }

    static void writeMessage(ByteArrayOutputStream out, int number, byte[] payload) {
        writeTag(out, number, 2);
        writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    /** 解出一条消息的字段；未知字段照样带回去，免得哪天万方多塞一个字段就把整条记录丢掉。 */
    static ArrayList<Field> read(byte[] payload) {
        ArrayList<Field> out = new ArrayList<Field>();
        int at = 0;
        while (at < payload.length) {
            long tag = varintAt(payload, at);
            at = varintEnd;
            int number = (int) (tag >>> 3), wireType = (int) (tag & 7L);
            if (number < 1) throw new IllegalArgumentException("protobuf 字段号无效");
            if (wireType == 0) {
                out.add(new Field(number, wireType, varintAt(payload, at), null));
                at = varintEnd;
            } else if (wireType == 1) {
                require(payload, at, 8);
                out.add(new Field(number, wireType, 0L, slice(payload, at, 8)));
                at += 8;
            } else if (wireType == 5) {
                require(payload, at, 4);
                out.add(new Field(number, wireType, 0L, slice(payload, at, 4)));
                at += 4;
            } else if (wireType == 2) {
                long declared = varintAt(payload, at);
                at = varintEnd;
                if (declared < 0 || declared > payload.length - at)
                    throw new IllegalArgumentException("protobuf 长度越界");
                int length = (int) declared;
                out.add(new Field(number, wireType, 0L, slice(payload, at, length)));
                at += length;
            } else {
                throw new IllegalArgumentException("protobuf 线类型不受支持");
            }
        }
        return out;
    }

    /** 第一个该编号的字节串字段，按 UTF-8 读。 */
    static String text(ArrayList<Field> fields, int number) {
        for (Field field : fields) if (field.number == number && field.bytes != null) return field.text();
        return "";
    }

    /** 所有该编号的嵌套消息，repeated 字段就靠它遍历。 */
    static ArrayList<byte[]> nested(ArrayList<Field> fields, int number) {
        ArrayList<byte[]> out = new ArrayList<byte[]>();
        for (Field field : fields) if (field.number == number && field.bytes != null) out.add(field.bytes);
        return out;
    }

    static ArrayList<String> texts(ArrayList<Field> fields, int number) {
        ArrayList<String> out = new ArrayList<String>();
        for (Field field : fields) if (field.number == number && field.bytes != null) out.add(field.text());
        return out;
    }

    static boolean flag(ArrayList<Field> fields, int number) {
        for (Field field : fields) if (field.number == number && field.bytes == null) return field.varint != 0;
        return false;
    }

    static long number(ArrayList<Field> fields, int number) {
        for (Field field : fields) if (field.number == number && field.bytes == null) return field.varint;
        return 0L;
    }

    /* varintAt 把结束位置放在这个字段里带回，Java 没有多返回值，读循环又最怕两者不同步。 */
    private static int varintEnd;

    private static long varintAt(byte[] payload, int at) {
        long value = 0L;
        int shift = 0;
        while (at < payload.length) {
            byte b = payload[at++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) { varintEnd = at; return value; }
            shift += 7;
            if (shift > 63) throw new IllegalArgumentException("protobuf varint 过长");
        }
        throw new IllegalArgumentException("protobuf varint 被截断");
    }

    private static void require(byte[] payload, int at, int need) {
        if (at < 0 || need < 0 || at + need > payload.length) throw new IllegalArgumentException("protobuf 提前结束");
    }

    private static byte[] slice(byte[] payload, int at, int length) {
        byte[] out = new byte[length];
        System.arraycopy(payload, at, out, 0, length);
        return out;
    }
}
