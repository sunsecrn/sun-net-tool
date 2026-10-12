#!/usr/bin/env python3
"""Deterministic synthetic services for an explicitly authorized LAN lab."""
import argparse
import http.server
import json
import os
from pathlib import Path
import signal
import socketserver
import ssl
import subprocess
import sys
import threading
import time

STATE = Path(os.environ.get("NETTOOL_LAB_STATE", "/private/tmp/nettool-lab"))
HTML = b'''<!doctype html><html><head><title>Sun NetTool synthetic lab</title></head>
<body><h1>AUTHORIZED SYNTHETIC FIXTURE</h1>
<a href="http://portal.example.invalid/landing">Synthetic domain reference</a>
<a href="http://192.0.2.10:8080/">Documentation-only IP reference</a>
<script src="/fixture.js"></script><script>
const api_key = "NETTOOL_FAKE_KEY_NOT_A_CREDENTIAL_1234";
const secret = "SYNTHETIC_SECRET_NEVER_VALID_123456";
const endpoint = "http://api.example.invalid/v1";
</script><p>No credentials here are real. References are not reachable targets.</p>
</body></html>'''
JS = b'const access_token = "NETTOOL_SYNTHETIC_TOKEN_NOT_VALID_1234";\nconst host = "api.example.invalid";\n'


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "NetToolSyntheticLab/1.0"
    sys_version = ""

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        status, body, headers = 200, HTML, {"Content-Type": "text/html; charset=utf-8"}
        local_ip = self.connection.getsockname()[0]
        # Use the accepted socket's actual local address, never an untrusted Host header.
        real_references = ('<p>Local controlled references</p><a href="http://' + local_ip +
                           ':80/landing">Local HTTP</a><a href="https://' + local_ip +
                           ':8443/health">Local HTTPS</a><script>const localApi = "http://' +
                           local_ip + ':80/health";</script>').encode()
        body = body.replace(b'</body>', real_references + b'</body>')
        if path == "/generate_204":
            status, body = 302, b"Synthetic portal redirect"
            headers["Location"] = "/portal-login"
        elif path == "/portal-login":
            body = b"<html><title>Synthetic portal</title><p>LAB ONLY. No login or credentials accepted.</p></html>"
        elif path == "/online/generate_204":
            status, body = 204, b""
        elif path == "/headers":
            body = b"Header profile test"
            headers["X-Request-User-Agent"] = self.headers.get("User-Agent", "")
            headers["X-Request-Accept"] = self.headers.get("Accept", "")
        elif path == "/health":
            body, headers = b'{"fixture":"nettool-lab","ok":true}', {"Content-Type": "application/json"}
        elif path == "/fixture.js":
            body, headers = JS + ('const localApi = "http://' + local_ip + ':80/health";\n').encode(), {"Content-Type": "application/javascript"}
        elif path in ("/redirect", "/redirect-2"):
            status, body = 302, b"Synthetic redirect"
            headers["Location"] = "/redirect-2" if path == "/redirect" else "/landing"
        elif path == "/loop-a" or path == "/loop-b":
            status, body = 302, b"Synthetic redirect loop"
            headers["Location"] = "/loop-b" if path == "/loop-a" else "/loop-a"
        elif path == "/auth":
            status, body = 401, b"Synthetic authentication challenge. Do not supply credentials."
            headers["WWW-Authenticate"] = 'Basic realm="NetTool Synthetic Lab"'
        elif path == "/forbidden":
            status, body = 403, b"Synthetic forbidden response"
        elif path == "/cookie":
            headers["Set-Cookie"] = "session=FAKE_SESSION_NOT_VALID_123456; HttpOnly; SameSite=Strict"
        elif path == "/large":
            body, headers = b"NETTOOL_SYNTHETIC_DATA\n" * 20000, {"Content-Type": "text/plain"}
        elif path == "/slow":
            time.sleep(3)
            body = b"Synthetic three-second response"
        elif path not in ("/", "/landing"):
            status, body = 404, b"Unknown synthetic fixture"
        self.send_response(status)
        self.send_header("X-NetTool-Fixture", "synthetic-only")
        self.send_header("Cache-Control", "no-store")
        for key, value in headers.items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, fmt, *args):
        # Paths/headers can contain user input; retain only method/status class metadata.
        print(json.dumps({"event": "http", "method": self.command}), flush=True)


class Banner(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(2)
        self.request.sendall(b"SSH-2.0-NetToolSynthetic_1.0\r\n")
        # Banner only. No authentication or shell, intentionally not an SSH server.


class BannerServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def certificate():
    STATE.mkdir(parents=True, exist_ok=True)
    os.chmod(STATE, 0o700)
    cert, key = STATE / "certificate.pem", STATE / "key.pem"
    if not cert.exists() or not key.exists():
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "7",
                        "-subj", "/CN=nettool-lab.invalid", "-keyout", str(key), "-out", str(cert)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.chmod(key, 0o600)
    return cert, key


def serve():
    cert, key = certificate()
    servers = []
    started = []
    try:
        for port in (80, 8443):
            server = http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler)
            servers.append(server)
            if port == 8443:
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.load_cert_chain(cert, key)
                server.socket = context.wrap_socket(server.socket, server_side=True)
        servers.append(BannerServer(("0.0.0.0", 2222), Banner))
        for server in servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            started.append(server)
        (STATE / "ready.json").write_text(json.dumps({"pid": os.getpid(), "ports": [80, 8443, 2222]}))
        stopped = threading.Event()
        signal.signal(signal.SIGTERM, lambda *_: stopped.set())
        signal.signal(signal.SIGINT, lambda *_: stopped.set())
        stopped.wait()
    finally:
        for server in servers:
            if server in started:
                server.shutdown()
            server.server_close()
        (STATE / "ready.json").unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["start", "stop", "status", "serve"])
    action = parser.parse_args().action
    if action == "serve":
        serve()
        return
    marker = STATE / "ready.json"
    if action == "status":
        print(marker.read_text() if marker.exists() else "stopped")
    elif action == "stop":
        if marker.exists():
            pid = json.loads(marker.read_text())["pid"]
            # Verify process identity before signaling a persisted PID.
            command = subprocess.check_output(["ps", "-p", str(pid), "-o", "command="], text=True).strip()
            if str(Path(__file__).resolve()) not in command or "serve" not in command:
                raise SystemExit("PID does not belong to this lab; refusing to signal")
            os.kill(pid, signal.SIGTERM)
            for _ in range(50):
                if not marker.exists():
                    break
                time.sleep(.1)
            if marker.exists():
                raise SystemExit("Shutdown pending")
        print("stopped")
    else:
        if marker.exists():
            raise SystemExit("Lab already has a ready marker; check status/stop before restarting")
        STATE.mkdir(parents=True, exist_ok=True)
        with (STATE / "server.log").open("ab") as output:
            child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "serve"],
                                     stdout=output, stderr=output, start_new_session=True)
        for _ in range(600):
            if marker.exists():
                print(marker.read_text())
                return
            if child.poll() is not None:
                raise SystemExit("Lab failed to start; inspect " + str(STATE / "server.log"))
            time.sleep(.1)
        raise SystemExit("Lab startup timed out; inspect logs")


if __name__ == "__main__":
    main()
