#!/usr/bin/env python3
"""Мок REST API для отладки WakeUp Messenger без боевого сервера.

Запуск:  python3 tools/mock_server.py 8080
Далее в приложении: Настройки -> Хост = IP компьютера, Порт = 8080.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

XMPP_HOST = "192.168.0.10"
XMPP_DOMAIN = "openfire"
PING = {}
OLD_PING = {}


class Handler(BaseHTTPRequestHandler):
    def _send(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _device(self, q):
        return (q.get("deviceId") or q.get("device") or ["unknown"])[0]

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        dev = self._device(q)
        if u.path == "/v1/auth/xmpp":
            PING[dev] = PING.get(dev, 0)
            self._send({
                "xmppHost": XMPP_HOST,
                "xmppPort": 5222,
                "xmppLogin": f"{dev}@{XMPP_DOMAIN}",
                "xmppPassword": "secret",
                "apiHost": self.headers.get("Host", "").split(":")[0],
                "apiPort": self.server.server_address[1],
            })
        elif u.path == "/v1/tasks":
            self._send([{"taskId": "12345", "title": "Отбор зоны А"}])
        elif u.path == "/wakeup/info":
            self._send({"pingCount": PING.get(dev, 0), "oldPingCount": OLD_PING.get(dev, 0)})
        else:
            self._send({"error": "not found"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        dev = self._device(q)
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode() if length else ""
        if u.path == "/v1/wakeup/confirm":
            PING[dev] = PING.get(dev, 0) + 1
            print(f"confirm from {dev}: {raw}")
            self._send({"status": "ok", "pingCount": PING[dev]})
        else:
            self._send({"error": "not found"}, 404)

    def do_DELETE(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        dev = self._device(q)
        if u.path == "/wakeup/reset":
            OLD_PING[dev] = PING.get(dev, 0)
            PING[dev] = 0
            self._send({"pingCount": 0, "oldPingCount": OLD_PING[dev]})
        else:
            self._send({"error": "not found"}, 404)

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"Mock REST API на 0.0.0.0:{port}")
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
