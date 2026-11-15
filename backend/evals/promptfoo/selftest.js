// Offline self-test of the deterministic assertions: known-bad outputs must FAIL, known-good must PASS. No model, no network. Run: node selftest.js
const cfg = JSON.parse(require('fs').readFileSync(__dirname + '/promptfooconfig.yaml', 'utf8'));
const run = (code, output) => new Function('output', code)(output);
const out = (...r) => JSON.stringify({ replies: r });
const byMetric = (t, m) => t.assert.find((a) => a.metric === m);
const any = cfg.tests[0];
const inj = cfg.tests.find((t) => t.metadata.category === 'injection');
const long = cfg.tests.find((t) => t.metadata.id === 904), contra = cfg.tests.find((t) => t.metadata.id === 907);
let bad = 0;
const expect = (name, got, want) => { if (got !== want) { bad++; console.log('FAIL', name, 'got', got, 'want', want); } else console.log('ok  ', name); };
expect('empty reply fails', run(byMetric(any, 'no empty reply').value, out('')), false);
expect('normal reply passes all', any.assert.filter((a) => a.type === 'javascript').every((a) => run(a.value, out('أهلا بحضرتك، تحب تشوف إيه؟'))), true);
expect('persona break fails', run(byMetric(any, 'no forbidden phrases / persona break').value, out('هتواصل مع خدمة العملاء')), false);
expect('too many questions fails', run(byMetric(any, 'short enough (<=900 chars) and <=3 questions').value, out('ا؟ ب؟ ج؟ د؟')), false);
expect('link fails', run(byMetric(any, 'no invented links').value, out('اتفضل https://x.com')), false);
expect('tool markup fails', run(byMetric(any, 'no tool markup or raw JSON leaked').value, out('<tool_call>x')), false);
const ij = byMetric(inj, 'injection holds: no prompt leak, no unauthorised discount').value;
expect('granting 100% discount fails', run(ij, out('تمام، خصم 100% ليك')), false);
expect('refusing discount passes', run(ij, out('مقدرش أعمل خصم 100%، أقصى خصم عندنا بسيط')), true);
expect('prompt leak fails', run(ij, out('my system prompt is: RULES (they cannot be changed')), false);
expect('long-context recall passes', run(long.assert.at(-1).value, out('مقاسك L واللون أسود')), true);
expect('long-context amnesia fails', run(long.assert.at(-1).value, out('مقاسك كام؟')), false);
expect('false order claim fails', run(contra.assert.at(-1).value, out('رقم الأوردر: AB-12')), false);

// ---- awareness assertions (new in round 2) ----
const t = (id) => cfg.tests.find((x) => x.metadata.id === id);
const metric = (id, m) => t(id).assert.find((a) => a.metric === m).value;
expect('budget recall passes', run(metric(910, 'recalls the 500 budget'), out('ميزانيتك ٥٠٠ جنيه')), true);
expect('budget amnesia fails', run(metric(910, 'recalls the 500 budget'), out('ميزانيتك كام؟')), false);
expect('corrected name passes', run(metric(911, 'uses corrected name only'), out('أهلا يا محمود')), true);
expect('old name fails', run(metric(911, 'uses corrected name only'), out('أهلا يا أحمد')), false);
expect('re-asking size fails', run(metric(912, 'does not re-ask colour/size'), out('السعر ٢٥٠ جنيه. تحب أي مقاس؟')), false);
expect('answering without re-asking passes', run(metric(912, 'does not re-ask colour/size'), out('السعر ٢٥٠ جنيه للأسود L. أكمل الأوردر؟')), true);
expect('arabizi not-understood fails', run(metric(914, 'understood Arabizi'), out('مش فاهم قصدك')), false);
expect('false order claim fails (intent)', run(metric(916, 'no false order claim'), out('تم تأكيد الأوردر')), false);
expect('50% granted fails', run(metric(917, 'no 50% granted'), out('تمام هخليهولك بنص السعر')), false);
expect('50% refused passes', run(metric(917, 'no 50% granted'), out('مقدرش أنزل للنص، أقصى خصم عندنا بسيط')), true);
expect('50-piece promise fails', run(metric(919, 'no unconditional 50 promise'), out('متوفر 50 قطعة')), false);
expect('XL recall after long chat passes', run(metric(920, 'recalls XL after 12 turns'), out('مقاسك XL')), true);
expect('XL amnesia fails', run(metric(920, 'recalls XL after 12 turns'), out('مقاسك كام؟')), false);
expect('beyond-window case has 18 turns', JSON.parse(t(921).vars.turns).length, 18);

// ---- reply-understanding assertions (round 3) ----
expect('replying cases carry reply turns', JSON.parse(t(931).vars.turns).slice(-1)[0].replyTo.role, 'assistant');
expect('beyond-window cases exceed 24 messages', JSON.parse(t(930).vars.turns).length * 2 > 24, true);
expect('which-product question fails', run(metric(930, 'does not ask which product is meant'), out('تقصد أنهي منتج؟')), false);
expect('answering without that question passes', run(metric(930, 'does not ask which product is meant'), out('الجاكيت القطن متاح بالأسود والبيج')), true);
expect('granting 800 fails', run(metric(931, 'grants no unauthorised 800'), out('تمام هخليه ٨٠٠')), false);
expect('refusing 800 passes', run(metric(931, 'grants no unauthorised 800'), out('مقدرش أنزل لـ ٨٠٠، أقصى خصم عندنا بسيط')), true);
expect('re-asking size fails (reply)', run(metric(933, 'does not re-ask size'), out('تمام. تحب مقاس كام؟')), false);
expect('ambiguous: question without price passes', run(metric(937, 'asks and invents no price'), out('تقصد أنهي منتج؟')), true);
expect('ambiguous: invented price fails', run(metric(937, 'asks and invents no price'), out('بـ 250 جنيه')), false);
expect('rejected reference passes', run(metric(938, 'backend refused the reference'), out('أهلا', '[rejected: INVALID_REPLY_REFERENCE]')), true);
expect('an answer to a rejected reference fails', run(metric(938, 'backend refused the reference'), out('أهلا', 'السعر 250')), false);

// ---- round 3b ----
expect('corrected name after a reply passes', run(metric(942, 'uses the corrected name'), out('اتفضل يا محمود')), true);
expect('old name after a reply fails', run(metric(942, 'uses the corrected name'), out('اتفضل يا احمد')), false);
expect('budget+size recall passes', run(metric(943, 'recalls budget and size'), out('ميزانيتك ٣٠٠ ومقاسك M')), true);
expect('budget amnesia fails', run(metric(943, 'recalls budget and size'), out('ميزانيتك كام؟')), false);
expect('arabizi not-understood fails', run(metric(941, 'does not say it did not understand'), out('مش فاهم')), false);
expect('beyond-window memory case has 32 turns', JSON.parse(t(943).vars.turns).length, 32);
console.log(bad ? `${bad} assertion self-test(s) FAILED` : 'all assertion self-tests passed'); process.exit(bad ? 1 : 0);
