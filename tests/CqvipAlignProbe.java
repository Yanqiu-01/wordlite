package com.rikkahub.wordlite;
import java.util.*;
/**
 * 维普的记录切分对不对：服务端把题名整个留白，parseCqvip 只能靠"摘要之前的那一段"去认这篇的
 * 文献号与刊名。这一台量一件事——摘要与文献号有没有配错行。做法是拿某篇的真摘要原句去查，
 * 返回里必然有一条的摘要就是这句，看那一条带走的文献号是不是原来那个。
 */
public final class CqvipAlignProbe {
  public static void main(String[] a) throws Exception {
    PaperSources.Limits L = new PaperSources.Limits(); L.perEngine = 8; L.timeoutSeconds = 25; L.proxy = "";
    ArrayList<PaperSources.Candidate> seeds = PaperSources.search("cqvip", "中间层 液相扩散焊 界面 组织", L, null);
    for (PaperSources.Candidate seed : seeds) {
      String s = seed.abstractText == null ? "" : seed.abstractText;
      if (s.length() < 80) continue;
      String probe = s.substring(20, Math.min(s.length(), 60));
      ArrayList<PaperSources.Candidate> again = PaperSources.search("cqvip", probe, L, null);
      int matchIdx = -1;
      for (int i = 0; i < again.size(); i++) if (again.get(i).abstractText.contains(probe.substring(0, 24))) { matchIdx = i; break; }
      System.out.println("seed id=" + seed.source.id + " venue=" + seed.source.title);
      System.out.println("  probe=[" + probe.substring(0, 24) + "] returned=" + again.size() + " abstractMatchAt=" + matchIdx);
      for (int i = 0; i < Math.min(3, again.size()); i++) {
        PaperSources.Candidate c = again.get(i);
        System.out.println("   [" + i + "] id=" + c.source.id + " venue=" + c.source.title + " abs=" + c.abstractText.substring(0, Math.min(26, c.abstractText.length())));
      }
      Thread.sleep(400);
      if (matchIdx >= 0) break;
    }
  }
}
