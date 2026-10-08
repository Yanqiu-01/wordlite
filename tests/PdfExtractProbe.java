package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;

/**
 * 取字量台：对给定的一份或多份 PDF，报 app 自己的解析器能读出多少字、卡在哪个字体、花了多久。
 * 真期刊 PDF 改前改后各跑一次，数字直接进更新日志（命令：java -cp ... com.rikkahub.wordlite.PdfExtractProbe 路径...）。
 */
public final class PdfExtractProbe {
    public static void main(String[] args) throws Exception {
        java.util.ArrayList<String> files = new java.util.ArrayList<String>();
        String dumpTo = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-cmap")) {
                CidUnicodeTables.install(new java.io.FileInputStream(args[++i]));
            } else if (args[i].equals("-dump")) {
                dumpTo = args[++i];
                new File(dumpTo).mkdirs();
            } else if (!args[i].equals("-no-cmap")) {
                files.add(args[i]);
            }
        }
        System.out.println("CID_TABLE " + (CidUnicodeTables.active() == null
                ? "not installed (改前状态)" : "installed " + CidUnicodeTables.active().describe()));
        args = files.toArray(new String[0]);
        long bytes = 0L, chars = 0L, cjk = 0L, lost = 0L, ms = 0L, ops = 0L, cid = 0L;
        for (String path : args) {
            File file = new File(path);
            byte[] data = Files.readAllBytes(file.toPath());
            long began = System.nanoTime();
            PdfFile.Extracted out = PdfFile.extractText(data);
            long cost = (System.nanoTime() - began) / 1000000L;
            if (dumpTo != null) {
                /* 字倒出来才谈得上逐字比：和 PyMuPDF 的输出对着行看，谁多谁少一目了然。 */
                Writer writer = new OutputStreamWriter(new FileOutputStream(new File(dumpTo,
                        file.getName() + ".txt")), java.nio.charset.StandardCharsets.UTF_8);
                try { writer.write(out.text); } finally { writer.close(); }
            }
            long readable = count(out.text);
            long han = countHan(out.text);
            bytes += data.length; chars += readable; cjk += han; ms += cost; ops += out.textOps;
            lost += out.undecodableGlyphs; cid += out.cidTableChars;
            System.out.printf(java.util.Locale.US,
                    "FILE %-24s bytes=%-9d pages=%-4d textOps=%-6d chars=%-6d hanzi=%-6d lost=%-6d 随包表=%-6d ms=%d 表=[%s] 卡住的字体=[%s]%n",
                    file.getName(), data.length, out.pages, out.textOps, readable, han,
                    out.undecodableGlyphs, out.cidTableChars, cost, out.cidTableOrderings, out.undecodableFonts);
        }
        System.out.printf(java.util.Locale.US,
                "TOTAL files=%d bytes=%d textOps=%d chars=%d hanzi=%d lost=%d 随包表=%d ms=%d%n",
                args.length, bytes, ops, chars, cjk, lost, cid, ms);
    }

    /** 可读正文按非空白字符数，和 PyMuPDF 那份真值同口径去比。 */
    private static long count(String text) {
        long n = 0;
        for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) n++;
        return n;
    }

    private static long countHan(String text) {
        long n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3000 && c <= 0x9FFF) n++;
        }
        return n;
    }
}
