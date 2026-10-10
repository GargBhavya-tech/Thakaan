"""Serve the crew console on the local network and print a QR code for the viewer URL.

Run:  python -I tools/console/serve.py [port]
Host page:    http://<laptop-ip>:<port>/            (paste or read sealed phone statuses)
Viewer page:  http://<laptop-ip>:<port>/?view=1     (what the QR code opens; read-only live copy)
The server holds the board in memory only. It stores levels and coarse reasons, never raw physiology.
"""
import json
import os
import socket
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "console"))
STATE = {"crew": {}, "feed": []}
TYPES = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".json": "application/json"}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/state":
            return self._send(200, json.dumps(STATE).encode(), "application/json")
        name = "index.html" if path in ("/", "") else os.path.basename(path)
        full = os.path.join(ROOT, name)
        if not os.path.isfile(full):
            return self._send(404, b"not found", "text/plain")
        with open(full, "rb") as f:
            self._send(200, f.read(), TYPES.get(os.path.splitext(name)[1], "application/octet-stream"))

    def do_POST(self):
        # only the host page (loopback) may publish; viewers over the network are read-only
        if self.client_address[0] not in ("127.0.0.1", "::1"):
            return self._send(403, b"read-only", "text/plain")
        if self.path != "/state":
            return self._send(404, b"not found", "text/plain")
        n = int(self.headers.get("Content-Length", "0"))
        if n > 1_000_000:
            return self._send(413, b"too large", "text/plain")
        try:
            data = json.loads(self.rfile.read(n))
            STATE["crew"] = data.get("crew", {})
            STATE["feed"] = data.get("feed", [])[-50:]
        except Exception:
            return self._send(400, b"bad json", "text/plain")
        self._send(204, b"", "text/plain")

    def log_message(self, *a):
        pass


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    url = f"http://{lan_ip()}:{port}/?view=1"
    try:
        import segno
        q = segno.make(url, error="m")
        out = os.path.join(ROOT, "viewer_qr.svg")
        q.save(out, scale=6)
        print("QR code saved to", out)
        try:
            q.terminal(compact=True)
        except UnicodeEncodeError:
            pass  # legacy Windows console: the SVG file is enough
    except ImportError:
        print("(pip install segno to get a QR code)")
    print("Host page  : http://127.0.0.1:%d/" % port)
    print("Viewer URL :", url)
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()

