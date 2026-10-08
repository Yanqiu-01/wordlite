package com.rikkahub.wordlite;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 一条检索请求能走的几条路，以及先试哪一条。
 * 手机上的现实是这样的：知网、万方、维普在国内直连最顺，绕到海外出口反而经常被拒；
 * 而 OpenAlex、Semantic Scholar 这一类海外源，很多网络环境里直连根本出不去，必须借电脑上的代理。
 * 代理开没开、监听在哪个端口，应用说了不算，所以这里不赌某一条路，只按主机名排先后，
 * 谁先连通就用谁，并把每个源最后实际走的路记下来，好在自检里如实告诉用户。
 */
final class Routes {
    static final String DIRECT = "直连";
    /** 自动探路的连接超时。真不通要等到用户设的超时，等于每条路都白等二十秒。 */
    static final int PROBE_CONNECT_SECONDS = 6;
    /** 自动探测只试这两个端口：Clash 系的混合端口，USB 直连时用 adb reverse 也能指到这里。 */
    static final int[] LOOPBACK_PORTS = {7897, 7890};
    private static final LinkedHashMap<String, String> USED = new LinkedHashMap<String, String>();
    private static final LinkedHashMap<String, Proxy> KNOWN_GOOD = new LinkedHashMap<String, Proxy>();
    /** 这个主机的直连拨不上（连接被拒、超时、TLS 谈崩）。只用来把直连从队首挪到队尾，不做别的判断。 */
    private static final LinkedHashMap<String, Boolean> DIRECT_FAILED = new LinkedHashMap<String, Boolean>();
    private static Proxy lastGood;

    private Routes() { }

    /**
     * 按先后排好的候选路，null 表示直连。用户显式填的代理永远排第一，那是他的意思；
     * 之后国内源先试直连、海外源先试代理，最后才是剩下的那条。回环地址（测试用的桩服务）不掺代理。
     */
    static synchronized List<Proxy> order(String host, Proxy explicit) {
        List<Proxy> out = new ArrayList<Proxy>();
        /* 用户显式填的代理永远排第一，回环上的桩服务也不例外——测试就是拿回环代理跑的。 */
        add(out, explicit);
        if (isLoopback(host)) {
            /* 本机桩服务不掺自动发现的那些端口，否则回归测试会被一个碰巧开着的代理劫走。 */
            out.add(null);
            return out;
        }
        boolean domestic = domestic(host);
        /* 国内源直连优先是 0.7.3 实测定的，可那条判据只对「直连出得去」的网络成立。实测过的一台手机：
           挂在移动数据上时十个源直连全部拨不上（连接被当场拒回），只有电脑上用 adb reverse 转过来的
           Clash 出得去。那种网络里每一扇窗口都先撞一次直连死路，等于把 0.7.3 想省下的那条六秒死路原样
           花回去。所以这里的判据跟着实测走：这个主机直连确实失败过、且刚刚有另一条路为它走通过，这一轮
           就先走那条，直连退到队尾——不删掉它，网络随时可能恢复。 */
        Proxy proven = KNOWN_GOOD.get(host);
        boolean directIsDead = domestic && proven != null && DIRECT_FAILED.containsKey(host);
        if (domestic && !directIsDead) addDirect(out);
        if (proven != null) add(out, proven);
        if (lastGood != null) add(out, lastGood);
        for (Proxy auto : autodiscovered()) add(out, auto);
        if (!domestic || directIsDead) addDirect(out);
        return out;
    }

    /** 系统代理（WLAN 里设的、或 adb 转出来的回环代理）加上 Clash 常见的两个本地端口。 */
    private static List<Proxy> autodiscovered() {
        List<Proxy> out = new ArrayList<Proxy>();
        String host = System.getProperty("http.proxyHost", "").trim();
        String port = System.getProperty("http.proxyPort", "").trim();
        if (!host.isEmpty()) {
            Proxy system = at(host, port);
            if (system != null) out.add(system);
        }
        for (int value : LOOPBACK_PORTS) {
            Proxy loop = at("127.0.0.1", String.valueOf(value));
            if (loop != null) out.add(loop);
        }
        return out;
    }

    /** 用户填的 host:port；写坏了就当没填，直接拨出去，而不是整次查重失败。 */
    static Proxy parse(String value) {
        String text = value == null ? "" : value.trim();
        int colon = text.lastIndexOf(':');
        if (colon < 1 || colon == text.length() - 1 || text.indexOf('/') >= 0) return null;
        String host = text.substring(0, colon).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return at(host, text.substring(colon + 1).trim());
    }

    private static Proxy at(String host, String port) {
        int number;
        try { number = Integer.parseInt(port); }
        catch (NumberFormatException error) { return null; }
        if (host.isEmpty() || number < 1 || number > 65535) return null;
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, number));
    }

    private static void add(List<Proxy> out, Proxy value) {
        if (value == null || contains(out, value)) return;
        out.add(value);
    }

    /** 直连（null）也去重：同一次请求不必两次把包发往同一个出不去的出口。 */
    private static void addDirect(List<Proxy> out) {
        if (!out.contains(null)) out.add(null);
    }

    private static boolean contains(List<Proxy> out, Proxy value) {
        for (Proxy known : out) if (same(known, value)) return true;
        return false;
    }

    /**
     * 两条路是不是同一条。{@code java.net.Proxy} 不重写 equals，两次 parse 出来的同一个
     * host:port 在 {@code ==} 与 {@code List.contains} 眼里是两个对象——0.7.3 之前用户在设置里填的
     * 代理只要和自动发现的端口撞上（填 127.0.0.1:7897 是最常见的写法，USB 反代就指这儿），
     * 同一条死路每扇窗口要被撞两遍，自检里那条路也被列两次。地址按值比，不靠对象身份。
     */
    static boolean same(Proxy left, Proxy right) {
        if (left == right) return true;
        if (left == null || right == null || left.type() != right.type()) return false;
        return String.valueOf(left.address()).equals(String.valueOf(right.address()));
    }

    static String label(Proxy via) {
        if (via == null) return DIRECT;
        InetSocketAddress address = (InetSocketAddress) via.address();
        return "代理 " + address.getHostString() + ":" + address.getPort();
    }

    /** 探路用的超时：用户显式填的代理和他设的超时都照原样，只有自动试的那几条压到几秒。 */
    static int connectSeconds(Proxy explicit, Proxy via, int timeoutSeconds) {
        int requested = timeoutSeconds <= 0 ? 20 : Math.min(timeoutSeconds, 120);
        if (via == null || via.equals(explicit)) return requested;
        return Math.min(requested, PROBE_CONNECT_SECONDS);
    }

    /** 哪条路通了就记住它，下一次同类请求先走这条，省得每扇窗口都把死路重撞一遍。 */
    static synchronized void succeeded(Proxy via) {
        lastGood = via;
    }

    /**
     * 这条路为这个主机没走通。跟着改两件事：直连失败过就别再把它排在第一位；一条代理失败过就不再算
     * 「上次为它走通的那条」——USB 反代随拔线消失，电脑上的 Clash 也会被关掉，记住一条死代理和记住
     * 一条死直连是同一种错。调用方只在「路本身不通」时进来，对方返回 403/429 不算（换条路也是同样答复）。
     */
    static synchronized void failed(String url, Proxy via) {
        String host = host(url);
        if (host.isEmpty()) return;
        if (via == null) {
            DIRECT_FAILED.put(host, Boolean.TRUE);
            while (DIRECT_FAILED.size() > 32) DIRECT_FAILED.remove(DIRECT_FAILED.keySet().iterator().next());
            return;
        }
        KNOWN_GOOD.remove(host);
    }

    /** 找过的路全列出来：自检失败时用户最需要知道的是"到底试过哪几条"，而不是又一句"网络失败"。 */
    static String candidateList(String explicitProxy) {
        StringBuilder out = new StringBuilder(DIRECT);
        Proxy explicit = parse(explicitProxy);
        if (explicit != null) out.append("、").append(label(explicit));
        for (Proxy auto : autodiscovered())
            if (!same(auto, explicit)) out.append("、").append(label(auto));
        return out.toString();
    }
    /** 哪个源最后走了哪条路，自检和查重说明都用它。主机名不是文档内容，写出来不算泄露。 */
    static synchronized void note(String url, Proxy via) {
        String host = host(url);
        if (host.isEmpty()) return;
        String text = label(via);
        if (DIRECT.equals(text)) via = null;
        KNOWN_GOOD.put(host, via);
        if (via == null) DIRECT_FAILED.remove(host);
        USED.put(host, text);
        while (USED.size() > 32) USED.remove(USED.keySet().iterator().next());
    }

    /** 这个主机最后走通了哪条路；没走过就直说没走过，不猜。 */
    static synchronized String routeFor(String host) {
        String text = USED.get(host);
        return text == null ? "未走过" : text;
    }

    static synchronized String summary() {
        if (USED.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String host : USED.keySet()) {
            if (out.length() > 0) out.append("  ");
            out.append(host).append(' ').append(USED.get(host));
        }
        return out.toString();
    }

    static synchronized void reset() {
        USED.clear();
        KNOWN_GOOD.clear();
        DIRECT_FAILED.clear();
        lastGood = null;
    }

    static String host(String url) {
        int scheme = url == null ? -1 : url.indexOf("://");
        if (scheme < 0) return "";
        int end = url.length();
        for (int i = scheme + 3; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        return url.substring(scheme + 3, end).toLowerCase(Locale.ROOT);
    }

    private static boolean isLoopback(String host) {
        return host.startsWith("127.") || host.equals("localhost") || host.startsWith("[::1]");
    }

    /**     * 国内库。海外出口经常拿不到它们的正常响应，所以这些一律先直连。     *     * <p>{@code cnki.com.cn} 是 0.7.3 补上的，漏掉它的代价是实测出来的：知网的检索口挂在     * {@code search.cnki.com.cn}，不在 {@code cnki.net} 下，于是它被当成海外源——手机挂着代理时，     * 问知网的第一跳绕到了海外出口。同一个匿名检索口，绕出去的相关度更低，直连时同一篇论文更容易被问回来     * （知网自召回实测见 docs/retrieval-recall.md）。国内库的身份该按域名认，不该漏一个子域。     */
    static boolean domestic(String host) {
        String[] suffixes = {"cnki.net", "cnki.com.cn", "wanfangdata.com.cn", "cqvip.com", "cqvip.com.cn",
                "ncpssd.org", "wanfangdata.com", "doc88.com", "baidu.com"};
        for (String suffix : suffixes)
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        return false;
    }
}


