// ignore_for_file: use_build_context_synchronously, prefer_const_constructors
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

// Developers (API keys, webhooks) and Content (blog, English product names) for the Business app.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

Future<void> _showOnce(BuildContext c, String title, String value, bool ar) => showDialog<void>(context: c, barrierDismissible: false, builder: (d) => AlertDialog(
      title: Text(title),
      content: SelectableText(value, textDirection: TextDirection.ltr, style: const TextStyle(fontFamily: 'monospace')),
      actions: [TextButton(onPressed: () => Navigator.pop(d), child: Text(ar ? 'نسخت' : 'I copied it'))],
    ));

class DevelopersScreen extends StatefulWidget {
  const DevelopersScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<DevelopersScreen> createState() => _DevelopersScreenState();
}

class _DevelopersScreenState extends State<DevelopersScreen> {
  int _gen = 0;
  Future<void> _do(Future<void> Function() f) async { if (await run(context, f) && mounted) setState(() => _gen++); }

  Future<String?> _ask(String title, String label) async {
    final c = TextEditingController();
    final ok = await showDialog<bool>(context: context, builder: (d) => AlertDialog(title: Text(title), content: TextField(controller: c, autofocus: true, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: label)), actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(S.of(context).ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(S.of(context).ar ? 'إنشاء' : 'Create'))]));
    final v = c.text.trim();
    c.dispose();
    return ok == true && v.isNotEmpty ? v : null;
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final api = widget.ctx.api;
    return Async<List<dynamic>>(
      key: ValueKey('dv$_gen'),
      load: () async => [await api.get('store/api-keys'), await api.get('store/webhooks')],
      builder: (context, d, reload) {
        final keys = _maps(d[0]), hooks = _maps(d[1]);
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Text(ar ? 'مفاتيح API' : 'API keys', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          Text(ar ? 'قراءة فقط لأوردراتك ومنتجاتك من أدواتك. المفتاح بيشتغل بس على عنوان متجرك.' : 'Read-only access to your orders and products. A key works only on your shop address.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
          for (final k in keys) ListTile(contentPadding: EdgeInsets.zero, title: Text('${k['name']}'), subtitle: Text('${k['prefix']}…  ${k['revokedAt'] != null ? (ar ? 'ملغي' : 'revoked') : (k['lastUsedAt'] != null ? '${ar ? 'آخر استخدام' : 'last used'} ${fmtDate(k['lastUsedAt'] as String?)}' : (ar ? 'عمره ما اتستخدم' : 'never used'))}', textDirection: TextDirection.ltr), trailing: k['revokedAt'] != null ? null : TextButton(onPressed: () => _do(() async { await api.delete('store/api-keys/${k['id']}'); }), child: Text(ar ? 'إلغاء' : 'Revoke'))),
          TextButton.icon(icon: const Icon(Icons.add), label: Text(ar ? 'مفتاح جديد' : 'New key'), onPressed: () async {
            final n = await _ask(ar ? 'مفتاح جديد' : 'New key', ar ? 'الاسم' : 'Name');
            if (n == null) return;
            Map<String, dynamic>? r;
            if (await run(context, () async { r = await api.post('store/api-keys', {'name': n}) as Map<String, dynamic>; }) && context.mounted) { await _showOnce(context, ar ? 'انسخ المفتاح دلوقتي. مش هيظهر تاني.' : 'Copy this key now. It is not shown again.', '${r!['key']}', ar); setState(() => _gen++); }
          }),
          const Divider(height: 28),
          Text('Webhooks', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          Text(ar ? 'بننده على عنوانك لما أوردر يتعمل أو حالته تتغير. كل نداء موقّع (X-Webhook-Signature).' : 'We call your address when an order is created or changes status. Every call is signed (X-Webhook-Signature).', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
          for (final h in hooks) ListTile(contentPadding: EdgeInsets.zero, title: Text('${h['url']}', textDirection: TextDirection.ltr, style: const TextStyle(fontSize: 13)), subtitle: Text('${h['failed']} ${ar ? 'فشل' : 'failed'}'), trailing: IconButton(icon: const Icon(Icons.delete_outline), onPressed: () => _do(() async { await api.delete('store/webhooks/${h['id']}'); }))),
          TextButton.icon(icon: const Icon(Icons.add), label: Text(ar ? 'ضيف webhook' : 'Add webhook'), onPressed: () async {
            final u = await _ask(ar ? 'webhook جديد' : 'New webhook', 'https://');
            if (u == null) return;
            Map<String, dynamic>? r;
            if (await run(context, () async { r = await api.post('store/webhooks', {'url': u}) as Map<String, dynamic>; }) && context.mounted) { await _showOnce(context, ar ? 'انسخ السر دلوقتي. مش هيظهر تاني.' : 'Copy this secret now. It is not shown again.', '${r!['secret']}', ar); setState(() => _gen++); }
          }),
        ]));
      },
    );
  }
}

class ContentScreen extends StatelessWidget {
  const ContentScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return DefaultTabController(length: 2, child: Column(children: [
      TabBar(tabs: [Tab(text: ar ? 'المدونة' : 'Blog'), Tab(text: ar ? 'الأسماء بالإنجليزي' : 'English names')]),
      Expanded(child: TabBarView(children: [_Blog(ctx: ctx), _English(ctx: ctx)])),
    ]));
  }
}

class _Blog extends StatefulWidget {
  const _Blog({required this.ctx});
  final Ctx ctx;
  @override
  State<_Blog> createState() => _BlogState();
}

class _BlogState extends State<_Blog> {
  int _gen = 0;

  Future<void> _edit(Map<String, dynamic>? p) async {
    final ar = S.of(context).ar;
    final api = widget.ctx.api;
    Map<String, dynamic> full = p ?? {};
    if (p != null) full = await api.get('store/blog/${p['id']}') as Map<String, dynamic>;
    final title = TextEditingController(text: '${full['title'] ?? ''}'), excerpt = TextEditingController(text: '${full['excerpt'] ?? ''}'), body = TextEditingController(text: '${full['body'] ?? ''}');
    var pub = full['published'] == true;
    if (!mounted) return;
    final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, showDragHandle: true, builder: (c) => StatefulBuilder(builder: (c, set) => Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, MediaQuery.of(c).viewInsets.bottom + 16),
      child: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        TextField(controller: title, decoration: InputDecoration(labelText: ar ? 'العنوان' : 'Title')),
        TextField(controller: excerpt, maxLength: 400, decoration: InputDecoration(labelText: ar ? 'ملخص قصير' : 'Short summary')),
        TextField(controller: body, minLines: 6, maxLines: 14, decoration: InputDecoration(labelText: ar ? 'النص (سطر فاضي بين الفقرات)' : 'Text (empty line between paragraphs)')),
        SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'انشر على الموقع' : 'Publish on the website'), value: pub, onChanged: (v) => set(() => pub = v)),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(ar ? 'حفظ' : 'Save')),
      ])),
    )));
    if (ok == true) {
      final payload = {'title': title.text, 'excerpt': excerpt.text, 'body': body.text, 'published': pub};
      if (await run(context, () async { if (p == null) { await api.post('store/blog', payload); } else { await api.put('store/blog/${p['id']}', payload); } }) && mounted) setState(() => _gen++);
    }
    title.dispose(); excerpt.dispose(); body.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Async<List<dynamic>>(
      key: ValueKey('bl$_gen'),
      load: () async => await widget.ctx.api.get('store/blog') as List<dynamic>,
      builder: (context, list, reload) => ListView(padding: const EdgeInsets.all(16), children: [
        FilledButton.icon(icon: const Icon(Icons.add), onPressed: () => _edit(null), label: Text(ar ? 'مقال جديد' : 'New post')),
        const SizedBox(height: 8),
        for (final p in _maps(list)) Card(child: ListTile(title: Text('${p['title']}'), subtitle: Text('/blog/${p['slug']}  ${p['published'] == true ? (ar ? '· منشور' : '· published') : (ar ? '· مسودة' : '· draft')}', textDirection: TextDirection.ltr), onTap: () => _edit(p),
          trailing: IconButton(icon: const Icon(Icons.delete_outline), onPressed: () async { if (await run(context, () async { await widget.ctx.api.delete('store/blog/${p['id']}'); }) && mounted) setState(() => _gen++); }))),
      ]),
    );
  }
}

class _English extends StatefulWidget {
  const _English({required this.ctx});
  final Ctx ctx;
  @override
  State<_English> createState() => _EnglishState();
}

class _EnglishState extends State<_English> {
  int _gen = 0;

  Future<void> _edit(Map<String, dynamic> p) async {
    final ar = S.of(context).ar;
    final n = TextEditingController(text: '${p['nameEn'] ?? ''}'), d = TextEditingController(text: '${p['descriptionEn'] ?? ''}');
    final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, showDragHandle: true, builder: (c) => Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, MediaQuery.of(c).viewInsets.bottom + 16),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('${p['name']}', style: const TextStyle(fontWeight: FontWeight.w900)),
        TextField(controller: n, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ar ? 'الاسم بالإنجليزي' : 'English name')),
        TextField(controller: d, textDirection: TextDirection.ltr, minLines: 2, maxLines: 5, decoration: InputDecoration(labelText: ar ? 'الوصف بالإنجليزي' : 'English description')),
        const SizedBox(height: 8),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(ar ? 'حفظ' : 'Save')),
      ]),
    ));
    if (ok == true && await run(context, () async { await widget.ctx.api.put('store/translations/${p['id']}', {'name': n.text, 'description': d.text}); }) && mounted) setState(() => _gen++);
    n.dispose(); d.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Async<List<dynamic>>(
      key: ValueKey('en$_gen'),
      load: () async => await widget.ctx.api.get('store/translations') as List<dynamic>,
      builder: (context, list, reload) => ListView(padding: const EdgeInsets.all(16), children: [
        Text(ar ? 'العملاء اللي بيقروا متجرك بالإنجليزي بيشوفوا الاسم الإنجليزي لو كتبته.' : 'Shoppers who read your shop in English see the English name where you wrote one.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
        const SizedBox(height: 8),
        OutlinedButton.icon(icon: const Icon(Icons.auto_awesome_rounded), onPressed: () async { Map<String, dynamic>? r; if (await run(context, () async { r = await widget.ctx.api.post('store/translations/draft', {}) as Map<String, dynamic>; }) && mounted) { toast(context, ar ? 'اتجهّز ${r!['drafted']}. راجعهم.' : '${r!['drafted']} drafted. Check them.'); setState(() => _gen++); } }, label: Text(ar ? 'جهّز إنجليزي بالذكاء الاصطناعي' : 'Draft English with AI')),
        for (final p in _maps(list)) Card(child: ListTile(title: Text('${p['name']}', maxLines: 1, overflow: TextOverflow.ellipsis), subtitle: Text('${p['nameEn'] ?? (ar ? 'بدون إنجليزي' : 'No English')}', textDirection: TextDirection.ltr), trailing: const Icon(Icons.edit_outlined), onTap: () => _edit(p))),
      ]),
    );
  }
}
