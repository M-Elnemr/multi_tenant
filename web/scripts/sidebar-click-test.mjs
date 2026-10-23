// Real mouse/touch clicks on every dashboard sidebar link, against a Chrome started with --remote-debugging-port (default 9333). usage: HOST=shop.localhost:3000 PHONE=... PASS=... W=1440 [MOBILE=1] node scripts/sidebar-click-test.mjs
const PORT = process.env.PORT || 9333;
const list = await (await fetch(`http://localhost:${PORT}/json`)).json();
const t = list.find(x => x.type === 'page');
const ws = new WebSocket(t.webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let n = 0; const pending = new Map();
ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } };
const send = (method, params = {}) => new Promise(res => { const i = ++n; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => (await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result?.result?.value;
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const W = +process.env.W || 1440, mobile = !!process.env.MOBILE;
await send('Page.enable'); await send('Emulation.setDeviceMetricsOverride', { width: W, height: mobile ? 780 : 900, deviceScaleFactor: 1, mobile });
const H = process.env.HOST;
await send('Page.navigate', { url: 'http://' + H + '/login' }); await sleep(3500);
await ev(`fetch('/api/bff/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ identifier: '${process.env.PHONE}', password: '${process.env.PASS}' }) }).then(r => r.status)`);
await send('Page.navigate', { url: 'http://' + H + '/dashboard' }); await sleep(4500);
const hrefs = await ev(`[...document.querySelectorAll('aside a[href^="/dashboard"]')].map(a => a.getAttribute('href'))`);
console.log('sidebar links:', hrefs.length);
let bad = 0;
for (const href of hrefs) {
  await send('Page.navigate', { url: 'http://' + H + '/dashboard' }); await sleep(2500);
  if (mobile) { await ev(`document.querySelector('header button[aria-label=Menu]').click()`); await sleep(500); }
  const pt = await ev(`(() => { const a = document.querySelector('aside a[href="${href}"]'); a.scrollIntoView({ block: 'center' }); const r = a.getBoundingClientRect(); const x = r.left + r.width / 2, y = r.top + r.height / 2; const top = document.elementFromPoint(x, y); return { x, y, hit: top === a || a.contains(top) }; })()`);
  if (!pt.hit) { console.log('BLOCKED', href); bad++; continue; }
  if (mobile) { await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: pt.x, y: pt.y }] }); await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); }
  else { await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: pt.x, y: pt.y, button: 'left', clickCount: 1 }); await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: pt.x, y: pt.y, button: 'left', clickCount: 1 }); }
  await sleep(2200);
  const path = await ev('location.pathname');
  if (path !== href && !(href === '/dashboard' && path === '/dashboard')) { console.log('NO NAVIGATION', href, '->', path); bad++; }
}
console.log(bad ? `FAILED: ${bad} of ${hrefs.length}` : `all ${hrefs.length} sidebar links navigate`);
process.exit(0);
