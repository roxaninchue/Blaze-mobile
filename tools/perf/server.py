#!/usr/bin/env python3
"""perf-browser bench: a fixed, offline test site for the in-app browser (served on the CI host, reached from the
emulator at http://10.0.2.2:<port>). Deterministic content; every sub-resource is delayed a little so a page load has a
realistic stream of progress ticks. Pages log their own timings to the console (WebView prints them to logcat)."""
import sys, time, random
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
IMG_DELAY = 0.03   # 30 ms per image: ~2-3 s of loading for 90 images over a few connections
N_IMG = 90
N_PARA = 260

WORDS = ("blaze browser frame scroll page tab render layer paint commit vsync raster tile compose webview thread "
         "network image font style script layout shadow corner toolbar progress history cookie").split()


def page(name: str, seed: int) -> str:
    rnd = random.Random(seed)
    paras = []
    for i in range(N_PARA):
        words = " ".join(rnd.choice(WORDS) for _ in range(rnd.randint(30, 70)))
        if i % 3 == 0:
            paras.append(f'<div class="card"><img loading="eager" src="/img/{seed}-{i % N_IMG}.svg" width="96" height="72">'
                         f'<p><b>{i}.</b> {words}</p></div>')
        else:
            paras.append(f"<p>{words}</p>")
    imgs_extra = "".join(f'<img src="/img/{seed}-x{k}.svg" width="40" height="30">' for k in range(N_IMG - N_PARA // 3 if N_IMG > N_PARA // 3 else 0))
    return f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Perf {name}</title>
<link rel="stylesheet" href="/style.css?s={seed}">
<script>window.__t0 = Date.now();</script>
</head><body>
<header class="top">Perf page {name} <span id="tick">0</span></header>
<main>{''.join(paras)}<div class="extra">{imgs_extra}</div></main>
<script src="/app.js?s={seed}"></script>
</body></html>"""


CSS = """body{margin:0;font:16px/1.5 sans-serif;color:#222;background:#fafafa}
.top{position:sticky;top:0;z-index:2;background:#fff;padding:12px 16px;box-shadow:0 2px 8px rgba(0,0,0,.2);font-weight:600}
main{padding:8px 14px}
.card{display:flex;gap:10px;align-items:flex-start;background:#fff;border-radius:14px;padding:10px;margin:10px 0;
 box-shadow:0 4px 14px rgba(0,0,0,.12)}
.card img{border-radius:10px;flex:none}
p{margin:8px 0}"""

# A small "live" element (like a ticker or carousel) that changes an inline style every frame, plus the timing report.
JS = """(function(){
  var el=document.getElementById('tick'), n=0;
  function f(){ n++; if(el){ el.textContent=n; el.style.opacity=(0.6+0.4*Math.abs(Math.sin(n/20))).toFixed(2);} requestAnimationFrame(f); }
  requestAnimationFrame(f);
  function report(){
    var nav=performance.getEntriesByType('navigation')[0]||{};
    var fcp=(performance.getEntriesByName('first-contentful-paint')[0]||{}).startTime||-1;
    var fp=(performance.getEntriesByName('first-paint')[0]||{}).startTime||-1;
    console.log('BLZPERF '+JSON.stringify({title:document.title,origin:Math.round(performance.timeOrigin),fp:Math.round(fp),
      fcp:Math.round(fcp),dcl:Math.round(nav.domContentLoadedEventEnd||-1),load:Math.round(nav.loadEventEnd||-1)}));
  }
  if(document.readyState==='complete') setTimeout(report,300); else addEventListener('load',function(){setTimeout(report,300);});
})();"""


def svg(key: str) -> bytes:
    h = (sum(map(ord, key)) * 37) % 360
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="96" height="72"><defs><linearGradient id="g">'
            f'<stop offset="0" stop-color="hsl({h},70%,60%)"/><stop offset="1" stop-color="hsl({(h + 60) % 360},70%,40%)"/>'
            f'</linearGradient></defs><rect width="96" height="72" rx="10" fill="url(#g)"/><circle cx="30" cy="36" r="14" fill="#fff8"/></svg>').encode()


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send(self, body: bytes, ctype: str):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path == "/page.html":
            name = q.get("name", ["A"])[0][:8]
            self.send(page(name, sum(map(ord, name))).encode(), "text/html; charset=utf-8")
        elif u.path == "/style.css":
            time.sleep(IMG_DELAY); self.send(CSS.encode(), "text/css")
        elif u.path == "/app.js":
            time.sleep(IMG_DELAY); self.send(JS.encode(), "application/javascript")
        elif u.path.startswith("/img/"):
            time.sleep(IMG_DELAY); self.send(svg(u.path), "image/svg+xml")
        else:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
