#!/usr/bin/env python3
"""A minimal WebDAV server for testing CryptVault's backup target on this host — the stand-in
for the docker Nextcloud of docs/PROVIDER_SETUP.md §5 when docker is not available.

Speaks exactly what the app's WebDAV store needs: OPTIONS, PROPFIND (Depth 0/1), HEAD, GET
(Range), PUT (If-Match / If-None-Match with ETags), DELETE, MKCOL, MOVE (Destination,
Overwrite), with HTTP Basic auth. Files live under --root. Not a general WebDAV server.

    python3 tools/webdav_test_server.py --root /tmp/dav --port 8081 --user test --password secret
    # emulator URL: http://10.0.2.2:8081/dav/

--fail-every N makes every Nth mutating request fail with 500, to test interrupted runs.
"""
import argparse, base64, hashlib, os, shutil, sys, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from xml.sax.saxutils import escape

ARGS = None
COUNTER = {"mutations": 0}

def etag_of(path):
    st = os.stat(path)
    return '"%s"' % hashlib.sha1(("%d-%d" % (st.st_size, st.st_mtime_ns)).encode()).hexdigest()[:16]

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # ---- helpers ------------------------------------------------------------------------------
    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command, fmt % args))

    def _auth(self):
        if not ARGS.user:
            return True
        h = self.headers.get("Authorization", "")
        ok = h.startswith("Basic ") and base64.b64decode(h[6:]).decode() == "%s:%s" % (ARGS.user, ARGS.password)
        if not ok:
            self.send_response(401); self.send_header("WWW-Authenticate", 'Basic realm="dav"'); self.send_header("Content-Length", "0"); self.end_headers()
        return ok

    def _local(self, url_path=None):
        p = urllib.parse.unquote(urllib.parse.urlsplit(url_path or self.path).path)
        if not p.startswith(ARGS.prefix):
            return None
        rel = p[len(ARGS.prefix):].strip("/")
        if ".." in rel.split("/"):
            return None
        return os.path.join(ARGS.root, rel) if rel else ARGS.root

    def _reply(self, code, body=b"", ctype="text/plain", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _drain(self):
        n = int(self.headers.get("Content-Length") or 0)
        while n > 0:
            n -= len(self.rfile.read(min(n, 65536)))

    def _maybe_fail(self):
        if ARGS.fail_every:
            COUNTER["mutations"] += 1
            if COUNTER["mutations"] % ARGS.fail_every == 0:
                self._drain(); self._reply(500, b"injected failure"); return True
        return False

    def _precondition(self, local):
        """If-Match / If-None-Match against the current ETag; returns True when the request may proceed."""
        exists = os.path.isfile(local)
        im, inm = self.headers.get("If-Match"), self.headers.get("If-None-Match")
        if im is not None:
            if im == "*":
                if not exists: return False
            elif not exists or etag_of(local) not in [t.strip() for t in im.split(",")]:
                return False
        if inm is not None:
            if inm == "*":
                if exists: return False
            elif exists and etag_of(local) in [t.strip() for t in inm.split(",")]:
                return False
        return True

    # ---- methods ------------------------------------------------------------------------------
    def do_OPTIONS(self):
        self._reply(200, extra={"DAV": "1,2", "Allow": "OPTIONS,PROPFIND,GET,HEAD,PUT,DELETE,MKCOL,MOVE"})

    def do_PROPFIND(self):
        if not self._auth(): return
        self._drain()
        local = self._local()
        if local is None or not os.path.exists(local):
            return self._reply(404)
        depth = self.headers.get("Depth", "1")
        entries = [(self.path.rstrip("/") + ("/" if os.path.isdir(local) else ""), local)]
        if depth != "0" and os.path.isdir(local):
            base = urllib.parse.urlsplit(self.path).path.rstrip("/")
            for name in sorted(os.listdir(local)):
                child = os.path.join(local, name)
                entries.append((base + "/" + urllib.parse.quote(name) + ("/" if os.path.isdir(child) else ""), child))
        out = ['<?xml version="1.0" encoding="utf-8"?>', '<D:multistatus xmlns:D="DAV:">']
        for href, path in entries:
            st = os.stat(path)
            if os.path.isdir(path):
                props = "<D:resourcetype><D:collection/></D:resourcetype>"
            else:
                props = "<D:resourcetype/><D:getcontentlength>%d</D:getcontentlength><D:getetag>%s</D:getetag>" % (st.st_size, escape(etag_of(path)))
            out.append("<D:response><D:href>%s</D:href><D:propstat><D:prop>%s<D:displayname>%s</D:displayname></D:prop>"
                       "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>" % (escape(href), props, escape(os.path.basename(path.rstrip("/")) or "/")))
        out.append("</D:multistatus>")
        self._reply(207, "\n".join(out).encode(), 'application/xml; charset="utf-8"')

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        if not self._auth(): return
        local = self._local()
        if local is None or not os.path.isfile(local):
            return self._reply(404)
        size = os.path.getsize(local)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        code = 200
        if rng and rng.startswith("bytes="):
            a, b = rng[6:].split("-", 1)
            start = int(a) if a else max(0, size - int(b))
            end = int(b) if (b and a) else size - 1
            code = 206
        length = max(0, end - start + 1)
        self.send_response(code)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(length))
        self.send_header("ETag", etag_of(local))
        self.send_header("Accept-Ranges", "bytes")
        if code == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(local, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                chunk = f.read(min(left, 1 << 16))
                if not chunk: break
                self.wfile.write(chunk); left -= len(chunk)

    def do_PUT(self):
        if not self._auth(): return
        local = self._local()
        if local is None:
            self._drain(); return self._reply(403)
        if not os.path.isdir(os.path.dirname(local)):
            self._drain(); return self._reply(409, b"parent missing")
        if not self._precondition(local):
            self._drain(); return self._reply(412)
        if self._maybe_fail(): return
        n = int(self.headers.get("Content-Length") or 0)
        tmp = local + ".uploading"
        with open(tmp, "wb") as f:
            while n > 0:
                chunk = self.rfile.read(min(n, 1 << 16))
                if not chunk: break
                f.write(chunk); n -= len(chunk)
        existed = os.path.exists(local)
        os.replace(tmp, local)
        self._reply(204 if existed else 201, extra={"ETag": etag_of(local)})

    def do_DELETE(self):
        if not self._auth(): return
        local = self._local()
        if local is None or not os.path.exists(local):
            return self._reply(404)
        if os.path.isfile(local) and not self._precondition(local):
            return self._reply(412)
        if self._maybe_fail(): return
        shutil.rmtree(local) if os.path.isdir(local) else os.remove(local)
        self._reply(204)

    def do_MKCOL(self):
        if not self._auth(): return
        self._drain()
        local = self._local()
        if local is None: return self._reply(403)
        if os.path.exists(local): return self._reply(405)
        if not os.path.isdir(os.path.dirname(local)): return self._reply(409)
        os.mkdir(local); self._reply(201)

    def do_MOVE(self):
        if not self._auth(): return
        self._drain()
        src = self._local()
        dest = self._local(self.headers.get("Destination", ""))
        if src is None or dest is None: return self._reply(400)
        if not os.path.exists(src): return self._reply(404)
        if not os.path.isdir(os.path.dirname(dest)): return self._reply(409)
        existed = os.path.exists(dest)
        if existed and self.headers.get("Overwrite", "T").upper() == "F": return self._reply(412)
        if self._maybe_fail(): return
        if existed: shutil.rmtree(dest) if os.path.isdir(dest) else os.remove(dest)
        os.replace(src, dest)
        self._reply(204 if existed else 201)

def main():
    global ARGS
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", required=True)
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--prefix", default="/dav")
    ap.add_argument("--user", default="")
    ap.add_argument("--password", default="")
    ap.add_argument("--fail-every", type=int, default=0)
    ARGS = ap.parse_args()
    ARGS.prefix = "/" + ARGS.prefix.strip("/")
    os.makedirs(ARGS.root, exist_ok=True)
    srv = ThreadingHTTPServer((ARGS.bind, ARGS.port), Handler)
    print("WebDAV test server on http://%s:%d%s/ root=%s" % (ARGS.bind, ARGS.port, ARGS.prefix, ARGS.root), flush=True)
    srv.serve_forever()

if __name__ == "__main__":
    main()
