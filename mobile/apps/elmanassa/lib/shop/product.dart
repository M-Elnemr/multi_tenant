import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

class ProductPage extends StatefulWidget {
  const ProductPage({super.key, required this.ctx, required this.slug});
  final StoreCtx ctx;
  final String slug;
  @override
  State<ProductPage> createState() => _ProductPageState();
}

class _ProductPageState extends State<ProductPage> {
  Map<String, String> choice = {};
  int qty = 1;
  final pager = PageController();

  @override
  void dispose() { pager.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = widget.ctx;
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(),
      body: Async<Map<String, dynamic>>(
        load: () async => await c.api.get('shop/products/${Uri.encodeComponent(widget.slug)}') as Map<String, dynamic>,
        builder: (context, p, _) {
          final options = ((p['options'] as List?) ?? []).cast<Map<String, dynamic>>();
          for (final o in options) { choice.putIfAbsent(o['name'] as String, () => (o['values'] as List).first as String); }
          final combo = options.map((o) => '${o['name']}=${choice[o['name']]}').join('|');
          final variants = (p['variants'] as List).cast<Map<String, dynamic>>();
          final v = variants.firstWhere((x) => x['comboKey'] == combo, orElse: () => variants.first);
          final inStock = ((v['available'] as num?) ?? 0) > 0;
          final price = (v['priceMinor'] as num).toInt();
          final compare = (v['compareAtPriceMinor'] as num?)?.toInt();
          final discount = compare != null && compare > price ? ((compare - price) * 100 / compare).round() : 0;
          final media = ((p['media'] as List?) ?? []).cast<Map<String, dynamic>>();
          final specs = ((p['specs'] as List?) ?? []).cast<Map<String, dynamic>>();
          final label = options.map((o) => choice[o['name']]).join(' / ');
          return ListView(children: [
            SizedBox(height: 360, child: media.isEmpty ? Container(color: cs.surfaceContainerHighest, child: const Icon(Icons.shopping_bag_outlined, size: 64)) : Stack(children: [
              PageView(controller: pager, children: [for (final m in media) Image.network(imageUrl(c.base, m) ?? '', fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(color: cs.surfaceContainerHighest))]),
              if (discount > 0) PositionedDirectional(start: 12, top: 12, child: pill(context, '-$discount%', bg: const Color(0xFFE5484D))),
            ])),
            Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              if ((p['brand'] as String?)?.isNotEmpty == true) Text((p['brand'] as String).toUpperCase(), style: TextStyle(color: cs.primary, fontWeight: FontWeight.w800, fontSize: 12, letterSpacing: 1)),
              Text(p['name'] as String, style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
              const SizedBox(height: 8),
              Wrap(crossAxisAlignment: WrapCrossAlignment.end, spacing: 10, children: [
                Text(s.money(price, c.tenant.currency), style: Theme.of(context).textTheme.headlineSmall?.copyWith(color: cs.primary, fontWeight: FontWeight.w900)),
                if (discount > 0) Text(s.money(compare, c.tenant.currency), style: const TextStyle(decoration: TextDecoration.lineThrough, color: Colors.grey)),
              ]),
              for (final o in options) ...[
                const SizedBox(height: 14),
                Text('${o['name']}: ${choice[o['name']]}', style: const TextStyle(fontWeight: FontWeight.w800)),
                const SizedBox(height: 6),
                Wrap(spacing: 8, children: [for (final val in (o['values'] as List).cast<String>()) ChoiceChip(label: Text(val), selected: choice[o['name']] == val, onSelected: (_) => setState(() { choice[o['name'] as String] = val; qty = 1; }))]),
              ],
              if (inStock && (v['available'] as num) <= 5) Padding(padding: const EdgeInsets.only(top: 10), child: Text(s.ar ? '🔥 متبقي ${v['available']} فقط' : '🔥 Only ${v['available']} left', style: const TextStyle(color: Colors.orange, fontWeight: FontWeight.w800))),
              const SizedBox(height: 16),
              Row(children: [
                Container(decoration: BoxDecoration(border: Border.all(color: cs.outlineVariant), borderRadius: BorderRadius.circular(30)), child: Row(children: [
                  IconButton(onPressed: qty > 1 ? () => setState(() => qty--) : null, icon: const Icon(Icons.remove)),
                  Text('$qty', style: const TextStyle(fontWeight: FontWeight.w900)),
                  IconButton(onPressed: qty < ((v['available'] as num?) ?? 1).toInt().clamp(1, 99) ? () => setState(() => qty++) : null, icon: const Icon(Icons.add)),
                ])),
                const SizedBox(width: 12),
                Expanded(child: FilledButton.icon(
                  onPressed: inStock && c.isOpen ? () {
                    c.cart.add(CartLine(variantId: v['id'] as String, name: p['name'] as String, label: label, priceMinor: price, qty: qty, image: imageUrl(c.base, media.isEmpty ? null : media.first, variant: 'thumb')));
                    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s.ar ? 'تمت الإضافة إلى السلة' : 'Added to cart')));
                    Navigator.of(context).pop();
                  } : null,
                  icon: const Icon(Icons.add_shopping_cart),
                  label: Text(inStock ? s.addToCart : (s.ar ? 'نفد المخزون' : 'Out of stock')),
                )),
              ]),
              if ((c.p['whatsapp'] as String?)?.isNotEmpty == true) Padding(padding: const EdgeInsets.only(top: 10), child: OutlinedButton.icon(
                onPressed: () => whatsapp(c.p['whatsapp'] as String, '${c.name}\n${p['name']}${label.isEmpty ? '' : ' ($label)'} × $qty'),
                icon: const Icon(Icons.chat, color: Colors.green), label: Text(s.ar ? 'اطلب عبر واتساب' : 'Order on WhatsApp'),
              )),
              const Divider(height: 32),
              if (((p['description'] as String?) ?? '').isNotEmpty) Text(p['description'] as String, style: const TextStyle(height: 1.6)),
              if (specs.isNotEmpty) ...[
                const SizedBox(height: 16),
                Text(s.ar ? 'المواصفات' : 'Specifications', style: const TextStyle(fontWeight: FontWeight.w900)),
                for (final sp in specs) Padding(padding: const EdgeInsets.symmetric(vertical: 6), child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text('${sp['k']}', style: const TextStyle(color: Colors.grey)), Text('${sp['v']}', style: const TextStyle(fontWeight: FontWeight.w700))])),
              ],
              if (((p['sizeGuide'] as String?) ?? '').isNotEmpty) ...[const SizedBox(height: 16), Text(s.ar ? 'دليل المقاسات' : 'Size guide', style: const TextStyle(fontWeight: FontWeight.w900)), Text(p['sizeGuide'] as String)],
            ])),
          ]);
        },
      ),
    );
  }
}
