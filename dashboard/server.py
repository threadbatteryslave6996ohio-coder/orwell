#!/usr/bin/env python3
"""Local static server + reverse proxy for the orwell dev dashboard.

None of auth-server, secrets-manager-server, or klippy-server set CORS
headers, so the dashboard's browser JS can't call them directly from a
different origin. This proxies /api/<service>/* to the real backend
server-side (no browser CORS involved) and serves the dashboard's static
files at "/".
"""
import http.server
import os
import urllib.error
import urllib.request

PORT = 9187
BACKENDS = {
    "/api/auth": "http://localhost:8081",
    "/api/secrets": "http://localhost:8083",
    "/api/klippy": "http://localhost:8082",
}
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")
FORWARD_REQUEST_HEADERS = ("Authorization", "X-Client-Id", "Content-Type")
DROP_RESPONSE_HEADERS = {"content-length", "transfer-encoding", "connection"}


class Handler(http.server.BaseHTTPRequestHandler):
    def _proxy(self, method):
        for prefix, base in BACKENDS.items():
            if self.path == prefix or self.path.startswith(prefix + "/") or self.path.startswith(prefix + "?"):
                target = base + self.path[len(prefix):]
                length = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(length) if length else None
                headers = {h: self.headers[h] for h in FORWARD_REQUEST_HEADERS if h in self.headers}
                req = urllib.request.Request(target, data=body, headers=headers, method=method)
                try:
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        resp_body = resp.read()
                        self.send_response(resp.status)
                        for k, v in resp.getheaders():
                            if k.lower() not in DROP_RESPONSE_HEADERS:
                                self.send_header(k, v)
                        self.send_header("Content-Length", str(len(resp_body)))
                        self.end_headers()
                        self.wfile.write(resp_body)
                except urllib.error.HTTPError as e:
                    resp_body = e.read()
                    self.send_response(e.code)
                    self.send_header("Content-Type", e.headers.get("Content-Type", "application/json"))
                    self.send_header("Content-Length", str(len(resp_body)))
                    self.end_headers()
                    self.wfile.write(resp_body)
                except Exception as e:
                    msg = str(e).encode()
                    self.send_response(502)
                    self.send_header("Content-Type", "text/plain")
                    self.send_header("Content-Length", str(len(msg)))
                    self.end_headers()
                    self.wfile.write(msg)
                return True
        return False

    def _static(self):
        path = self.path.split("?")[0]
        if path == "/":
            path = "/index.html"
        fpath = os.path.join(STATIC_DIR, path.lstrip("/"))
        if not os.path.abspath(fpath).startswith(os.path.abspath(STATIC_DIR)):
            self.send_response(403)
            self.end_headers()
            return
        if not os.path.isfile(fpath):
            self.send_response(404)
            self.end_headers()
            return
        ctype = "text/html"
        if fpath.endswith(".js"):
            ctype = "application/javascript"
        elif fpath.endswith(".css"):
            ctype = "text/css"
        with open(fpath, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if not self._proxy("GET"):
            self._static()

    def do_POST(self):
        self._proxy("POST")

    def do_PUT(self):
        self._proxy("PUT")

    def do_DELETE(self):
        self._proxy("DELETE")

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"orwell dashboard: http://localhost:{PORT}")
    httpd.serve_forever()
