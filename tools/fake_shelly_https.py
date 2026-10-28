#!/usr/bin/env python3
"""Faux appareil Shelly Gen2 en HTTPS — USAGE LOCAL UNIQUEMENT (tests du fork, lot C1).

Ne jamais l'exposer hors de la machine de développement : certificat auto-signé, aucune
authentification, aucune protection.

Ce que fait le script :
  - port HTTP  : toute requête reçoit un 301 vers https://<hôte>:<port-https>/rpc
  - port HTTPS : JSON-RPC sur POST /rpc avec un certificat auto-signé (openssl), conservé entre
    les lancements dans --state-dir (défaut ~/.cache/hestia-fake-shelly/, hors dépôt) : relancer
    le script garde le même certificat, donc l'épingle de l'appli reste valable. Le chemin du
    certificat, son origine (réutilisé / généré) et son empreinte SHA-256 (même format que
    l'appli) sont affichés au démarrage ; --rotate-cert en régénère un nouveau pour tester
    « certificat changé ».
  - méthodes gérées : Shelly.GetDeviceInfo, Shelly.GetComponents, Switch.GetStatus, Switch.Set.

ATTENTION aux ports : l'appli contacte toujours les vrais ports 80 (HTTP) et 443 (HTTPS), jamais
8080/8443. Les valeurs par défaut 8080/8443 évitent seulement d'exiger root ; pour tester avec
l'appli, soit lancer avec sudo et --http-port 80 --https-port 443, soit rediriger les ports
(émulateur : `adb reverse tcp:80 tcp:8080` et `adb reverse tcp:443 tcp:8443`, puis ajouter
l'appareil avec l'adresse 127.0.0.1).

Exemple :
  sudo python3 tools/fake_shelly_https.py --http-port 80 --https-port 443
"""
import argparse
import hashlib
import json
import os
import ssl
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE = {"output": False}
DEVICE_ID = "shellyfake-AABBCCDDEEFF"
MAC = "AABBCCDDEEFF"


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


def make_https_handler():
    class Handler(BaseHTTPRequestHandler):
        def _send(self, code, payload):
            body = json.dumps(payload).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path.split("?")[0] != "/rpc":
                return self._send(404, {"error": "not found"})
            length = int(self.headers.get("Content-Length") or 0)
            try:
                req = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                return self._send(400, {"error": "bad json"})
            result, error = handle_rpc(req.get("method"), req.get("params"))
            resp = {"id": req.get("id")}
            resp.update({"error": error} if error else {"result": result})
            self._send(200, resp)

        def log_message(self, fmt, *args):
            print("[https]", self.address_string(), fmt % args)

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
            print("[http ]", self.address_string(), fmt % args)

    return Handler


def main():
    ap = argparse.ArgumentParser(description="Faux Shelly Gen2 HTTPS (usage local uniquement).")
    ap.add_argument("--http-port", type=int, default=8080, help="port HTTP, redirige en 301 (défaut 8080 ; l'appli exige 80)")
    ap.add_argument("--https-port", type=int, default=8443, help="port HTTPS (défaut 8443 ; l'appli exige 443)")
    ap.add_argument("--rotate-cert", action="store_true", help="régénère le certificat (test « certificat changé »)")
    ap.add_argument("--state-dir", default=os.path.expanduser("~/.cache/hestia-fake-shelly"), help="dossier où le certificat est conservé entre les lancements")
    args = ap.parse_args()

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
    https = ThreadingHTTPServer(("0.0.0.0", args.https_port), make_https_handler())
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
