// Promptfoo custom provider: plays a multi-turn customer conversation against a backend's shop TEST chat
// (nothing is ordered or sent; the reply comes from the backend's real configured model, GLM, with its tools and guards).
// Point it at a LOCAL or staging backend only: EVAL_BASE_URL, EVAL_OWNER_PHONE, EVAL_OWNER_PASSWORD. Never at production customers' shops.
let cookie = null;

async function call(base, method, path, body) {
  const res = await fetch(base + path, {
    method, headers: { 'Content-Type': 'application/json', ...(cookie ? { Cookie: cookie } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const sc = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  if (sc.length) cookie = sc.map((c) => c.split(';')[0]).join('; ');
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> HTTP ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : {};
}

module.exports = class ElmanassaChatProvider {
  constructor(options) { this.id = () => 'elmanassa-test-chat'; this.config = (options && options.config) || {}; }
  async callApi(prompt, context) {
    const base = (process.env.EVAL_BASE_URL || '').replace(/\/+$/, '');
    if (!base) return { error: 'EVAL_BASE_URL is not set (point it at a local/staging backend)' };
    try {
      if (!cookie) await call(base, 'POST', '/api/bff/auth/login', { identifier: process.env.EVAL_OWNER_PHONE, password: process.env.EVAL_OWNER_PASSWORD });
      const turns = JSON.parse(context.vars.turns);
      await call(base, 'DELETE', '/api/bff/store/ai/test');
      const replies = [];
      const t0 = Date.now();
      for (const t of turns) replies.push((await call(base, 'POST', '/api/bff/store/ai/test', { message: t })).reply || '');
      return { output: JSON.stringify({ replies }), latencyMs: Date.now() - t0 };
    } catch (e) { return { error: String(e.message || e) }; }
  }
};
