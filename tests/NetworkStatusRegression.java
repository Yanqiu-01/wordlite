package com.rikkahub.wordlite;

/**
 * "手机这会儿有没有网"这句话的判据与措辞。
 *
 * 为什么单独建一台：结果页一度把"检索站没走通"说成"本机没联网"，而那一刻手机连着 5G。
 * 用户照着这句话去开关飞行模式、去查 WLAN，全是没有用的方向——真正没通的那一段
 * （直连被拒 / 电脑上转发的代理端口没人监听 / 站点要人机验证）由 DuplicateEngine 那句带数字的话负责说。
 * 所以这一台只管两件事：手机确实没网时才有资格说没网；手机有网时那句话里不许出现任何"没网"的字样。
 */
public final class NetworkStatusRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    private static void offlineSaysSoOnce() {
        String line = NetworkStatus.line(false, "WLAN", false);
        check(line.contains("没有可用网络"), "手机没网时才说没网：" + line);
        check(!line.contains("%"), "网络那一行不写任何比率：" + line);
    }

    private static void onlineNeverClaimsOffline() {
        String wifi = NetworkStatus.line(true, "WLAN", true);
        check(wifi.contains("已连上WLAN"), "连着 WLAN 要报出 WLAN：" + wifi);
        check(!wifi.contains("没有可用网络") && !wifi.contains("没联网"),
                "手机有网时不许出现任何没网的说法：" + wifi);
        check(wifi.contains("不是手机断了网"), "有网而检索失败时要把方向指对：" + wifi);
        String cell = NetworkStatus.line(true, "移动数据", true);
        check(cell.contains("移动数据") && cell.contains("按流量计费"),
                "走流量要标出来（一轮查重按满额度是几十 MB）：" + cell);
    }

    private static void unknownTypeNameStillReads() {
        String blank = NetworkStatus.line(true, "  ", false);
        check(blank.contains("已连上网络") && !blank.contains("  "),
                "类型名缺也不崩、不留双空格：" + blank);
    }

    public static void main(String[] args) {
        offlineSaysSoOnce();
        onlineNeverClaimsOffline();
        unknownTypeNameStillReads();
        System.out.println("SUMMARY " + count + " assertions passed"
                + " (系统事实那一段要真机才量得到，本用例量的是判据与措辞).");
    }
}

