#!/usr/bin/env python3
"""Only loopback requests. Run after lab.py start."""
import http.client
import socket
import ssl
import unittest


class LabTest(unittest.TestCase):
    def get(self, path):
        connection = http.client.HTTPConnection("127.0.0.1", 80, timeout=5)
        try:
            connection.request("GET", path)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_html_synthetic_evidence(self):
        status, headers, body = self.get("/")
        self.assertEqual(status, 200)
        self.assertEqual(headers["X-NetTool-Fixture"], "synthetic-only")
        self.assertIn(b"NETTOOL_FAKE_KEY_NOT_A_CREDENTIAL", body)
        self.assertIn(b"192.0.2.10", body)
        self.assertIn(b"http://127.0.0.1:80/landing", body)
        self.assertIn(b"https://127.0.0.1:8443/health", body)

    def test_redirect_chain(self):
        for path, destination in [("/redirect", "/redirect-2"), ("/redirect-2", "/landing")]:
            status, headers, _ = self.get(path)
            self.assertEqual((status, headers["Location"]), (302, destination))
        self.assertEqual(self.get("/landing")[0], 200)

    def test_loops_challenges_cookie_and_limit(self):
        self.assertEqual(self.get("/loop-a")[1]["Location"], "/loop-b")
        self.assertEqual(self.get("/loop-b")[1]["Location"], "/loop-a")
        self.assertEqual(self.get("/auth")[0], 401)
        self.assertEqual(self.get("/forbidden")[0], 403)
        self.assertIn("FAKE_SESSION", self.get("/cookie")[1]["Set-Cookie"])
        self.assertGreater(len(self.get("/large")[2]), 256 * 1024)

    def test_portal_and_online(self):
        self.assertEqual(self.get("/generate_204")[1]["Location"], "/portal-login")
        self.assertEqual(self.get("/portal-login")[0], 200)
        self.assertEqual(self.get("/online/generate_204")[0], 204)

    def test_banner(self):
        with socket.create_connection(("127.0.0.1", 2222), timeout=2) as conn:
            self.assertEqual(conn.recv(128), b"SSH-2.0-NetToolSynthetic_1.0\r\n")

    def test_tls_rejected_by_default(self):
        connection = http.client.HTTPSConnection("127.0.0.1", 8443, timeout=3)
        try:
            with self.assertRaises(ssl.SSLCertVerificationError):
                connection.request("GET", "/health")
        finally:
            connection.close()

    def test_tls_fixture_with_local_pinned_certificate(self):
        # Test harness trusts only the lab certificate; production app must still reject it.
        from lab import STATE
        context = ssl.create_default_context(cafile=str(STATE / "certificate.pem"))
        with socket.create_connection(("127.0.0.1", 8443), timeout=3) as raw:
            with context.wrap_socket(raw, server_hostname="nettool-lab.invalid") as conn:
                conn.sendall(b"GET /health HTTP/1.1\r\nHost: nettool-lab.invalid\r\nConnection: close\r\n\r\n")
                self.assertIn(b"200 OK", conn.recv(2048))


if __name__ == "__main__":
    unittest.main(verbosity=2)
