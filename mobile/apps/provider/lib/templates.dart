import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

// Settings card: pick how the online store looks. Products, cart and checkout are the same in every look.

const _looks = <(String, String, String, String, String, List<Color>)>[
  ('original', 'الأصلي', 'Original', 'بسيط ونظيف لأي متجر', 'Clean and simple, for any store', [Color(0xFFF8FAFC), Color(0xFFC7D2FE)]),
  ('atelier', 'أتيليه', 'Atelier', 'أنيق ومجلاتي، للأزياء', 'Editorial, for fashion', [Color(0xFFFFFFFF), Color(0xFFD6D3D1)]),
  ('souq', 'سوق', 'Souq', 'سوق مزدحم بالعروض، للإلكترونيات والبقالة', 'Dense marketplace, for electronics and groceries', [Color(0xFFE11D48), Color(0xFFFECDD3)]),
  ('midnight', 'منتصف الليل', 'Midnight', 'داكن وجريء، للتقنية وملابس الشباب', 'Dark and bold, for tech and streetwear', [Color(0xFF0B1020), Color(0xFF3B2F7A)]),
  ('bloom', 'بلوم', 'Bloom', 'ناعم وودود، للأطفال والتجميل', 'Soft and friendly, for kids and beauty', [Color(0xFFFFF1F5), Color(0xFFFBCFE8)]),
  ('oud', 'عود', 'Oud', 'أسود وذهبي، للعطور والعبايات', 'Black and gold, for perfume and abayas', [Color(0xFF111111), Color(0xFFD9C28A)]),
];

class TemplateCard extends StatefulWidget {
  const TemplateCard({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<TemplateCard> createState() => _TemplateCardState();
}

class _TemplateCardState extends State<TemplateCard> {
  String? _current;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    widget.ctx.api.get('store/profile').then((p) { if (mounted) setState(() => _current = '${(p as Map)['template'] ?? 'original'}'); }).catchError((_) {});
  }

  Future<void> _use(String id) async {
    final ar = S.of(context).ar;
    setState(() => _busy = true);
    final ok = await run(context, () async { await widget.ctx.api.patch('store/profile', {'template': id}); });
    if (!mounted) return;
    setState(() { _busy = false; if (ok) _current = id; });
    if (ok) toast(context, ar ? 'تم تغيير شكل المتجر' : 'Store look changed');
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final write = widget.ctx.canWrite('settings');
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(ar ? 'شكل المتجر' : 'Store look', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
      const SizedBox(height: 2),
      Text(ar ? 'اختار شكل متجرك. المنتجات والسلة والدفع بتشتغل بنفس الطريقة في كل الأشكال.' : 'Pick how your storefront looks. Products, cart and checkout work the same in every look.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
      const SizedBox(height: 10),
      if (_current == null) const Padding(padding: EdgeInsets.all(12), child: Center(child: CircularProgressIndicator())),
      if (_current != null) for (final l in _looks) Padding(padding: const EdgeInsets.only(bottom: 8), child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: !write || _busy || _current == l.$1 ? null : () => _use(l.$1),
        child: Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(14), border: Border.all(color: _current == l.$1 ? Theme.of(context).colorScheme.primary : const Color(0xFFE2E8F0), width: _current == l.$1 ? 2 : 1)),
          child: Row(children: [
            Container(width: 64, height: 48, decoration: BoxDecoration(borderRadius: BorderRadius.circular(8), gradient: LinearGradient(colors: l.$6, begin: Alignment.topCenter, end: Alignment.bottomCenter), border: Border.all(color: const Color(0xFFE2E8F0)))),
            const SizedBox(width: 12),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(ar ? l.$2 : l.$3, style: const TextStyle(fontWeight: FontWeight.w900)),
              Text(ar ? l.$4 : l.$5, style: const TextStyle(fontSize: 12.5, color: Color(0xFF6B7C8D))),
            ])),
            if (_current == l.$1) Icon(Icons.check_circle_rounded, color: Theme.of(context).colorScheme.primary) else if (write) Text(ar ? 'استخدم' : 'Use', style: TextStyle(fontWeight: FontWeight.w800, color: Theme.of(context).colorScheme.primary)),
          ]),
        ),
      )),
    ])));
  }
}
