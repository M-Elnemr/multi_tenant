import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'products.dart';

// ---------------------------------------------------------------- Orders

class OrdersScreen extends StatefulWidget {
  const OrdersScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<OrdersScreen> createState() => _OrdersScreenState();
}

class _OrdersScreenState extends State<OrdersScreen> {
  int _unconfirmed = 0;
  @override
  void initState() {
    super.initState();
    widget.ctx.api.get('store/orders/summary').then((r) { if (mounted) setState(() => _unconfirmed = ((r as Map)['unconfirmed'] as num?)?.toInt() ?? 0); }).catchError((_) {});
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final ctx = widget.ctx;
    return PagedList(
      api: ctx.api,
      path: 'store/orders',
      searchHint: ar ? 'رقم الطلب أو اسم العميل أو الهاتف' : 'Order number, customer or phone',
      filterParam: 'status',
      filters: [('', s.all), for (final st in ['REQUESTED', 'PREPARING', 'SHIPPED', 'ARRIVED', 'RETURNED', 'CANCELLED']) (st, ar ? _arStatus[st]! : st[0] + st.substring(1).toLowerCase())],
      header: _unconfirmed > 0 ? Container(margin: const EdgeInsets.fromLTRB(16, 4, 16, 4), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFFF4DD), borderRadius: BorderRadius.circular(14)), child: Row(children: [const Icon(Icons.phone_in_talk_rounded, color: Color(0xFFD98200)), const SizedBox(width: 8), Expanded(child: Text(ar ? '$_unconfirmed طلب لم يتم تأكيده بالاتصال بالعميل' : '$_unconfirmed order(s) not yet confirmed by phone', style: const TextStyle(fontWeight: FontWeight.w700)))])) : null,
      emptyText: ar ? 'لا توجد طلبات' : 'No orders',
      itemBuilder: (c, o, reload) => Card(child: ListTile(
        contentPadding: const EdgeInsets.fromLTRB(16, 8, 12, 8),
        title: Row(children: [Expanded(child: Text('${o['orderNumber']}', textDirection: TextDirection.ltr, textAlign: TextAlign.start, style: const TextStyle(fontWeight: FontWeight.w900))), StatusPill('${o['status']}')]),
        subtitle: Padding(padding: const EdgeInsets.only(top: 4), child: Text('${o['customerNameSnapshot']} · ${fmtDate(o['createdAt'] as String?)}\n${s.money(o['totalMinor'] as num, (o['currency'] as String?) ?? ctx.currency)}${o['confirmedAt'] == null && o['status'] == 'REQUESTED' ? (ar ? '  ·  غير مؤكد' : '  ·  unconfirmed') : ''}')),
        isThreeLine: true,
        onTap: () async {
          await Navigator.of(c).push(MaterialPageRoute(builder: (_) => OrderDetail(ctx: ctx, id: o['id'] as String)));
          await reload();
        },
      )),
    );
  }

  static const _arStatus = {'REQUESTED': 'جديد', 'PREPARING': 'قيد التجهيز', 'SHIPPED': 'تم الشحن', 'ARRIVED': 'تم التسليم', 'RETURNED': 'مرتجع', 'CANCELLED': 'ملغي'};
}

class OrderDetail extends StatelessWidget {
  const OrderDetail({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final String id;
  static const _next = {'PENDING': ['REQUESTED', 'CANCELLED'], 'REQUESTED': ['PREPARING', 'CANCELLED'], 'PREPARING': ['SHIPPED', 'CANCELLED'], 'SHIPPED': ['ARRIVED', 'RETURNED'], 'ARRIVED': ['RETURNED']};
  static const _arTo = {'REQUESTED': 'قبول', 'PREPARING': 'بدء التجهيز', 'SHIPPED': 'تم الشحن', 'ARRIVED': 'تم التسليم', 'RETURNED': 'مرتجع', 'CANCELLED': 'إلغاء'};

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'تفاصيل الطلب' : 'Order')),
      body: Async<Map<String, dynamic>>(
        load: () async => await ctx.api.get('store/orders/$id') as Map<String, dynamic>,
        builder: (context, o, reload) {
          final cur = (o['currency'] as String?) ?? ctx.currency;
          final items = ((o['items'] as List?) ?? const []).cast<Map<String, dynamic>>();
          final next = (_next[o['status']] ?? const <String>[]);
          final phone = o['customerPhoneSnapshot'] as String?;
          final addr = [o['area'], o['landmark'], (o['shippingAddress'] as Map?)?['addressLine1'], (o['shippingAddress'] as Map?)?['city']].where((e) => e != null && '$e'.isNotEmpty).join('، ');
          return ListView(padding: const EdgeInsets.all(16), children: [
            Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [Expanded(child: Text('${o['orderNumber']}', textDirection: TextDirection.ltr, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900))), StatusPill('${o['status']}')]),
              const SizedBox(height: 8),
              kv(ar ? 'العميل' : 'Customer', '${o['customerNameSnapshot']}'),
              kv(s.phone, phone ?? '', ltr: true),
              kv(ar ? 'العنوان' : 'Address', addr),
              kv(ar ? 'الدفع' : 'Payment', '${o['paymentMethod']} · ${o['paymentStatus']}'),
              kv(ar ? 'التاريخ' : 'Date', fmtDate(o['createdAt'] as String?)),
              if ((o['notes'] as String?)?.isNotEmpty == true) kv(ar ? 'ملاحظات' : 'Notes', '${o['notes']}'),
            ]))),
            Row(children: [
              Expanded(child: OutlinedButton.icon(onPressed: () => dial(phone), icon: const Icon(Icons.call_rounded), label: Text(ar ? 'اتصال' : 'Call'))),
              const SizedBox(width: 8),
              Expanded(child: OutlinedButton.icon(onPressed: () => whatsapp(phone, ar ? 'مرحباً ${o['customerNameSnapshot']}، بخصوص طلبك ${o['orderNumber']}' : 'Hello ${o['customerNameSnapshot']}, about your order ${o['orderNumber']}'), icon: const Icon(Icons.chat_rounded), label: const Text('WhatsApp'))),
            ]),
            if (o['confirmedAt'] == null && o['status'] == 'REQUESTED' && ctx.can('order.update_status'))
              Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton.tonalIcon(onPressed: () async { if (await run(context, () => ctx.api.post('store/orders/$id/confirm', {}))) await reload(); }, icon: const Icon(Icons.verified_rounded), label: Text(ar ? 'تم الاتصال وتأكيد الطلب' : 'Customer confirmed by phone'))),
            if (next.isNotEmpty && ctx.can('order.update_status'))
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Wrap(spacing: 8, runSpacing: 8, children: [
                  for (final st in next)
                    FilledButton(
                      style: st == 'CANCELLED' ? FilledButton.styleFrom(backgroundColor: Theme.of(context).colorScheme.error, minimumSize: const Size(0, 44)) : FilledButton.styleFrom(minimumSize: const Size(0, 44), padding: const EdgeInsets.symmetric(horizontal: 18)),
                      onPressed: () async {
                        if (st == 'CANCELLED' || st == 'RETURNED') {
                          final yes = await showDialog<bool>(context: context, builder: (c) => AlertDialog(title: Text(ar ? 'تأكيد' : 'Confirm'), content: Text(ar ? 'هل أنت متأكد؟' : 'Are you sure?'), actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: Text(ar ? 'لا' : 'No')), TextButton(onPressed: () => Navigator.pop(c, true), child: Text(ar ? 'نعم' : 'Yes'))]));
                          if (yes != true) return;
                        }
                        if (context.mounted && await run(context, () => ctx.api.post('store/orders/$id/status', {'status': st}))) await reload();
                      },
                      child: Text(ar ? (_arTo[st] ?? st) : st[0] + st.substring(1).toLowerCase()),
                    ),
                ]),
              ),
            Padding(padding: const EdgeInsets.fromLTRB(4, 18, 4, 6), child: Text(ar ? 'المنتجات' : 'Items', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
            for (final it in items) Card(child: ListTile(title: Text('${it['productNameSnapshot']}${(it['variantNameSnapshot'] as String?)?.isNotEmpty == true ? ' · ${it['variantNameSnapshot']}' : ''}', style: const TextStyle(fontWeight: FontWeight.w700)), subtitle: Text('${it['quantity']} × ${s.money(it['unitPriceMinor'] as num, cur)}'), trailing: Text(s.money(it['lineTotalMinor'] as num, cur), style: const TextStyle(fontWeight: FontWeight.w900)))),
            Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(children: [
              kv(ar ? 'المجموع' : 'Subtotal', s.money(o['subtotalMinor'] as num, cur)),
              if ((o['discountMinor'] as num? ?? 0) > 0) kv(ar ? 'الخصم' : 'Discount', s.money(o['discountMinor'] as num, cur)),
              kv(ar ? 'الشحن' : 'Shipping', s.money(o['shippingMinor'] as num, cur)),
              const Divider(),
              kv(ar ? 'الإجمالي' : 'Total', s.money(o['totalMinor'] as num, cur)),
            ]))),
          ]);
        },
      ),
    );
  }
}

// ---------------------------------------------------------------- Returns / Reviews

class ReturnsScreen extends StatelessWidget {
  const ReturnsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return PagedList(
      api: ctx.api,
      path: 'store/returns',
      filterParam: 'status',
      filters: [('', s.all), ('REQUESTED', ar ? 'جديد' : 'New'), ('APPROVED', ar ? 'مقبول' : 'Approved'), ('REJECTED', ar ? 'مرفوض' : 'Rejected')],
      emptyText: ar ? 'لا توجد مرتجعات' : 'No returns',
      itemBuilder: (c, r, reload) => Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [Expanded(child: Text('${r['orderNumber']}', textDirection: TextDirection.ltr, textAlign: TextAlign.start, style: const TextStyle(fontWeight: FontWeight.w900))), StatusPill('${r['status']}')]),
        const SizedBox(height: 4),
        Text('${r['customerNameSnapshot']} · ${s.money(r['totalMinor'] as num, (r['currency'] as String?) ?? ctx.currency)}', style: const TextStyle(color: Color(0xFF6B7C8D))),
        const SizedBox(height: 6),
        Text('${r['reason']}${(r['details'] as String?)?.isNotEmpty == true ? '\n${r['details']}' : ''}'),
        if (r['status'] == 'REQUESTED') Padding(padding: const EdgeInsets.only(top: 10), child: Row(children: [
          FilledButton(style: FilledButton.styleFrom(minimumSize: const Size(0, 38)), onPressed: () async { if (await run(c, () => ctx.api.post('store/returns/${r['id']}/decision', {'status': 'APPROVED'}))) await reload(); }, child: Text(ar ? 'قبول' : 'Approve')),
          const SizedBox(width: 8),
          OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 38)), onPressed: () async { if (await run(c, () => ctx.api.post('store/returns/${r['id']}/decision', {'status': 'REJECTED'}))) await reload(); }, child: Text(ar ? 'رفض' : 'Reject')),
          const SizedBox(width: 8),
          IconButton(onPressed: () => dial(r['customerPhoneSnapshot'] as String?), icon: const Icon(Icons.call_rounded)),
        ])),
      ]))),
    );
  }
}

class ReviewsScreen extends StatelessWidget {
  const ReviewsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return PagedList(
      api: ctx.api,
      path: 'store/reviews',
      filterParam: 'status',
      filters: [('', s.all), ('PENDING', ar ? 'بانتظار المراجعة' : 'Pending'), ('PUBLISHED', ar ? 'منشور' : 'Published'), ('HIDDEN', ar ? 'مخفي' : 'Hidden')],
      emptyText: ar ? 'لا توجد تقييمات' : 'No reviews',
      itemBuilder: (c, r, reload) => Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Row(children: [for (var i = 1; i <= 5; i++) Icon(i <= ((r['rating'] as num?) ?? 0) ? Icons.star_rounded : Icons.star_border_rounded, color: const Color(0xFFF59E0B), size: 20)]),
          const Spacer(),
          StatusPill('${r['status']}'),
        ]),
        const SizedBox(height: 4),
        Text('${r['productName']}', style: const TextStyle(fontWeight: FontWeight.w800)),
        if ((r['reviewText'] as String?)?.isNotEmpty == true) Padding(padding: const EdgeInsets.only(top: 4), child: Text('${r['reviewText']}')),
        Padding(padding: const EdgeInsets.only(top: 8), child: Wrap(spacing: 8, children: [
          if (r['status'] != 'PUBLISHED') FilledButton(style: FilledButton.styleFrom(minimumSize: const Size(0, 36)), onPressed: () async { if (await run(c, () => ctx.api.post('store/reviews/${r['id']}/status', {'status': 'PUBLISHED'}))) await reload(); }, child: Text(ar ? 'نشر' : 'Publish')),
          if (r['status'] != 'HIDDEN') OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 36)), onPressed: () async { if (await run(c, () => ctx.api.post('store/reviews/${r['id']}/status', {'status': 'HIDDEN'}))) await reload(); }, child: Text(ar ? 'إخفاء' : 'Hide')),
        ])),
      ]))),
    );
  }
}

// ---------------------------------------------------------------- Products / Inventory

class ProductsScreen extends StatelessWidget {
  const ProductsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return Column(children: [
      Expanded(child: PagedList(
        api: ctx.api,
        path: 'store/products',
        searchHint: ar ? 'ابحث عن منتج' : 'Search products',
        emptyText: ar ? 'لا توجد منتجات' : 'No products',
        itemBuilder: (c, p, reload) => Card(child: ListTile(
          leading: CircleAvatar(backgroundColor: Theme.of(c).colorScheme.primary.withValues(alpha: 0.12), child: Icon(Icons.shopping_bag_rounded, color: Theme.of(c).colorScheme.primary)),
          title: Text('${p['name']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text('${s.money(p['minPriceMinor'] as num?, (p['currency'] as String?) ?? ctx.currency)} · ${ar ? 'متاح' : 'in stock'}: ${p['available']}'),
          trailing: StatusPill('${p['status']}'),
          onTap: () async { await Navigator.of(c).push(MaterialPageRoute(builder: (_) => ProductFormPage(ctx: ctx, id: '${p['id']}'))); reload(); },
        )),
      )),
      if (ctx.can('product.create')) Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 8), child: FilledButton.icon(onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ProductFormPage(ctx: ctx))), icon: const Icon(Icons.add_photo_alternate_rounded), label: Text(ar ? 'إضافة منتج جديد' : 'Add a new product'))),
    ]);
  }
}

class InventoryScreen extends StatefulWidget {
  const InventoryScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends State<InventoryScreen> {
  bool _low = false;
  int _n = 0;

  Future<void> _adjust(Map<String, dynamic> row, VoidCallback reload) async {
    final s = S.of(context);
    final ar = s.ar;
    final delta = TextEditingController();
    final reason = TextEditingController();
    String type = 'PURCHASE';
    final ok = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (c) => StatefulBuilder(builder: (c, set) => Padding(
        padding: EdgeInsets.fromLTRB(20, 4, 20, MediaQuery.of(c).viewInsets.bottom + 20),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('${row['productName']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
          Text('${row['sku']} · ${ar ? 'الحالي' : 'on hand'}: ${row['quantityOnHand']}', style: const TextStyle(color: Color(0xFF6B7C8D))),
          const SizedBox(height: 12),
          SegmentedButton<String>(segments: [ButtonSegment(value: 'PURCHASE', label: Text(ar ? 'إضافة' : 'Add')), ButtonSegment(value: 'DAMAGE', label: Text(ar ? 'تالف' : 'Damage')), ButtonSegment(value: 'ADJUSTMENT', label: Text(ar ? 'تسوية' : 'Adjust'))], selected: {type}, onSelectionChanged: (v) => set(() => type = v.first)),
          const SizedBox(height: 12),
          TextField(controller: delta, keyboardType: const TextInputType.numberWithOptions(signed: true), decoration: InputDecoration(labelText: ar ? 'الكمية (للتسوية استخدم - للنقص)' : 'Quantity (use - to reduce on Adjust)')),
          const SizedBox(height: 10),
          TextField(controller: reason, decoration: InputDecoration(labelText: ar ? 'السبب' : 'Reason')),
          const SizedBox(height: 14),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(ar ? 'حفظ' : 'Save')),
        ]),
      )),
    );
    if (ok != true || !mounted) return;
    var d = (int.tryParse(delta.text.trim().replaceAll('-', '')) ?? 0).abs();
    if (d == 0) return;
    if (type == 'DAMAGE' || (type == 'ADJUSTMENT' && delta.text.trim().startsWith('-'))) d = -d;
    if (await run(context, () => widget.ctx.api.post('store/inventory/adjustments', {'branchId': row['branchId'], 'variantId': row['variantId'], 'delta': d, 'type': type, 'reason': reason.text.trim()}))) { _n++; reload(); setState(() {}); }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return PagedList(
      key: ValueKey('$_low-$_n'),
      api: widget.ctx.api,
      path: 'store/inventory',
      query: {'lowStock': '$_low'},
      emptyText: ar ? 'لا توجد أصناف' : 'No stock items',
      header: SwitchListTile(dense: true, title: Text(ar ? 'المخزون المنخفض فقط' : 'Low stock only', style: const TextStyle(fontWeight: FontWeight.w700)), value: _low, onChanged: (v) => setState(() => _low = v)),
      itemBuilder: (c, r, reload) {
        final low = (r['available'] as num) <= (r['lowStockThreshold'] as num);
        return Card(child: ListTile(
          title: Text('${r['productName']}${(r['comboKey'] as String?)?.isNotEmpty == true ? ' · ${r['comboKey']}' : ''}', style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text('${r['sku']} · ${r['branchName']}\n${ar ? 'متاح' : 'Available'}: ${r['available']}  (${ar ? 'محجوز' : 'reserved'} ${r['quantityReserved']})'),
          isThreeLine: true,
          trailing: Row(mainAxisSize: MainAxisSize.min, children: [if (low) const Icon(Icons.warning_amber_rounded, color: Color(0xFFD98200)), if (widget.ctx.can('inventory.adjust')) IconButton(onPressed: () => _adjust(r, reload), icon: const Icon(Icons.edit_rounded))]),
        ));
      },
    );
  }
}

// ---------------------------------------------------------------- Customers / Coupons

class CustomersScreen extends StatelessWidget {
  const CustomersScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return PagedList(
      api: ctx.api,
      path: 'store/customers',
      searchHint: ar ? 'اسم العميل أو الهاتف' : 'Customer name or phone',
      emptyText: ar ? 'لا يوجد عملاء' : 'No customers',
      itemBuilder: (c, u, reload) => Card(child: ListTile(
        leading: CircleAvatar(child: Text(('${u['name']}'.isEmpty ? '?' : '${u['name']}'[0]).toUpperCase())),
        title: Text('${u['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
        subtitle: Text('${u['phone'] ?? ''}\n${u['ordersCount']} ${ar ? 'طلب' : 'orders'} · ${s.money(u['totalSpentMinor'] as num?, ctx.currency)}', textDirection: TextDirection.ltr, textAlign: TextAlign.start),
        isThreeLine: true,
        trailing: Row(mainAxisSize: MainAxisSize.min, children: [IconButton(onPressed: () => dial(u['phone'] as String?), icon: const Icon(Icons.call_rounded)), IconButton(onPressed: () => whatsapp(u['phone'] as String?), icon: const Icon(Icons.chat_rounded))]),
      )),
    );
  }
}

