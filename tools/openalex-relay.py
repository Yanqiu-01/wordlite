#!/usr/bin/env python3
"""本机直连转发器（只为量台服务，不进包、不参与产品逻辑）。

为什么需要它：Routes 对海外源是"代理优先、直连垫底"，而 OpenAlex 按出口 IP 计配额，
这台电脑走 Clash 出口那条路的配额已经见底（X-RateLimit-Remaining: 2），直连那条反而还有。
Java 侧改不了这个顺序（也不该改），所以量台把 OpenAlex 的端点指到本机的这个转发器，
由它直连 api.openalex.org。转发的还是同一个接口、同一份响应，只是少绕一段代理。

用法： py tools/openalex-relay.py [端口]      默认 8797
日志： artifacts/tmp/openalex-relay.log   每条一行：状态 / 字节 / 剩余配额
"""
import json
import os
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "https://api.openalex.org"
# ProxyHandler({}) = 明确不走任何代理：这台机器的 shell 会注入 HTTP_PROXY/HTTPS_PROXY，
# 跟着环境走就又撞回那条没配额的出口了。
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
LOG = os.path.join("artifacts", "tmp", "openalex-relay.log")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        request = urllib.request.Request(UPSTREAM + self.path, headers={
            "User-Agent": "wordlite-docx-scan-probe/1.0",
            "Accept": "application/json",
        })
        try:
            with OPENER.open(request, timeout=40) as upstream:
                body = upstream.read()
                self.reply(upstream.status, body, dict(upstream.headers))
        except urllib.error.HTTPError as error:
            body = error.read() or b""
            self.reply(error.code, body, dict(error.headers or {}))
        except Exception as error:
            note("502 %s %s" % (len(self.path), error))
            self.reply(502, str(error).encode("utf-8"), {})

    def reply(self, status, body, headers):
        self.send_response(status)
        self.send_header("Content-Type", headers.get("Content-Type", "application/json"))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        note("%s %dB remaining=%s %s" % (status, len(body),
                                         headers.get("X-RateLimit-Remaining", "-"), self.path[:70]))

    def log_message(self, fmt, *args):
        pass


def note(line):
    try:
        os.makedirs(os.path.dirname(LOG), exist_ok=True)
        with open(LOG, "a", encoding="utf-8") as handle:
            handle.write(line + "\n")
    except OSError:
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8797
    note("listen %d -> %s (direct)" % (port, UPSTREAM))
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
