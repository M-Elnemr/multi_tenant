// ignore_for_file: use_build_context_synchronously, prefer_const_constructors
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

// Growth tools of the Business app: incomplete carts, customer segments and free WhatsApp broadcasts, loyalty points, feeds and server-side tracking, announcement tests, the AI store builder.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

Widget _note(String t) => Text(t, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5, height: 1.4));

// ---- incomplete carts ---------------------------------------------------------------------------------------------------

class CartsScreen extends StatefulWidget {
  const CartsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<CartsScreen> createState() => _CartsScreenState();
}

class _CartsScreenState extends State<CartsScreen> {
  String _view = 'due';
  int _gen = 0;
  Future<void> _do(Future<void> Function() f) async { if (await run(context, f) && mounted) setState(() => _gen++); }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final api = widget.ctx.api;
    final write = widget.ctx.can('cart.manage');
    return Async<Map<String, dynamic>>(
      key: ValueKey('c$_view$_gen'),
      load: () async => await api.get('store/carts', query: {'view': _view}) as Map<String, dynamic>,
      builder: (context, d, reload) {
        final st = d['stats'] as Map<String, dynamic>;
        final set = d['settings'] as Map<String, dynamic>;
        final carts = _maps(d['carts']);
        const rs = {'SENT_FREE': ('التذكير اتبعت (مجاني)', 'Reminder sent (free)'), 'SENT_PAID': ('التذكير اتبعت (مدفوع)', 'Reminder sent (paid)'), 'NOT_SENT_WINDOW': ('ماتبعتش: العميل ماكلمكش في ٢٤ ساعة', 'Not sent: customer has not written in 24h'), 'NO_CREDIT': ('ماتبعتش: رصيد واتساب مش كفاية', 'Not sent: no WhatsApp credit'), 'NOT_APPROVED': ('ماتبعتش: القالب لسه ماتوافقش عليه', 'Not sent: template not approved'), 'NO_CHANNEL': ('ماتبعتش: واتساب مش متوصل', 'Not sent: WhatsApp not connected'), 'FAILED': ('فشل الإرسال', 'Failed to send')};
        Widget tile(String t, String v, [String? h]) => Expanded(child: Card(child: Padding(padding: const EdgeInsets.all(10), child: Column(children: [Text(t, textAlign: TextAlign.center, style: const TextStyle(fontSize: 11, color: Color(0xFF6B7C8D))), FittedBox(child: Text(v, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))), if (h != null) Text(h, style: const TextStyle(fontSize: 10.5, color: Color(0xFF6B7C8D)))]))));
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          _note(ar ? 'عملاء سابوا رقمهم وماكملوش الطلب. السلة بتظهر هنا بعد ٢٤ ساعة من بدايتها.' : 'Shoppers who left their phone number and did not finish. Carts show here 24 hours after they were started.'),
          const SizedBox(height: 8),
          Row(children: [tile(ar ? 'مستنية +٢٤ ساعة' : 'Waiting 24h+', '${st['dueCount']}', s.money(st['dueMinor'] as num?, widget.ctx.currency)), tile(ar ? 'اتسترجعت' : 'Recovered', '${st['recoveredCount']}', s.money(st['recoveredMinor'] as num?, widget.ctx.currency)), tile(ar ? 'النسبة' : 'Rate', '${st['recoveryRatePercent']}%')]),
          const SizedBox(height: 4),
          SegmentedButton<String>(showSelectedIcon: false, segments: [ButtonSegment(value: 'due', label: Text(ar ? 'بعد ٢٤ ساعة' : '24h+')), ButtonSegment(value: 'recent', label: Text(ar ? 'حديثة' : 'Recent')), ButtonSegment(value: 'recovered', label: Text(ar ? 'اتسترجعت' : 'Recovered'))], selected: {_view}, onSelectionChanged: (v) => setState(() => _view = v.first)),
          const SizedBox(height: 8),
          if (carts.isEmpty) emptyState(ar ? 'مفيش سلات هنا.' : 'No carts here.', icon: Icons.shopping_cart_outlined),
          for (final c in carts) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(children: [Expanded(child: Text('${c['name'] ?? (ar ? 'عميل' : 'Customer')}', style: const TextStyle(fontWeight: FontWeight.w900))), Text(s.money(c['totalMinor'] as num?, '${c['currency']}'), style: const TextStyle(fontWeight: FontWeight.w900))]),
            Text('${c['phone']}  ·  ${fmtDate(c['createdAt'] as String?)}', textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
            const SizedBox(height: 4),
            for (final i in _maps(c['items'])) Text('${i['quantity']} × ${i['name']}${i['variant'] != null ? ' (${i['variant']})' : ''}'),
            if (c['contactedAt'] != null) Text(ar ? 'اتكلمت معاه' : 'Contacted', style: const TextStyle(color: Color(0xFF1D4ED8), fontSize: 12)),
            if (c['reminderStatus'] != null) Text(ar ? (rs['${c['reminderStatus']}']?.$1 ?? '${c['reminderStatus']}') : (rs['${c['reminderStatus']}']?.$2 ?? '${c['reminderStatus']}'), style: const TextStyle(color: Color(0xFFB45309), fontSize: 12)),
            if (write && c['status'] == 'OPEN') Padding(padding: const EdgeInsets.only(top: 8), child: Wrap(spacing: 8, runSpacing: 4, children: [
              FilledButton.icon(icon: const Icon(Icons.chat_rounded, size: 18), onPressed: () async { await launch('${c['whatsappUrl']}'); await api.post('store/carts/${c['id']}/contacted', {}).catchError((_) => null); }, label: Text(ar ? 'واتساب' : 'WhatsApp')),
              OutlinedButton.icon(icon: const Icon(Icons.call_rounded, size: 18), onPressed: () async { await dial('${c['phone']}'); await api.post('store/carts/${c['id']}/contacted', {}).catchError((_) => null); }, label: Text(ar ? 'اتصال' : 'Call')),
              OutlinedButton(onPressed: () => _do(() async { await api.post('store/carts/${c['id']}/remind', {}); if (mounted) toast(context, ar ? 'التذكير اتبعت.' : 'Reminder sent.'); }), child: Text(ar ? 'ابعت تذكير' : 'Send reminder')),
              TextButton(onPressed: () => _do(() async { await api.post('store/carts/${c['id']}/dismiss', {}); }), child: Text(ar ? 'تجاهل' : 'Dismiss')),
            ])),
          ]))),
          const SizedBox(height: 8),
          _CartSettings(ctx: widget.ctx, settings: set, onSaved: () => setState(() => _gen++)),
        ]));
      },
    );
  }
}

class _CartSettings extends StatefulWidget {
  const _CartSettings({required this.ctx, required this.settings, required this.onSaved});
  final Ctx ctx;
  final Map<String, dynamic> settings;
  final VoidCallback onSaved;
  @override
  State<_CartSettings> createState() => _CartSettingsState();
}

class _CartSettingsState extends State<_CartSettings> {
  late bool _on = widget.settings['recoveryEnabled'] == true, _paid = widget.settings['allowPaid'] == true;
  late final _hours = TextEditingController(text: '${widget.settings['remindAfterHours']}'), _coupon = TextEditingController(text: '${widget.settings['couponCode'] ?? ''}');

  @override
  void dispose() { _hours.dispose(); _coupon.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final write = widget.ctx.can('cart.manage');
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(ar ? 'التذكير التلقائي' : 'Automatic reminders', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
      _note(ar ? 'متوقف تلقائيًا. لما تشغّله بيتبعت تذكير واحد لكل سلة على واتساب، ومجاني لو العميل كلمك في آخر ٢٤ ساعة.' : 'Off by default. When on, one WhatsApp reminder per cart, free when the customer wrote to you in the last 24 hours.'),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'التذكير التلقائي' : 'Automatic reminders'), value: _on, onChanged: write ? (v) => setState(() { _on = v; if (!v) _paid = false; }) : null),
      const SizedBox(height: 6),
      Row(children: [Expanded(child: TextField(controller: _hours, enabled: write, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: ar ? 'ذكّر بعد (ساعات)' : 'Remind after (hours)', isDense: true))), const SizedBox(width: 8), Expanded(child: TextField(controller: _coupon, enabled: write, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ar ? 'كوبون (اختياري)' : 'Coupon (optional)', isDense: true)))]),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'اسمح بالرسائل المدفوعة' : 'Allow paid messages'), subtitle: Text(ar ? 'خارج نافذة الـ٢٤ ساعة واتساب بيحاسب (${s.money(widget.settings['messagePriceMinor'] as num?, widget.ctx.currency)} للرسالة من رصيدك). متوقف إلا لو شغّلته.' : 'Outside the 24-hour window WhatsApp charges (${s.money(widget.settings['messagePriceMinor'] as num?, widget.ctx.currency)} each from your credit). Off unless you turn it on.'), value: _paid, onChanged: write && _on ? (v) => setState(() => _paid = v) : null),
      if (write) FilledButton(onPressed: () async {
        if (await run(context, () async { await widget.ctx.api.put('store/carts/settings', {'recoveryEnabled': _on, 'allowPaid': _paid, 'remindAfterHours': int.tryParse(_hours.text) ?? 24, 'couponCode': _coupon.text.trim()}); }) && context.mounted) { toast(context, ar ? 'تم الحفظ' : 'Saved'); widget.onSaved(); }
      }, child: Text(ar ? 'حفظ' : 'Save')),
    ])));
  }
}

// ---- customers growth ----------------------------------------------------------------------------------------------------

class GrowthScreen extends StatelessWidget {
  const GrowthScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return DefaultTabController(length: 5, child: Column(children: [
      TabBar(isScrollable: true, tabAlignment: TabAlignment.start, tabs: [Tab(text: ar ? 'الشرائح' : 'Segments'), Tab(text: ar ? 'نقاط الولاء' : 'Loyalty'), Tab(text: ar ? 'الملفات والتتبع' : 'Feeds'), Tab(text: ar ? 'اختبار الإعلان' : 'A/B test'), Tab(text: ar ? 'ابدأ بالذكاء' : 'AI start')]),
      Expanded(child: TabBarView(children: [_Segments(ctx: ctx), _Loyalty(ctx: ctx), _Feeds(ctx: ctx), _Experiments(ctx: ctx), _Builder(ctx: ctx)])),
    ]));
  }
}

class _Segments extends StatefulWidget {
  const _Segments({required this.ctx});
  final Ctx ctx;
  @override
  State<_Segments> createState() => _SegmentsState();
}

class _SegmentsState extends State<_Segments> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  String _key = 'NEW';
  final _msg = TextEditingController();
  int _gen = 0;

  @override
  void dispose() { _msg.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final s = S.of(context);
    final ar = s.ar;
    const names = {'NEW': ('جدد', 'New'), 'REPEAT': ('متكررين', 'Repeat'), 'VIP': ('VIP', 'VIP'), 'AT_RISK': ('معرضين للفقد', 'At risk'), 'LAPSED': ('غايبين', 'Lapsed')};
    return Async<List<dynamic>>(
      key: ValueKey('sg$_key$_gen'),
      load: () async => [await widget.ctx.api.get('store/segments'), await widget.ctx.api.get('store/segments/$_key')],
      builder: (context, d, reload) {
        final counts = (d[0] as Map)['segments'] as Map;
        final det = d[1] as Map<String, dynamic>;
        final pv = det['preview'] as Map<String, dynamic>;
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Wrap(spacing: 8, children: [for (final k in names.keys) ChoiceChip(label: Text('${ar ? names[k]!.$1 : names[k]!.$2} ${counts[k] ?? 0}'), selected: _key == k, onSelected: (_) => setState(() => _key = k))]),
          const SizedBox(height: 12),
          Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(ar ? 'رسالة واتساب للشريحة دي' : 'WhatsApp message to this segment', style: const TextStyle(fontWeight: FontWeight.w900)),
            Text(ar ? '${pv['audience']} عميل، ${pv['withConsent']} وافقوا على واتساب، و${pv['reachableNow']} ينفع نبعتلهم مجانًا دلوقتي.' : '${pv['audience']} customers, ${pv['withConsent']} agreed to WhatsApp, ${pv['reachableNow']} can get a free message now.'),
            _note(ar ? 'مجاني فقط: للعملاء اللي وافقوا وكلموك في آخر ٢٤ ساعة. مفيش رسالة مدفوعة بتتبعت أبدًا. رسالة جماعية واحدة في اليوم.' : 'Free only: customers who agreed and wrote to you in the last 24 hours. No paid message is ever sent. One broadcast a day.'),
            const SizedBox(height: 12),
            TextField(controller: _msg, maxLines: 3, maxLength: 800, decoration: InputDecoration(labelText: ar ? 'الرسالة ({name} = أول اسم العميل)' : 'Message ({name} = first name)')),
            FilledButton(onPressed: () async {
              Map<String, dynamic>? r;
              if (await run(context, () async { r = await widget.ctx.api.post('store/broadcasts', {'segment': _key, 'message': _msg.text}) as Map<String, dynamic>; }) && context.mounted) {
                toast(context, ar ? 'اتبعت ${r!['sent']}. اتخطى: ${r!['skippedNoWindow']} برا نافذة الـ٢٤ ساعة، ${r!['skippedNoConsent']} من غير موافقة.' : 'Sent ${r!['sent']}. Skipped: ${r!['skippedNoWindow']} outside the 24h window, ${r!['skippedNoConsent']} without consent.');
                _msg.clear(); setState(() => _gen++);
              }
            }, child: Text(ar ? 'ابعت' : 'Send')),
          ]))),
          const SizedBox(height: 8),
          for (final c in _maps(det['customers'])) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text('${c['name'] ?? ''}'), subtitle: Text('${c['phone']}', textDirection: TextDirection.ltr), trailing: Text('${c['orders']} · ${s.money(c['spent'] as num?, widget.ctx.currency)}${c['whatsappConsent'] == true ? ' ✓' : ''}')),
        ]));
      },
    );
  }
}

class _Loyalty extends StatefulWidget {
  const _Loyalty({required this.ctx});
  final Ctx ctx;
  @override
  State<_Loyalty> createState() => _LoyaltyState();
}

class _LoyaltyState extends State<_Loyalty> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  bool? _on;
  final _pct = TextEditingController(), _min = TextEditingController();
  bool _filled = false;
  int _gen = 0;

  @override
  void dispose() { _pct.dispose(); _min.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final ar = S.of(context).ar;
    return Async<Map<String, dynamic>>(
      key: ValueKey('ly$_gen'),
      load: () async => await widget.ctx.api.get('store/loyalty') as Map<String, dynamic>,
      builder: (context, d, reload) {
        final st = d['settings'] as Map<String, dynamic>;
        if (!_filled) { _filled = true; _on = st['enabled'] == true; _pct.text = '${st['earnPercent']}'; _min.text = '${st['minRedeem']}'; }
        return ListView(padding: const EdgeInsets.all(16), children: [
          _note(ar ? 'العملاء بيكسبوا نقاط على الأوردرات اللي بتوصل ويحولوها لكوبون لمرة واحدة. النقطة = جنيه.' : 'Customers earn points on delivered orders and turn them into a one-time coupon. 1 point = 1 currency unit.'),
          SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'نقاط الولاء' : 'Loyalty points'), value: _on ?? false, onChanged: (v) => setState(() => _on = v)),
          Row(children: [Expanded(child: TextField(controller: _pct, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: ar ? 'نسبة النقاط (%)' : 'Points back (%)', isDense: true))), const SizedBox(width: 8), Expanded(child: TextField(controller: _min, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: ar ? 'أقل نقاط للاستبدال' : 'Minimum to redeem', isDense: true)))]),
          const SizedBox(height: 8),
          FilledButton(onPressed: () async { if (await run(context, () async { await widget.ctx.api.put('store/loyalty', {'enabled': _on, 'earnPercent': int.tryParse(_pct.text) ?? 5, 'minRedeem': int.tryParse(_min.text) ?? 20}); }) && context.mounted) { toast(context, ar ? 'تم الحفظ' : 'Saved'); setState(() { _filled = false; _gen++; }); } }, child: Text(ar ? 'حفظ' : 'Save')),
          const SizedBox(height: 12),
          Text(ar ? 'أعلى العملاء نقاطًا' : 'Top customers by points', style: const TextStyle(fontWeight: FontWeight.w900)),
          for (final c in _maps(d['top'])) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text('${c['name'] ?? ''}'), subtitle: Text('${c['phone']}', textDirection: TextDirection.ltr), trailing: Text('${c['points']} / ${c['lifetimePoints']}', style: const TextStyle(fontWeight: FontWeight.w800))),
        ]);
      },
    );
  }
}

class _Feeds extends StatefulWidget {
  const _Feeds({required this.ctx});
  final Ctx ctx;
  @override
  State<_Feeds> createState() => _FeedsState();
}

class _FeedsState extends State<_Feeds> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  final _token = TextEditingController();
  bool? _on;
  int _gen = 0;

  @override
  void dispose() { _token.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final ar = S.of(context).ar;
    final feeds = [(ar ? 'Google Merchant Center' : 'Google Merchant Center', '/feeds/google.xml'), (ar ? 'كتالوج فيسبوك وإنستجرام' : 'Facebook and Instagram catalog', '/feeds/facebook.csv'), (ar ? 'ملف المنتجات (JSON)' : 'Product feed (JSON)', '/feeds/products.json'), (ar ? 'ملخص المتجر للذكاء الاصطناعي' : 'Shop summary for AI assistants', '/llms.txt')];
    return Async<Map<String, dynamic>>(
      key: ValueKey('fd$_gen'),
      load: () async => await widget.ctx.api.get('store/tracking') as Map<String, dynamic>,
      builder: (context, t, reload) {
        _on ??= t['enabled'] == true;
        return ListView(padding: const EdgeInsets.all(16), children: [
          _note(ar ? 'اديك العناوين دي لـ Google Merchant Center أو كتالوج فيسبوك. دايمًا فيها أسعارك ومخزونك الحقيقي.' : 'Give these addresses to Google Merchant Center or the Facebook catalog. They always show your real prices and stock.'),
          for (final f in feeds) Card(child: ListTile(title: Text(f.$1), subtitle: Text('https://${widget.ctx.host}${f.$2}', textDirection: TextDirection.ltr), trailing: const Icon(Icons.open_in_new_rounded), onTap: () => launch('https://${widget.ctx.host}${f.$2}'))),
          const SizedBox(height: 8),
          Text(ar ? 'أحداث الشراء من السيرفر (ميتا)' : 'Server-side purchase events (Meta)', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          _note(ar ? 'بيبعت كل أوردر جديد لميتا من السيرفر. محتاج رقم البكسل (إعدادات المتجر) وتوكن Conversions API.' : 'Sends each new order to Meta from the server. Needs your Pixel ID (store settings) and a Conversions API token.'),
          if (t['pixelId'] == null) Padding(padding: const EdgeInsets.only(top: 6), child: Text(ar ? 'ضيف رقم Meta Pixel في إعدادات المتجر الأول.' : 'Add your Meta Pixel ID in the store settings first.', style: const TextStyle(color: Color(0xFFB45309)))),
          SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'الأحداث من السيرفر' : 'Server-side events'), value: _on ?? false, onChanged: (v) => setState(() => _on = v)),
          TextField(controller: _token, obscureText: true, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ar ? 'توكن Conversions API' : 'Conversions API token', helperText: t['hasToken'] == true ? (ar ? 'فيه توكن محفوظ. سيبه فاضي عشان يفضل.' : 'A token is saved. Leave empty to keep it.') : null)),
          if (t['lastStatus'] != null) Padding(padding: const EdgeInsets.only(top: 6), child: Text('${ar ? 'آخر حدث' : 'Last event'}: ${t['lastStatus']}', style: const TextStyle(fontSize: 12.5, color: Color(0xFF6B7C8D)))),
          const SizedBox(height: 8),
          FilledButton(onPressed: () async { if (await run(context, () async { await widget.ctx.api.put('store/tracking', {'enabled': _on, if (_token.text.trim().isNotEmpty) 'token': _token.text.trim()}); }) && context.mounted) { toast(context, ar ? 'تم الحفظ' : 'Saved'); _token.clear(); setState(() { _on = null; _gen++; }); } }, child: Text(ar ? 'حفظ' : 'Save')),
        ]);
      },
    );
  }
}

class _Experiments extends StatefulWidget {
  const _Experiments({required this.ctx});
  final Ctx ctx;
  @override
  State<_Experiments> createState() => _ExperimentsState();
}

class _ExperimentsState extends State<_Experiments> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  final _a = TextEditingController(), _b = TextEditingController();
  int _gen = 0;

  @override
  void dispose() { _a.dispose(); _b.dispose(); super.dispose(); }

  Future<void> _do(Future<void> Function() f) async { if (await run(context, f) && mounted) setState(() => _gen++); }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final ar = S.of(context).ar;
    final api = widget.ctx.api;
    return Async<List<dynamic>>(
      key: ValueKey('ex$_gen'),
      load: () async => await api.get('store/experiments') as List<dynamic>,
      builder: (context, list, reload) => ListView(padding: const EdgeInsets.all(16), children: [
        _note(ar ? 'اعرض نصين مختلفين في الشريط اللي فوق متجرك، نص لنص الزوار. بعد زوار كفاية هتشوف أنهي نص جاب أوردرات أكتر وإنت تختار.' : 'Show two texts in the bar at the top of your shop, half of the visitors see each. After enough visitors you see which got more orders, and you choose.'),
        const SizedBox(height: 10),
        TextField(controller: _a, decoration: InputDecoration(labelText: ar ? 'النص أ' : 'Text A')),
        TextField(controller: _b, decoration: InputDecoration(labelText: ar ? 'النص ب' : 'Text B')),
        const SizedBox(height: 8),
        FilledButton(onPressed: () => _do(() async { await api.post('store/experiments', {'a': _a.text, 'b': _b.text}); _a.clear(); _b.clear(); }), child: Text(ar ? 'ابدأ الاختبار' : 'Start the test')),
        const SizedBox(height: 12),
        for (final e in _maps(list)) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [Expanded(child: Text('${e['name']}', style: const TextStyle(fontWeight: FontWeight.w900))), Text(e['status'] == 'RUNNING' ? (ar ? 'شغال' : 'Running') : (e['winner'] != null ? (ar ? 'اتطبق ${e['winner']}' : 'Applied ${e['winner']}') : (ar ? 'متوقف' : 'Stopped')))]),
          for (final v in ['A', 'B']) Container(margin: const EdgeInsets.only(top: 6), padding: const EdgeInsets.all(10), decoration: BoxDecoration(border: Border.all(color: e['recommendation'] == v ? const Color(0xFF34D399) : const Color(0xFFE2E8F0)), borderRadius: BorderRadius.circular(12), color: e['recommendation'] == v ? const Color(0xFFECFDF5) : null), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('$v: ${e[v == 'A' ? 'variantA' : 'variantB']}', style: const TextStyle(fontWeight: FontWeight.w800)),
            Text('${e[v == 'A' ? 'viewsA' : 'viewsB']} ${ar ? 'زائر' : 'visitors'} · ${e[v == 'A' ? 'ordersA' : 'ordersB']} ${ar ? 'أوردر' : 'orders'} · ${e[v == 'A' ? 'rateA' : 'rateB']}%', textDirection: TextDirection.ltr),
            if (e['recommendation'] == v) Text(ar ? 'أفضل بوضوح' : 'Clearly better', style: const TextStyle(color: Color(0xFF047857), fontWeight: FontWeight.w800, fontSize: 12)),
          ])),
          if (e['enoughData'] != true && e['status'] == 'RUNNING') _note(ar ? 'لسه الزوار مش كفاية للحسم (١٠٠ في كل جهة و١٠ أوردرات).' : 'Not enough visitors yet (100 on each side and 10 orders).'),
          if (e['status'] == 'RUNNING') Wrap(spacing: 8, children: [
            FilledButton(onPressed: () => _do(() async { await api.post('store/experiments/${e['id']}/apply', {'winner': 'A'}); }), child: Text(ar ? 'استخدم أ' : 'Use A')),
            FilledButton(onPressed: () => _do(() async { await api.post('store/experiments/${e['id']}/apply', {'winner': 'B'}); }), child: Text(ar ? 'استخدم ب' : 'Use B')),
            TextButton(onPressed: () => _do(() async { await api.post('store/experiments/${e['id']}/stop', {}); }), child: Text(ar ? 'إيقاف' : 'Stop')),
          ]),
        ]))),
      ]),
    );
  }
}

class _Builder extends StatefulWidget {
  const _Builder({required this.ctx});
  final Ctx ctx;
  @override
  State<_Builder> createState() => _BuilderState();
}

class _BuilderState extends State<_Builder> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  final _d = TextEditingController();
  Map<String, dynamic>? _res;
  bool _busy = false;

  @override
  void dispose() { _d.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final s = S.of(context);
    final ar = s.ar;
    return ListView(padding: const EdgeInsets.all(16), children: [
      _note(ar ? 'اوصف متجرك. الذكاء الاصطناعي بيكتب وصف قصير وأول قايمة منتجات كمسودات. مفيش حاجة بتظهر للعملاء غير بعد ما تراجعها وتسعّرها وتنشرها.' : 'Describe your shop. The AI writes a short description and a first list of products as drafts. Nothing goes live until you review, price and publish it.'),
      const SizedBox(height: 10),
      TextField(controller: _d, maxLines: 5, maxLength: 1500, decoration: InputDecoration(labelText: ar ? 'بتبيع إيه ولمين؟' : 'What do you sell and to whom?', hintText: ar ? 'ببيع ملابس قطن للشباب في القاهرة: تيشيرتات وهوديز، الأسعار من ١٥٠ لـ٧٠٠' : 'I sell cotton basics for young men in Cairo: t-shirts and hoodies, 150 to 700')),
      FilledButton(onPressed: _busy ? null : () async {
        setState(() => _busy = true);
        Map<String, dynamic>? r;
        final ok = await run(context, () async { r = await widget.ctx.api.post('store/builder', {'description': _d.text, 'language': ar ? 'ar' : 'en'}) as Map<String, dynamic>; });
        if (mounted) setState(() { _busy = false; if (ok) _res = r; });
      }, child: Text(ar ? 'اعمل مسودات' : 'Create drafts')),
      if (_res != null) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(ar ? 'اتعمل ${_res!['products'].length} منتج مسودة.' : '${_res!['products'].length} draft products created.', style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF047857))),
        if ('${_res!['shortDescription']}'.isNotEmpty) Text('${_res!['descriptionSaved'] == true ? (ar ? 'اتحفظ كوصف لمتجرك' : 'Saved as your shop description') : (ar ? 'وصفك الأصلي فضل. الاقتراح' : 'Your own description was kept. Suggestion')}: ${_res!['shortDescription']}'),
        Text(ar ? 'الشكل المقترح: ${_res!['suggestedTemplate']}' : 'Suggested look: ${_res!['suggestedTemplate']}'),
        for (final p in _maps(_res!['products'])) Text('• ${p['name']} · ${s.money(p['priceMinor'] as num?, widget.ctx.currency)}'),
        Text(ar ? 'راجع المسودات من المنتجات.' : 'Review the drafts in Products.', style: const TextStyle(color: Color(0xFF6B7C8D))),
      ]))),
    ]);
  }
}
