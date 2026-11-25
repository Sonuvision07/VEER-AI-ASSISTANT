#!/usr/bin/env python3
"""VEER Windows companion: restricted, authenticated local agent."""
from __future__ import annotations
import argparse, json, os, secrets, webbrowser
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

APP_DIR = Path(os.environ.get("VEER_HOME", str(Path.home() / "VEER"))).expanduser().resolve()
WORKSPACE = (APP_DIR / "workspace").resolve()
TOKEN_FILE = APP_DIR / "agent.token"
MAX_BODY = 64 * 1024

def load_token():
    APP_DIR.mkdir(parents=True, exist_ok=True); WORKSPACE.mkdir(parents=True, exist_ok=True)
    if TOKEN_FILE.exists():
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(token) >= 40: return token
    token = secrets.token_urlsafe(48)
    TOKEN_FILE.write_text(token, encoding="utf-8")
    try: os.chmod(TOKEN_FILE, 0o600)
    except OSError: pass
    return token
TOKEN = load_token()

def safe_path(raw):
    target = (WORKSPACE / raw).resolve()
    if target != WORKSPACE and WORKSPACE not in target.parents: raise ValueError("Path outside VEER workspace")
    return target

class Handler(BaseHTTPRequestHandler):
    server_version = "VEER-PC-Agent/0.1"
    def log_message(self, fmt, *args): print("[%s] %s" % (datetime.now().isoformat(timespec="seconds"), fmt % args))
    def send_json(self, status, data):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status); self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw))); self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(raw)
    def authorized(self): return secrets.compare_digest(self.headers.get("Authorization", ""), "Bearer " + TOKEN)
    def body(self):
        size = int(self.headers.get("Content-Length", "0"))
        if size < 0 or size > MAX_BODY: raise ValueError("Request body too large")
        value = json.loads(self.rfile.read(size).decode("utf-8") or "{}")
        if not isinstance(value, dict): raise ValueError("JSON object required")
        return value
    def do_GET(self):
        if not self.authorized(): return self.send_json(401, {"ok":False,"error":"Unauthorized"})
        route = urlparse(self.path)
        if route.path == "/health": return self.send_json(200, {"ok":True,"agent":"VEER-PC-Agent","version":"0.1","time":datetime.now(timezone.utc).isoformat()})
        if route.path == "/files":
            try:
                folder = safe_path(parse_qs(route.query).get("path", ["."])[0])
                if not folder.is_dir(): raise ValueError("Folder not found")
                items = [{"name":p.name,"type":"folder" if p.is_dir() else "file","size":p.stat().st_size if p.is_file() else None} for p in sorted(folder.iterdir(), key=lambda x:x.name.lower())[:200]]
                return self.send_json(200, {"ok":True,"path":str(folder.relative_to(WORKSPACE)),"items":items})
            except Exception as e: return self.send_json(400, {"ok":False,"error":str(e)})
        return self.send_json(404, {"ok":False,"error":"Not found"})
    def do_POST(self):
        if not self.authorized(): return self.send_json(401, {"ok":False,"error":"Unauthorized"})
        try:
            data = self.body(); route = urlparse(self.path).path
            if route == "/open-url":
                url = str(data.get("url","")).strip(); parsed = urlparse(url)
                if parsed.scheme not in ("https","http") or not parsed.netloc: raise ValueError("Only valid HTTP(S) URLs allowed")
                webbrowser.open(url, new=2); return self.send_json(200, {"ok":True,"message":"Browser open request sent","url":url})
            if route == "/write-text":
                rel = str(data.get("path","")).strip(); content = data.get("content","")
                if not rel or not isinstance(content,str): raise ValueError("path and text content required")
                if len(content.encode("utf-8")) > MAX_BODY: raise ValueError("Text too large")
                target = safe_path(rel); target.parent.mkdir(parents=True, exist_ok=True)
                if target.exists(): return self.send_json(409, {"ok":False,"error":"Refusing to overwrite existing file"})
                target.write_text(content, encoding="utf-8"); return self.send_json(201, {"ok":True,"created":str(target.relative_to(WORKSPACE))})
            return self.send_json(404, {"ok":False,"error":"Not found"})
        except Exception as e: return self.send_json(400, {"ok":False,"error":str(e)})

def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--port",type=int,default=8765); args=parser.parse_args()
    server=ThreadingHTTPServer(("127.0.0.1",args.port),Handler)
    print("VEER PC Agent on http://127.0.0.1:%d" % args.port); print("Workspace:",WORKSPACE); print("Private token:",TOKEN_FILE); print("Stop with Ctrl+C")
    try: server.serve_forever()
    except KeyboardInterrupt: print("Stopping VEER PC Agent")
    finally: server.server_close()
if __name__ == "__main__": main()
