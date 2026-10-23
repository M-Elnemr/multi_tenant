import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'channels.dart' show providerMark;
import 'common.dart';
import 'forms.dart';

// Connecting a sales platform or a shipping company without leaving the app: pick the country, pick the company, fill in what it asks for.
// "Log in with ..." (Salla, YouCan, Amazon) and Meta must open the company's own login page in the browser; the lists reload when you come back.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).cast<Map<String, dynamic>>();
const _flags = {'EG': '🇪🇬', 'SA': '🇸🇦', 'AE': '🇦🇪'};
const _countryAr = {'EG': 'مصر', 'SA': 'السعودية', 'AE': 'الإمارات'};
const _countryEn = {'EG': 'Egypt', 'SA': 'Saudi Arabia', 'AE': 'UAE'};

/// Runs [onResume] each time the app comes back to the foreground (after a login in the browser).
class ResumeReload extends StatefulWidget {
  const ResumeReload({super.key, required this.onResume, required this.child});
  final VoidCallback onResume;
  final Widget child;
  @override
  State<ResumeReload> createState() => _ResumeReloadState();
}

class _ResumeReloadState extends State<ResumeReload> with WidgetsBindingObserver {
  @override
  void initState() { super.initState(); WidgetsBinding.instance.addObserver(this); }
  @override
  void dispose() { WidgetsBinding.instance.removeObserver(this); super.dispose(); }
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) { if (state == AppLifecycleState.resumed && mounted) widget.onResume(); }
  @override
  Widget build(BuildContext context) => widget.child;
}

/// Opens the connect screen; true when something was connected.
Future<bool> openConnect(BuildContext context, Ctx ctx, {required bool carrier}) async =>
    await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => ConnectScreen(ctx: ctx, carrier: carrier))) == true;

class ConnectScreen extends StatefulWidget {
  const ConnectScreen({super.key, required this.ctx, required this.carrier});
  final Ctx ctx;
  final bool carrier;
  @override
  State<ConnectScreen> createState() => _ConnectScreenState();
}

class _ConnectScreenState extends State<ConnectScreen> {
  String _country = 'EG';
  bool _connected = false;

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final ctx = widget.ctx;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) { if (!didPop) Navigator.of(context).pop(_connected); },
      child: Scaffold(
        appBar: AppBar(title: Text(widget.carrier ? (ar ? 'اربط شركة شحن' : 'Connect a shipping company') : (ar ? 'اربط منصة' : 'Connect a platform'))),
        body: Column(children: [
          SizedBox(height: 56, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.fromLTRB(12, 10, 12, 4), children: [
            for (final c in const ['EG', 'SA', 'AE']) Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: ChoiceChip(
              label: Text('${_flags[c]} ${ar ? _countryAr[c] : _countryEn[c]}'), selected: _country == c, onSelected: (_) => setState(() => _country = c))),
          ])),
          Expanded(child: Async<List<Map<String, dynamic>>>(
            key: ValueKey(_country),
            load: () async {
              final path = widget.carrier ? 'store/shipping/carriers/catalog' : 'store/integrations/catalog';
              final all = _maps(await ctx.api.get(path, query: {'country': _country}));
              return widget.carrier ? all : all.where((p) => p['kind'] == 'MARKETPLACE' || p['kind'] == 'STORE_PLATFORM').toList();
            },
            builder: (context, items, reload) => ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 24), children: [
              if (items.isEmpty) emptyState(ar ? 'لا توجد شركات متاحة لهذا البلد' : 'Nothing available for this country yet'),
              for (final it in items) _tile(context, it, ar),
            ]),
          )),
        ]),
      ),
    );
  }

  Widget _tile(BuildContext context, Map<String, dynamic> it, bool ar) {
    final soon = it['status'] == 'SOON';
    final beta = it['status'] == 'BETA';
    final help = '${ar ? it['helpAr'] : it['helpEn']}';
    return Card(child: ListTile(
      enabled: !soon,
      leading: providerMark('${it['id']}', '${it['name']}', size: 42),
      title: Row(children: [
        Flexible(child: Text('${it['name']}', style: const TextStyle(fontWeight: FontWeight.w900))),
        if (beta) Padding(padding: const EdgeInsetsDirectional.only(start: 8), child: _badge(ar ? 'تجريبي' : 'Beta', const Color(0xFFB7791F))),
        if (soon) Padding(padding: const EdgeInsetsDirectional.only(start: 8), child: _badge(ar ? 'قريباً' : 'Coming soon', const Color(0xFF6B7C8D))),
      ]),
      subtitle: soon ? null : Text(help, maxLines: 2, overflow: TextOverflow.ellipsis),
      trailing: soon ? null : const Icon(Icons.chevron_right_rounded),
      onTap: soon ? null : () => _form(context, it),
    ));
  }

  Widget _badge(String t, Color c) => Container(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2), decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(8)), child: Text(t, style: TextStyle(color: c, fontSize: 11, fontWeight: FontWeight.w800)));

  Future<void> _form(BuildContext context, Map<String, dynamic> it) async {
    final ar = S.of(context).ar;
    final ctx = widget.ctx;
    final fields = _maps(it['fields']);
    final controllers = {for (final f in fields) '${f['key']}': TextEditingController()};
    final label = TextEditingController();
    final oauth = it['oauth'] == true && !widget.carrier;
    final ok = await formSheet(
      context,
      title: ar ? 'اربط ${it['name']}' : 'Connect ${it['name']}',
      saveLabel: ar ? 'اربط' : 'Connect',
      canSave: () => fields.every((f) => f['optional'] == true || controllers['${f['key']}']!.text.trim().isNotEmpty),
      body: (x, set) => [
        Text('${ar ? it['helpAr'] : it['helpEn']}', style: const TextStyle(height: 1.4)),
        if (it['status'] == 'BETA') Container(
          margin: const EdgeInsets.only(top: 10), padding: const EdgeInsets.all(10), decoration: BoxDecoration(color: const Color(0xFFFFF4DB), borderRadius: BorderRadius.circular(12)),
          child: Text(ar ? 'تجريبي: كتبناه حسب توثيق الشركة ولم نجربه بحساب حقيقي بعد. جرّب بطلب واحد أولاً.' : 'Beta: built from the company\'s documentation and not tried with a real account yet. Test with one order first.', style: const TextStyle(fontSize: 12.5)),
        ),
        if (oauth) ...[
          const SizedBox(height: 12),
          FilledButton.icon(
            icon: const Icon(Icons.login_rounded),
            label: Text(ar ? 'سجّل الدخول عبر ${it['name']}' : 'Log in with ${it['name']}'),
            onPressed: () async {
              Map<String, dynamic>? r;
              if (await run(x, () async { r = await ctx.api.post('store/integrations/oauth/${it['id']}/start', {'country': _country}) as Map<String, dynamic>; }) && r != null) {
                await launch('${r!['url']}');
                if (x.mounted) Navigator.of(x).pop(false);
                if (context.mounted) toast(context, ar ? 'أكمل تسجيل الدخول في المتصفح ثم ارجع هنا' : 'Finish the login in your browser, then come back here');
                if (mounted) setState(() => _connected = true);
              }
            },
          ),
          Padding(padding: const EdgeInsets.only(top: 10), child: Center(child: Text(ar ? 'أو الصق البيانات بنفسك' : 'or paste the details yourself', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)))),
        ],
        const SizedBox(height: 10),
        for (final f in fields) Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: TextField(
            controller: controllers['${f['key']}'],
            obscureText: f['secret'] == true && f['multiline'] != true,
            minLines: f['multiline'] == true ? 4 : 1,
            maxLines: f['multiline'] == true ? 8 : 1,
            autocorrect: false,
            enableSuggestions: false,
            textDirection: TextDirection.ltr,
            onChanged: (_) => set(() {}),
            decoration: InputDecoration(labelText: '${ar ? f['labelAr'] : f['labelEn']}${f['optional'] == true ? (ar ? ' (اختياري)' : ' (optional)') : ''}', hintText: '${f['placeholder'] ?? ''}'),
          ),
        ),
        tf(label, '${ar ? 'اسم الحساب' : 'Account name'} (${ar ? 'اختياري' : 'optional'})'),
        Row(children: [
          const Icon(Icons.lock_outline_rounded, size: 14, color: Color(0xFF6B7C8D)),
          const SizedBox(width: 6),
          Expanded(child: Text(ar ? 'تُحفظ البيانات مشفّرة ولا تظهر مرة أخرى.' : 'Details are stored encrypted and never shown again.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D)))),
        ]),
        if ('${it['docsUrl']}'.isNotEmpty) Align(alignment: AlignmentDirectional.centerStart, child: TextButton(onPressed: () => launch('${it['docsUrl']}'), child: Text(ar ? 'دليل ${it['name']} ↗' : '${it['name']} documentation ↗'))),
      ],
      onSave: () async {
        await ctx.api.post(widget.carrier ? 'store/shipping/carriers' : 'store/integrations/accounts', {
          'provider': it['id'], 'country': _country, if (label.text.trim().isNotEmpty) 'label': label.text.trim(),
          'credentials': {for (final e in controllers.entries) if (e.value.text.trim().isNotEmpty) e.key: e.value.text.trim()},
        });
      },
    );
    for (final c in [...controllers.values, label]) { c.dispose(); }
    if (ok && mounted) {
      setState(() => _connected = true);
      if (context.mounted) { toast(context, ar ? 'تم الربط' : 'Connected'); Navigator.of(context).pop(true); }
    }
  }
}

/// The shipping companies this shop has connected, with a way to add or remove one.
class CarriersScreen extends StatefulWidget {
  const CarriersScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<CarriersScreen> createState() => _CarriersScreenState();
}

class _CarriersScreenState extends State<CarriersScreen> {
  int _gen = 0;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final ctx = widget.ctx;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'شركات الشحن' : 'Shipping companies')),
      body: Async<List<Map<String, dynamic>>>(
        key: ValueKey(_gen),
        load: () async => _maps(await ctx.api.get('store/shipping/carriers')),
        builder: (context, list, reload) => ListView(padding: const EdgeInsets.all(16), children: [
          if (list.isEmpty) emptyState(ar ? 'لم تربط شركة شحن بعد. كل متجر يربط حسابه الخاص مع شركة الشحن.' : 'No shipping company yet. Each shop connects its own account with the company.', icon: Icons.local_shipping_outlined),
          for (final a in list) Card(child: ListTile(
            leading: providerMark('${a['provider']}', '${a['name']}', size: 42),
            title: Text('${a['name']} ${_flags[a['country']] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w900)),
            subtitle: Text(a['status'] == 'ERROR' ? '${a['lastError'] ?? ''}' : '${a['label'] ?? ''}'),
            trailing: ctx.canWrite('shipping') ? IconButton(
              icon: const Icon(Icons.link_off_rounded, color: Color(0xFFD14343)),
              onPressed: () async {
                if (!await confirm(context, ar ? 'فصل ${a['name']}؟ تبقى الشحنات الحالية كما هي.' : 'Disconnect ${a['name']}? Existing shipments stay as they are.') || !context.mounted) return;
                if (await run(context, () => ctx.api.delete('store/shipping/carriers/${a['id']}'))) await reload();
              },
            ) : null,
          )),
          if (ctx.canWrite('shipping')) FilledButton.icon(
            onPressed: () async { if (await openConnect(context, ctx, carrier: true) && mounted) setState(() => _gen++); },
            icon: const Icon(Icons.add_link_rounded), label: Text(ar ? 'اربط شركة شحن' : 'Connect a shipping company')),
        ]),
      ),
    );
  }
}
