#!/usr/bin/env python3
"""A small read-only load test for a shop's public API (no k6 needed): N workers hit the storefront endpoints for S seconds and the script prints requests per second, errors and p50/p95/p99 latency.
usage: loadtest.py http://localhost:8080 shop-host.example.com [workers=30] [seconds=20]
Run it against a copy of the server, not the live shop: it sends real traffic."""
import sys, time, threading, urllib.request, statistics, random

base, host = sys.argv[1].rstrip("/"), sys.argv[2]
workers = int(sys.argv[3]) if len(sys.argv) > 3 else 30
seconds = int(sys.argv[4]) if len(sys.argv) > 4 else 20
paths = ["/api/v1/shop/profile", "/api/v1/shop/home", "/api/v1/shop/products?pageSize=12", "/api/v1/shop/categories", "/api/v1/shop/feed"]
lat, errors, lock = [], [0], threading.Lock()
stop = time.time() + seconds

def worker():
    while time.time() < stop:
        p = random.choice(paths)
        t = time.time()
        try:
            req = urllib.request.Request(base + p, headers={"Host": host, "X-Forwarded-Host": host})
            with urllib.request.urlopen(req, timeout=20) as r:
                r.read()
                ok = r.status == 200
        except Exception:
            ok = False
        d = (time.time() - t) * 1000
        with lock:
            lat.append(d)
            if not ok: errors[0] += 1

ts = [threading.Thread(target=worker) for _ in range(workers)]
[t.start() for t in ts]; [t.join() for t in ts]
lat.sort()
q = lambda f: lat[min(len(lat) - 1, int(len(lat) * f))]
print(f"workers={workers} seconds={seconds} requests={len(lat)} rps={len(lat) / seconds:.1f} errors={errors[0]} p50={q(0.5):.0f}ms p95={q(0.95):.0f}ms p99={q(0.99):.0f}ms max={lat[-1]:.0f}ms")
