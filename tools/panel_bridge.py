#!/usr/bin/env python3
"""Public ingress bridge for the FB-SMP admin panel.

The panel itself stays bound to loopback only. This bridge listens on the
container port that the platform publishes over HTTPS, rewrites the Host header
to a loopback value so the panel accepts the request, and forwards the session
cookie both ways. Every other inbound path on the public port is rejected, so
only the control room is reachable from outside the container.
"""
import http.client
import os
import socketserver
import sys
from http.server import BaseHTTPRequestHandler

LISTEN_HOST = os.environ.get("PANEL_BRIDGE_LISTEN", "0.0.0.0")
LISTEN_PORT = int(os.environ.get("PANEL_BRIDGE_PORT", "12000"))
PANEL_HOST = os.environ.get("PANEL_BRIDGE_TARGET_HOST", "127.0.0.1")
PANEL_PORT = int(os.environ.get("PANEL_BRIDGE_TARGET_PORT", "8765"))
PANEL_HOST_HEADER = "%s:%d" % (PANEL_HOST, PANEL_PORT)
ALLOWED_PREFIXES = ("/api/",)
ALLOWED_EXACT = ("/", "/index.html", "/favicon.ico", "/health")

HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade",
}


def _allowed(path: str) -> bool:
    return path in ALLOWED_EXACT or path.startswith(ALLOWED_PREFIXES)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "FBSMPPanelBridge"

    def log_message(self, fmt, *args):
        pass

    def _reject(self, status, message):
        body = ('{"error":"%s"}' % message).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _forward(self):
        path = self.path
        if not _allowed(path.split("?", 1)[0]):
            self._reject(404, "Not found.")
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None

        headers = {}
        for name, value in self.headers.items():
            if name.lower() in HOP_BY_HOP or name.lower() == "host":
                continue
            headers[name] = value
        headers["Host"] = PANEL_HOST_HEADER
        headers["X-Forwarded-Proto"] = "https"
        headers["X-Forwarded-For"] = self.client_address[0]

        connection = http.client.HTTPConnection(PANEL_HOST, PANEL_PORT, timeout=30)
        try:
            connection.request(self.command, path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            self.send_response(response.status)
            for name, value in response.getheaders():
                if name.lower() in HOP_BY_HOP or name.lower() == "content-length":
                    continue
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(payload)
        except Exception:
            self._reject(502, "The admin panel is unavailable.")
        finally:
            connection.close()

    do_GET = _forward
    do_POST = _forward
    do_HEAD = _forward
    do_PUT = _forward
    do_DELETE = _forward


class ThreadedServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with ThreadedServer((LISTEN_HOST, LISTEN_PORT), Handler) as httpd:
        sys.stderr.write("FB-SMP panel bridge on %s:%d -> %s:%d\n"
                         % (LISTEN_HOST, LISTEN_PORT, PANEL_HOST, PANEL_PORT))
        sys.stderr.flush()
        httpd.serve_forever()
