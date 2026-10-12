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

# Progressive Web App assets served by the bridge itself (no game-server rebuild
# needed). The panel HTML is fetched from the plugin and patched in flight with
# the manifest / install / service-worker tags.
PANEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "panel")
STATIC_FILES = {
    "/manifest.webmanifest": ("manifest.webmanifest", "application/manifest+json; charset=utf-8"),
    "/sw.js": ("sw.js", "text/javascript; charset=utf-8"),
    "/icon-192.png": ("icon-192.png", "image/png"),
    "/icon-512.png": ("icon-512.png", "image/png"),
    "/icon-180.png": ("icon-180.png", "image/png"),
    "/apple-touch-icon.png": ("icon-180.png", "image/png"),
    "/favicon.ico": ("icon-192.png", "image/png"),
}
PWA_HEAD = (
    '<link rel="manifest" href="/manifest.webmanifest">'
    '<meta name="theme-color" content="#0c1118">'
    '<meta name="application-name" content="FB-SMP Control Room">'
    '<meta name="mobile-web-app-capable" content="yes">'
    '<meta name="apple-mobile-web-app-capable" content="yes">'
    '<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">'
    '<meta name="apple-mobile-web-app-title" content="FB-SMP">'
    '<link rel="apple-touch-icon" href="/apple-touch-icon.png">'
    '<link rel="icon" type="image/png" href="/icon-192.png">'
    '<script>if("serviceWorker" in navigator){'
    'window.addEventListener("load",function(){navigator.serviceWorker.register("/sw.js").catch(function(){})})}'
    '</script>'
)

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

    def _serve_static(self, spec):
        filename, content_type = spec
        try:
            with open(os.path.join(PANEL_DIR, filename), "rb") as handle:
                payload = handle.read()
        except OSError:
            self._reject(404, "Not found.")
            return
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "public, max-age=3600")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _panelled_index(self, path):
        """Fetch the panel HTML and inject the PWA tags into its <head>."""
        headers = {"Host": PANEL_HOST_HEADER, "X-Forwarded-Proto": "https",
                   "X-Forwarded-For": self.client_address[0]}
        connection = http.client.HTTPConnection(PANEL_HOST, PANEL_PORT, timeout=30)
        try:
            connection.request("GET", path, headers=headers)
            response = connection.getresponse()
            payload = response.read()
        except Exception:
            self._reject(502, "The admin panel is unavailable.")
            return
        finally:
            connection.close()
        if response.status == 200:
            text = payload.decode("utf-8", "replace")
            if "</head>" in text and "manifest.webmanifest" not in text:
                text = text.replace("</head>", PWA_HEAD + "</head>", 1)
            payload = text.encode("utf-8")
        self.send_response(response.status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _forward(self):
        path = self.path
        bare = path.split("?", 1)[0]
        if bare in STATIC_FILES:
            self._serve_static(STATIC_FILES[bare])
            return
        if bare in ("/", "/index.html"):
            self._panelled_index(path)
            return
        if not _allowed(bare):
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
