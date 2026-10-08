package com.rikkahub.wordlite;

/**
 * "手机这会儿到底有没有网"——这一句必须由手机自己回答，而且只在查重没跑成的时候说。
 *
 * 起因是界面上出现过一句"本机没联网，查重没有完成"，而用户的手机当时连着 5G。
 * 检索站不通的原因有很多种（直连被拒、电脑上转发的代理端口没人监听、站点要人机验证），
 * 把它们一律说成"你手机没网"既不对，也把人推向完全错误的下一步。所以这里只报系统给的事实，
 * 判定与措辞分开：真正的失败原因仍由 DuplicateEngine 那句带数字的话来说。
 */
final class NetworkStatus {
    private NetworkStatus() { }

    /** 系统说没有网络时的那句话。这一句是唯一允许出现"手机没网"字样的场合。 */
    static String line(boolean hasNetwork, String typeName, boolean metered) {
        if (!hasNetwork)
            return "手机网络：系统报告现在没有可用网络（WLAN 与移动数据都没连上）";
        String type = typeName == null || typeName.trim().isEmpty() ? "网络" : typeName.trim();
        return "手机网络：已连上" + type + (metered ? "（按流量计费）" : "")
                + "。检索没跑成不是手机断了网，是走不通检索站那一段";
    }

    /** 从 Context 取当前事实；取不到（无权限、老系统、后台受限）就当不知道，不猜。 */
    static String line(android.content.Context context) {
        if (context == null) return "";
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    context.getApplicationContext()
                            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            android.net.Network network = cm.getActiveNetwork();
            if (network == null) return line(false, "", false);
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return "";
            boolean internet = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            if (!internet) return line(false, "", false);
            String type = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "WLAN"
                    : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "移动数据"
                    : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ? "网线"
                    : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ? "VPN" : "网络";
            boolean free = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            return line(true, type, !free);
        } catch (Exception ignored) {
            return "";
        }
    }
}
