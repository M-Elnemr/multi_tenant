// ignore_for_file: use_build_context_synchronously, prefer_const_constructors
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

// Developers (API keys, webhooks) for the Business app.

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
