import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'settings.dart';
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

  void _openNumberSettings() {
    final ar = S.of(context).ar;
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(ar ? 'الإعدادات' : 'Settings')), body: StoreSettings(ctx: widget.ctx, initialTab: 'whatsapp'))));
  }

  Future<void> _put(Map<String, dynamic> body) async {
    if (await run(context, () async { await widget.ctx.api.put('store/ai/evolution/report', body); }) && mounted) setState(() => _gen++);
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final write = widget.ctx.can('copilot.use');
    return ResumeReload(onResume: () => setState(() => _gen++), child: Async<Map<String, dynamic>>(
      key: ValueKey('wr$_gen'),
      load: () async => (await widget.ctx.api.get('store/ai/evolution/report').catchError((_) => <String, dynamic>{}) as Map).cast<String, dynamic>(),
      builder: (context, fr, reload) {
        final linked = fr['linked'] == true;
        final price = S.of(context).money(10, widget.ctx.currency);
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Text(ar ? 'كل ليلة هيوصلك على واتساب مبيعاتك وأكتر منتج اتباع والمخزون القليل و٣ حاجات تعملها تاني يوم، من رقم المتجر المربوط. كل رسالة بـ $price.' : "Every night your sales, best seller, low stock and 3 things to do tomorrow arrive on WhatsApp from the shop's linked number. Each message costs $price.", style: const TextStyle(color: Color(0xFF475569), height: 1.45)),
          const SizedBox(height: 12),
          if (!linked) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(ar ? 'اربط رقم واتساب المتجر الأول عشان توصلك التقارير.' : "Link the shop's WhatsApp number first to receive reports."),
            const SizedBox(height: 8),
            FilledButton.icon(onPressed: _openNumberSettings, icon: const Icon(Icons.link_rounded), label: Text(ar ? 'اربط الرقم' : 'Link the number')),
          ])))
          else Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Container(padding: const EdgeInsets.all(10), decoration: BoxDecoration(color: const Color(0xFFDCFCE7), borderRadius: BorderRadius.circular(12)), child: Text(ar ? 'التقرير بيتبعت من رقم المتجر المربوط لنفس الرقم (+${fr['phone'] ?? ''})، زي رسالة لنفسك.' : "The report is sent from the shop's linked number to itself (+${fr['phone'] ?? ''}), like a message to yourself.")),
            SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'إرسال التقارير' : 'Send reports'), value: fr['enabled'] == true, onChanged: write ? (v) => _put({'enabled': v}) : null),
            ListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'ابعت بعد' : 'Send after'), subtitle: Text(ar ? 'آخر اليوم، من ٦ المغرب لحد ١١:٥٩' : 'End of day, 18:00 to 23:59'),
              trailing: Directionality(textDirection: TextDirection.ltr, child: Text('${fr['sendAfter']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              onTap: !write ? null : () async {
                final parts = '${fr['sendAfter']}'.split(':');
                final t = await showTimePicker(context: context, initialTime: TimeOfDay(hour: int.tryParse(parts[0]) ?? 23, minute: int.tryParse(parts.length > 1 ? parts[1] : '0') ?? 0));
                if (t == null || !context.mounted) return;
                await _put({'sendAfter': '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}'});
              }),
            ListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'اللغة' : 'Language'), trailing: SegmentedButton<String>(showSelectedIcon: false, segments: const [ButtonSegment(value: 'ar', label: Text('ع')), ButtonSegment(value: 'en', label: Text('EN'))], selected: {'${fr['language'] ?? 'ar'}'}, onSelectionChanged: !write ? null : (v) => _put({'language': v.first}))),
            OutlinedButton.icon(onPressed: !write ? null : () async { if (await run(context, () async { await widget.ctx.api.post('store/ai/evolution/report/send-now', {}); }) && context.mounted) toast(context, ar ? 'التقرير اتبعت على واتساب بتاعك' : 'The report was sent to your WhatsApp'); }, icon: const Icon(Icons.send_rounded), label: Text(ar ? 'ابعت تقرير النهارده' : "Send today's report")),
          ]))),
        ]));
      },
    ));
  }
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
