// ignore_for_file: use_build_context_synchronously, prefer_const_constructors
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:platform_core/platform_core.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'common.dart';
import 'forms.dart';

// Retail tools of the Business app: COD risk labels, tax invoices, point of sale with QR scanning, QR labels, purchasing, reports, store settings cards.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

const _reasons = {
  'RETURNED_BEFORE': ('هذا الرقم رجّع أوردرات قبل كده', 'This phone returned orders before'),
  'CANCELLED_BEFORE': ('هذا الرقم لغى أوردرات قبل كده', 'This phone cancelled orders before'),
  'GOOD_HISTORY': ('سجل استلام كويس', 'Good delivery history'),
  'MANY_TODAY': ('أكتر من أوردر من الرقم ده النهارده', 'Several orders from this phone today'),
  'FIRST_ORDER_BIG': ('أول أوردر وأكبر بكتير من المعتاد', 'First order, much bigger than usual'),
  'LARGE_QUANTITY': ('كمية كبيرة', 'Large quantity'),
  'FAKE_LOOKING_PHONE': ('رقم التليفون شكله وهمي', 'The phone number looks fake'),
  'SHORT_NAME': ('الاسم شكله ناقص', 'Name looks incomplete'),
  'THIN_ADDRESS': ('العنوان قصير أوي', 'Address is too short'),
};

/// A small chip on an order row when cash-on-delivery risk is high or medium.
/// Where an order came from: website, Facebook, Instagram, WhatsApp, the till or elsewhere.
const Map<String, List<String>> kChannels = {
  'WEBSITE': ['الموقع', 'Website'], 'FACEBOOK': ['فيسبوك', 'Facebook'], 'INSTAGRAM': ['إنستجرام', 'Instagram'], 'WHATSAPP': ['واتساب', 'WhatsApp'], 'POS': ['نقطة البيع', 'Point of sale'], 'OTHER': ['أخرى', 'Other'],
};
const Map<String, Color> _channelColors = {'WEBSITE': Color(0xFFE0F2FE), 'FACEBOOK': Color(0xFFDBEAFE), 'INSTAGRAM': Color(0xFFFCE7F3), 'WHATSAPP': Color(0xFFD1FAE5), 'POS': Color(0xFFFEF3C7), 'OTHER': Color(0xFFF1F5F9)};
const Map<String, IconData> _channelIcons = {'WEBSITE': Icons.language_rounded, 'FACEBOOK': Icons.facebook_rounded, 'INSTAGRAM': Icons.photo_camera_rounded, 'WHATSAPP': Icons.chat_rounded, 'POS': Icons.point_of_sale_rounded, 'OTHER': Icons.more_horiz_rounded};

Widget channelChip(Map<String, dynamic> o, bool ar) {
  final c = kChannels.containsKey(o['channel']) ? o['channel'] as String : 'OTHER';
  return Chip(
    visualDensity: VisualDensity.compact, padding: EdgeInsets.zero, backgroundColor: _channelColors[c],
    avatar: Icon(_channelIcons[c], size: 14, color: Colors.black87),
    label: Text(kChannels[c]![ar ? 0 : 1], style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: Colors.black87)),
  );
}

Widget riskChip(Map<String, dynamic> o, bool ar) {
  final level = o['riskLevel'];
  if ((level != 'HIGH' && level != 'MEDIUM') || o['paymentMethod'] != 'CASH_ON_DELIVERY') return const SizedBox.shrink();
  final high = level == 'HIGH';
  final held = o['needsConfirmation'] == true && o['confirmedAt'] == null;
  return Padding(padding: const EdgeInsetsDirectional.only(start: 6), child: Chip(
    visualDensity: VisualDensity.compact, padding: EdgeInsets.zero,
    backgroundColor: high ? const Color(0xFFFEE2E2) : const Color(0xFFFEF3C7),
    label: Text('${high ? (ar ? 'خطورة عالية' : 'High risk') : (ar ? 'خطورة متوسطة' : 'Medium risk')}${held ? (ar ? ' · موقوف' : ' · held') : ''}', style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: high ? const Color(0xFF991B1B) : const Color(0xFF92400E))),
  ));
}

/// The explanation on the order screen: the score, the reasons, and what to do first.
Widget riskBanner(Map<String, dynamic> o, bool ar) {
  final level = o['riskLevel'];
  if ((level != 'HIGH' && level != 'MEDIUM') || o['paymentMethod'] != 'CASH_ON_DELIVERY') return const SizedBox.shrink();
  final high = level == 'HIGH';
  final reasons = '${o['riskReasons'] ?? ''}'.split(',').where((x) => x.isNotEmpty).toList();
  final held = o['needsConfirmation'] == true && o['confirmedAt'] == null;
  return Container(
    margin: const EdgeInsets.only(bottom: 10), padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(color: high ? const Color(0xFFFEE2E2) : const Color(0xFFFEF3C7), borderRadius: BorderRadius.circular(14)),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('${high ? '⚠️ ${ar ? 'احتمال رفض عالي' : 'High risk of refusal'}' : (ar ? 'احتمال رفض متوسط' : 'Medium risk of refusal')} (${o['riskScore']}/100)', style: const TextStyle(fontWeight: FontWeight.w900)),
      for (final r in reasons) Text('• ${ar ? (_reasons[r]?.$1 ?? r) : (_reasons[r]?.$2 ?? r)}'),
      if (held) Padding(padding: const EdgeInsets.only(top: 6), child: Text(ar ? 'اتصل بالعميل وأكّد الأوردر قبل ما تجهزه أو تشحنه.' : 'Call the customer and confirm the order before preparing or shipping it.', style: const TextStyle(fontWeight: FontWeight.w800))),
    ]),
  );
}

/// "Tax invoice" and "Credit note" buttons on the order screen.
Widget taxDocButtons(BuildContext context, Ctx ctx, Map<String, dynamic> o, String orderId, Future<void> Function() reload) {
  if (!ctx.can('invoice.manage')) return const SizedBox.shrink();
  final ar = S.of(context).ar;
  final status = '${o['status']}';
  final canInvoice = status != 'CANCELLED' && status != 'PENDING';
  final canCredit = ['RETURNED', 'REFUNDED', 'CANCELLED'].contains(status);
  if (!canInvoice && !canCredit) return const SizedBox.shrink();
  Future<void> make(String kind) async {
    Map<String, dynamic>? inv;
    if (await run(context, () async { inv = await ctx.api.post('store/orders/$orderId/$kind', {}) as Map<String, dynamic>; }) && inv != null && context.mounted) {
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => InvoiceScreen(ctx: ctx, id: '${inv!['id']}')));
      await reload();
    }
  }
  return Padding(padding: const EdgeInsets.only(top: 8), child: Row(children: [
    if (canInvoice) Expanded(child: OutlinedButton.icon(onPressed: () => make('invoice'), icon: const Icon(Icons.receipt_long_rounded), label: Text(ar ? 'فاتورة ضريبية' : 'Tax invoice'))),
    if (canInvoice && canCredit) const SizedBox(width: 8),
    if (canCredit) Expanded(child: OutlinedButton.icon(onPressed: () => make('credit-note'), icon: const Icon(Icons.undo_rounded), label: Text(ar ? 'إشعار دائن' : 'Credit note'))),
  ]));
}

class InvoiceScreen extends StatelessWidget {
  const InvoiceScreen({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final String id;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'الفاتورة' : 'Invoice')),
      body: Async<Map<String, dynamic>>(
        load: () async => await ctx.api.get('store/invoices/$id') as Map<String, dynamic>,
        builder: (context, v, reload) {
          final cur = '${v['currency']}';
          final credit = v['kind'] == 'CREDIT_NOTE';
          String m(Object? x) => s.money(x as num?, cur);
          return ListView(padding: const EdgeInsets.all(16), children: [
            Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${v['sellerName']}', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
              if ('${v['sellerTaxId']}'.isNotEmpty) Text('${ar ? 'الرقم الضريبي' : 'Tax ID'}: ${v['sellerTaxId']}'),
              Text('${v['sellerAddress']}'),
              const Divider(height: 24),
              Text(credit ? (ar ? 'إشعار دائن' : 'Credit note') : (ar ? 'فاتورة ضريبية' : 'Tax invoice'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
              Text('${v['number']}', textDirection: TextDirection.ltr, style: const TextStyle(fontFamily: 'monospace')),
              Text(fmtDate(v['issuedAt'] as String?)),
              const SizedBox(height: 8),
              Text('${v['buyerName']}  ${v['buyerPhone']}'),
              Text('${v['buyerAddress']}'),
              const Divider(height: 24),
              for (final l in _maps(v['lines'])) Padding(padding: const EdgeInsets.symmetric(vertical: 3), child: Row(children: [
                Expanded(child: Text('${l['quantity']} × ${l['name']}${l['variant'] != null ? ' (${l['variant']})' : ''}')),
                Text(m(l['totalMinor'])),
              ])),
              const Divider(height: 24),
              kv(ar ? 'المجموع' : 'Subtotal', m(v['subtotalMinor'])),
              if ((v['discountMinor'] as num? ?? 0) > 0) kv(ar ? 'الخصم' : 'Discount', '-${m(v['discountMinor'])}'),
              if ((v['shippingMinor'] as num? ?? 0) > 0) kv(ar ? 'التوصيل' : 'Delivery', m(v['shippingMinor'])),
              kv(ar ? 'الإجمالي' : 'Total', '${credit ? '-' : ''}${m(v['totalMinor'])}'),
              if ((v['vatPercent'] as num? ?? 0) > 0) kv('VAT ${v['vatPercent']}%', v['vatIncluded'] == true ? '${ar ? 'شامل' : 'included'} ${m(v['vatMinor'])}' : (ar ? 'غير مضافة' : 'not added')),
              if (v['reason'] != null) kv(ar ? 'السبب' : 'Reason', '${v['reason']}'),
            ]))),
            Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'للطباعة استخدم لوحة التحكم على الموقع.' : 'To print, use the dashboard on the website.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))),
          ]);
        },
      ),
    );
  }
}

class InvoicesScreen extends StatelessWidget {
  const InvoicesScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return PagedList(
      api: ctx.api, path: 'store/invoices', emptyText: ar ? 'لا توجد فواتير بعد. افتح أوردر واضغط فاتورة ضريبية.' : 'No invoices yet. Open an order and press Tax invoice.',
      itemBuilder: (c, i, reload) => Card(child: ListTile(
        title: Text('${i['number']}', textDirection: TextDirection.ltr, textAlign: TextAlign.start, style: const TextStyle(fontWeight: FontWeight.w900)),
        subtitle: Text('${i['buyerName']} · ${fmtDate(i['issuedAt'] as String?, time: false)}${i['kind'] == 'CREDIT_NOTE' ? (ar ? ' · إشعار دائن' : ' · credit note') : ''}'),
        trailing: Text(s.money(i['totalMinor'] as num?, '${i['currency']}'), style: const TextStyle(fontWeight: FontWeight.w800)),
        onTap: () => Navigator.of(c).push(MaterialPageRoute(builder: (_) => InvoiceScreen(ctx: ctx, id: '${i['id']}'))),
      )),
    );
  }
}

// ---- point of sale -------------------------------------------------------------------------------------------------------

/// The till: a Sell tab and, for people who may refund, a Return tab.
class PosScreen extends StatefulWidget {
  const PosScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<PosScreen> createState() => _PosScreenState();
}

class _PosScreenState extends State<PosScreen> {
  int _tab = 0;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final canReturn = widget.ctx.can('refund.create');
    return Column(children: [
      if (canReturn) Padding(padding: const EdgeInsets.fromLTRB(16, 8, 16, 0), child: SegmentedButton<int>(
        segments: [ButtonSegment(value: 0, label: Text(ar ? 'بيع' : 'Sell'), icon: const Icon(Icons.point_of_sale_rounded)), ButtonSegment(value: 1, label: Text(ar ? 'مرتجع' : 'Return'), icon: const Icon(Icons.assignment_return_rounded))],
        selected: {_tab}, onSelectionChanged: (v) => setState(() => _tab = v.first))),
      Expanded(child: _tab == 1 && canReturn ? _ReturnView(ctx: widget.ctx) : _SellView(ctx: widget.ctx)),
    ]);
  }
}

/// A code from a label QR (the product address with ?v=SKU) or a plain barcode / SKU: the part that identifies the product.
String _codeKey(String raw) {
  final c = raw.trim();
  if (c.startsWith('http')) { final v = Uri.tryParse(c)?.queryParameters['v']; if (v != null && v.isNotEmpty) return v; }
  return c;
}

/// A laser (keyboard-wedge) scanner types the code very fast and ends with Enter. This listens for that burst when no text box has focus.
/// Slow typing is ignored. It is removed with the screen.
class _WedgeListener {
  _WedgeListener(this.onCode) { HardwareKeyboard.instance.addHandler(_handle); }
  final void Function(String code) onCode;
  String _buf = '';
  DateTime _last = DateTime.fromMillisecondsSinceEpoch(0);

  bool _typing() => FocusManager.instance.primaryFocus?.context?.findAncestorWidgetOfExactType<EditableText>() != null;

  bool _handle(KeyEvent e) {
    if (e is! KeyDownEvent || _typing()) return false;
    final now = DateTime.now();
    if (e.logicalKey == LogicalKeyboardKey.enter || e.logicalKey == LogicalKeyboardKey.numpadEnter) {
      final code = _buf;
      _buf = '';
      if (code.length >= 4) { onCode(code); return true; }
      return false;
    }
    final ch = e.character;
    if (ch == null || ch.length != 1) return false;
    _buf = now.difference(_last).inMilliseconds > 80 ? ch : _buf + ch;   // a person is slower than 80 ms per key
    _last = now;
    return false;
  }

  void dispose() => HardwareKeyboard.instance.removeHandler(_handle);
}

class _SellView extends StatefulWidget {
  const _SellView({required this.ctx});
  final Ctx ctx;
  @override
  State<_SellView> createState() => _SellViewState();
}

class _SellViewState extends State<_SellView> {
  _WedgeListener? _wedge;
  final _focus = FocusNode();
  final _code = TextEditingController(), _discount = TextEditingController(), _phone = TextEditingController(), _name = TextEditingController();
  final _lines = <Map<String, dynamic>>[];
  String _method = 'CASH';
  bool _busy = false;
  Map<String, dynamic>? _done;
  int _gen = 0;

  @override
  void initState() { super.initState(); _wedge = _WedgeListener((c) { if (mounted && _done == null) _scan(c); }); }

  @override
  void dispose() { _wedge?.dispose(); _focus.dispose(); for (final c in [_code, _discount, _phone, _name]) { c.dispose(); } super.dispose(); }

  /// Adds a product the cashier picked from the list (or found by scanning).
  void _add(Map<String, dynamic> f) {
    setState(() {
      final i = _lines.indexWhere((l) => l['variantId'] == f['variantId']);
      if (i >= 0) { _lines[i]['qty'] = ((_lines[i]['qty'] as int) + 1).clamp(1, ((f['available'] as num?) ?? 1).toInt().clamp(1, 1000)); } else { _lines.add({...f, 'qty': 1}); }
      _code.clear();
    });
  }

  /// Typed text + Enter, or a scanner: an exact barcode / SKU / label address; when nothing matches exactly but exactly one product matches the text, that one.
  Future<void> _submitText(String raw) async {
    final c = _codeKey(raw);
    if (c.isEmpty) return;
    Map<String, dynamic>? f;
    try { f = await widget.ctx.api.get('store/pos/lookup', query: {'code': c}) as Map<String, dynamic>; } catch (_) {
      final r = _maps(await widget.ctx.api.get('store/pos/search', query: {'q': c, 'limit': '2'}));
      if (r.length == 1) f = r.first;
    }
    if (!mounted) return;
    if (f == null) { ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(S.of(context).ar ? 'مفيش منتج بالكود ده' : 'No product matches'))); return; }
    _add(f);
  }

  Future<Iterable<Map<String, dynamic>>> _options(String q) async {
    try { return _maps(await widget.ctx.api.get('store/pos/search', query: {'q': q.trim(), 'limit': '30'})); } catch (_) { return const []; }
  }

  Future<void> _scan(String raw) => _submitText(raw);

  num get _subtotal => _lines.fold<num>(0, (n, l) => n + (l['priceMinor'] as num) * (l['qty'] as int));

  Future<void> _sell() async {
    setState(() => _busy = true);
    final disc = toMinor(_discount.text);
    Map<String, dynamic>? r;
    final ok = await run(context, () async {
      r = await widget.ctx.api.post('store/pos/sales', {'items': [for (final l in _lines) {'variantId': l['variantId'], 'quantity': l['qty']}], 'paymentMethod': _method, if (disc > 0) 'discountMinor': disc, if (_name.text.trim().isNotEmpty) 'customerName': _name.text.trim(), if (_phone.text.trim().isNotEmpty) 'phone': _phone.text.trim()}) as Map<String, dynamic>;
    });
    if (!mounted) return;
    setState(() { _busy = false; if (ok) { _done = {...r!, 'lines': List<Map<String, dynamic>>.from(_lines.map((l) => Map<String, dynamic>.from(l))), 'discount': disc}; _lines.clear(); _discount.clear(); _phone.clear(); _name.clear(); _gen++; } });
  }

  Future<void> _camera() async {
    final code = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => const CodeScanPage()));
    if (code != null) await _scan(code);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final cur = _lines.isEmpty ? widget.ctx.currency : '${_lines.first['currency']}';
    if (_done != null) {
      final d = _done!;
      return ListView(padding: const EdgeInsets.all(16), children: [
        Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const Icon(Icons.check_circle_rounded, color: Color(0xFF16A34A), size: 48),
          Center(child: Text(ar ? 'تمت البيعة' : 'Sale complete', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18))),
          Center(child: Text('${d['orderNumber']}', textDirection: TextDirection.ltr)),
          const Divider(height: 24),
          for (final l in (d['lines'] as List).cast<Map<String, dynamic>>()) Padding(padding: const EdgeInsets.symmetric(vertical: 2), child: Row(children: [Expanded(child: Text('${l['qty']} × ${l['name']}')), Text(s.money((l['priceMinor'] as num) * (l['qty'] as int), '${l['currency']}'))])),
          if ((d['discount'] as int) > 0) kv(ar ? 'الخصم' : 'Discount', '-${s.money(d['discount'] as num, '${d['currency']}')}'),
          kv(ar ? 'الإجمالي' : 'Total', s.money(d['totalMinor'] as num, '${d['currency']}')),
          const SizedBox(height: 12),
          FilledButton(onPressed: () => setState(() => _done = null), child: Text(ar ? 'بيعة جديدة' : 'New sale')),
        ]))),
      ]);
    }
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Row(children: [
          Expanded(child: LayoutBuilder(builder: (context, box) => RawAutocomplete<Map<String, dynamic>>(
            textEditingController: _code, focusNode: _focus,
            optionsBuilder: (v) => _options(v.text),
            displayStringForOption: (o) => '${o['name']}',
            onSelected: (o) { _add(o); _focus.requestFocus(); },
            fieldViewBuilder: (c, ctrl, node, submit) => TextField(controller: ctrl, focusNode: node, textInputAction: TextInputAction.done, onSubmitted: _submitText,
                decoration: InputDecoration(labelText: ar ? 'اكتب اسم المنتج أو الكود، أو امسح الباركود' : 'Type a name or code, or scan the barcode', isDense: true, prefixIcon: const Icon(Icons.search_rounded), suffixIcon: IconButton(icon: const Icon(Icons.arrow_drop_down_rounded), onPressed: node.requestFocus))),
            optionsViewBuilder: (c, onSelected, options) => Align(alignment: AlignmentDirectional.topStart, child: Material(elevation: 6, borderRadius: BorderRadius.circular(14), child: ConstrainedBox(constraints: BoxConstraints(maxHeight: 300, maxWidth: box.maxWidth), child: ListView.builder(
              padding: EdgeInsets.zero, shrinkWrap: true, itemCount: options.length,
              itemBuilder: (c, i) {
                final o = options.elementAt(i);
                final out = ((o['available'] as num?) ?? 0) <= 0;
                return ListTile(dense: true, enabled: true, onTap: () => onSelected(o),
                  title: Text('${o['name']}${o['variantLabel'] != null ? ' · ${o['variantLabel']}' : ''}', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: FontWeight.w700, color: out ? Colors.grey : null)),
                  subtitle: Text('${o['sku'] ?? ''}${o['barcode'] != null ? ' · ${o['barcode']}' : ''}', textDirection: TextDirection.ltr, textAlign: TextAlign.start),
                  trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [Text(S.of(c).money(o['priceMinor'] as num, '${o['currency']}'), style: const TextStyle(fontWeight: FontWeight.w800)), Text('${ar ? 'مخزون' : 'stock'} ${o['available']}', style: const TextStyle(fontSize: 11))]));
              },
            )))),
          ))),
          const SizedBox(width: 8),
          IconButton.filled(onPressed: _camera, icon: const Icon(Icons.qr_code_scanner_rounded)),
        ]),
        const SizedBox(height: 10),
        if (_lines.isEmpty) emptyState(ar ? 'لسه ماتمسحش حاجة.' : 'Nothing scanned yet.', icon: Icons.qr_code_2_rounded),
        for (final l in _lines) Card(child: ListTile(
          title: Text('${l['name']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text('${l['variantLabel'] ?? ''} · ${s.money(l['priceMinor'] as num, '${l['currency']}')} · ${ar ? 'مخزون' : 'stock'} ${l['available']}'),
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [
            IconButton(icon: const Icon(Icons.remove_circle_outline), onPressed: () => setState(() { final q = (l['qty'] as int) - 1; if (q < 1) { _lines.remove(l); } else { l['qty'] = q; } })),
            Text('${l['qty']}', style: const TextStyle(fontWeight: FontWeight.w900)),
            IconButton(icon: const Icon(Icons.add_circle_outline), onPressed: () => setState(() { if ((l['qty'] as int) < ((l['available'] as num?) ?? 1).toInt()) l['qty'] = (l['qty'] as int) + 1; })),
          ]),
        )),
        if (_lines.isNotEmpty) ...[
          const SizedBox(height: 8),
          Row(children: [Expanded(child: TextField(key: ValueKey('d$_gen'), controller: _discount, keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: InputDecoration(labelText: ar ? 'خصم' : 'Discount', isDense: true))), const SizedBox(width: 8),
            Expanded(child: DropdownButtonFormField<String>(initialValue: _method, decoration: const InputDecoration(isDense: true), items: [DropdownMenuItem(value: 'CASH', child: Text(ar ? 'كاش' : 'Cash')), DropdownMenuItem(value: 'CARD_POS', child: Text(ar ? 'ماكينة فيزا' : 'Card machine')), DropdownMenuItem(value: 'TRANSFER', child: Text(ar ? 'تحويل' : 'Transfer'))], onChanged: (v) => setState(() => _method = v ?? 'CASH')))]),
          const SizedBox(height: 8),
          Row(children: [Expanded(child: TextField(controller: _name, decoration: InputDecoration(labelText: ar ? 'اسم العميل (اختياري)' : 'Customer name (optional)', isDense: true))), const SizedBox(width: 8),
            Expanded(child: TextField(controller: _phone, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ar ? 'التليفون (للنقاط)' : 'Phone (for points)', isDense: true)))]),
          const SizedBox(height: 12),
          FilledButton(onPressed: _busy ? null : _sell, child: Text('${ar ? 'حصّل' : 'Charge'} ${s.money((_subtotal - toMinor(_discount.text)).clamp(0, double.infinity), cur)}')),
        ],
        const SizedBox(height: 12),
        Align(alignment: AlignmentDirectional.centerStart, child: TextButton.icon(onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(ar ? 'ملصقات QR' : 'QR labels')), body: LabelsScreen(ctx: widget.ctx)))), icon: const Icon(Icons.qr_code_2_rounded), label: Text(ar ? 'ملصقات QR للمنتجات' : 'Product QR labels'))),
      ]),
    );
  }
}

/// Returns by item: find the order by its number, scan each piece that came back (or tap +), and only those pieces go back to stock and are refunded.
class _ReturnView extends StatefulWidget {
  const _ReturnView({required this.ctx});
  final Ctx ctx;
  @override
  State<_ReturnView> createState() => _ReturnViewState();
}

class _ReturnViewState extends State<_ReturnView> {
  final _q = TextEditingController(), _reason = TextEditingController();
  Map<String, dynamic>? _order;
  final _take = <String, int>{};
  Map<String, dynamic>? _result;
  bool _busy = false;
  _WedgeListener? _wedge;

  @override
  void initState() { super.initState(); _wedge = _WedgeListener(_scanned); }

  @override
  void dispose() { _wedge?.dispose(); _q.dispose(); _reason.dispose(); super.dispose(); }

  List<Map<String, dynamic>> get _items => _maps(_order?['items']);

  void _bump(Map<String, dynamic> l, int by) {
    final id = '${l['orderItemId']}';
    setState(() => _take[id] = ((_take[id] ?? 0) + by).clamp(0, (l['returnable'] as num).toInt()));
  }

  void _scanned(String raw) {
    if (_order == null || !mounted) return;
    final key = _codeKey(raw);
    final ar = S.of(context).ar;
    final l = _items.where((x) => x['barcode'] == key || x['sku'] == key).cast<Map<String, dynamic>?>().firstWhere((_) => true, orElse: () => null);
    String? msg;
    if (l == null) { msg = ar ? 'المنتج ده مش ضمن الطلب' : 'This product is not in the order'; }
    else if ((_take['${l['orderItemId']}'] ?? 0) >= (l['returnable'] as num).toInt()) { msg = ar ? 'كل القطع اتحسبت بالفعل' : 'All pieces are already counted'; }
    else { _bump(l, 1); }
    if (msg != null) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _find() async {
    final q = _q.text.trim();
    if (q.length < 3) return;
    Map<String, dynamic>? o;
    if (await run(context, () async { o = await widget.ctx.api.get('store/pos/returns/order', query: {'q': q}) as Map<String, dynamic>; }) && mounted) {
      setState(() { _order = o; _take.clear(); _result = null; });
    }
  }

  Future<void> _camera() async {
    final code = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => const CodeScanPage()));
    if (code != null) _scanned(code);
  }

  num get _value => _items.fold<num>(0, (n, l) => n + (l['unitMinor'] as num) * (_take['${l['orderItemId']}'] ?? 0));
  bool get _any => _take.values.any((v) => v > 0);

  Future<void> _submit() async {
    setState(() => _busy = true);
    Map<String, dynamic>? r;
    final ok = await run(context, () async {
      r = await widget.ctx.api.post('store/pos/returns', {'orderId': _order!['id'], if (_reason.text.trim().isNotEmpty) 'reason': _reason.text.trim(), 'items': [for (final e in _take.entries) if (e.value > 0) {'orderItemId': e.key, 'quantity': e.value}]}) as Map<String, dynamic>;
    });
    if (!mounted) return;
    setState(() { _busy = false; if (ok) { _result = r; _order = null; _take.clear(); _reason.clear(); _q.clear(); } });
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final cur = '${_order?['currency'] ?? widget.ctx.currency}';
    return ListView(padding: const EdgeInsets.all(16), children: [
      Row(children: [
        Expanded(child: TextField(controller: _q, textDirection: TextDirection.ltr, textInputAction: TextInputAction.search, onSubmitted: (_) => _find(), decoration: InputDecoration(labelText: ar ? 'رقم الطلب (مثال POS-20261024-000001)' : 'Order number (e.g. POS-20261024-000001)', isDense: true))),
        const SizedBox(width: 8),
        IconButton.filled(onPressed: _find, icon: const Icon(Icons.search_rounded)),
      ]),
      if (_result != null) Card(color: const Color(0xFFDCFCE7), child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(ar ? 'تم تسجيل المرتجع. المخزون رجع والإشعار الدائن جاهز.' : 'Return recorded. Stock is back and the credit note is ready.', style: const TextStyle(fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        Text('${ar ? 'المبلغ المسترد' : 'Refunded'}: ${s.money((_result!['refundMinor'] as num?) ?? 0, widget.ctx.currency)}${_result!['orderFullyReturned'] == true ? (ar ? ' · الطلب كله مرتجع' : ' · whole order returned') : ''}'),
        Text('${(_result!['creditNote'] as Map?)?['number'] ?? ''}', textDirection: TextDirection.ltr),
      ]))),
      if (_order != null) ...[
        const SizedBox(height: 10),
        Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [Expanded(child: Text('${_order!['orderNumber']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))), IconButton(onPressed: _camera, icon: const Icon(Icons.qr_code_scanner_rounded))]),
          Text('${_order!['customerNameSnapshot']} · ${s.money(_order!['totalMinor'] as num, cur)}'),
          if (_order!['canReturn'] != true) Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'الإرجاع للطلبات المسلّمة اللي لسه فيها قطع.' : 'Only delivered orders with pieces left can have items returned.', style: const TextStyle(color: Color(0xFFB45309), fontWeight: FontWeight.w700)))
          else Padding(padding: const EdgeInsets.only(top: 6), child: Text(ar ? 'امسح كل قطعة رجعت أو اضغط +' : 'Scan each returned piece, or tap +', style: const TextStyle(color: Color(0xFF6B7C8D)))),
        ]))),
        for (final l in _items) Card(child: ListTile(
          title: Text('${l['name']}${l['variantName'] != null ? ' · ${l['variantName']}' : ''}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text('${l['sku']}${l['barcode'] != null ? ' · ${l['barcode']}' : ''}\n${ar ? 'اتباع' : 'Sold'} ${l['quantity']} · ${ar ? 'رجع' : 'returned'} ${l['returned']} · ${s.money(l['unitMinor'] as num, cur)}', textDirection: TextDirection.ltr, textAlign: TextAlign.start),
          isThreeLine: true,
          trailing: _order!['canReturn'] == true && (l['returnable'] as num) > 0 ? Row(mainAxisSize: MainAxisSize.min, children: [
            IconButton(icon: const Icon(Icons.remove_circle_outline), onPressed: () => _bump(l, -1)),
            Text('${_take['${l['orderItemId']}'] ?? 0}', style: const TextStyle(fontWeight: FontWeight.w900)),
            IconButton(icon: const Icon(Icons.add_circle_outline), onPressed: () => _bump(l, 1)),
          ]) : null,
        )),
        if (_order!['canReturn'] == true) ...[
          const SizedBox(height: 8),
          TextField(controller: _reason, decoration: InputDecoration(labelText: ar ? 'السبب (اختياري)' : 'Reason (optional)', isDense: true)),
          const SizedBox(height: 12),
          FilledButton(onPressed: _busy || !_any ? null : _submit, child: Text('${ar ? 'رجّع واسترد' : 'Return and refund'} ${s.money(_value, cur)}')),
        ],
      ],
    ]);
  }
}

class CodeScanPage extends StatefulWidget {
  const CodeScanPage({super.key});
  @override
  State<CodeScanPage> createState() => CodeScanPageState();
}

class CodeScanPageState extends State<CodeScanPage> {
  final _c = MobileScannerController(formats: const [BarcodeFormat.qrCode, BarcodeFormat.ean13, BarcodeFormat.ean8, BarcodeFormat.code128, BarcodeFormat.upcA], detectionSpeed: DetectionSpeed.noDuplicates);
  bool _done = false;

  @override
  void dispose() { _c.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(backgroundColor: Colors.black, foregroundColor: Colors.white, title: Text(ar ? 'امسح ملصق المنتج' : 'Scan a product label')),
      body: Stack(children: [
        MobileScanner(
          controller: _c,
          onDetect: (cap) { if (_done) return; for (final b in cap.barcodes) { final v = b.rawValue; if (v != null && v.isNotEmpty) { _done = true; Navigator.of(context).pop(v); return; } } },
          errorBuilder: (c, e) => Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(ar ? 'تعذر فتح الكاميرا. اسمح بالوصول للكاميرا من إعدادات الهاتف.' : 'The camera could not open. Allow camera access in the phone settings.', textAlign: TextAlign.center, style: const TextStyle(color: Colors.white)))),
        ),
        Center(child: Container(width: 250, height: 250, decoration: BoxDecoration(border: Border.all(color: Colors.white, width: 3), borderRadius: BorderRadius.circular(24)))),
      ]),
    );
  }
}

/// Every sellable variant with its QR code (the product's web address plus the SKU): tap to enlarge for a customer or to print from the website.
class LabelsScreen extends StatefulWidget {
  const LabelsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<LabelsScreen> createState() => _LabelsScreenState();
}

class _LabelsScreenState extends State<LabelsScreen> {
  String _q = '';
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return Column(children: [
      Padding(padding: const EdgeInsets.all(12), child: TextField(decoration: InputDecoration(prefixIcon: const Icon(Icons.search), hintText: ar ? 'ابحث باسم المنتج أو الكود' : 'Search by name or code', isDense: true), onChanged: (v) => setState(() => _q = v))),
      Expanded(child: Async<List<dynamic>>(
        key: ValueKey(_q),
        load: () async => await widget.ctx.api.get('store/pos/labels', query: {'q': _q}) as List<dynamic>,
        builder: (context, list, reload) {
          final rows = _maps(list);
          if (rows.isEmpty) return emptyState(ar ? 'لا توجد منتجات' : 'No products', icon: Icons.qr_code_2_rounded);
          return ListView(padding: const EdgeInsets.fromLTRB(12, 0, 12, 24), children: [
            for (final l in rows) Card(child: ListTile(
              leading: QrImageView(data: '${l['qr']}', size: 54, padding: EdgeInsets.zero),
              title: Text('${l['name']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text('${l['variantLabel'] ?? ''} ${l['sku']} · ${s.money(l['priceMinor'] as num, '${l['currency']}')}', textDirection: TextDirection.ltr),
              onTap: () => showDialog<void>(context: context, builder: (d) => AlertDialog(
                title: Text('${l['name']}', maxLines: 2),
                content: Column(mainAxisSize: MainAxisSize.min, children: [SizedBox(width: 240, height: 240, child: QrImageView(data: '${l['qr']}', backgroundColor: Colors.white)), const SizedBox(height: 8), Text('${l['sku']}', textDirection: TextDirection.ltr)]),
                actions: [TextButton(onPressed: () => Navigator.pop(d), child: Text(ar ? 'إغلاق' : 'Close'))],
              )),
            )),
          ]);
        },
      )),
    ]);
  }
}

// ---- purchasing ---------------------------------------------------------------------------------------------------------

class PurchasingScreen extends StatefulWidget {
  const PurchasingScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<PurchasingScreen> createState() => _PurchasingScreenState();
}

class _PurchasingScreenState extends State<PurchasingScreen> {
  int _gen = 0;
  Future<void> _do(Future<void> Function() f) async { if (await run(context, f) && mounted) setState(() => _gen++); }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final api = widget.ctx.api;
    return Async<List<dynamic>>(
      key: ValueKey('po$_gen'),
      load: () async => [await api.get('store/purchase-orders'), await api.get('store/suppliers')],
      builder: (context, d, reload) {
        final pos = _maps(d[0]);
        final sups = _maps(d[1]);
        const tone = {'DRAFT': Color(0xFF1D4ED8), 'ORDERED': Color(0xFFB45309), 'RECEIVED': Color(0xFF047857), 'CANCELLED': Color(0xFFB91C1C)};
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          FilledButton.icon(icon: const Icon(Icons.auto_awesome_rounded), onPressed: () => _do(() async { await api.post('store/purchase-orders/draft-low-stock', {}); }), label: Text(ar ? 'جهّز أمر شراء من المخزون القليل' : 'Draft an order from low stock')),
          const SizedBox(height: 12),
          if (pos.isEmpty) emptyState(ar ? 'لا توجد أوامر شراء.' : 'No purchase orders yet.', icon: Icons.shopping_basket_outlined),
          for (final p in pos) Card(child: ExpansionTile(
            title: Row(children: [Expanded(child: Text('${p['number']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w900))), Text(s.money(p['totalMinor'] as num, widget.ctx.currency)), const SizedBox(width: 8), Text('${p['status']}', style: TextStyle(color: tone['${p['status']}'], fontWeight: FontWeight.w800, fontSize: 12))]),
            subtitle: Text('${p['supplierName'] ?? ''} · ${fmtDate(p['createdAt'] as String?, time: false)}'),
            onExpansionChanged: (_) {},
            children: [
              FutureBuilder<dynamic>(future: api.get('store/purchase-orders/${p['id']}'), builder: (c, snap) {
                if (!snap.hasData) return const Padding(padding: EdgeInsets.all(12), child: LinearProgressIndicator());
                final items = _maps((snap.data as Map)['items']);
                return Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  for (final i in items) Text('${i['quantity']} × ${i['productName']}  ${i['sku']}', textDirection: TextDirection.ltr),
                  Wrap(spacing: 8, children: [
                    if (p['status'] == 'DRAFT') OutlinedButton(onPressed: () => _do(() async { await api.post('store/purchase-orders/${p['id']}/ordered', {}); }), child: Text(ar ? 'تم الطلب' : 'Mark ordered')),
                    if (p['status'] == 'DRAFT' || p['status'] == 'ORDERED') FilledButton(onPressed: () => _do(() async { await api.post('store/purchase-orders/${p['id']}/receive', {}); }), child: Text(ar ? 'وصلت: ضيف للمخزون' : 'Received: add to stock')),
                    if (p['status'] == 'DRAFT' || p['status'] == 'ORDERED') TextButton(onPressed: () => _do(() async { await api.post('store/purchase-orders/${p['id']}/cancel', {}); }), child: Text(ar ? 'إلغاء' : 'Cancel')),
                  ]),
                ]));
              }),
            ],
          )),
          const SizedBox(height: 12),
          Text(ar ? 'الموردين' : 'Suppliers', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          for (final x in sups) ListTile(contentPadding: EdgeInsets.zero, title: Text('${x['name']}'), subtitle: Text('${x['phone'] ?? ''}', textDirection: TextDirection.ltr), trailing: IconButton(icon: const Icon(Icons.delete_outline), onPressed: () => _do(() async { await api.delete('store/suppliers/${x['id']}'); }))),
          TextButton.icon(icon: const Icon(Icons.add), label: Text(ar ? 'إضافة مورد' : 'Add supplier'), onPressed: () async {
            final n = TextEditingController(), ph = TextEditingController();
            final ok = await showDialog<bool>(context: context, builder: (dlg) => AlertDialog(
              title: Text(ar ? 'مورد جديد' : 'New supplier'),
              content: Column(mainAxisSize: MainAxisSize.min, children: [TextField(controller: n, decoration: InputDecoration(labelText: ar ? 'الاسم' : 'Name')), TextField(controller: ph, keyboardType: TextInputType.phone, decoration: InputDecoration(labelText: s.phone))]),
              actions: [TextButton(onPressed: () => Navigator.pop(dlg, false), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(dlg, true), child: Text(ar ? 'حفظ' : 'Save'))],
            ));
            if (ok == true) await _do(() async { await api.post('store/suppliers', {'name': n.text.trim(), 'phone': ph.text.trim()}); });
            n.dispose(); ph.dispose();
          }),
        ]));
      },
    );
  }
}

// ---- reports -------------------------------------------------------------------------------------------------------------

/// Report centre: one period for every tab, the previous period beside each total, a revenue chart and the splits by channel, payment, area and product.
class ReportsScreen extends StatefulWidget {
  const ReportsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<ReportsScreen> createState() => _ReportsScreenState();
}

class _ReportsScreenState extends State<ReportsScreen> {
  int _days = 30;
  String _tab = 'overview';

  static const _tabs = {'overview': ['نظرة عامة', 'Overview'], 'sales': ['المبيعات', 'Sales'], 'products': ['المنتجات', 'Products'], 'customers': ['العملاء', 'Customers'], 'operations': ['التشغيل', 'Operations']};
  static const _pay = {'CASH_ON_DELIVERY': ['الدفع عند الاستلام', 'Cash on delivery'], 'CARD': ['بطاقة', 'Card'], 'CASH': ['كاش', 'Cash'], 'CARD_POS': ['فيزا', 'Card machine'], 'TRANSFER': ['تحويل', 'Transfer']};

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final ctx = widget.ctx;
    String m(Object? v) => s.money(v as num?, ctx.currency);
    String pct(num n, num d) => d == 0 ? '-' : '${(n * 100 / d).round()}%';
    String? delta(num cur, num prev) => prev == 0 ? (cur == 0 ? null : (ar ? 'جديد' : 'new')) : '${cur >= prev ? '+' : ''}${((cur - prev) * 100 / prev).round()}%';
    Widget tile(String t, String v, {String? h, bool? up}) => Expanded(child: Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(children: [
          Text(t, textAlign: TextAlign.center, style: const TextStyle(fontSize: 11.5, color: Color(0xFF6B7C8D))),
          const SizedBox(height: 4),
          FittedBox(child: Text(v, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))),
          if (h != null) Text(h, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: up == null ? const Color(0xFF6B7C8D) : (up ? const Color(0xFF15803D) : const Color(0xFFB91C1C)))),
        ]))));
    Widget head(String t) => Padding(padding: const EdgeInsets.only(top: 14, bottom: 6), child: Text(t, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)));
    Widget bars(List<Map<String, dynamic>> rows, String Function(Map<String, dynamic>) label) {
      final max = rows.fold<num>(1, (n, r) => (r['revenueMinor'] as num) > n ? r['revenueMinor'] as num : n);
      if (rows.isEmpty) return Padding(padding: const EdgeInsets.all(8), child: Text(ar ? 'لا توجد مبيعات في الفترة دي لسه.' : 'No sales in this period yet.', style: const TextStyle(color: Color(0xFF6B7C8D))));
      return Column(children: [for (final r in rows) Padding(padding: const EdgeInsets.symmetric(vertical: 5), child: Column(children: [
        Row(children: [Expanded(child: Text(label(r), maxLines: 1, overflow: TextOverflow.ellipsis)), Text(m(r['revenueMinor']), style: const TextStyle(fontWeight: FontWeight.w800)), const SizedBox(width: 6), Text('${r['orders'] ?? r['units']}', style: const TextStyle(fontSize: 11, color: Color(0xFF6B7C8D)))]),
        const SizedBox(height: 3),
        ClipRRect(borderRadius: BorderRadius.circular(6), child: LinearProgressIndicator(value: ((r['revenueMinor'] as num) / max).toDouble().clamp(0.02, 1.0), minHeight: 7, backgroundColor: const Color(0xFFE8EEF4))),
      ]))]);
    }
    String chan(Map<String, dynamic> r) => r['key'] == '-' ? '—' : (kChannels['${r['key']}']?[ar ? 0 : 1] ?? '${r['key']}');
    String payl(Map<String, dynamic> r) => r['key'] == '-' ? '—' : (_pay['${r['key']}']?[ar ? 0 : 1] ?? '${r['key']}');

    return Column(children: [
      SizedBox(height: 52, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.fromLTRB(16, 8, 16, 0), children: [
        for (final e in _tabs.entries) Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: ChoiceChip(label: Text(e.value[ar ? 0 : 1]), selected: _tab == e.key, onSelected: (_) => setState(() => _tab = e.key))),
      ])),
      Padding(padding: const EdgeInsets.fromLTRB(16, 6, 16, 0), child: SegmentedButton<int>(
        segments: [for (final d in [7, 30, 90]) ButtonSegment(value: d, label: Text(ar ? 'آخر $d يوم' : 'Last $d days'))],
        selected: {_days}, onSelectionChanged: (v) => setState(() => _days = v.first))),
      Expanded(child: Async<List<dynamic>>(
        key: ValueKey('r$_days'),
        load: () async => [
          await ctx.api.get('store/reports/overview', query: {'days': '$_days'}),
          await ctx.api.get('store/reports/margin', query: {'days': '$_days'}),
          await ctx.api.get('store/reports/cohorts'),
          await ctx.api.get('store/attribution', query: {'days': '$_days'}),
        ],
        builder: (context, d, reload) {
          final o = d[0] as Map<String, dynamic>, g = d[1] as Map<String, dynamic>, a = d[3] as Map<String, dynamic>;
          final cohorts = _maps(d[2]);
          final cur = o['current'] as Map<String, dynamic>, prev = o['previous'] as Map<String, dynamic>;
          final trend = _maps(o['trend']);
          final ops = o['operations'] as Map<String, dynamic>;
          final mix = o['customerMix'] as Map<String, dynamic>;
          final children = <Widget>[];
          switch (_tab) {
            case 'overview':
              children.addAll([
                Row(children: [
                  tile(ar ? 'الإيراد' : 'Revenue', m(cur['revenueMinor']), h: delta(cur['revenueMinor'] as num, prev['revenueMinor'] as num), up: (cur['revenueMinor'] as num) >= (prev['revenueMinor'] as num)),
                  tile(ar ? 'الطلبات' : 'Orders', '${cur['orders']}', h: delta(cur['orders'] as num, prev['orders'] as num), up: (cur['orders'] as num) >= (prev['orders'] as num)),
                ]),
                Row(children: [
                  tile(ar ? 'متوسط الطلب' : 'Average order', m(cur['aovMinor']), h: delta(cur['aovMinor'] as num, prev['aovMinor'] as num), up: (cur['aovMinor'] as num) >= (prev['aovMinor'] as num)),
                  tile(ar ? 'العملاء' : 'Customers', '${cur['customers']}', h: delta(cur['customers'] as num, prev['customers'] as num), up: (cur['customers'] as num) >= (prev['customers'] as num)),
                ]),
                Text(ar ? 'مقارنة بالفترة اللي قبلها' : 'Compared with the period before', style: const TextStyle(fontSize: 11.5, color: Color(0xFF6B7C8D))),
                head(ar ? 'الإيراد يوميًا' : 'Revenue by day'),
                Card(child: Padding(padding: const EdgeInsets.all(12), child: SizedBox(height: 150, child: CustomPaint(painter: _TrendPainter([for (final t in trend) (t['revenueMinor'] as num).toDouble()], Theme.of(context).colorScheme.primary), size: Size.infinite)))),
                head(ar ? 'الأكثر مبيعًا' : 'Best sellers'),
                bars([for (final p in _maps(o['topProducts'])) {...p, 'orders': '${p['units']} ${ar ? 'قطعة' : 'units'}'}], (r) => '${r['name']}'),
                head(ar ? 'المبيعات حسب القناة' : 'Sales by channel'),
                bars(_maps(o['byChannel']), chan),
              ]);
            case 'sales':
              children.addAll([
                head(ar ? 'المبيعات حسب القناة' : 'Sales by channel'), bars(_maps(o['byChannel']), chan),
                head(ar ? 'المبيعات حسب طريقة الدفع' : 'Sales by payment method'), bars(_maps(o['byPayment']), payl),
                head(ar ? 'المبيعات حسب المحافظة' : 'Sales by governorate'), bars(_maps(o['byGovernorate']), (r) => r['key'] == '-' ? '—' : '${r['key']}'),
              ]);
            case 'products':
              final low = _maps(o['lowStock']);
              children.addAll([
                head(ar ? 'الأكثر مبيعًا' : 'Best sellers'),
                bars([for (final p in _maps(o['topProducts'])) {...p, 'orders': '${p['units']} ${ar ? 'قطعة' : 'units'}'}], (r) => '${r['name']}'),
                head(ar ? 'على وشك النفاد' : 'Running low'),
                if (low.isEmpty) Text(ar ? 'مفيش حاجة على وشك النفاد.' : 'Nothing is running low.', style: const TextStyle(color: Color(0xFF6B7C8D))),
                for (final x in low) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text('${x['name']}', maxLines: 1, overflow: TextOverflow.ellipsis), subtitle: Text('${x['sku']}', textDirection: TextDirection.ltr, textAlign: TextAlign.start), trailing: Text('${x['available']}', style: TextStyle(fontWeight: FontWeight.w900, color: (x['available'] as num) <= 0 ? const Color(0xFFB91C1C) : const Color(0xFFB45309)))),
                head(ar ? 'هامش الربح' : 'Margin'),
                Row(children: [tile(ar ? 'الإيراد' : 'Revenue', m(g['totalRevenueMinor'])), tile(ar ? 'الهامش' : 'Margin', m(g['totalMarginMinor'])), tile('%', '${g['marginPercent']}%')]),
                Text(ar ? 'المنتجات بدون سعر تكلفة مش داخلة في الإجمالي.' : 'Products without a cost price are not counted in the totals.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
                for (final p in _maps(g['products']).take(15)) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text('${p['name']}', maxLines: 1, overflow: TextOverflow.ellipsis), subtitle: Text('${p['units']} ${ar ? 'قطعة' : 'units'} · ${m(p['revenueMinor'])}'), trailing: Text(p['marginPercent'] == null ? (ar ? 'بدون تكلفة' : 'No cost') : '${p['marginPercent']}%', style: const TextStyle(fontWeight: FontWeight.w800))),
              ]);
            case 'customers':
              children.addAll([
                Row(children: [tile(ar ? 'العملاء' : 'Customers', '${cur['customers']}', h: delta(cur['customers'] as num, prev['customers'] as num), up: (cur['customers'] as num) >= (prev['customers'] as num)), tile(ar ? 'جدد' : 'New', '${mix['newCustomers']}'), tile(ar ? 'راجعين' : 'Returning', '${mix['returningCustomers']}', h: pct(mix['returningCustomers'] as num, (mix['newCustomers'] as num) + (mix['returningCustomers'] as num)))]),
                head(ar ? 'رجوع العملاء' : 'Customer cohorts'),
                Text(ar ? 'من اللي طلبوا أول مرة في شهر، كام واحد رجع بعد شهر وشهرين وتلاتة.' : 'Of the customers who first ordered in a month, how many came back after 1, 2 and 3 months.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
                for (final c in cohorts) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text(fmtDate(c['month'] as String?, time: false)), subtitle: Text('${c['customers']} ${ar ? 'عميل جديد' : 'new'} · ${c['back1']} / ${c['back2']} / ${c['back3']}')),
              ]);
            default:
              children.addAll([
                Row(children: [tile(ar ? 'ملغية' : 'Cancelled', '${ops['cancelled']}', h: pct(ops['cancelled'] as num, ops['allOrders'] as num)), tile(ar ? 'مرتجعة' : 'Returned', '${ops['returned']}', h: pct(ops['returned'] as num, ops['allOrders'] as num))]),
                Row(children: [tile(ar ? 'مستردة عند الكاشير' : 'Refunded at till', m(ops['refundedMinor'])), tile(ar ? 'دفع عند الاستلام' : 'Cash on delivery', '${ops['cod']}', h: pct(ops['cod'] as num, ops['allOrders'] as num))]),
                head(ar ? 'الإيراد جه منين' : 'Where the revenue came from'),
                Row(children: [tile(ar ? 'باعه البائع الذكي' : 'AI seller', m(a['aiSellerRevenueMinor']), h: '${a['aiConversations']} ${ar ? 'محادثة' : 'chats'}'), tile(ar ? 'سلات اترجعت' : 'Recovered carts', m(a['recoveredCartsMinor']), h: '${a['recoveredCarts']}')]),
                Row(children: [tile(ar ? 'وفّرته من أوردرات خطرة' : 'Risky COD saved', m(a['codSavedMinor']), h: '${a['riskyOrdersLost']} ${ar ? 'ضاعت' : 'lost'}'), tile(ar ? 'أوردرات بنقاط' : 'Orders with points', '${a['loyaltyOrders']}')]),
              ]);
          }
          return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.fromLTRB(16, 4, 16, 24), children: children));
        },
      )),
    ]);
  }
}

class _TrendPainter extends CustomPainter {
  _TrendPainter(this.points, this.color);
  final List<double> points;
  final Color color;
  @override
  void paint(Canvas canvas, Size size) {
    if (points.isEmpty) return;
    final max = points.fold<double>(1, (n, v) => v > n ? v : n);
    final step = points.length > 1 ? size.width / (points.length - 1) : 0.0;
    final pts = [for (var i = 0; i < points.length; i++) Offset(i * step, size.height - 4 - (points[i] / max) * (size.height - 8))];
    final grid = Paint()..color = const Color(0x14000000)..strokeWidth = 1;
    for (final g in [0.25, 0.5, 0.75]) { canvas.drawLine(Offset(0, size.height * g), Offset(size.width, size.height * g), grid); }
    final path = Path()..moveTo(pts.first.dx, pts.first.dy);
    for (final p in pts.skip(1)) { path.lineTo(p.dx, p.dy); }
    final area = Path.from(path)..lineTo(pts.last.dx, size.height)..lineTo(pts.first.dx, size.height)..close();
    canvas.drawPath(area, Paint()..shader = LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [color.withValues(alpha: 0.28), color.withValues(alpha: 0)]).createShader(Offset.zero & size));
    canvas.drawPath(path, Paint()..color = color..style = PaintingStyle.stroke..strokeWidth = 2.5..strokeJoin = StrokeJoin.round..strokeCap = StrokeCap.round);
  }

  @override
  bool shouldRepaint(_TrendPainter old) => old.points != points || old.color != color;
}

// ---- store settings cards ---------------------------------------------------------------------------------------------

class CodGuardCard extends StatefulWidget {
  const CodGuardCard({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<CodGuardCard> createState() => _CodGuardCardState();
}

class _CodGuardCardState extends State<CodGuardCard> {
  Map<String, dynamic>? _g;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async { try { final r = await widget.ctx.api.get('store/cod-guard') as Map<String, dynamic>; if (mounted) setState(() => _g = r); } catch (_) {} }

  Future<void> _set(Map<String, dynamic> body) async { if (await run(context, () async { await widget.ctx.api.put('store/cod-guard', body); }) && mounted) await _load(); }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final s = S.of(context);
    final g = _g;
    if (g == null) return const SizedBox.shrink();
    final st = g['settings'] as Map<String, dynamic>;
    final write = widget.ctx.can('settings.manage');
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(ar ? 'حارس الدفع عند الاستلام' : 'Cash-on-delivery guard', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
      const SizedBox(height: 4),
      Text(ar ? 'كل أوردر دفع عند الاستلام بياخد درجة خطورة. وممكن توقف الأوردرات الخطرة لحد ما تأكدها.' : 'Every cash-on-delivery order gets a risk score. Risky orders can be held until you confirm them.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(child: _num(ar ? 'خطورة عالية' : 'High risk', '${g['high']}', const Color(0xFFFEE2E2))),
        const SizedBox(width: 6),
        Expanded(child: _num(ar ? 'متوسطة' : 'Medium', '${g['medium']}', const Color(0xFFFEF3C7))),
        const SizedBox(width: 6),
        Expanded(child: _num(ar ? 'وفّرت' : 'Saved', s.money(g['savedMinor'] as num?, widget.ctx.currency), const Color(0xFFD1FAE5))),
      ]),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'أوقف الأوردرات عالية الخطورة' : 'Hold high-risk orders'), subtitle: Text(ar ? 'مش هتتجهز غير بعد ما تأكدها' : 'They cannot be prepared or shipped until you confirm them'), value: st['holdHighRisk'] == true, onChanged: write ? (v) => _set({'holdHighRisk': v}) : null),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'أوقف متوسطة الخطورة كمان' : 'Hold medium-risk orders too'), value: st['holdMediumRisk'] == true, onChanged: write ? (v) => _set({'holdMediumRisk': v}) : null),
    ])));
  }

  Widget _num(String l, String v, Color c) => Container(padding: const EdgeInsets.all(10), decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(12)), child: Column(children: [Text(l, style: const TextStyle(fontSize: 11.5)), FittedBox(child: Text(v, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)))]));
}

class MarketCard extends StatefulWidget {
  const MarketCard({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<MarketCard> createState() => _MarketCardState();
}

class _MarketCardState extends State<MarketCard> {
  Map<String, dynamic>? _m;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async { try { final r = await widget.ctx.api.get('store/market') as Map<String, dynamic>; if (mounted) setState(() => _m = r); } catch (_) {} }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final m = _m;
    if (m == null || !widget.ctx.can('settings.manage')) return const SizedBox.shrink();
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(ar ? 'الدولة' : 'Country', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
      const SizedBox(height: 4),
      Text(ar ? 'بيظبط العملة والضريبة والتوقيت لمصر أو السعودية أو الإمارات.' : 'Sets the currency, VAT and time zone for Egypt, Saudi Arabia or the UAE.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
      const SizedBox(height: 8),
      Wrap(spacing: 8, children: [
        for (final p in _maps(m['presets'])) ChoiceChip(
          label: Text('${ar ? p['nameAr'] : p['nameEn']} · ${p['currency']}'),
          selected: m['country'] == p['country'],
          onSelected: (_) async { if (await run(context, () async { await widget.ctx.api.put('store/market', {'country': p['country']}); }) && context.mounted) { toast(context, ar ? 'تم. أعد فتح التطبيق لتحديث العملة.' : 'Done. Reopen the app to refresh the currency.'); await _load(); } },
        ),
      ]),
      if ((m['productVariants'] as num? ?? 0) > 0) Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'الأسعار اللي دخلتها قبل كده مش بتتحول. غيّرها بعد تغيير الدولة.' : 'Prices you already entered are NOT converted. Re-price after changing the country.', style: const TextStyle(color: Color(0xFFB45309), fontSize: 12.5))),
    ])));
  }
}
