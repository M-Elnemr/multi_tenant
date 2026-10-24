import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';

// Marketing autopilot: the weekly content plan (nothing is posted until approved) and the ad optimizer with its hard limits, its log and copy variants.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

const _planStatus = {'SUGGESTED': ('مقترح', 'Suggested'), 'APPROVED': ('موافق عليه', 'Approved'), 'SCHEDULED': ('مجدول', 'Scheduled'), 'POSTED': ('اتنشر', 'Posted'), 'FAILED': ('فشل', 'Failed'), 'SKIPPED': ('متخطى', 'Skipped')};

class PlanTab extends StatefulWidget {
  const PlanTab({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<PlanTab> createState() => _PlanTabState();
}

class _PlanTabState extends State<PlanTab> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  int _gen = 0;

  Future<void> _do(Future<void> Function() f) async { if (await run(context, f) && mounted) setState(() => _gen++); }

  Future<void> _edit(Map<String, dynamic> item) async {
    final c = TextEditingController(text: '${item['caption']}');
    final ar = S.of(context).ar;
    final ok = await showDialog<bool>(context: context, builder: (d) => AlertDialog(
      title: Text(ar ? 'تعديل النص' : 'Edit the text'),
      content: TextField(controller: c, maxLines: 8, minLines: 4, maxLength: 2000),
      actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'حفظ' : 'Save'))],
    ));
    if (ok == true) await _do(() async { await widget.ctx.api.patch('store/marketing/plan/${item['id']}', {'caption': c.text.trim()}); });
    c.dispose();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final ar = S.of(context).ar;
    final write = widget.ctx.canWrite('marketing');
    return Async<Map<String, dynamic>>(
      key: ValueKey('pl$_gen'),
      load: () async => await widget.ctx.api.get('store/marketing/plan') as Map<String, dynamic>,
      builder: (context, d, reload) {
        final items = _maps(d['items']).where((i) => i['status'] != 'SKIPPED').toList();
        final videos = d['videosEnabled'] == true;
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Card(color: const Color(0xFFEFF6FF), child: Padding(padding: const EdgeInsets.all(12), child: Text(ar ? 'بوست مقترح لكل يوم في الأسبوع الجاي، من أكتر منتجاتك مبيعًا ومنتجاتك الجديدة. مفيش حاجة بتتنشر غير بعد موافقتك.' : 'A suggested post for each day of the coming week, from your best sellers and new products. Nothing is posted until you approve it.'))),
          const SizedBox(height: 10),
          if (items.isEmpty) emptyState(ar ? 'مفيش خطة للأسبوع الجاي لسه.' : 'No plan for the coming week yet.', icon: Icons.event_note_outlined),
          for (final i in items) Card(margin: const EdgeInsets.only(bottom: 10), child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(children: [
              Directionality(textDirection: TextDirection.ltr, child: Text('${i['day']}', style: const TextStyle(fontWeight: FontWeight.w900))),
              const Spacer(),
              Flexible(child: Text(i['kind'] == 'SHOP' ? (ar ? 'عن المتجر' : 'About the shop') : '${i['productName'] ?? ''}', overflow: TextOverflow.ellipsis, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))),
              const SizedBox(width: 8),
              Chip(visualDensity: VisualDensity.compact, label: Text(ar ? (_planStatus['${i['status']}']?.$1 ?? '${i['status']}') : (_planStatus['${i['status']}']?.$2 ?? '${i['status']}'))),
            ]),
            const SizedBox(height: 6),
            Text('${i['caption']}', style: const TextStyle(height: 1.45)),
            if (i['imageUrl'] != null) Padding(padding: const EdgeInsets.only(top: 8), child: ClipRRect(borderRadius: BorderRadius.circular(12), child: Image.network('${i['imageUrl']}', height: 150, fit: BoxFit.cover, errorBuilder: (_, __, ___) => const SizedBox.shrink()))),
            if (write && i['status'] == 'SUGGESTED') Padding(padding: const EdgeInsets.only(top: 10), child: Wrap(spacing: 8, runSpacing: 6, children: [
              FilledButton(onPressed: () => _do(() async { await widget.ctx.api.post('store/marketing/plan/${i['id']}/approve', {}); }), child: Text(ar ? 'موافق' : 'Approve')),
              OutlinedButton(onPressed: () => _edit(i), child: Text(ar ? 'تعديل' : 'Edit')),
              if (videos && i['kind'] == 'PRODUCT') OutlinedButton.icon(icon: const Icon(Icons.movie_creation_outlined, size: 18), onPressed: () => _do(() async { await widget.ctx.api.post('store/marketing/plan/${i['id']}/video', {}); }), label: Text(ar ? 'اعمل فيديو' : 'Make a video')),
              TextButton(onPressed: () => _do(() async { await widget.ctx.api.post('store/marketing/plan/${i['id']}/skip', {}); }), child: Text(ar ? 'تخطي' : 'Skip')),
            ])),
            if (i['hasVideo'] == true) Padding(padding: const EdgeInsets.only(top: 8), child: Row(children: [const Icon(Icons.movie_rounded, size: 18, color: Color(0xFF047857)), const SizedBox(width: 6), Expanded(child: Text(ar ? 'الفيديو جاهز - حمّله من الموقع (التسويق ← خطة الأسبوع) وانشره كريلز.' : 'The video is ready - download it on the website (Marketing > Weekly plan) and post it as a reel.', style: const TextStyle(fontSize: 12.5, color: Color(0xFF047857))))])),
          ]))),
          if (write) Padding(padding: const EdgeInsets.only(top: 4), child: FilledButton.tonalIcon(
            icon: const Icon(Icons.auto_awesome_rounded),
            onPressed: () => _do(() async { await widget.ctx.api.post('store/marketing/plan/generate', {'language': ar ? 'ar' : 'en'}); }),
            label: Text(ar ? 'جهّز خطة الأسبوع الجاي' : 'Plan the coming week'))),
        ]));
      },
    );
  }
}

class AutopilotTab extends StatefulWidget {
  const AutopilotTab({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<AutopilotTab> createState() => _AutopilotTabState();
}

class _AutopilotTabState extends State<AutopilotTab> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;
  int _gen = 0;
  final _target = TextEditingController(), _daily = TextEditingController(), _monthly = TextEditingController(), _step = TextEditingController();
  bool _enabled = false, _filled = false;

  @override
  void dispose() { for (final c in [_target, _daily, _monthly, _step]) { c.dispose(); } super.dispose(); }

  void _fill(Map<String, dynamic> g) {
    if (_filled) return;
    _filled = true;
    _enabled = g['enabled'] == true;
    String m(Object? v) => (v as num? ?? 0) == 0 ? '' : fmtMoney(v as num);
    _target.text = m(g['targetCpcMinor']);
    _daily.text = m(g['dailyCapMinor']);
    _monthly.text = m(g['monthlyCapMinor']);
    _step.text = '${g['maxStepPct'] ?? 20}';
  }

  Future<void> _save() async {
    final ar = S.of(context).ar;
    if (await run(context, () async {
      await widget.ctx.api.put('store/marketing/guardrails', {'enabled': _enabled, 'targetCpcMinor': toMinor(_target.text), 'dailyCapMinor': toMinor(_daily.text), 'monthlyCapMinor': toMinor(_monthly.text), 'maxStepPct': int.tryParse(_step.text) ?? 20});
    }) && mounted) { toast(context, ar ? 'تم الحفظ' : 'Saved'); setState(() { _filled = false; _gen++; }); }
  }

  static const _kinds = {'PAUSE': ('أوقف إعلان', 'Paused'), 'RAISE_BUDGET': ('زوّد الميزانية', 'Raised budget'), 'LOWER_BUDGET': ('قلّل الميزانية', 'Lowered budget'), 'PAUSE_VARIANT': ('أوقف نص', 'Paused a text'), 'KEEP_VARIANT': ('احتفظ بأحسن نص', 'Kept best text')};

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final ar = S.of(context).ar;
    final write = widget.ctx.canWrite('marketing');
    Widget field(TextEditingController c, String label, {String? hint}) => TextField(controller: c, enabled: write, keyboardType: const TextInputType.numberWithOptions(decimal: true), textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: label, helperText: hint, isDense: true));
    return Async<List<dynamic>>(
      key: ValueKey('ap$_gen'),
      load: () async => [await widget.ctx.api.get('store/marketing/guardrails'), await widget.ctx.api.get('store/marketing/ad-actions'), await widget.ctx.api.get('store/marketing/campaigns')],
      builder: (context, d, reload) {
        _fill(d[0] as Map<String, dynamic>);
        final actions = _maps(d[1]);
        final camps = _maps(d[2]).where((c) => c['status'] == 'ACTIVE' || c['status'] == 'PAUSED').toList();
        return RefreshIndicator(onRefresh: () async { setState(() { _filled = false; _gen++; }); }, child: ListView(padding: const EdgeInsets.all(16), children: [
          Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(ar ? 'محسّن الإعلانات' : 'Ad optimizer', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
            const SizedBox(height: 4),
            Text(ar ? 'بيحسّن بس الإعلانات اللي إنت شغّلتها، جوه الحدود دي: بيوقف إعلان تكلفة الضغطة فيه عالية أوي، وبيزوّد إعلان ناجح بخطوات صغيرة، وبيحتفظ بأحسن نص من ٣. ومش بيبدأ إنفاق جديد أبدًا.' : 'Optimizes only the ads you launched, inside these limits: pauses an ad that costs far too much per click, raises a winning ad in small steps, and keeps the best of three texts. It never starts new spending.', style: const TextStyle(color: Color(0xFF475569), height: 1.4)),
            SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'تشغيل المحسّن' : 'Optimizer on'), value: _enabled, onChanged: write ? (v) => setState(() => _enabled = v) : null),
            field(_target, ar ? 'تكلفة الضغطة المقبولة' : 'Cost per click I accept', hint: ar ? 'بعملة متجرك' : 'In your currency'),
            const SizedBox(height: 10),
            Row(children: [Expanded(child: field(_daily, ar ? 'أقصى ميزانية يومية' : 'Daily budget cap')), const SizedBox(width: 10), Expanded(child: field(_monthly, ar ? 'أقصى ميزانية شهرية' : 'Monthly budget cap'))]),
            const SizedBox(height: 10),
            field(_step, ar ? 'أكبر زيادة (٪)' : 'Biggest raise (%)'),
            const SizedBox(height: 8),
            Text(ar ? 'لو الحد صفر، المحسّن عمره ما هيزوّد ميزانية.' : 'A cap of 0 means the optimizer will never raise a budget.', style: const TextStyle(color: Color(0xFFB45309), fontSize: 12.5)),
            if (write) Padding(padding: const EdgeInsets.only(top: 10), child: Row(children: [
              Expanded(child: FilledButton(onPressed: _save, child: Text(ar ? 'حفظ' : 'Save'))),
              const SizedBox(width: 8),
              Expanded(child: OutlinedButton(onPressed: () async { if (await run(context, () async { await widget.ctx.api.post('store/marketing/ad-optimize', {}); }) && mounted) setState(() { _filled = false; _gen++; }); }, child: Text(ar ? 'شغّل دلوقتي' : 'Run now'))),
            ])),
          ]))),
          const SizedBox(height: 12),
          Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(ar ? 'اللي عمله المحسّن' : 'What the optimizer did', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)),
            if (actions.isEmpty) Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'لسه مفيش حاجة.' : 'Nothing yet.', style: const TextStyle(color: Color(0xFF6B7C8D)))),
            for (final a in actions) ListTile(contentPadding: EdgeInsets.zero, dense: true,
              title: Text('${ar ? (_kinds['${a['kind']}']?.$1 ?? a['kind']) : (_kinds['${a['kind']}']?.$2 ?? a['kind'])} • ${a['campaign']}', style: const TextStyle(fontWeight: FontWeight.w700)),
              subtitle: Text('${a['reason']}'),
              trailing: a['undoneAt'] != null ? Text(ar ? 'اتراجع عنه' : 'Undone', style: const TextStyle(fontSize: 12, color: Color(0xFF94A3B8))) : !write ? null : TextButton(onPressed: () async { if (await run(context, () async { await widget.ctx.api.post('store/marketing/ad-actions/${a['id']}/undo', {}); }) && mounted) setState(() { _filled = false; _gen++; }); }, child: Text(ar ? 'تراجع' : 'Undo'))),
          ]))),
          const SizedBox(height: 12),
          Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(ar ? '٣ نصوص لكل إعلان' : 'Three texts per ad', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)),
            const SizedBox(height: 4),
            Text(ar ? 'بيشغّل ٣ نصوص مختلفة لنفس الإعلان. بعد ٣ أيام بيحتفظ بالنص الأرخص في تكلفة الضغطة وبيوقف الباقي.' : 'Runs three different texts for the same ad. After 3 days the one with the lowest cost per click is kept and the others are paused.', style: const TextStyle(color: Color(0xFF475569), fontSize: 12.5)),
            if (camps.isEmpty) Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'لسه مفيش حملات.' : 'No campaigns yet.')),
            for (final c in camps) _Variants(ctx: widget.ctx, c: c),
          ]))),
        ]));
      },
    );
  }
}

class _Variants extends StatefulWidget {
  const _Variants({required this.ctx, required this.c});
  final Ctx ctx;
  final Map<String, dynamic> c;
  @override
  State<_Variants> createState() => _VariantsState();
}

class _VariantsState extends State<_Variants> {
  List<Map<String, dynamic>>? _v;
  bool _open = false;

  Future<void> _load() async { try { final r = await widget.ctx.api.get('store/marketing/campaigns/${widget.c['id']}/variants'); if (mounted) setState(() => _v = _maps(r)); } catch (_) { if (mounted) setState(() => _v = const []); } }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final write = widget.ctx.canWrite('marketing');
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      ListTile(contentPadding: EdgeInsets.zero, dense: true, title: Text('${widget.c['name']}', style: const TextStyle(fontWeight: FontWeight.w700)), trailing: Icon(_open ? Icons.expand_less : Icons.expand_more),
        onTap: () { setState(() => _open = !_open); if (_open && _v == null) _load(); }),
      if (_open && _v != null && _v!.isEmpty && write) Align(alignment: AlignmentDirectional.centerStart, child: FilledButton.tonal(onPressed: () async {
        if (await run(context, () async { await widget.ctx.api.post('store/marketing/campaigns/${widget.c['id']}/variants', {'language': ar ? 'ar' : 'en'}); }) && mounted) _load();
      }, child: Text(ar ? 'ابدأ ٣ نصوص' : 'Start 3 texts'))),
      if (_open) for (final x in _v ?? const <Map<String, dynamic>>[]) Container(
        margin: const EdgeInsets.only(bottom: 8), padding: const EdgeInsets.all(10), decoration: BoxDecoration(color: const Color(0xFFF8FAFC), borderRadius: BorderRadius.circular(12)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [Expanded(child: Text('${x['headline']}', style: const TextStyle(fontWeight: FontWeight.w800))), Text(switch ('${x['status']}') { 'WINNER' => ar ? 'الأفضل' : 'Best', 'PAUSED' => ar ? 'متوقف' : 'Paused', _ => ar ? 'شغال' : 'Running' }, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700))]),
          Text('${x['primaryText']}', style: const TextStyle(color: Color(0xFF475569))),
          if ((x['insights'] as Map?)?['clicks'] != null) Text('${(x['insights'] as Map)['clicks']} · ${(x['insights'] as Map)['spend']}', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
        ]),
      ),
    ]);
  }
}
