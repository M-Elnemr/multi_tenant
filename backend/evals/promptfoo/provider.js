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
      // a turn is the customer's text, or {say, replyTo: {role, nth} | {text}}: the customer answers a specific earlier message of this chat (the nth message of that role, 1-based,
      // negative from the end; or the first message containing the text). The id comes from the chat's own history, never invented.
      for (const t of turns) {
        const turn = typeof t === 'string' ? { say: t } : t;
        let replyToMessageId;
        if (turn.replyTo) {
          const hist = await call(base, 'GET', '/api/bff/store/ai/test');
          const pool = turn.replyTo.text ? hist.filter((m) => (m.content || '').includes(turn.replyTo.text)) : hist.filter((m) => m.role === (turn.replyTo.role || 'assistant'));
          const n = turn.replyTo.nth || 1;
          const m = n > 0 ? pool[n - 1] : pool[pool.length + n];
          replyToMessageId = m && m.id;
          if (!replyToMessageId) { replies.push('[eval error: the message to reply to was not found]'); continue; }
        }
        if (turn.replyToId) replyToMessageId = turn.replyToId;   // an id we do not hold (another chat, another shop, nothing): the backend must refuse it
        try { replies.push((await call(base, 'POST', '/api/bff/store/ai/test', { message: turn.say, replyToMessageId })).reply || ''); }
        catch (e) { if (turn.expectRejected && /INVALID_REPLY_REFERENCE|HTTP 400/.test(String(e.message))) replies.push('[rejected: INVALID_REPLY_REFERENCE]'); else throw e; }
      }
      return { output: JSON.stringify({ replies }), latencyMs: Date.now() - t0 };
    } catch (e) { return { error: String(e.message || e) }; }
  }
};
