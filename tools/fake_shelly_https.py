#!/usr/bin/env python3
"""Faux appareil Shelly Gen2 en HTTPS — USAGE LOCAL UNIQUEMENT (tests du fork, lots C1 et C2).

Ne jamais l'exposer hors de la machine de développement : certificat auto-signé, mot de passe
factice, aucune protection.

Ce que fait le script :
  - port HTTP  : toute requête reçoit un 301 vers https://<hôte>:<port-https>/rpc
  - port HTTPS : JSON-RPC sur POST /rpc avec un certificat auto-signé (openssl), conservé entre
    les lancements dans --state-dir (défaut ~/.cache/hestia-fake-shelly/, hors dépôt) : relancer
    le script garde le même certificat, donc l'épingle de l'appli reste valable. Le chemin du
    certificat, son origine (réutilisé / généré) et son empreinte SHA-256 (même format que
    l'appli) sont affichés au démarrage ; --rotate-cert en régénère un nouveau pour tester
    « certificat changé ».
  - méthodes gérées : Shelly.GetDeviceInfo, Shelly.GetComponents, Switch.GetStatus, Switch.Set.

Options du lot C2 (mot de passe) :
  --password PW        protège /rpc par une authentification digest SHA-256 réelle (RFC 7616),
                       comme un Shelly : utilisateur « admin », realm « shellyplugmg3-fake », qop=auth,
                       nonce aléatoire valable 1 h, nc strictement croissant par nonce. Sans en-tête
                       ou en cas d'échec : 401 + nouveau défi WWW-Authenticate. Le mot de passe n'est
                       jamais affiché par le script.
  --throttle-after N   après N authentifications refusées (en-tête présent mais faux), répond 429
                       à toute requête pendant 60 s (test « appareil saturé »).
  --http-only          sert le RPC directement sur le port HTTP, sans redirection ni TLS : un appareil
                       protégé en HTTP simple.

ATTENTION aux ports : l'appli contacte toujours les vrais ports 80 (HTTP) et 443 (HTTPS), jamais
8080/8443. Les valeurs par défaut 8080/8443 évitent seulement d'exiger root ; pour tester avec
l'appli, soit lancer avec sudo et --http-port 80 --https-port 443, soit rediriger les ports
(émulateur : `adb reverse tcp:80 tcp:8080` et `adb reverse tcp:443 tcp:8443`, puis ajouter
l'appareil avec l'adresse 127.0.0.1).

Exemples :
  sudo python3 tools/fake_shelly_https.py --http-port 80 --https-port 443
  python3 tools/fake_shelly_https.py --http-only --http-port 8080 --password secret
  python3 tools/fake_shelly_https.py --password secret --throttle-after 3
"""
import argparse
import hashlib
import json
import os
import re
import secrets
import ssl
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"output": False}
DEVICE_ID = "shellyfake-AABBCCDDEEFF"
MAC = "AABBCCDDEEFF"

REALM = "shellyplugmg3-fake"
NONCE_TTL = 3600  # un nonce reste valable 1 h
THROTTLE_SECONDS = 60


def sha256_hex(text):
    return hashlib.sha256(text.encode()).hexdigest()


class Auth:
    """Authentification digest SHA-256 (RFC 7616) ; inactive si `password` est None."""

    def __init__(self, password, throttle_after):
        self.password = password
        self.throttle_after = throttle_after
        self.lock = threading.Lock()
        self.nonces = {}  # nonce -> [expiration, dernier nc accepté]
        self.failures = 0
        self.throttled_until = 0.0

    def challenge(self):
        """Crée un nonce frais et renvoie la valeur de l'en-tête WWW-Authenticate."""
        nonce = secrets.token_hex(16)
        with self.lock:
            now = time.time()
            self.nonces = {n: v for n, v in self.nonces.items() if v[0] > now}
            self.nonces[nonce] = [now + NONCE_TTL, 0]
        return 'Digest qop="auth", realm="%s", nonce="%s", algorithm=SHA-256' % (REALM, nonce)

    def check(self, header):
        """Renvoie (code HTTP, valeur WWW-Authenticate ou None) ; 200 si accès autorisé."""
        if self.password is None:
            return 200, None
        with self.lock:
            if time.time() < self.throttled_until:
                return 429, None
        if not header or not header.startswith("Digest "):
            return 401, self.challenge()
        f = {k.lower(): (q if q else v) for k, q, v in re.findall(r'(\w+)=(?:"([^"]*)"|([^,\s]*))', header[7:])}
        ok = False
        with self.lock:
            entry = self.nonces.get(f.get("nonce", ""))
            try:
                nc = int(f.get("nc", ""), 16)
            except ValueError:
                nc = -1
            if (entry and entry[0] > time.time() and f.get("username") == "admin"
                    and f.get("uri") == "/rpc" and f.get("qop") == "auth"
                    and f.get("algorithm", "SHA-256").upper() == "SHA-256" and nc > entry[1]):
                ha1 = sha256_hex("admin:%s:%s" % (REALM, self.password))
                ha2 = sha256_hex("POST:/rpc")
                expected = sha256_hex("%s:%s:%s:%s:auth:%s" % (ha1, f["nonce"], f["nc"], f.get("cnonce", ""), ha2))
                if secrets.compare_digest(expected, f.get("response", "")):
                    entry[1] = nc
                    ok = True
            if not ok:
                self.failures += 1
                if self.throttle_after and self.failures >= self.throttle_after:
                    self.throttled_until = time.time() + THROTTLE_SECONDS
                    self.failures = 0
        return (200, None) if ok else (401, self.challenge())


def generate_cert(directory):
    """Génère un certificat auto-signé (30 jours) dans `directory` ; renvoie (cert, clé)."""
    cert = os.path.join(directory, "cert.pem")
    key = os.path.join(directory, "key.pem")
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30",
         "-subj", "/CN=shelly-fake", "-keyout", key, "-out", cert],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return cert, key


def fingerprint(cert_path):
    """Empreinte SHA-256 du certificat entier, hexa majuscule séparé par « : »."""
    with open(cert_path) as f:
        der = ssl.PEM_cert_to_DER_cert(f.read())
    digest = hashlib.sha256(der).hexdigest().upper()
    return ":".join(digest[i:i + 2] for i in range(0, len(digest), 2))


def switch_status():
    return {"id": 0, "source": "init", "output": STATE["output"], "apower": 0.0}


def handle_rpc(method, params):
    """Renvoie (result, None) ou (None, erreur)."""
    if method == "Shelly.GetDeviceInfo":
        return {
            "id": DEVICE_ID, "mac": MAC, "model": "FAKE-HTTPS", "gen": 2,
            "fw_id": "20260101-000000/fake", "ver": "1.0.0-fake", "app": "FakeHttps",
            "auth_en": False, "name": "Fake HTTPS",
        }, None
    if method == "Shelly.GetComponents":
        offset = int((params or {}).get("offset", 0))
        comps = [{"key": "switch:0", "status": switch_status(), "config": {"id": 0, "name": None}}]
        return {"components": comps[offset:], "offset": offset, "total": len(comps)}, None
    if method == "Switch.GetStatus":
        return switch_status(), None
    if method == "Switch.Set":
        was_on = STATE["output"]
        STATE["output"] = bool((params or {}).get("on", False))
        return {"was_on": was_on}, None
    return None, {"code": -103, "message": "Unknown method " + str(method)}


def auth_flag(handler):
    """« auth=yes » si la requête portait un en-tête Authorization, sinon « auth=no » (jamais sa valeur)."""
    headers = getattr(handler, "headers", None)
    return "auth=yes" if headers is not None and headers.get("Authorization") else "auth=no"


def make_rpc_handler(auth, tag):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, code, payload, www_authenticate=None):
            body = json.dumps(payload).encode()
            self.send_response(code)
            if www_authenticate:
                self.send_header("WWW-Authenticate", www_authenticate)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path.split("?")[0] != "/rpc":
                return self._send(404, {"error": "not found"})
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length)
            code, www = auth.check(self.headers.get("Authorization"))
            if code == 429:
                return self._send(429, {"error": "too many attempts"})
            if code == 401:
                return self._send(401, {"error": {"code": 401, "message": "Unauthorized"}}, www)
            try:
                req = json.loads(body or b"{}")
            except ValueError:
                return self._send(400, {"error": "bad json"})
            result, error = handle_rpc(req.get("method"), req.get("params"))
            resp = {"id": req.get("id")}
            resp.update({"error": error} if error else {"result": result})
            self._send(200, resp)

        def log_message(self, fmt, *args):
            print(tag, self.address_string(), fmt % args, auth_flag(self))

    return Handler


def make_http_handler(https_port):
    class Handler(BaseHTTPRequestHandler):
        def _redirect(self):
            host = (self.headers.get("Host") or "localhost").rsplit(":", 1)[0]
            port = "" if https_port == 443 else ":%d" % https_port
            self.send_response(301)
            self.send_header("Location", "https://%s%s/rpc" % (host, port))
            self.send_header("Content-Length", "0")
            self.end_headers()

        do_GET = do_POST = do_PUT = do_HEAD = _redirect

        def log_message(self, fmt, *args):
            print("[http ]", self.address_string(), fmt % args, auth_flag(self))

    return Handler


def main():
    ap = argparse.ArgumentParser(description="Faux Shelly Gen2 HTTPS (usage local uniquement).")
    ap.add_argument("--http-port", type=int, default=8080, help="port HTTP, redirige en 301 (défaut 8080 ; l'appli exige 80)")
    ap.add_argument("--https-port", type=int, default=8443, help="port HTTPS (défaut 8443 ; l'appli exige 443)")
    ap.add_argument("--rotate-cert", action="store_true", help="régénère le certificat (test « certificat changé »)")
    ap.add_argument("--password", default=None, help="active l'authentification digest SHA-256 avec ce mot de passe (jamais affiché)")
    ap.add_argument("--throttle-after", type=int, default=0, help="après N authentifications refusées, répond 429 pendant 60 s (0 = jamais)")
    ap.add_argument("--http-only", action="store_true", help="sert le RPC directement sur le port HTTP, sans redirection ni TLS")
    ap.add_argument("--state-dir", default=os.path.expanduser("~/.cache/hestia-fake-shelly"), help="dossier où le certificat est conservé entre les lancements")
    args = ap.parse_args()

    auth = Auth(args.password, args.throttle_after)
    print("Mot de passe :", "activé (digest SHA-256, realm %s)" % REALM if args.password else "aucun")
    if args.throttle_after:
        print("429 pendant %d s après %d refus" % (THROTTLE_SECONDS, args.throttle_after))

    if args.http_only:
        http = ThreadingHTTPServer(("0.0.0.0", args.http_port), make_rpc_handler(auth, "[http ]"))
        print("HTTP  :%d /rpc (sans redirection, sans TLS)" % args.http_port, flush=True)
        try:
            http.serve_forever()
        except KeyboardInterrupt:
            pass
        return

    # Certificat conservé entre les lancements ; régénéré seulement si absent ou --rotate-cert.
    os.makedirs(args.state_dir, exist_ok=True)
    cert = os.path.join(args.state_dir, "cert.pem")
    key = os.path.join(args.state_dir, "key.pem")
    if args.rotate_cert or not (os.path.exists(cert) and os.path.exists(key)):
        cert, key = generate_cert(args.state_dir)
        print("Certificat généré :", cert)
    else:
        print("Certificat réutilisé :", cert)
    print("Empreinte SHA-256 :", fingerprint(cert), flush=True)

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    https = ThreadingHTTPServer(("0.0.0.0", args.https_port), make_rpc_handler(auth, "[https]"))
    https.socket = ctx.wrap_socket(https.socket, server_side=True)
    http = ThreadingHTTPServer(("0.0.0.0", args.http_port), make_http_handler(args.https_port))
    print("HTTP  :%d -> 301 vers https" % args.http_port)
    print("HTTPS :%d /rpc" % args.https_port)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    try:
        https.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
