package com.rikkahub.wordlite;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 装在用户手机里的 FlareSolverr。
 *
 * 本机那一轮实测（docs/endpoints-access.md）说得很清楚：万方与维普那两个口，一次 HTTP 请求拿不到东西，
 * 真浏览器渲染之后才给——万方渲染后 3,331 汉字，里面有 1,500+ 字的「全文精要」与整条参考文献表。
 * FlareSolverr 是本机 Docker，装不进 APK；Android 自己带着 WebView 内核，
 * 后台加载 + 等静默 + 取 innerText，就是同一件事的手机版本，不需要电脑、不需要账号、不需要root。
 *
 * 三处与直觉相反的地方，都在代码里注着：
 *   1 WebView 只能在有 Looper 的线程上建，所以本类内部一律切回主线程建、主线程轮询、主线程回调；
 *     调用方可以在工作线程直接调 start()。
 *   2 等 onPageFinished 不够。Vue 那一页要在页面自己把数据请求回来之后才写 DOM，
 *     Chrome --dump-dom 因为虚拟时间预算不够就先交卷，只拿到 457 汉字。所以等"文本连续 N 毫秒不再变"。
 *   3 WebView 从没挂到窗口上过也会算 innerText，前提是它被 measure/layout 过。
 *     所以这一份被挂进内容视图，1080x1600、alpha 0、挪出屏幕外，用完立刻摘掉。
 *
 * 取回的那一页由 {@link PageText} 判断是正文、精要、摘要还是墙；墙一律按失败处理，一个字都不入库。
 */
public final class PageRender {
    private PageRender() { }

    /** 静默窗口、轮询间隔、总时限。三个数都有默认值，设置里给高级用户留出改的口子。 */
    public static final class Options {
        public int timeoutMillis = PageText.DEFAULT_TIMEOUT_MILLIS;
        public int silenceMillis = PageText.DEFAULT_SILENCE_MILLIS;
        public int pollMillis = PageText.DEFAULT_POLL_MILLIS;
        public String userAgent = PageText.USER_AGENT;
    }

    /** 一次渲染的结果。失败分五种：没有 WebView 实现、网络失败、超时、被弹回墙、那一页太薄。 */
    public static final class Result {
        public String url = "";
        public String finalUrl = "";
        public String title = "";
        /** 清洗后的整页文本；失败时也留着，界面上要说"取回了什么"。 */
        public String text = "";
        public boolean ok;
        /** 成功时是 PageText.SHAPE_READABLE，失败时是那一堵墙或那一种失败。 */
        public String shape = "";
        /** 失败种类的人话；成功时为空。 */
        public String failure = "";
        /** 该入库的那一段与它的档位；失败时为 null。 */
        public PageText.Picked picked;
        public int polls;
        public int chars;
        public int chinese;
        public long millis;
        /** 到时限还没静默下来：文本照样交出来，但要说明这是掐表那一刻的形状。 */
        public boolean timedOut;
        /** WebView 有没有真的挂到界面上：没挂上就没有 layout，innerText 会是空的。 */
        public boolean attached;
        /** 主文档有没有走完 onPageFinished：没有它，超时那条话就该说"还在加载"而不是"没静默"。 */
        public boolean pageFinished;
        /** evaluateJavascript 实际回了几次值：回值为零与回了值但都是空串，是两种完全不同的坏。 */
        public int dumps;

        public String describe() {
            if (ok) return "取回 " + chinese + " 个汉字，轮询 " + polls + " 次，" + (millis / 1000L) + " 秒";
            return failure.length() == 0 ? shape : failure;
        }
    }

    public interface Callback { void onResult(Result result); }

    /** 一次正在跑的渲染。取消是协作式的：主线程下一次轮询时收工。 */
    public static final class Handle {
        private volatile boolean cancelled;
        private volatile Runnable abort;

        public boolean cancelled() { return cancelled; }

        public void cancel() {
            cancelled = true;
            Runnable now = abort;
            if (now != null) now.run();
        }
    }

    /**
     * 取整页文本的那段 JS：先摘掉脚本与样式节点，再取 innerText；算不出就退到 textContent。
     * 返回值是一段 JSON 三件套 [地址, 标题, 文本]，由 evaluateJavascript 的回调直接交回来。
     */
    private static final String DUMP_SCRIPT =
            "(function(){try{var d=document;if(d&&d.querySelectorAll){"
          + "var n=d.querySelectorAll('script,style,noscript,svg,iframe');for(var i=0;i<n.length;i++){n[i].remove();}}"
          + "var b=d&&d.body?d.body:null;"
          + "var t=b?(b.innerText||b.textContent||''):'';"
          + "return [d?d.location.href:'',d?d.title:'',t];"
          + "}catch(e){return ['','',''];}})();";

    private static final class Job {
        /** 建 WebView 与挂载都用这一份：application context 里没有内容视图，挂不上就没有 layout。 */
        final Context host;
        final Context app;
        final String url;
        final Options options;
        final Callback callback;
        final Handle handle;
        final Handler main = new Handler(Looper.getMainLooper());
        final long started = System.currentTimeMillis();
        WebView view;
        ViewGroup parent;
        String lastText = "";
        String lastUrl = "";
        String lastTitle = "";
        long lastChange = started;
        boolean pageFinished;
        int dumps;
        long deadline;
        int polls;
        boolean finished;
        boolean networkError;
        String networkMessage = "";

        boolean attached;

        Job(Context context, String url, Options options, Callback callback, Handle handle) {
            this.host = context;
            this.app = context.getApplicationContext();
            this.url = url == null ? "" : url;
            this.options = options == null ? new Options() : options;
            this.callback = callback;
            this.handle = handle;
        }

        void start() {
            deadline = System.currentTimeMillis() + options.timeoutMillis;
            try {
                view = new WebView(host);
            } catch (Throwable error) {
                // 国产 ROM 上 WebView 实现可能被裁掉或被禁用：这是一种独立的失败，别混进"超时"。
                finish(fail(PageText.SHAPE_SHELL, "这台手机没有可用的 WebView 内核：" + error));
                return;
            }
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            // 查重取的是字，不是图：挡掉图片与图里的请求，一页能省掉几 MB 流量。
            settings.setLoadsImagesAutomatically(false);
            settings.setBlockNetworkImage(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(false);
            if (options.userAgent != null && options.userAgent.length() > 0) settings.setUserAgentString(options.userAgent);
            view.setBackgroundColor(Color.TRANSPARENT);
            view.setFocusable(false);
            view.setWebViewClient(new WebViewClient() {
                public void onPageFinished(WebView web, String href) {
                    pageFinished = true;
                    // 静默计时从这里重新开始走：ticker 每 pollMillis 取一次字，连续 silenceMillis
                    // 不再变化才算这一页写完了。这里不直接取字，避免和 ticker 并成两条轮询链。
                    if (href != null && href.length() > 0) lastUrl = href;
                    lastChange = System.currentTimeMillis();
                }

                public void onReceivedError(WebView web, WebResourceRequest request, WebResourceError error) {
                    if (request == null || !request.isForMainFrame()) return;   // 子资源失败不算这一页失败
                    networkError = true;
                    networkMessage = error == null ? "" : String.valueOf(error.getDescription());
                }
            });
            attach();
            view.loadUrl(url);
            main.postDelayed(ticker, options.pollMillis);
        }

        /** 挂进内容视图但不画出来：没有 layout 就没有 innerText，这是这一步存在的全部理由。 */
        private void attach() {
            attached = false;
            FrameLayout content = null;
            if (host instanceof android.app.Activity) {
                View holder = ((android.app.Activity) host).findViewById(android.R.id.content);
                if (holder instanceof FrameLayout) content = (FrameLayout) holder;
            }
            if (content == null) return;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 1600);
            view.setAlpha(0f);
            view.setTranslationY(20000f);
            content.addView(view, params);
            parent = content;
            attached = true;
        }

        private final Runnable ticker = new Runnable() {
            public void run() {
                if (finished) return;
                if (handle.cancelled()) { finish(fail(PageText.SHAPE_SHELL, "已取消")); return; }
                if (networkError) {
                    finish(fail(PageText.SHAPE_SHELL, "页面加载失败：" + networkMessage));
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    Result timed = decide();
                    timed.timedOut = true;
                    if (timed.ok) { finish(timed); return; }
                    timed.shape = PageText.SHAPE_SHELL;
                    // 超时不是一句话能打完的：卡在加载、卡在取字不回值、还是回了值但页面就是没内容，
                    // 三种坏的下一步完全不一样，界面上必须分开说。
                    StringBuilder why = new StringBuilder("等了 ")
                            .append(options.timeoutMillis / 1000L).append(" 秒没收工：");
                    if (!timed.attached) why.append("这一页始终没能挂上界面（没有 layout 就没有 innerText）");
                    else if (!timed.pageFinished) why.append("主文档始终没加载完（连接挂在那里，多半是网络那条路不通）");
                    else if (timed.dumps == 0) why.append("页面加载完了，但取字一次都没回值");
                    else why.append("取字回了 " + timed.dumps + " 次，最后一次只有 " + timed.chinese
                            + " 个可读汉字（这一页本来就是空的，或在等它自己的数据）");
                    timed.failure = why.toString();
                    finish(timed);
                    return;
                }
                if (view != null) {
                    try {
                        view.evaluateJavascript(DUMP_SCRIPT, new android.webkit.ValueCallback<String>() {
                            public void onReceiveValue(String raw) { take(raw); }
                        });
                    } catch (Throwable ignored) {
                        // 页面正在跳转时 evaluateJavascript 可能抛：这一拍丢掉，下一拍再来。
                    }
                }
                polls++;
                main.postDelayed(this, options.pollMillis);
            }
        };

        /** evaluateJavascript 交回来的那一拍：解出三个字段，再交给主线程判静默。 */
        private void take(String raw) {
            dumps++;
            String href = "";
            String pageTitle = "";
            String bodyText = "";
            // evaluateJavascript 交回来的是"这个 JS 值的 JSON 写法"：直接返回数组时是一段数组文本，
            // 但若脚本自己 stringify 过，这里拿到的就是一个被引号包起来的字符串。两种都接住，
            // 不然解包失败会被当成"这一页没字"，我在这儿被坑过一次：74 次取字全数报空。
            String body = raw == null ? "[]" : raw.trim();
            if (body.startsWith("\"")) {
                try {
                    body = new org.json.JSONArray("[" + body + "]").getString(0);
                } catch (Throwable error) {
                    return;
                }
            }
            try {
                org.json.JSONArray row = new org.json.JSONArray(body.length() == 0 ? "[]" : body);
                href = row.optString(0, "");
                pageTitle = row.optString(1, "");
                bodyText = row.optString(2, "");
            } catch (Throwable error) {
                return;        // 这一拍没取到值（页面正在跳转），不算失败，下一拍再取
            }
            onDump(href, pageTitle, bodyText);
        }

        /** 静默判据与墙的早停都在这里。 */
        public void onDump(final String href, final String pageTitle, final String bodyText) {
            main.post(new Runnable() {
                public void run() {
                    if (finished) return;
                    String cleaned = PageText.clean(bodyText);
                    if (!cleaned.equals(lastText)) {
                        lastText = cleaned;
                        lastUrl = href == null || href.length() == 0 ? lastUrl : href;
                        lastTitle = pageTitle == null ? "" : pageTitle;
                        lastChange = System.currentTimeMillis();
                    }
                    // 弹回登录页/滑块页就立刻收工：那种页上也有"摘要""关键词"这些字，
                    // 多等一会儿只会让一堵墙看起来更像正文。
                    String shape = PageText.shapeOf(lastUrl, lastText);
                    if (!PageText.SHAPE_READABLE.equals(shape)
                            && !PageText.SHAPE_SHELL.equals(shape) && !PageText.SHAPE_THIN.equals(shape)) {
                        Result result = decide();
                        result.shape = shape;
                        result.failure = "这一页是" + shape + "，一个字都不入库";
                        finish(result);
                        return;
                    }
                    if (lastText.length() == 0) return;
                    if (System.currentTimeMillis() - lastChange < options.silenceMillis) return;
                    finish(decide());
                }
            });
        }

        private Result decide() {
            Result result = new Result();
            result.url = url;
            result.finalUrl = lastUrl;
            result.title = lastTitle;
            result.text = lastText;
            result.polls = polls;
            result.attached = attached;
            result.pageFinished = pageFinished;
            result.dumps = dumps;
            result.millis = System.currentTimeMillis() - started;
            result.picked = PageText.pick(url, lastUrl, lastTitle, lastText);
            result.shape = result.picked.shape;
            result.chars = result.picked.chars;
            // 整页的汉字数，不是取回那一段的：手机上那台量台报"整页 90 个汉字"而整页有 900 个，
            // 看日志的人会以为页面是空的，其实是摘要那一段只有 90 个。
            result.chinese = result.picked.pageChinese;
            result.ok = result.picked.ok;
            if (!result.ok) result.failure = result.picked.shape + "：" + result.picked.note;
            return result;
        }

        private Result fail(String shape, String message) {
            Result result = decide();
            result.ok = false;
            result.shape = shape;
            result.failure = message;
            return result;
        }

        /** 摘掉 WebView、停掉轮询、把结果交回去。只在主线程上跑。 */
        void finish(final Result result) {
            if (finished) return;
            finished = true;
            main.removeCallbacks(ticker);
            if (view != null) {
                view.stopLoading();
                if (parent != null) parent.removeView(view);
                parent = null;
                view.removeAllViews();
                try {
                    view.destroy();
                } catch (Throwable ignored) {
                    // 销毁失败没有后果：这一页的文本已经交出去了，没必要为一个清理动作再报一次错。
                }
                view = null;
            }
            if (callback == null) return;
            main.post(new Runnable() {
                public void run() { callback.onResult(result); }
            });
        }
    }

    /** 有没有 WebView 内核可试（ROM 裁掉内核的机器上，这一句先说清，别让查重撞成一个空结果）。 */
    public static boolean available(Context context) {
        if (context == null) return false;
        try {
            Class.forName("android.webkit.WebView");
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 渲染一页。任何线程都能调：内部一律切到主线程建 WebView，回调也在主线程上回来。
     * 返回一个把手，界面用它取消。
     */
    public static Handle start(Context context, String url, Options options, final Callback callback) {
        final Handle handle = new Handle();
        if (context == null || url == null || url.trim().length() == 0) {
            if (callback != null) {
                Result result = new Result();
                result.url = url == null ? "" : url;
                result.shape = PageText.SHAPE_SHELL;
                result.failure = "没有地址或上下文，没开始加载";
                callback.onResult(result);
            }
            return handle;
        }
        final Job job = new Job(context, url, options, callback, handle);
        handle.abort = new Runnable() {
            public void run() {
                job.main.post(new Runnable() {
                    public void run() { job.finish(job.fail(PageText.SHAPE_SHELL, "已取消")); }
                });
            }
        };
        job.main.post(new Runnable() {
            public void run() { job.start(); }
        });
        return handle;
    }

    /**
     * 探针与自检用的同步外壳：在工作线程上等这一页跑完。主线程调用会直接拿到一句拒绝——
     * 渲染要靠主线程的 Looper 转，主线程一停在这儿转就全停了。
     */
    public static Result renderBlocking(Context context, String url, Options options, Handle handle) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Result refused = new Result();
            refused.url = url == null ? "" : url;
            refused.shape = PageText.SHAPE_SHELL;
            refused.failure = "渲染要占主线程转圈，请在工作线程调用";
            return refused;
        }
        final Result[] box = new Result[1];
        final Object lock = new Object();
        Handle started = start(context, url, options, new Callback() {
            public void onResult(Result result) {
                synchronized (lock) {
                    box[0] = result;
                    lock.notifyAll();
                }
            }
        });
        if (handle != null) handle.abort = new Runnable() {
            public void run() { started.cancel(); }
        };
        long waitUntil = System.currentTimeMillis()
                + (options == null ? PageText.DEFAULT_TIMEOUT_MILLIS : options.timeoutMillis) + 5000L;
        synchronized (lock) {
            while (box[0] == null) {
                long left = waitUntil - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException error) {
                    break;
                }
            }
        }
        if (box[0] == null) {
            started.cancel();
            Result timeout = new Result();
            timeout.url = url == null ? "" : url;
            timeout.shape = PageText.SHAPE_SHELL;
            timeout.failure = "等这一页渲染超时";
            return timeout;
        }
        return box[0];
    }
}