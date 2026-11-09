import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'channels.dart';
import 'common.dart';
import 'theme/templates.dart';
import 'retail.dart';
import 'forms.dart';
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
  String _channel = '';
  /// The "more filters" sheet: payment, discount, risk, total range, source and sort.
  Map<String, String> _more = {};

  Future<void> _openMore() async {
    final ar = AppLocale.isAr;
    final f = Map<String, String>.from(_more);
    final min = TextEditingController(text: f['minTotal'] == null ? '' : '${(int.parse(f['minTotal']!) / 100).round()}');
    final max = TextEditingController(text: f['maxTotal'] == null ? '' : '${(int.parse(f['maxTotal']!) / 100).round()}');
    Widget group(String title, String key, Map<String, String> options, void Function(void Function()) set) => Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF6B7C8D), fontSize: 12)),
            const SizedBox(height: 6),
            Wrap(spacing: 6, runSpacing: 6, children: [for (final o in options.entries) ChoiceChip(label: Text(o.value), selected: f[key] == o.key, showCheckmark: false, onSelected: (v) => set(() => v ? f[key] = o.key : f.remove(key)))]),
          ]),
        );
    final ok = await showModalBottomSheet<bool>(
      context: context, isScrollControlled: true, showDragHandle: true,
      builder: (c) => StatefulBuilder(builder: (c, set) => Padding(
        padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.of(c).viewInsets.bottom + 16),
        child: SingleChildScrollView(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
          Text(ar ? 'فلاتر أكثر' : 'More filters', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
          const SizedBox(height: 12),
          group(ar ? 'الترتيب' : 'Sort', 'sort', ar ? {'NEWEST': 'الأحدث', 'OLDEST': 'الأقدم', 'TOTAL_DESC': 'الأعلى قيمة', 'TOTAL_ASC': 'الأقل قيمة'} : {'NEWEST': 'Newest', 'OLDEST': 'Oldest', 'TOTAL_DESC': 'Highest', 'TOTAL_ASC': 'Lowest'}, set),
          group(ar ? 'طريقة الدفع' : 'Payment', 'paymentMethod', ar ? {'CASH_ON_DELIVERY': 'الدفع عند الاستلام', 'CARD': 'بطاقة'} : {'CASH_ON_DELIVERY': 'Cash on delivery', 'CARD': 'Card'}, set),
          group(ar ? 'حالة الدفع' : 'Payment status', 'paymentStatus', ar ? {'UNPAID': 'غير مدفوع', 'PAID': 'مدفوع', 'REFUNDED': 'مسترد'} : {'UNPAID': 'Unpaid', 'PAID': 'Paid', 'REFUNDED': 'Refunded'}, set),
          group(ar ? 'الخصم' : 'Discount', 'discount', ar ? {'OFFER': 'بعرض', 'COUPON': 'بكوبون', 'ANY': 'بأي خصم', 'NONE': 'بدون خصم'} : {'OFFER': 'Offer', 'COUPON': 'Coupon', 'ANY': 'Any', 'NONE': 'None'}, set),
          group(ar ? 'المخاطرة' : 'Risk', 'risk', ar ? {'HIGH': 'عالية', 'MEDIUM': 'متوسطة', 'LOW': 'منخفضة'} : {'HIGH': 'High', 'MEDIUM': 'Medium', 'LOW': 'Low'}, set),
          group(ar ? 'المصدر' : 'Placed from', 'source', ar ? {'WEB': 'الموقع', 'AI_CHAT': 'البائع الذكي', 'POS': 'نقطة البيع', 'OPEN': 'السوق المفتوح'} : {'WEB': 'Website', 'AI_CHAT': 'AI seller', 'POS': 'POS', 'OPEN': 'Marketplace'}, set),
          Text(ar ? 'قيمة الطلب' : 'Order total', style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF6B7C8D), fontSize: 12)),
          const SizedBox(height: 6),
          Row(children: [
            Expanded(child: TextField(controller: min, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: ar ? 'من' : 'From', isDense: true))),
            const SizedBox(width: 10),
            Expanded(child: TextField(controller: max, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: ar ? 'إلى' : 'To', isDense: true))),
          ]),
          const SizedBox(height: 16),
          Row(children: [
            Expanded(child: OutlinedButton(onPressed: () { f.clear(); min.clear(); max.clear(); set(() {}); }, child: Text(ar ? 'مسح الكل' : 'Clear all'))),
            const SizedBox(width: 10),
            Expanded(child: FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(ar ? 'عرض النتائج' : 'Show results'))),
          ]),
        ])),
      )),
    );
    if (ok != true || !mounted) return;
    final mn = int.tryParse(min.text.trim()), mx = int.tryParse(max.text.trim());
    if (mn != null) { f['minTotal'] = '${mn * 100}'; } else { f.remove('minTotal'); }
    if (mx != null) { f['maxTotal'] = '${mx * 100}'; } else { f.remove('maxTotal'); }
    if (f['sort'] == 'NEWEST') f.remove('sort');
    setState(() => _more = f);
  }
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
      key: ValueKey('orders-$_channel-$_more'),
      api: ctx.api,
      path: 'store/orders',
      query: {if (_channel.isNotEmpty) 'channel': _channel, ..._more},
      dateRange: true,
      extraFilters: [Padding(padding: const EdgeInsets.symmetric(horizontal: 4), child: ActionChip(
        avatar: Icon(Icons.tune_rounded, size: 18, color: _more.isEmpty ? null : Colors.white),
        backgroundColor: _more.isEmpty ? null : Theme.of(context).colorScheme.primary,
        label: Text('${ar ? 'فلاتر أكثر' : 'More filters'}${_more.isEmpty ? '' : ' (${_more.length})'}', style: TextStyle(fontWeight: FontWeight.w800, color: _more.isEmpty ? null : Colors.white)),
        onPressed: _openMore))],
      searchHint: ar ? 'رقم الطلب أو الاسم أو الهاتف أو المنتج' : 'Order no., name, phone or product',
      filterParam: 'status',
      filters: [('', s.all), for (final st in ['REQUESTED', 'PREPARING', 'SHIPPED', 'ARRIVED', 'RETURNED', 'CANCELLED']) (st, ar ? _arStatus[st]! : st[0] + st.substring(1).toLowerCase())],
      header: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(height: 44, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 16), children: [
          for (final e in [MapEntry('', [ar ? 'كل القنوات' : 'All channels', '']), ...kChannels.entries])
            Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: ChoiceChip(label: Text(e.value[ar ? 0 : 1] == '' ? e.value[0] : e.value[ar ? 0 : 1]), selected: _channel == e.key, onSelected: (_) => setState(() => _channel = e.key))),
        ])),
        if (_unconfirmed > 0) Container(margin: const EdgeInsets.fromLTRB(16, 4, 16, 4), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFFF4DD), borderRadius: BorderRadius.circular(14)), child: Row(children: [const Icon(Icons.phone_in_talk_rounded, color: Color(0xFFD98200)), const SizedBox(width: 8), Expanded(child: Text(ar ? '$_unconfirmed طلب لم يتم تأكيده بالاتصال بالعميل' : '$_unconfirmed order(s) not yet confirmed by phone', style: const TextStyle(fontWeight: FontWeight.w700)))])),
      ]),
      emptyText: ar ? 'لا توجد طلبات' : 'No orders',
      itemBuilder: (c, o, reload) {
        final unconfirmed = o['confirmedAt'] == null && o['status'] == 'REQUESTED';
        final risk = riskChip(o, ar);
        return TemplateListCard(
          edge: toneFor('${o['status']}'),
          title: '${o['orderNumber']}',
          titleLtr: true,
          subtitle: '${(kChannels[o['channel']] ?? kChannels['OTHER']!)[ar ? 0 : 1]} · ${o['customerNameSnapshot']} · ${fmtDate(o['createdAt'] as String?)}${unconfirmed ? (ar ? '  ·  غير مؤكد' : '  ·  unconfirmed') : ''}',
          trailing: TemplateListFigure(s.money(o['totalMinor'] as num, (o['currency'] as String?) ?? ctx.currency), pill: StatusPill('${o['status']}')),
          footer: risk is SizedBox ? null : Align(alignment: AlignmentDirectional.centerStart, child: risk),
          onTap: () async {
            await Navigator.of(c).push(MaterialPageRoute(builder: (_) => OrderDetail(ctx: ctx, id: o['id'] as String)));
            await reload();
          },
        );
      },
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
            Container(
              decoration: AppTemplate.of(context).cardDecoration().copyWith(border: Border(top: BorderSide(color: toneFor('${o['status']}'), width: 4))),
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [Expanded(child: Text('${o['orderNumber']}', textDirection: TextDirection.ltr, textAlign: TextAlign.start, style: AppTemplate.of(context).heading(20))), StatusPill('${o['status']}')]),
              if (o['totalMinor'] is num) Padding(padding: const EdgeInsets.only(top: 6, bottom: 2), child: Text(s.money(o['totalMinor'] as num, (o['currency'] as String?) ?? ctx.currency), style: AppTemplate.of(context).heading(24, color: AppTemplate.of(context).isShop ? AppTemplate.of(context).accent : AppTemplate.of(context).ink))),
              Align(alignment: AlignmentDirectional.centerStart, child: channelChip(o, ar)),
              const SizedBox(height: 4),
              kv(ar ? 'العميل' : 'Customer', '${o['customerNameSnapshot']}'),
              kv(s.phone, phone ?? '', ltr: true),
              kv(ar ? 'العنوان' : 'Address', addr),
              kv(ar ? 'الدفع' : 'Payment', '${o['paymentMethod']} · ${o['paymentStatus']}'),
              kv(ar ? 'التاريخ' : 'Date', fmtDate(o['createdAt'] as String?)),
              if ((o['notes'] as String?)?.isNotEmpty == true) kv(ar ? 'ملاحظات' : 'Notes', '${o['notes']}'),
            ]),
            ),
            riskBanner(o, ar),
            Row(children: [
              Expanded(child: OutlinedButton.icon(onPressed: () => dial(phone), icon: const Icon(Icons.call_rounded), label: Text(ar ? 'اتصال' : 'Call'))),
              const SizedBox(width: 8),
              Expanded(child: OutlinedButton.icon(onPressed: () => whatsapp(phone, ar ? 'مرحباً ${o['customerNameSnapshot']}، بخصوص طلبك ${o['orderNumber']}' : 'Hello ${o['customerNameSnapshot']}, about your order ${o['orderNumber']}'), icon: const Icon(Icons.chat_rounded), label: const Text('WhatsApp'))),
            ]),
            taxDocButtons(context, ctx, o, id, reload),
            if (ctx.can('order.update_status') && ctx.canSee('shipping') && ['REQUESTED', 'PREPARING'].contains(o['status']))
              Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton.tonalIcon(
                icon: const Icon(Icons.local_shipping_rounded),
                label: Text(ar ? 'اشحن مع شركة شحن' : 'Ship with a carrier'),
                onPressed: () async { if (await shipSheet(context, ctx, orderId: id)) await reload(); },
              )),
            if (ctx.can('order.update_status') && ['PREPARING', 'SHIPPED', 'ARRIVED'].contains(o['status']))
              Padding(padding: const EdgeInsets.only(top: 8), child: OutlinedButton.icon(
                icon: const Icon(Icons.local_shipping_outlined),
                label: Text(ar ? 'بيانات التتبع والشحن' : 'Courier and tracking'),
                onPressed: () async {
                  final courier = TextEditingController(text: '${o['courierName'] ?? ''}'), number = TextEditingController(text: '${o['trackingNumber'] ?? ''}'), url = TextEditingController(text: '${o['trackingUrl'] ?? ''}');
                  if (await formSheet(context, title: ar ? 'التتبع والشحن' : 'Courier and tracking', body: (x, set) => [tf(courier, ar ? 'شركة الشحن' : 'Courier', hint: 'Bosta, Aramex, Mylerz…'), tf(number, ar ? 'رقم الشحنة' : 'Tracking number', ltr: true), tf(url, ar ? 'رابط التتبع' : 'Tracking link', ltr: true)],
                      onSave: () async { await ctx.api.patch('store/orders/$id/tracking', {'courierName': courier.text.trim(), 'trackingNumber': number.text.trim(), 'trackingUrl': url.text.trim()}); })) {
                    await reload();
                  }
                },
              )),
            if (ctx.can('refund.create') && !['CANCELLED', 'PENDING'].contains(o['status']) && o['paymentStatus'] == 'PAID')
              Padding(padding: const EdgeInsets.only(top: 8), child: OutlinedButton.icon(
                icon: const Icon(Icons.currency_exchange_rounded),
                label: Text(ar ? 'استرداد مبلغ' : 'Refund'),
                onPressed: () async {
                  final amount = TextEditingController(text: fmtMoney(o['totalMinor'] as num?));
                  if (await formSheet(context, title: ar ? 'استرداد مبلغ' : 'Refund', canSave: () => toMinor(amount.text) > 0,
                      body: (x, set) => [tf(amount, '${ar ? 'المبلغ' : 'Amount'} ($cur)', number: true, ltr: true, onChanged: (_) => set(() {}))],
                      onSave: () async { await ctx.api.post('store/orders/$id/refund', {'amountMinor': toMinor(amount.text)}); })) {
                    await reload();
                  }
                },
              )),
            if (ctx.can('order.update_status') && (phone ?? '').isNotEmpty)
              Padding(padding: const EdgeInsets.only(top: 8), child: TextButton.icon(
                icon: const Icon(Icons.block_rounded, color: Color(0xFFD14343)),
                label: Text(ar ? 'حظر هذا الرقم' : 'Block this number', style: const TextStyle(color: Color(0xFFD14343))),
                onPressed: () async {
                  if (!await confirm(context, ar ? 'حظر الرقم من الطلب مستقبلًا؟' : 'Block this number from ordering?') || !context.mounted) return;
                  if (await run(context, () => ctx.api.post('store/phone-flags', {'phone': phone, 'blocked': true})) && context.mounted) toast(context, ar ? 'تم الحظر' : 'Blocked');
                },
              )),
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
            for (final it in items) Card(child: ListTile(title: Text('${it['productNameSnapshot']}${(it['variantNameSnapshot'] as String?)?.isNotEmpty == true ? ' · ${it['variantNameSnapshot']}' : ''}', style: const TextStyle(fontWeight: FontWeight.w700)), subtitle: Text('${it['quantity']} × ${s.money(it['unitPriceMinor'] as num, cur)}${(it['note'] as String?)?.isNotEmpty == true ? '\n📝 ${it['note']}' : ''}'), trailing: Text(s.money(it['lineTotalMinor'] as num, cur), style: const TextStyle(fontWeight: FontWeight.w900)))),
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
      dateRange: true,
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
      dateRange: true,
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
        itemBuilder: (c, p, reload) {
          final t = AppTemplate.of(c);
          final available = (p['available'] as num?) ?? 0;
          return TemplateListCard(
            edge: toneFor('${p['status']}'),
            leading: Container(width: 42, height: 42, decoration: BoxDecoration(color: t.accent.withValues(alpha: t.isClinic ? 0.45 : 0.14), borderRadius: BorderRadius.circular(t.isLawyer ? 8 : 14)), child: Icon(Icons.shopping_bag_rounded, color: t.isShop ? t.accent : t.ink)),
            title: '${p['name']}',
            subtitle: '${ar ? 'متاح' : 'in stock'}: $available',
            trailing: TemplateListFigure(s.money(p['minPriceMinor'] as num?, (p['currency'] as String?) ?? ctx.currency), pill: StatusPill('${p['status']}')),
            footer: available <= 0 ? Text(ar ? 'نفد من المخزون' : 'Out of stock', style: const TextStyle(color: Color(0xFFD14343), fontWeight: FontWeight.w800, fontSize: 12)) : null,
            onTap: () async { await Navigator.of(c).push(MaterialPageRoute(builder: (_) => ProductFormPage(ctx: ctx, id: '${p['id']}'))); reload(); },
          );
        },
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

  /// Add (or set) stock for every colour / size of one product in one sheet.
  Future<void> _adjust(Map<String, dynamic> row, VoidCallback reload) async {
    final ar = AppLocale.isAr;
    final api = widget.ctx.api;
    Map<String, dynamic> d;
    List<Map<String, dynamic>> branches;
    try {
      d = Map<String, dynamic>.from(await api.get('store/products/${row['productId']}') as Map);
      branches = ((await api.get('store/branches') as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    } catch (e) { if (mounted) toast(context, errorText(e), isError: true); return; }
    if (!mounted) return;
    final variants = ((d['variants'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).where((v) => v['status'] == 'ACTIVE').toList();
    String branch = '${row['branchId'] ?? (branches.isEmpty ? '' : branches.first['id'])}';
    String mode = 'add', type = 'PURCHASE';
    final qty = {for (final v in variants) '${v['id']}': TextEditingController()};
    final reason = TextEditingController();
    final all = TextEditingController();
    final fe = FieldErrors();
    int onHand(Map<String, dynamic> v) => (((v['stock'] as List?) ?? const []).cast<Map>().where((x) => '${x['branchId']}' == branch).map((x) => (x['quantityOnHand'] as num).toInt()).firstOrNull) ?? 0;
    final ok = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (c) => StatefulBuilder(builder: (c, set) => Padding(
        padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.of(c).viewInsets.bottom + 16),
        child: SizedBox(height: MediaQuery.of(c).size.height * 0.78, child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('${d['name']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
          const SizedBox(height: 10),
          SegmentedButton<String>(segments: [ButtonSegment(value: 'add', label: Text(ar ? 'إضافة / خصم' : 'Add / remove')), ButtonSegment(value: 'set', label: Text(ar ? 'ضبط العدد' : 'Set count'))], selected: {mode}, onSelectionChanged: (v) => set(() => mode = v.first)),
          const SizedBox(height: 8),
          if (mode == 'add') Wrap(spacing: 6, children: [for (final t in const ['PURCHASE', 'RETURN', 'ADJUSTMENT', 'DAMAGE']) ChoiceChip(label: Text(ar ? {'PURCHASE': 'شراء', 'RETURN': 'مرتجع', 'ADJUSTMENT': 'تسوية', 'DAMAGE': 'تالف'}[t]! : t), selected: type == t, onSelected: (_) => set(() => type = t))]),
          if (branches.length > 1) DropdownButtonFormField<String>(initialValue: branch, decoration: InputDecoration(labelText: ar ? 'الفرع' : 'Branch', isDense: true), items: [for (final b in branches) DropdownMenuItem(value: '${b['id']}', child: Text('${b['name']}'))], onChanged: (v) => set(() => branch = v ?? branch)),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: TextField(controller: all, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: mode == 'add' ? (ar ? 'نفس الكمية للكل' : 'Same quantity for all') : (ar ? 'اضبط الكل على' : 'Set all to'), isDense: true))),
            const SizedBox(width: 8),
            OutlinedButton(onPressed: () => set(() { for (final q in qty.values) { q.text = all.text.trim(); } }), child: Text(ar ? 'تطبيق' : 'Apply')),
          ]),
          const SizedBox(height: 8),
          Expanded(child: ListView(children: [
            for (var i = 0; i < variants.length; i++) Builder(builder: (_) {
              final v = variants[i];
              final q = qty['${v['id']}']!;
              final now = onHand(v);
              final n = int.tryParse(q.text.trim()) ?? 0;
              final after = mode == 'set' ? (q.text.trim().isEmpty ? now : n) : now + (type == 'DAMAGE' ? -n : n);
              return Container(
                key: fe.key('lines[$i]'),
                margin: const EdgeInsets.only(bottom: 8), padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(border: Border.all(color: fe.under('lines[$i]') != null ? const Color(0xFFD14343) : Theme.of(c).dividerColor), borderRadius: BorderRadius.circular(14)),
                child: Row(children: [
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(comboLabel(v['comboKey']).isEmpty ? '${v['sku']}' : comboLabel(v['comboKey']), style: const TextStyle(fontWeight: FontWeight.w800)),
                    Text('${ar ? 'الآن' : 'Now'} $now → $after', style: TextStyle(fontSize: 12, color: after < 0 ? const Color(0xFFD14343) : const Color(0xFF1E9E5A), fontWeight: FontWeight.w700)),
                    if (fe.under('lines[$i]') != null) Text(fe.under('lines[$i]')!, style: const TextStyle(fontSize: 12, color: Color(0xFFD14343))),
                  ])),
                  IconButton.outlined(visualDensity: VisualDensity.compact, onPressed: () => set(() => q.text = '${(n - 1).clamp(0, 99999)}'), icon: const Icon(Icons.remove_rounded, size: 18)),
                  SizedBox(width: 62, child: TextField(controller: q, textAlign: TextAlign.center, keyboardType: TextInputType.number, onChanged: (_) => set(() {}), decoration: InputDecoration(isDense: true, hintText: mode == 'add' ? '+0' : '$now'))),
                  IconButton.outlined(visualDensity: VisualDensity.compact, onPressed: () => set(() => q.text = '${n + 1}'), icon: const Icon(Icons.add_rounded, size: 18)),
                ]),
              );
            }),
          ])),
          TextField(controller: reason, decoration: InputDecoration(labelText: ar ? 'السبب (اختياري)' : 'Reason (optional)', isDense: true)),
          const SizedBox(height: 10),
          FilledButton(onPressed: () async {
            final lines = <Map<String, dynamic>>[];
            for (final v in variants) {
              final t = qty['${v['id']}']!.text.trim();
              if (t.isEmpty) continue;
              final n = int.tryParse(t) ?? 0;
              lines.add(mode == 'set' ? {'branchId': branch, 'variantId': v['id'], 'setTo': n, 'reason': reason.text.trim()} : {'branchId': branch, 'variantId': v['id'], 'delta': type == 'DAMAGE' ? -n : n, 'type': type, 'reason': reason.text.trim()});
            }
            if (lines.isEmpty) { toast(c, ar ? 'اكتب كمية لاختيار واحد على الأقل' : 'Enter a quantity for at least one option', isError: true); return; }
            if (await runForm(c, fe, () => api.post('store/inventory/bulk', lines)) && c.mounted) Navigator.pop(c, true);
            set(() {});
          }, child: Text(ar ? 'حفظ' : 'Save')),
        ])),
      )),
    );
    if (ok == true && mounted) { _n++; reload(); setState(() {}); toast(context, ar ? 'تم تحديث المخزون' : 'Stock updated'); }
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
          title: Text('${r['productName']}${(r['comboKey'] as String?)?.isNotEmpty == true ? ' · ${comboLabel(r['comboKey'])}' : ''}', style: const TextStyle(fontWeight: FontWeight.w800)),
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
      dateRange: true,
      dateLabel: ar ? 'تاريخ الانضمام' : 'Joined',
      searchHint: ar ? 'اسم العميل أو الهاتف' : 'Customer name or phone',
      emptyText: ar ? 'لا يوجد عملاء' : 'No customers',
      itemBuilder: (c, u, reload) => Card(child: ListTile(
        onTap: () => Navigator.of(c).push(MaterialPageRoute(builder: (_) => CustomerDetail(ctx: ctx, customer: u))),
        leading: CircleAvatar(child: Text(('${u['name']}'.isEmpty ? '?' : '${u['name']}'[0]).toUpperCase())),
        title: Text('${u['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
        subtitle: Text('${localPhone(u['phone'])}\n${u['ordersCount']} ${ar ? 'طلب' : 'orders'} · ${s.money(u['totalSpentMinor'] as num?, ctx.currency)}', textDirection: TextDirection.ltr, textAlign: TextAlign.start),
        isThreeLine: true,
        trailing: Row(mainAxisSize: MainAxisSize.min, children: [IconButton(onPressed: () => dial(u['phone'] as String?), icon: const Icon(Icons.call_rounded)), IconButton(onPressed: () => whatsapp(u['phone'] as String?), icon: const Icon(Icons.chat_rounded))]),
      )),
    );
  }
}

/// One client of the shop: contact details, spending, their orders, and one-tap call / WhatsApp.
class CustomerDetail extends StatelessWidget {
  const CustomerDetail({super.key, required this.ctx, required this.customer});
  final Ctx ctx;
  final Map<String, dynamic> customer;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final c = customer;
    final phone = c['phone'] as String?;
    final addr = c['address'] is Map ? (c['address'] as Map) : const {};
    final address = [addr['addressLine1'], addr['district'], addr['city']].where((e) => e != null && '$e'.isNotEmpty).join('، ');
    return Scaffold(
      appBar: AppBar(title: Text('${c['name']}')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          kv(ar ? 'الهاتف' : 'Phone', phone ?? '', ltr: true),
          if ((c['email'] ?? '').toString().isNotEmpty) kv(ar ? 'البريد' : 'Email', '${c['email']}', ltr: true),
          if (address.isNotEmpty) kv(ar ? 'العنوان' : 'Address', address),
          kv(ar ? 'رقم العميل' : 'Customer no.', '${c['customerNumber'] ?? ''}', ltr: true),
          kv(ar ? 'عميل منذ' : 'Customer since', fmtDate(c['createdAt'] as String?, time: false)),
          kv(ar ? 'الطلبات' : 'Orders', '${c['ordersCount']}'),
          kv(ar ? 'إجمالي المشتريات' : 'Total spent', s.money(c['totalSpentMinor'] as num?, ctx.currency)),
          _GenderRow(ctx: ctx, customer: c),
          if (phone != null && phone.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 10), child: Row(children: [
            Expanded(child: FilledButton.icon(onPressed: () => dial(phone), icon: const Icon(Icons.call_rounded), label: Text(ar ? 'اتصال' : 'Call'))),
            const SizedBox(width: 10),
            Expanded(child: FilledButton.icon(style: FilledButton.styleFrom(backgroundColor: const Color(0xFF1E9E5A)), onPressed: () => whatsapp(phone, ar ? 'مرحبًا ${c['name']}' : 'Hello ${c['name']}'), icon: const Icon(Icons.chat_rounded), label: const Text('WhatsApp'))),
          ])),
        ]))),
        sectionTitle(ar ? 'طلبات العميل' : 'Orders'),
        Async<List<dynamic>>(
          load: () async => await ctx.api.get('store/customers/${c['id']}/orders') as List<dynamic>,
          builder: (context, orders, reload) => Column(children: [
            if (orders.isEmpty) emptyState(ar ? 'لا توجد طلبات' : 'No orders'),
            for (final o in orders.cast<Map<String, dynamic>>())
              Card(child: ListTile(
                title: Text('#${o['orderNumber']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text(fmtDate(o['createdAt'] as String?)),
                trailing: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.end, children: [Text(s.money(o['totalMinor'] as num?, ctx.currency), style: const TextStyle(fontWeight: FontWeight.w800)), StatusPill('${o['status']}')]),
                onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => OrderDetail(ctx: ctx, id: '${o['id']}'))),
              )),
          ]),
        ),
      ]),
    );
  }
}

/// Whether the customer is a man or a woman, so messages use the right form. A guess from the name is shown as a guess; the customer's own choice cannot be changed here.
class _GenderRow extends StatefulWidget {
  const _GenderRow({required this.ctx, required this.customer});
  final Ctx ctx;
  final Map<String, dynamic> customer;
  @override
  State<_GenderRow> createState() => _GenderRowState();
}

class _GenderRowState extends State<_GenderRow> {
  late String? _g = widget.customer['gender'] as String?, _src = widget.customer['genderSource'] as String?;

  Future<void> _set(String g) async {
    final clear = _g == g && _src == 'OWNER';
    if (await run(context, () async { await widget.ctx.api.put('store/customers/${widget.customer['id']}/gender', {'gender': clear ? '' : g}); }) && mounted) {
      setState(() { _g = clear ? null : g; _src = clear ? null : 'OWNER'; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final self = _src == 'SELF';
    final note = self ? (ar ? 'اختاره العميل بنفسه' : 'chosen by the customer') : _src == 'AI' ? (ar ? 'تخمين من الاسم' : 'guessed from the name') : _src == 'OWNER' ? (ar ? 'إنت حددته' : 'set by you') : (ar ? 'غير معروف' : 'unknown');
    return Padding(padding: const EdgeInsets.only(top: 8), child: Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
      Text(ar ? 'النوع' : 'Gender', style: const TextStyle(fontWeight: FontWeight.w700)),
      ChoiceChip(label: Text(ar ? 'ذكر' : 'Male'), selected: _g == 'MALE', onSelected: self ? null : (_) => _set('MALE')),
      ChoiceChip(label: Text(ar ? 'أنثى' : 'Female'), selected: _g == 'FEMALE', onSelected: self ? null : (_) => _set('FEMALE')),
      Text(note, style: const TextStyle(fontSize: 11.5, color: Color(0xFF6B7C8D))),
    ]));
  }
}
