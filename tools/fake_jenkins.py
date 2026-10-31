#!/usr/bin/env python3
"""A Jenkins-shaped endpoint serving one build's artifacts.

    fake_jenkins.py <port> <log> [--status 401]
"""
import http.server
import json
import sys
import urllib.parse

port, log_path = int(sys.argv[1]), sys.argv[2]
status = 200
args = sys.argv[3:]
while args:
    if args.pop(0) == "--status":
        status = int(args.pop(0))

ARTIFACTS = {
    "dist/app.jar": b"jar-bytes\n",
    "reports/index.html": b"<html>report</html>\n",
    "reports/nested/deep.txt": b"deep\n",
    "reports/a file with spaces.txt": b"spaced\n",
}


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"path": self.path,
                                  "authorization": self.headers.get("Authorization")}) + "\n")
        if status != 200:
            self.send_response(status)
            self.end_headers()
            self.wfile.write(b'{"message":"unauthorized"}')
            return
        if "/api/json" in self.path:
            body = json.dumps({"number": 42, "result": "SUCCESS",
                               "artifacts": [{"relativePath": p} for p in ARTIFACTS]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
            return
        marker = "/artifact/"
        if marker in self.path:
            raw = self.path.split(marker, 1)[1]
            path = urllib.parse.unquote(raw)
            if path in ARTIFACTS:
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.end_headers()
                self.wfile.write(ARTIFACTS[path])
                return
        self.send_response(404)
        self.end_headers()

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
