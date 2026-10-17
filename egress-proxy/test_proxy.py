import base64
import importlib.util
import os
import socket
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from unittest import mock

import proxy

SECRET = b"s" * 40


def basic(token):
    return "Basic " + base64.b64encode((token + ":x").encode()).decode()


def tok(hosts=("api.github.com",), ttl=60, secret=SECRET, now=None):
    return proxy.mint_token(secret, hosts, ttl, "run1", now=now)


def gai(*ips):
    return lambda host, port, type=0: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port)) for ip in ips]


class AuthorizeTest(unittest.TestCase):
    def test_valid_token_and_allowed_host_passes(self):
        proxy.authorize(basic(tok()), "api.github.com", 443, secret=SECRET)
        proxy.authorize(basic(tok()), "API.GitHub.com", 80, secret=SECRET)

    def test_disallowed_host_denied(self):
        for h in ["evil.com", "github.com", "x.api.github.com", "api.github.com.evil.com"]:
            with self.assertRaises(proxy.Denied) as c:
                proxy.authorize(basic(tok()), h, 443, secret=SECRET)
            self.assertEqual(c.exception.code, 403)

    def test_no_wildcards(self):
        with self.assertRaises(proxy.Denied):
            proxy.authorize(basic(tok(("*.github.com",))), "api.github.com", 443, secret=SECRET)

    def test_expired_token_denied(self):
        t = tok(ttl=60, now=time.time() - 120)
        with self.assertRaises(proxy.Denied) as c:
            proxy.authorize(basic(t), "api.github.com", 443, secret=SECRET)
        self.assertEqual((c.exception.code, c.exception.reason), (407, "token expired"))

    def test_forged_token_denied(self):
        for t in [tok(secret=b"x" * 40), tok()[:-2] + "AA", "garbage", "a.b", ""]:
            with self.assertRaises(proxy.Denied) as c:
                proxy.authorize(basic(t), "api.github.com", 443, secret=SECRET)
            self.assertEqual(c.exception.code, 407)

    def test_tampered_payload_denied(self):
        payload, sig = tok().split(".")
        evil = proxy.mint_token(SECRET, ["evil.com"], 60, "r").split(".")[0]
        with self.assertRaises(proxy.Denied):
            proxy.authorize(basic(evil + "." + sig), "evil.com", 443, secret=SECRET)

    def test_missing_or_non_basic_header_denied(self):
        for h in [None, "", "Bearer abc", "Basic !!!notb64"]:
            with self.assertRaises(proxy.Denied) as c:
                proxy.authorize(h, "api.github.com", 443, secret=SECRET)
            self.assertEqual(c.exception.code, 407)

    def test_other_ports_denied(self):
        for p in [22, 25, 8080, 27017, 8089, 8090, 0]:
            with self.assertRaises(proxy.Denied) as c:
                proxy.authorize(basic(tok()), "api.github.com", p, secret=SECRET)
            self.assertEqual(c.exception.code, 403)

    def test_unset_secret_fails_closed(self):
        with mock.patch.dict(os.environ, {"SANDBOX_EGRESS_SECRET": ""}):
            self.assertIsNone(proxy.get_secret())
            with self.assertRaises(proxy.Denied):
                proxy.authorize(basic(tok()), "api.github.com", 443)
        with mock.patch.dict(os.environ, {"SANDBOX_EGRESS_SECRET": "short"}):
            self.assertIsNone(proxy.get_secret())

    def test_sidecar_minted_token_verifies(self):
        spec = importlib.util.spec_from_file_location(
            "sandbox_app", os.path.join(os.path.dirname(__file__), "..", "sandbox", "app.py"))
        app = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(app)
        t = app.mint_egress_token(SECRET, ["api.github.com"], 30, "rid9")
        hosts, rid = proxy.verify_token(t, SECRET)
        self.assertEqual((hosts, rid), (frozenset({"api.github.com"}), "rid9"))


class SsrfTest(unittest.TestCase):
    def test_private_and_special_ips_refused(self):
        for ip in ["127.0.0.1", "10.0.0.5", "172.17.0.1", "172.31.255.1", "192.168.1.1", "169.254.169.254",
                   "100.64.0.1", "224.0.0.1", "0.0.0.0", "240.0.0.1", "::1", "fe80::1", "fc00::1",
                   "::ffff:127.0.0.1", "::ffff:10.1.2.3", "ff02::1", "2002:7f00:1::1"]:
            self.assertFalse(proxy.ip_is_allowed(ip), ip)

    def test_public_ips_allowed(self):
        for ip in ["140.82.112.5", "1.1.1.1", "2606:4700:4700::1111"]:
            self.assertTrue(proxy.ip_is_allowed(ip), ip)

    def test_private_resolution_denied(self):
        with self.assertRaises(proxy.Denied) as c:
            proxy.resolve_checked("api.github.com", 443, getaddrinfo=gai("10.0.0.7"))
        self.assertEqual(c.exception.code, 403)

    def test_any_private_address_among_many_denies(self):
        with self.assertRaises(proxy.Denied):
            proxy.resolve_checked("api.github.com", 443, getaddrinfo=gai("140.82.112.5", "127.0.0.1"))

    def test_public_resolution_returns_ips_for_pinning(self):
        self.assertEqual(proxy.resolve_checked("a.com", 443, getaddrinfo=gai("1.1.1.1", "1.1.1.1", "8.8.8.8")),
                         ["1.1.1.1", "8.8.8.8"])

    def test_connect_goes_to_checked_ip_not_hostname(self):
        cc = mock.Mock(return_value="sock")
        self.assertEqual(proxy.connect_checked(["1.1.1.1"], 443, create_connection=cc), "sock")
        self.assertEqual(cc.call_args[0][0], ("1.1.1.1", 443))

    def test_dns_failure_is_502(self):
        def boom(*a, **k):
            raise socket.gaierror("nx")
        with self.assertRaises(proxy.Denied) as c:
            proxy.resolve_checked("nx.example", 443, getaddrinfo=boom)
        self.assertEqual(c.exception.code, 502)


class ServerTest(unittest.TestCase):
    """Real socket against the handler, denial paths only (no outbound traffic needed)."""

    @classmethod
    def setUpClass(cls):
        cls._env = mock.patch.dict(os.environ, {"SANDBOX_EGRESS_SECRET": SECRET.decode()})
        cls._env.start()
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), proxy.Handler)
        cls.srv.daemon_threads = True
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()
        cls._env.stop()

    def raw(self, request):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        s.sendall(request.encode())
        data = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            data += chunk
        s.close()
        return data.decode(errors="replace")

    def test_connect_without_credentials_is_407(self):
        self.assertIn(" 407 ", self.raw("CONNECT api.github.com:443 HTTP/1.1\r\nHost: x\r\n\r\n").split("\r\n")[0])

    def test_connect_disallowed_host_is_403(self):
        r = self.raw("CONNECT evil.com:443 HTTP/1.1\r\nProxy-Authorization: %s\r\n\r\n" % basic(tok()))
        self.assertIn(" 403 ", r.split("\r\n")[0])

    def test_connect_bad_port_is_403(self):
        r = self.raw("CONNECT api.github.com:22 HTTP/1.1\r\nProxy-Authorization: %s\r\n\r\n" % basic(tok()))
        self.assertIn(" 403 ", r.split("\r\n")[0])

    def test_plain_http_to_private_ip_literal_is_denied_by_allowlist(self):
        r = self.raw("GET http://172.17.0.1/ HTTP/1.1\r\nHost: 172.17.0.1\r\nProxy-Authorization: %s\r\n\r\n"
                     % basic(tok()))
        self.assertIn(" 403 ", r.split("\r\n")[0])

    def test_allowed_name_resolving_private_is_denied(self):
        with mock.patch.object(proxy, "_getaddrinfo", gai("10.1.1.1")):
            r = self.raw("CONNECT api.github.com:443 HTTP/1.1\r\nProxy-Authorization: %s\r\n\r\n" % basic(tok()))
        self.assertIn(" 403 ", r.split("\r\n")[0])
        self.assertIn("non-public", r)

    def test_allowed_connect_tunnels_bytes(self):
        echo = socket.socket()
        echo.bind(("127.0.0.1", 0))
        echo.listen(1)
        threading.Thread(target=lambda: (lambda c: (c.sendall(c.recv(100)), c.close()))(echo.accept()[0]),
                         daemon=True).start()
        port = echo.getsockname()[1]
        with mock.patch.object(proxy, "ALLOWED_PORTS", (port,)), \
                mock.patch.object(proxy, "ip_is_allowed", lambda ip: True), \
                mock.patch.object(proxy, "resolve_checked", lambda h, p: ["127.0.0.1"]):
            s = socket.create_connection(("127.0.0.1", self.port), timeout=5)
            s.sendall(("CONNECT api.github.com:%d HTTP/1.1\r\nProxy-Authorization: %s\r\n\r\n"
                       % (port, basic(tok()))).encode())
            buf = b""
            while b"\r\n\r\n" not in buf:
                buf += s.recv(4096)
            self.assertIn(b" 200 ", buf.split(b"\r\n")[0])
            s.sendall(b"ping")
            self.assertEqual(s.recv(100), b"ping")
            s.close()
        echo.close()


if __name__ == "__main__":
    unittest.main()
