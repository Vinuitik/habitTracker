#!/usr/bin/env python3
"""
Egress proxy for KPI capability sandbox runners. Stdlib only, no Docker socket.

Runners sit on an `internal: true` docker network where this proxy is the only reachable host.
Each run carries a stateless token in Proxy-Authorization (Basic, username = token):
    token = b64url(json{"h":[hosts],"exp":epoch,"rid":runId}) + "." + b64url(HMAC-SHA256(secret, payloadB64))
minted by the sandbox sidecar with the shared SANDBOX_EGRESS_SECRET.

Allowed: CONNECT host:443|80 and absolute-URI plain HTTP to port 80|443, ONLY for exact hostnames in
the token. DNS is resolved HERE; every resolved address must be globally routable (SSRF guard) and the
connection goes to the checked IP (no re-resolution). Fails closed: no/short secret -> deny everything.
"""
import base64
import binascii
import hashlib
import hmac
import ipaddress
import json
import os
import select
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PORT = int(os.environ.get("EGRESS_PROXY_PORT", "3128"))
ALLOWED_PORTS = (80, 443)
MAX_CONNECTIONS = int(os.environ.get("EGRESS_MAX_CONNECTIONS", "64"))
CONNECT_TIMEOUT = float(os.environ.get("EGRESS_CONNECT_TIMEOUT", "10"))
IDLE_TIMEOUT = float(os.environ.get("EGRESS_IDLE_TIMEOUT", "60"))
MAX_TUNNEL_SECONDS = float(os.environ.get("EGRESS_MAX_TUNNEL_SECONDS", "120"))
MIN_SECRET_LEN = 32

_getaddrinfo = socket.getaddrinfo  # indirection so tests can mock DNS without touching the global socket
_conn_slots = threading.BoundedSemaphore(MAX_CONNECTIONS)


def get_secret():
    s = os.environ.get("SANDBOX_EGRESS_SECRET", "")
    return s.encode() if len(s) >= MIN_SECRET_LEN else None


def _b64d(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _b64e(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def mint_token(secret, hosts, ttl_seconds, run_id, now=None):
    """Same format the sandbox sidecar mints (sandbox/app.py mint_egress_token); used by tests."""
    exp = int((time.time() if now is None else now) + ttl_seconds)
    payload = _b64e(json.dumps({"h": sorted(hosts), "exp": exp, "rid": run_id}, separators=(",", ":")).encode())
    sig = _b64e(hmac.new(secret, payload.encode(), hashlib.sha256).digest())
    return payload + "." + sig


def verify_token(token, secret, now=None):
    """Returns (hosts frozenset, run_id) or raises ValueError(reason). Constant-time signature check."""
    if secret is None:
        raise ValueError("egress secret not configured")
    if not token or token.count(".") != 1 or len(token) > 4096:
        raise ValueError("malformed token")
    payload, sig = token.split(".")
    expected = _b64e(hmac.new(secret, payload.encode(), hashlib.sha256).digest())
    if not hmac.compare_digest(sig, expected):
        raise ValueError("bad signature")
    try:
        data = json.loads(_b64d(payload))
        hosts, exp, rid = data["h"], data["exp"], str(data.get("rid", ""))
        if not isinstance(hosts, list) or not all(isinstance(h, str) for h in hosts) \
                or not isinstance(exp, int) or isinstance(exp, bool):
            raise ValueError
    except (ValueError, KeyError, TypeError, binascii.Error):
        raise ValueError("malformed token")
    if (time.time() if now is None else now) >= exp:
        raise ValueError("token expired")
    return frozenset(h.lower() for h in hosts), rid


def token_from_header(value):
    """Proxy-Authorization: Basic base64(token:anything) -> token, or None."""
    if not value:
        return None
    parts = value.split(None, 1)
    if len(parts) != 2 or parts[0].lower() != "basic":
        return None
    try:
        return base64.b64decode(parts[1].strip(), validate=True).decode().split(":", 1)[0]
    except (binascii.Error, UnicodeDecodeError, ValueError):
        return None


def ip_is_allowed(ip_str):
    """SSRF guard: only globally routable unicast addresses."""
    try:
        ip = ipaddress.ip_address(ip_str.split("%")[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            return ip_is_allowed(str(ip.ipv4_mapped))
        if ip.sixtofour is not None and not ip_is_allowed(str(ip.sixtofour)):
            return False
        if ip.teredo is not None:
            return False
    return (ip.is_global and not ip.is_multicast and not ip.is_private and not ip.is_loopback
            and not ip.is_link_local and not ip.is_reserved and not ip.is_unspecified)


class Denied(Exception):
    def __init__(self, code, reason):
        super().__init__(reason)
        self.code = code
        self.reason = reason


def authorize(proxy_auth_header, host, port, secret=None, now=None):
    """Raises Denied unless token valid + host exact-match in token + port allowed."""
    secret = secret if secret is not None else get_secret()
    token = token_from_header(proxy_auth_header)
    if token is None:
        raise Denied(407, "missing proxy credentials")
    try:
        hosts, _rid = verify_token(token, secret, now)
    except ValueError as e:
        raise Denied(407, str(e))
    if port not in ALLOWED_PORTS:
        raise Denied(403, "port %s not allowed" % port)
    if host.lower().rstrip(".") not in hosts:
        raise Denied(403, "host not in allowlist")


def resolve_checked(host, port, getaddrinfo=None):
    """Resolve and vet EVERY address. Returns vetted IP strings or raises Denied."""
    try:
        infos = (getaddrinfo or _getaddrinfo)(host, port, type=socket.SOCK_STREAM)
    except OSError:
        raise Denied(502, "dns resolution failed")
    ips = []
    for info in infos:
        ip = info[4][0]
        if not ip_is_allowed(ip):
            raise Denied(403, "destination resolves to a non-public address")
        if ip not in ips:
            ips.append(ip)
    if not ips:
        raise Denied(502, "no addresses")
    return ips


def connect_checked(ips, port, create_connection=None):
    create_connection = create_connection or socket.create_connection
    last = None
    for ip in ips:
        try:
            return create_connection((ip, port), timeout=CONNECT_TIMEOUT)
        except OSError as e:
            last = e
    raise Denied(502, "connect failed: %s" % (last.__class__.__name__,))


def pipe(a, b):
    """Bidirectional relay until either side closes, idle timeout, or max lifetime."""
    deadline = time.time() + MAX_TUNNEL_SECONDS
    socks = [a, b]
    while time.time() < deadline:
        r, _, x = select.select(socks, [], socks, IDLE_TIMEOUT)
        if x or not r:
            return
        for s in r:
            try:
                data = s.recv(65536)
            except OSError:
                return
            if not data:
                return
            try:
                (b if s is a else a).sendall(data)
            except OSError:
                return


def parse_hostport(authority, default_port):
    host, port = authority, default_port
    if authority.startswith("["):
        raise Denied(400, "ip literals not allowed")
    if ":" in authority:
        host, _, p = authority.rpartition(":")
        if not p.isdigit():
            raise Denied(400, "bad port")
        port = int(p)
    if not host:
        raise Denied(400, "empty host")
    return host, port


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    timeout = 30

    def _deny(self, code, reason, host="-", port="-"):
        print("DENY %s:%s %s (%s)" % (host, port, reason, code), flush=True)
        body = (reason + "\n").encode()
        try:
            self.send_response(code, reason)
            if code == 407:
                self.send_header("Proxy-Authenticate", 'Basic realm="egress"')
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            pass

    def _open_upstream(self, host, port):
        authorize(self.headers.get("Proxy-Authorization"), host, port)
        return connect_checked(resolve_checked(host, port), port)

    def do_CONNECT(self):
        host, port = "-", "-"
        if not _conn_slots.acquire(blocking=False):
            return self._deny(503, "proxy busy")
        try:
            host, port = parse_hostport(self.path, 443)
            up = self._open_upstream(host, port)
            print("ALLOW CONNECT %s:%s" % (host, port), flush=True)
            try:
                self.send_response(200, "Connection Established")
                self.end_headers()
                self.wfile.flush()
                pipe(self.connection, up)
            finally:
                up.close()
        except Denied as d:
            self._deny(d.code, d.reason, host, port)
        except Exception as e:  # noqa: BLE001
            self._deny(502, "proxy error: " + e.__class__.__name__, host, port)
        finally:
            _conn_slots.release()

    def _plain(self):
        host, port = "-", "-"
        if not _conn_slots.acquire(blocking=False):
            return self._deny(503, "proxy busy")
        try:
            u = urlsplit(self.path)
            if u.scheme != "http" or not u.netloc or "@" in u.netloc:
                raise Denied(400, "absolute http:// URI required")
            host, port = parse_hostport(u.netloc, 80)
            if self.headers.get("Transfer-Encoding"):
                raise Denied(501, "chunked request bodies unsupported")
            up = self._open_upstream(host, port)
            print("ALLOW %s %s:%s" % (self.command, host, port), flush=True)
            try:
                target = (u.path or "/") + ("?" + u.query if u.query else "")
                lines = ["%s %s HTTP/1.1" % (self.command, target)]
                for k, v in self.headers.items():
                    if k.lower() in ("proxy-authorization", "proxy-connection", "connection", "keep-alive"):
                        continue
                    lines.append("%s: %s" % (k, v))
                lines.append("Connection: close")
                up.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))
                n = int(self.headers.get("Content-Length") or 0)
                if n:
                    up.sendall(self.rfile.read(n))
                pipe(self.connection, up)
            finally:
                up.close()
        except Denied as d:
            self._deny(d.code, d.reason, host, port)
        except Exception as e:  # noqa: BLE001
            self._deny(502, "proxy error: " + e.__class__.__name__, host, port)
        finally:
            _conn_slots.release()

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = do_PATCH = do_OPTIONS = _plain

    def log_message(self, fmt, *args):  # request lines can carry URLs; ALLOW/DENY lines above are the log
        pass


def main():
    if get_secret() is None:
        print("WARNING: SANDBOX_EGRESS_SECRET unset or < %d chars: egress proxy will DENY ALL requests"
              % MIN_SECRET_LEN, flush=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    print("egress proxy listening on :%d" % PORT, flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
