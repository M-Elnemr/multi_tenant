// ignore_for_file: use_build_context_synchronously, prefer_const_constructors
import 'package:flutter/material.dart';
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

class PosScreen extends StatefulWidget {
  const PosScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<PosScreen> createState() => _PosScreenState();
}

class _PosScreenState extends State<PosScreen> {
  final _code = TextEditingController(), _discount = TextEditingController(), _phone = TextEditingController(), _name = TextEditingController();
  final _lines = <Map<String, dynamic>>[];
  String _method = 'CASH';
  bool _busy = false;
  Map<String, dynamic>? _done;
  int _gen = 0;

  @override
  void dispose() { for (final c in [_code, _discount, _phone, _name]) { c.dispose(); } super.dispose(); }

  Future<void> _scan(String raw) async {
    final c = raw.trim();
    if (c.isEmpty) return;
    Map<String, dynamic>? f;
    if (await run(context, () async { f = await widget.ctx.api.get('store/pos/lookup', query: {'code': c}) as Map<String, dynamic>; }) && f != null && mounted) {
      setState(() {
        final i = _lines.indexWhere((l) => l['variantId'] == f!['variantId']);
        if (i >= 0) { _lines[i]['qty'] = ((_lines[i]['qty'] as int) + 1).clamp(1, ((f!['available'] as num?) ?? 1).toInt().clamp(1, 1000)); } else { _lines.add({...f!, 'qty': 1}); }
        _code.clear();
      });
    }
  }

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
    final code = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => const _CodeScanPage()));
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
          Expanded(child: TextField(controller: _code, textDirection: TextDirection.ltr, textInputAction: TextInputAction.done, onSubmitted: _scan, decoration: InputDecoration(labelText: ar ? 'امسح أو اكتب الكود' : 'Scan or type the code', isDense: true, suffixIcon: IconButton(icon: const Icon(Icons.add_rounded), onPressed: () => _scan(_code.text))))),
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

class _CodeScanPage extends StatefulWidget {
  const _CodeScanPage();
  @override
  State<_CodeScanPage> createState() => _CodeScanPageState();
}

class _CodeScanPageState extends State<_CodeScanPage> {
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

class ReportsScreen extends StatelessWidget {
  const ReportsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    String m(Object? v) => s.money(v as num?, ctx.currency);
    return Async<List<dynamic>>(
      load: () async => [await ctx.api.get('store/attribution', query: {'days': '30'}), await ctx.api.get('store/reports/margin', query: {'days': '30'}), await ctx.api.get('store/reports/cohorts')],
      builder: (context, d, reload) {
        final a = d[0] as Map<String, dynamic>, g = d[1] as Map<String, dynamic>;
        final cohorts = _maps(d[2]);
        const src = {'WEB': ('الموقع', 'Website'), 'AI_CHAT': ('البائع الذكي', 'AI seller'), 'POS': ('نقطة البيع', 'Point of sale'), 'WHATSAPP': ('واتساب', 'WhatsApp')};
        Widget tile(String t, String v, [String? h]) => Expanded(child: Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(children: [Text(t, textAlign: TextAlign.center, style: const TextStyle(fontSize: 11.5, color: Color(0xFF6B7C8D))), const SizedBox(height: 4), FittedBox(child: Text(v, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))), if (h != null) Text(h, style: const TextStyle(fontSize: 11, color: Color(0xFF6B7C8D)))]))));
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Text(ar ? 'الإيراد جه منين (٣٠ يوم)' : 'Where the revenue came from (30 days)', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          const SizedBox(height: 8),
          Row(children: [tile(ar ? 'باعه البائع الذكي' : 'AI seller', m(a['aiSellerRevenueMinor']), '${a['aiConversations']} ${ar ? 'محادثة' : 'chats'}'), tile(ar ? 'سلات اترجعت' : 'Recovered carts', m(a['recoveredCartsMinor']), '${a['recoveredCarts']}')]),
          Row(children: [tile(ar ? 'وفّرته من أوردرات خطرة' : 'Risky COD saved', m(a['codSavedMinor']), '${a['riskyOrdersLost']} ${ar ? 'ضاعت' : 'lost'}'), tile(ar ? 'أوردرات بنقاط' : 'Orders with points', '${a['loyaltyOrders']}')]),
          for (final x in _maps(a['bySource'])) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text(ar ? (src['${x['source']}']?.$1 ?? '${x['source']}') : (src['${x['source']}']?.$2 ?? '${x['source']}')), subtitle: Text('${x['orders']} ${ar ? 'أوردر' : 'orders'}'), trailing: Text(m(x['revenueMinor']), style: const TextStyle(fontWeight: FontWeight.w800))),
          const Divider(height: 28),
          Text(ar ? 'هامش الربح' : 'Margin', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          Row(children: [tile(ar ? 'الإيراد' : 'Revenue', m(g['totalRevenueMinor'])), tile(ar ? 'الهامش' : 'Margin', m(g['totalMarginMinor'])), tile('%', '${g['marginPercent']}%')]),
          Text(ar ? 'المنتجات بدون سعر تكلفة مش داخلة في الإجمالي.' : 'Products without a cost price are not counted in the totals.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
          for (final p in _maps(g['products']).take(15)) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text('${p['name']}', maxLines: 1, overflow: TextOverflow.ellipsis), subtitle: Text('${p['units']} ${ar ? 'قطعة' : 'units'} · ${m(p['revenueMinor'])}'), trailing: Text(p['marginPercent'] == null ? (ar ? 'بدون تكلفة' : 'no cost') : '${p['marginPercent']}%', style: const TextStyle(fontWeight: FontWeight.w800))),
          const Divider(height: 28),
          Text(ar ? 'رجوع العملاء' : 'Customer cohorts', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          Text(ar ? 'من اللي طلبوا أول مرة في شهر، كام واحد رجع بعد شهر وشهرين وتلاتة.' : 'Of the customers who first ordered in a month, how many came back after 1, 2 and 3 months.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
          for (final c in cohorts) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text(fmtDate(c['month'] as String?, time: false)), subtitle: Text('${c['customers']} ${ar ? 'عميل جديد' : 'new'} · ${c['back1']} / ${c['back2']} / ${c['back3']}')),
        ]));
      },
    );
  }
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
