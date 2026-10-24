import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'connect.dart';

// "Ask your business": the three things to do next, stock and price ideas, a chat answered from the shop's own numbers, the daily WhatsApp report, and what the AI seller could not answer.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

const _kind = {'RESTOCK': ('المخزون', 'Stock', Icons.inventory_2_rounded, Color(0xFFB45309)), 'PROMOTE': ('ترويج', 'Promote', Icons.campaign_rounded, Color(0xFF047857)), 'FOLLOW_UP': ('متابعة', 'Follow up', Icons.forum_rounded, Color(0xFF1D4ED8))};

class CopilotScreen extends StatelessWidget {
  const CopilotScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return DefaultTabController(length: 3, child: Column(children: [
      TabBar(tabs: [Tab(text: ar ? 'اسأل' : 'Ask'), Tab(text: ar ? 'تقرير واتساب' : 'WhatsApp report'), Tab(text: ar ? 'المساعد' : 'Assistant')]),
      Expanded(child: TabBarView(children: [_AskTab(ctx: ctx), _WaReportTab(ctx: ctx), _AssistantTab(ctx: ctx)])),
    ]));
  }
}

class _AskTab extends StatefulWidget {
  const _AskTab({required this.ctx});
  final Ctx ctx;
  @override
  State<_AskTab> createState() => _AskTabState();
}

class _AskTabState extends State<_AskTab> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  final _text = TextEditingController();
  final _scroll = ScrollController();
  List<Map<String, dynamic>>? _msgs;
  bool _busy = false;
  int _gen = 0;

  @override
  void dispose() { _text.dispose(); _scroll.dispose(); super.dispose(); }

  Future<void> _ask(String q) async {
    final text = q.trim();
    if (text.isEmpty || _busy) return;
    final base = List<Map<String, dynamic>>.of(_msgs ?? const []);
    setState(() { _msgs = [...base, {'role': 'user', 'content': text}]; _busy = true; _text.clear(); });
    _toEnd();
    String? answer;
    final ok = await run(context, () async { answer = (await widget.ctx.api.post('store/copilot/ask', {'question': text}) as Map)['answer'] as String; });
    if (!mounted) return;
    setState(() { _busy = false; _msgs = ok ? [...base, {'role': 'user', 'content': text}, {'role': 'assistant', 'content': answer ?? ''}] : base; });
    _toEnd();
  }

  void _toEnd() => WidgetsBinding.instance.addPostFrameCallback((_) { if (_scroll.hasClients) _scroll.animateTo(_scroll.position.maxScrollExtent + 200, duration: const Duration(milliseconds: 250), curve: Curves.easeOut); });

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final s = S.of(context);
    final ar = s.ar;
    final chips = ar ? ['ليه مبيعاتي اتغيرت الأسبوع ده؟', 'أطلب إيه من المخزون؟', 'أنهي منتج أعمله خصم؟', 'إعلاناتي ماشية إزاي؟'] : ['Why did my sales change this week?', 'What should I re-order?', 'Which product should I discount?', 'How are my ads doing?'];
    return Async<Map<String, dynamic>>(
      key: ValueKey('cp$_gen'),
      load: () async {
        final o = await widget.ctx.api.get('store/copilot/overview', query: {'lang': ar ? 'ar' : 'en'}) as Map<String, dynamic>;
        _msgs ??= _maps(await widget.ctx.api.get('store/copilot/messages')).map((m) => {'role': m['role'], 'content': m['content']}).toList();
        return o;
      },
      builder: (context, d, reload) {
        final week = d['week'] as Map<String, dynamic>;
        final change = week['salesChangePercent'] as num?;
        final msgs = _msgs ?? const [];
        return Column(children: [
          Expanded(child: RefreshIndicator(onRefresh: () async { setState(() => _gen++); }, child: ListView(controller: _scroll, padding: const EdgeInsets.all(16), children: [
            for (final (i, a) in _maps(d['actions']).indexed) Padding(padding: const EdgeInsets.only(bottom: 10), child: _ActionCard(n: i + 1, a: a, ar: ar)),
            Row(children: [
              Expanded(child: _Mini(ar ? 'أوردرات الأسبوع' : 'Orders this week', '${week['orders']}')),
              const SizedBox(width: 8),
              Expanded(child: _Mini(ar ? 'المبيعات' : 'Sales', s.money(((week['sales'] as num) * 100).round(), '${week['currency']}'))),
              const SizedBox(width: 8),
              Expanded(child: _Mini(ar ? 'التغيير' : 'Change', change == null ? '-' : '${change > 0 ? '+' : ''}$change%', ltr: true)),
            ]),
            const SizedBox(height: 12),
            _Section(ar ? 'اطلب قريب' : 'Re-order soon', _maps(d['reorder']).isEmpty ? [Text(ar ? 'مفيش منتج هيخلص قبل ما تلحق تطلب.' : 'Nothing will run out before you can re-order.', style: const TextStyle(color: Color(0xFF6B7C8D)))] : [
              for (final r in _maps(d['reorder'])) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text('${r['name']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                subtitle: Text(ar ? 'المتبقي ${r['stock']} • يكفي ${r['coverDays']} يوم' : '${r['stock']} left • ${r['coverDays']} days of cover'),
                trailing: Chip(label: Text(ar ? 'اطلب ${r['suggestedOrderQuantity']}' : 'Order ${r['suggestedOrderQuantity']}'), backgroundColor: r['urgent'] == true ? const Color(0xFFFEE2E2) : const Color(0xFFFEF3C7))),
            ]),
            const SizedBox(height: 12),
            _Section(ar ? 'أفكار للأسعار' : 'Price ideas', _maps(d['prices']).isEmpty ? [Text(ar ? 'مفيش أفكار للأسعار دلوقتي.' : 'No price ideas right now.', style: const TextStyle(color: Color(0xFF6B7C8D)))] : [
              for (final p in _maps(d['prices'])) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text('${p['name']}', style: const TextStyle(fontWeight: FontWeight.w700)),
                subtitle: Text(p['idea'] == 'DISCOUNT'
                    ? (ar ? 'بيع بطيء: ${p['sold']} قطعة في ٢٨ يوم والمخزون ${p['stock']}' : 'Slow seller: ${p['sold']} sold in 28 days, ${p['stock']} in stock')
                    : (ar ? 'بيتباع بسرعة: ${p['sold']} قطعة والمخزون يكفي ${p['cover']} يوم' : 'Sells fast: ${p['sold']} sold, stock covers ${p['cover']} days')),
                trailing: Directionality(textDirection: TextDirection.ltr, child: Text('${p['from']} → ${p['to']}  ${p['idea'] == 'DISCOUNT' ? '-' : '+'}${p['percent']}%', style: const TextStyle(fontWeight: FontWeight.w800)))),
            ]),
            const SizedBox(height: 16),
            Text(ar ? 'اسأل نشاطك' : 'Ask your business', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
            const SizedBox(height: 8),
            if (d['available'] == false) Card(color: const Color(0xFFFEF3C7), child: Padding(padding: const EdgeInsets.all(12), child: Text(ar ? 'المساعد لسه مش متفعّل.' : 'The assistant is not set up yet.'))),
            if (msgs.isEmpty && d['available'] != false) Wrap(spacing: 8, runSpacing: 8, children: [for (final c in chips) ActionChip(label: Text(c), onPressed: _busy ? null : () => _ask(c))]),
            for (final m in msgs) Align(
              alignment: m['role'] == 'user' ? AlignmentDirectional.centerEnd : AlignmentDirectional.centerStart,
              child: Container(
                margin: const EdgeInsets.symmetric(vertical: 4), padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10), constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.82),
                decoration: BoxDecoration(color: m['role'] == 'user' ? Theme.of(context).colorScheme.primary : const Color(0xFFF1F5F9), borderRadius: BorderRadius.circular(16)),
                child: SelectableText('${m['content']}', style: TextStyle(color: m['role'] == 'user' ? Colors.white : const Color(0xFF1F2937))),
              ),
            ),
            if (_busy) Padding(padding: const EdgeInsets.all(8), child: Text(ar ? 'براجع أرقامك...' : 'Checking your numbers...', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))),
          ]))),
          if (d['available'] != false) SafeArea(top: false, child: Padding(padding: const EdgeInsets.fromLTRB(12, 4, 12, 8), child: Row(children: [
            Expanded(child: TextField(controller: _text, maxLength: 600, minLines: 1, maxLines: 3, textInputAction: TextInputAction.send, onSubmitted: _ask,
                decoration: InputDecoration(counterText: '', hintText: ar ? 'ليه مبيعاتي اتغيرت؟' : 'Why did my sales change?', isDense: true))),
            const SizedBox(width: 8),
            IconButton.filled(onPressed: _busy ? null : () => _ask(_text.text), icon: const Icon(Icons.send_rounded)),
          ]))),
        ]);
      },
    );
  }
}

class _ActionCard extends StatelessWidget {
  const _ActionCard({required this.n, required this.a, required this.ar});
  final int n;
  final Map<String, dynamic> a;
  final bool ar;
  @override
  Widget build(BuildContext context) {
    final k = _kind['${a['kind']}'] ?? _kind['FOLLOW_UP']!;
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Container(width: 36, height: 36, decoration: BoxDecoration(color: k.$4.withValues(alpha: 0.12), shape: BoxShape.circle), child: Icon(k.$3, color: k.$4, size: 20)),
      const SizedBox(width: 12),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('$n. ${a['title']}', style: const TextStyle(fontWeight: FontWeight.w900)),
        const SizedBox(height: 3),
        Text('${a['detail']}', style: const TextStyle(color: Color(0xFF475569), height: 1.4)),
      ])),
    ])));
  }
}

class _Mini extends StatelessWidget {
  const _Mini(this.label, this.value, {this.ltr = false});
  final String label, value;
  final bool ltr;
  @override
  Widget build(BuildContext context) => Card(child: Padding(padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12), child: Column(children: [
        Text(label, textAlign: TextAlign.center, style: const TextStyle(fontSize: 11.5, color: Color(0xFF6B7C8D))),
        const SizedBox(height: 4),
        Directionality(textDirection: ltr ? TextDirection.ltr : Directionality.of(context), child: FittedBox(child: Text(value, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)))),
      ])));
}

class _Section extends StatelessWidget {
  const _Section(this.title, this.children);
  final String title;
  final List<Widget> children;
  @override
  Widget build(BuildContext context) => Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(title, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)),
        const SizedBox(height: 6),
        ...children,
      ])));
}

// ---- the daily WhatsApp report --------------------------------------------------------------------------------------------

class _WaReportTab extends StatefulWidget {
  const _WaReportTab({required this.ctx});
  final Ctx ctx;
  @override
  State<_WaReportTab> createState() => _WaReportTabState();
}

class _WaReportTabState extends State<_WaReportTab> {
  int _gen = 0;
  Map<String, dynamic>? _code;

  Future<void> _patch(Map<String, dynamic> body) async {
    if (await run(context, () async { await widget.ctx.api.patch('store/whatsapp-report', body); }) && mounted) setState(() => _gen++);
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final write = widget.ctx.can('copilot.use');
    return ResumeReload(onResume: () => setState(() => _gen++), child: Async<Map<String, dynamic>>(
      key: ValueKey('wr$_gen'),
      load: () async => await widget.ctx.api.get('store/whatsapp-report') as Map<String, dynamic>,
      builder: (context, d, reload) {
        final linked = d['linked'] == true;
        final digits = '${d['platformNumber'] ?? ''}'.replaceAll(RegExp(r'\D'), '');
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Text(ar ? 'ابعت رسالة مرة واحدة لرقم الواتساب بتاعنا، وهيوصلك كل ليلة مبيعاتك وأكتر منتج اتباع والمخزون القليل و٣ حاجات تعملها تاني يوم، مجانًا.' : 'Message our WhatsApp number once and we send you your sales, best seller, low stock and 3 things to do tomorrow every night, free of charge.', style: const TextStyle(color: Color(0xFF475569), height: 1.45)),
          const SizedBox(height: 12),
          if (d['configured'] != true) Card(color: const Color(0xFFFEF3C7), child: Padding(padding: const EdgeInsets.all(12), child: Text(ar ? 'رقم التقرير لسه مش متظبط على السيرفر.' : 'The report number is not set up on this server yet.'))),
          if (d['configured'] == true && !linked) ...[
            if (_code == null) FilledButton.icon(onPressed: () async { if (await run(context, () async { _code = await widget.ctx.api.post('store/whatsapp-report/link-code', {}) as Map<String, dynamic>; }) && mounted) setState(() {}); }, icon: const Icon(Icons.vpn_key_rounded), label: Text(ar ? 'هات كود الربط' : 'Get my link code'))
            else Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text(ar ? 'ابعت الكود ده لرقم الواتساب بتاعنا:' : 'Send this code to our WhatsApp number:'),
              const SizedBox(height: 8),
              Center(child: Directionality(textDirection: TextDirection.ltr, child: SelectableText('${_code!['code']}', style: const TextStyle(fontSize: 26, fontWeight: FontWeight.w900, letterSpacing: 3)))),
              const SizedBox(height: 8),
              if (digits.isNotEmpty) FilledButton.icon(onPressed: () => launch('https://wa.me/$digits?text=${Uri.encodeComponent('${_code!['code']}')}'), icon: const Icon(Icons.chat_rounded), label: Text(ar ? 'افتح واتساب' : 'Open WhatsApp')),
              TextButton(onPressed: () => setState(() => _gen++), child: Text(ar ? 'بعتّه - تأكد دلوقتي' : 'I sent it - check now')),
              Text(ar ? 'الكود شغال ٣٠ دقيقة.' : 'The code works for 30 minutes.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
            ]))),
          ],
          if (linked) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Wrap(spacing: 8, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
              Chip(label: Text(ar ? 'مربوط' : 'Linked'), backgroundColor: const Color(0xFFD1FAE5)),
              Directionality(textDirection: TextDirection.ltr, child: Text('+${d['phone']}')),
              Chip(label: Text(d['windowOpen'] == true ? (ar ? 'التقارير بتوصلك' : 'Reports can be sent') : (ar ? 'ابعتلنا أي رسالة عشان نكمّل' : 'Send us any message to continue')), backgroundColor: d['windowOpen'] == true ? const Color(0xFFD1FAE5) : const Color(0xFFFEF3C7)),
            ]),
            if (d['needsPing'] == true) Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'تقرير إمبارح ماتبعتش لأنك ماكلمتناش من يوم. ابعت أي رسالة (أو اضغط الزر في آخر تقرير) عشان يوصلك التقرير الجاي.' : "Yesterday's report could not be sent because you did not message us for a day. Send any message (or tap the button on the last report) to receive the next one.", style: const TextStyle(color: Color(0xFFB45309)))),
            if (d['lastDay'] != null) Padding(padding: const EdgeInsets.only(top: 6), child: Text('${ar ? 'آخر تقرير' : 'Last report'}: ${d['lastDay']} · ${_status(d['lastStatus'], ar)}', style: const TextStyle(fontSize: 12.5, color: Color(0xFF6B7C8D)))),
            const Divider(height: 24),
            SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'إرسال التقارير' : 'Send reports'), value: d['enabled'] == true, onChanged: write ? (v) => _patch({'enabled': v}) : null),
            ListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'ابعت بعد' : 'Send after'), subtitle: Text(ar ? 'آخر اليوم، من ٦ المغرب لحد ١١:٥٩' : 'End of day, 18:00 to 23:59'),
              trailing: Directionality(textDirection: TextDirection.ltr, child: Text('${d['sendAfter']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              onTap: !write ? null : () async {
                final parts = '${d['sendAfter']}'.split(':');
                final t = await showTimePicker(context: context, initialTime: TimeOfDay(hour: int.tryParse(parts[0]) ?? 23, minute: int.tryParse(parts[1]) ?? 59));
                if (t != null) _patch({'sendAfter': '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}'});
              }),
            ListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'اللغة' : 'Language'), trailing: SegmentedButton<String>(showSelectedIcon: false, segments: const [ButtonSegment(value: 'ar', label: Text('عربي')), ButtonSegment(value: 'en', label: Text('EN'))], selected: {'${d['language']}'}, onSelectionChanged: write ? (v) => _patch({'language': v.first}) : null)),
            const SizedBox(height: 4),
            Row(children: [
              Expanded(child: OutlinedButton.icon(onPressed: !write ? null : () async { if (await run(context, () async { await widget.ctx.api.post('store/whatsapp-report/send-now', {}); }) && context.mounted) { toast(context, ar ? 'اتبعت. شوف الواتساب.' : 'Sent. Check WhatsApp.'); setState(() => _gen++); } }, icon: const Icon(Icons.send_rounded), label: Text(ar ? 'ابعت تقرير النهارده' : "Send today's report"))),
              const SizedBox(width: 8),
              TextButton(onPressed: !write ? null : () async { if (await run(context, () async { await widget.ctx.api.delete('store/whatsapp-report'); }) && mounted) setState(() { _code = null; _gen++; }); }, child: Text(ar ? 'فك الربط' : 'Unlink')),
            ]),
          ]))),
        ]));
      },
    ));
  }

  String _status(Object? s, bool ar) => switch ('$s') { 'SENT' => ar ? 'اتبعت' : 'sent', 'WINDOW_CLOSED' => ar ? 'مستني رسالتك' : 'waiting for your message', _ => ar ? 'فشل' : 'failed' };
}

// ---- what the AI seller could not answer --------------------------------------------------------------------------------------

class _AssistantTab extends StatefulWidget {
  const _AssistantTab({required this.ctx});
  final Ctx ctx;
  @override
  State<_AssistantTab> createState() => _AssistantTabState();
}

class _AssistantTabState extends State<_AssistantTab> {
  int _gen = 0;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final write = widget.ctx.can('ai.manage');
    return Async<List<dynamic>>(
      key: ValueKey('as$_gen'),
      load: () async => [await widget.ctx.api.get('store/ai/usage'), await widget.ctx.api.get('store/ai/unanswered')],
      builder: (context, d, reload) {
        final u = d[0] as Map<String, dynamic>;
        final open = _maps(d[1]).where((q) => q['resolved'] != true).toList();
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Card(child: ListTile(leading: const Icon(Icons.bolt_rounded), title: Text(ar ? 'الردود الآلية الشهر ده' : 'Automatic replies this month'), trailing: Text('${u['usedThisMonth']} / ${u['monthlyCap']}', style: const TextStyle(fontWeight: FontWeight.w900)))),
          const SizedBox(height: 8),
          _Section(ar ? 'أسئلة المساعد ماقدرش يجاوبها' : 'Questions the assistant could not answer', [
            Text(ar ? 'علّم المساعد الإجابة (من الدروس في الموقع) عشان يرد عليها المرة الجاية.' : 'Teach the assistant the answer (Lessons on the website) so it can reply next time.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
            if (open.isEmpty) Padding(padding: const EdgeInsets.only(top: 10), child: Text(ar ? 'مفيش أسئلة مفتوحة.' : 'No open questions.')),
            for (final q in open) ListTile(contentPadding: EdgeInsets.zero, title: Text('${q['question']}'), trailing: !write ? null : TextButton(onPressed: () async { if (await run(context, () async { await widget.ctx.api.post('store/ai/unanswered/${q['id']}/resolve', {}); }) && mounted) setState(() => _gen++); }, child: Text(ar ? 'تم' : 'Done'))),
          ]),
        ]));
      },
    );
  }
}
