import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'campaign.dart';
import 'connect.dart';
import 'forms.dart';

// Sales channels (Amazon, noon, Shopify, WooCommerce, Salla, YouCan, Zid), shipping and AI marketing. Connecting is in connect.dart; the "Log in with ..." flows
// open the company's own login in the browser and the lists reload when the app comes back.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).cast<Map<String, dynamic>>();
const _flags = {'EG': '🇪🇬', 'SA': '🇸🇦', 'AE': '🇦🇪'};
const _stageAr = {'NEW': 'جديد', 'PROCESSING': 'قيد التجهيز', 'SHIPPED': 'تم الشحن', 'DELIVERED': 'تم التسليم', 'CANCELLED': 'ملغي', 'RETURNED': 'مرتجع'};
const _cur = {'EG': 'EGP', 'SA': 'SAR', 'AE': 'AED'};

const _actionAr = {
  'cancel': 'إلغاء الطلب', 'canceled': 'إلغاء الطلب', 'cancelled': 'إلغاء الطلب', 'complete': 'تحديد كمكتمل', 'delivered': 'تحديد كمُسلَّم', 'delivering': 'تحديد كمشحون', 'fulfill': 'تحديد كمشحون',
  'in_progress': 'قبول الطلب (قيد التنفيذ)', 'indelivery': 'تحديد كمشحون', 'preparing': 'بدء التجهيز', 'processing': 'قبول الطلب (قيد التجهيز)', 'ship': 'تأكيد الشحن',
};

/// The platform sends English action names: shown in Arabic when the app is Arabic.
String _actionLabel(Map<String, dynamic> a, bool ar) => ar ? (_actionAr['${a['key']}'] ?? '${a['label']}') : '${a['label']}';

String _stage(String st, bool ar) => ar ? (_stageAr[st] ?? st) : st[0] + st.substring(1).toLowerCase();

/// The coloured badge of a platform / carrier.
Widget providerMark(String provider, String name, {double size = 40}) => _mark(provider, name, size: size);

Widget _mark(String provider, String name, {double size = 40}) {
  const colors = {'AMAZON': Color(0xFF232F3E), 'NOON': Color(0xFFFEEE00), 'SHOPIFY': Color(0xFF95BF47), 'WOOCOMMERCE': Color(0xFF7F54B3), 'SALLA': Color(0xFF004D5A), 'YOUCAN': Color(0xFF1E66F5), 'ARAMEX': Color(0xFFE1251B), 'BOSTA': Color(0xFFE30613), 'ZID': Color(0xFF5B2D90), 'AYMAKAN': Color(0xFFF26B21), 'MYLERZ': Color(0xFF0E2A47)};
  final bg = colors[provider] ?? const Color(0xFF475569);
  final fg = bg.computeLuminance() > 0.6 ? Colors.black : Colors.white;
  final t = name.replaceAll(RegExp(r'[^A-Za-z0-9]'), '');
  return Container(width: size, height: size, alignment: Alignment.center, decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(size / 3)), child: Text(t.substring(0, t.length < 2 ? t.length : 2).toUpperCase(), style: TextStyle(color: fg, fontWeight: FontWeight.w900, fontSize: size / 3)));
}

// ------------------------------------------------------------------ Channels hub

class ChannelsScreen extends StatefulWidget {
  const ChannelsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<ChannelsScreen> createState() => _ChannelsScreenState();
}

class _ChannelsScreenState extends State<ChannelsScreen> {
  String? _account;
  int _gen = 0;

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final ctx = widget.ctx;
    return ResumeReload(onResume: () => setState(() => _gen++), child: Async<List<Map<String, dynamic>>>(
      key: ValueKey('acc$_gen'),
      load: () async => _maps(await ctx.api.get('store/integrations/accounts')).where((a) => a['kind'] == 'MARKETPLACE' || a['kind'] == 'STORE_PLATFORM').toList(),
      builder: (context, accounts, reload) {
        if (accounts.isEmpty) {
          return ListView(padding: const EdgeInsets.all(24), children: [
            emptyState(ar ? 'لم تربط أي منصة بعد. اربط أمازون أو نون أو شوبيفاي أو ووكومرس أو سلة أو يوكان لترى طلباتها ومنتجاتها وإحصائياتها هنا.' : 'No platform connected yet. Connect Amazon, noon, Shopify, WooCommerce, Salla or YouCan to see its orders, products and insights here.', icon: Icons.hub_outlined),
            FilledButton.icon(onPressed: () async { if (await openConnect(context, ctx, carrier: false) && mounted) setState(() => _gen++); }, icon: const Icon(Icons.add_link_rounded), label: Text(ar ? 'اربط منصة' : 'Connect a platform')),
          ]);
        }
        final picked = accounts.any((a) => a['id'] == _account) ? _account : null;
        return DefaultTabController(length: 3, child: Column(children: [
          SizedBox(height: 62, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.fromLTRB(12, 10, 12, 4), children: [
            Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: ChoiceChip(label: Text(ar ? 'كل المنصات' : 'All platforms'), selected: picked == null, onSelected: (_) => setState(() => _account = null))),
            for (final a in accounts) Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: ChoiceChip(
              avatar: _mark('${a['provider']}', '${a['name']}', size: 22),
              label: Text('${a['name']} ${_flags[a['country']] ?? ''}${(a['newOrders'] as num? ?? 0) > 0 ? '  •${a['newOrders']}' : ''}'),
              selected: picked == a['id'], onSelected: (_) => setState(() => _account = '${a['id']}'))),
          ])),
          TabBar(tabs: [Tab(text: ar ? 'الإحصائيات' : 'Insights'), Tab(text: ar ? 'الطلبات' : 'Orders'), Tab(text: ar ? 'المنصات' : 'Platforms')]),
          Expanded(child: TabBarView(children: [
            _Insights(key: ValueKey('i$picked$_gen'), ctx: ctx, accountId: picked, onPick: (id) => setState(() => _account = id)),
            _ChannelOrders(key: ValueKey('o$picked$_gen'), ctx: ctx, accountId: picked),
            _Platforms(ctx: ctx, accounts: picked == null ? accounts : accounts.where((a) => a['id'] == picked).toList(), onChanged: () { setState(() => _gen++); reload(); }),
          ])),
        ]));
      },
    ));
  }
}

class _Insights extends StatelessWidget {
  const _Insights({super.key, required this.ctx, required this.accountId, required this.onPick});
  final Ctx ctx;
  final String? accountId;
  final ValueChanged<String> onPick;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return Async<Map<String, dynamic>>(
      load: () async => await ctx.api.get('store/integrations/insights', query: {'days': '30', 'accountId': accountId}) as Map<String, dynamic>,
      builder: (context, d, reload) {
        final t = d['totals'] as Map<String, dynamic>;
        final channels = _maps(d['channels']);
        final cur = channels.map((c) => _cur[c['country']] ?? 'EGP').toSet();
        String m(num? v) => cur.length == 1 ? s.money(v, cur.first) : '-';
        final funnel = (d['funnel'] as Map).cast<String, dynamic>();
        final maxF = funnel.values.fold<num>(1, (a, b) => (b as num) > a ? b : a);
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Row(children: [
            Expanded(child: _Stat(ar ? 'الإيرادات (30 يوم)' : 'Revenue (30 days)', m(t['revenueMinor'] as num?))),
            const SizedBox(width: 10),
            Expanded(child: _Stat(ar ? 'الطلبات' : 'Orders', '${t['orders']}', hint: (t['newOrders'] as num) > 0 ? (ar ? '${t['newOrders']} جديد' : '${t['newOrders']} new') : null)),
          ]),
          const SizedBox(height: 10),
          Row(children: [
            Expanded(child: _Stat(ar ? 'متوسط الطلب' : 'Average order', m(t['avgOrderMinor'] as num?))),
            const SizedBox(width: 10),
            Expanded(child: _Stat(ar ? 'المنتجات' : 'Products', '${t['listings']}', hint: (t['lowStock'] as num) > 0 ? (ar ? '${t['lowStock']} مخزون قليل' : '${t['lowStock']} low stock') : null)),
          ]),
          if (_maps(d['attention']).isNotEmpty) ...[
            const SizedBox(height: 14),
            Text(ar ? 'يحتاج انتباهك' : 'Needs attention', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
            for (final a in _maps(d['attention'])) Card(color: const Color(0xFFFFF4DD), child: ListTile(dense: true, leading: _mark('${a['provider']}', '${a['name']}', size: 30), title: Text('${a['name']}'), subtitle: Text(_attn(a, ar)))),
          ],
          if (accountId == null && channels.isNotEmpty) ...[
            const SizedBox(height: 14),
            Text(ar ? 'حسب المنصة' : 'By platform', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
            for (final c in channels) Card(child: ListTile(
              onTap: () => onPick('${c['id']}'),
              leading: _mark('${c['provider']}', '${c['name']}'),
              title: Text('${c['name']} ${_flags[c['country']] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text('${s.money(c['revenueMinor'] as num?, _cur[c['country']] ?? 'EGP')} · ${c['orders']} ${ar ? 'طلب' : 'orders'}'),
              trailing: c['status'] == 'ERROR' ? const Icon(Icons.error_outline, color: Color(0xFFD14343)) : const Icon(Icons.chevron_right_rounded),
            )),
          ],
          const SizedBox(height: 14),
          Text(ar ? 'الطلبات حسب المرحلة' : 'Orders by stage', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          for (final st in ['NEW', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'RETURNED']) Padding(padding: const EdgeInsets.symmetric(vertical: 5), child: Row(children: [
            SizedBox(width: 92, child: Text(_stage(st, ar), style: const TextStyle(fontSize: 13))),
            Expanded(child: ClipRRect(borderRadius: BorderRadius.circular(8), child: LinearProgressIndicator(minHeight: 10, value: ((funnel[st] as num?) ?? 0) / maxF))),
            SizedBox(width: 36, child: Text('${funnel[st] ?? 0}', textAlign: TextAlign.end, style: const TextStyle(fontWeight: FontWeight.w800))),
          ])),
          if (_maps(d['topProducts']).isNotEmpty) ...[
            const SizedBox(height: 14),
            Text(ar ? 'الأكثر مبيعاً' : 'Best sellers', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
            for (final p in _maps(d['topProducts'])) ListTile(dense: true, contentPadding: EdgeInsets.zero, title: Text('${p['title']}', maxLines: 1, overflow: TextOverflow.ellipsis), trailing: Text('${p['units']} × · ${m(p['revenueMinor'] as num?)}')),
          ],
        ]));
      },
    );
  }

  static String _attn(Map<String, dynamic> a, bool ar) => switch (a['type']) {
        'SYNC_ERROR' => '${a['message']}',
        'LATE_ORDERS' => ar ? '${a['count']} طلب ينتظر أكثر من يومين' : '${a['count']} order(s) waiting over 2 days',
        'LISTING_ERRORS' => ar ? '${a['count']} منتج فشل تحديثه' : '${a['count']} product(s) failed to update',
        'LOW_STOCK' => ar ? '${a['count']} منتج شارف على النفاد' : '${a['count']} product(s) almost out of stock',
        _ => ar ? '${a['count']} منتج غير مرتبط بمتجرك' : '${a['count']} product(s) not linked to your shop',
      };
}

class _Stat extends StatelessWidget {
  const _Stat(this.label, this.value, {this.hint});
  final String label, value;
  final String? hint;
  @override
  Widget build(BuildContext context) => Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)),
        const SizedBox(height: 6),
        FittedBox(fit: BoxFit.scaleDown, child: Text(value, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 22))),
        if (hint != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(hint!, style: const TextStyle(color: Color(0xFFD98200), fontSize: 12, fontWeight: FontWeight.w700))),
      ])));
}

class _ChannelOrders extends StatelessWidget {
  const _ChannelOrders({super.key, required this.ctx, required this.accountId});
  final Ctx ctx;
  final String? accountId;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return PagedList(
      api: ctx.api,
      path: 'store/integrations/orders',
      query: {'accountId': accountId},
      searchHint: ar ? 'رقم الطلب أو العميل أو الهاتف' : 'Order number, customer or phone',
      filterParam: 'stage',
      filters: [('', s.all), for (final st in ['NEW', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'RETURNED']) (st, _stage(st, ar))],
      emptyText: ar ? 'لا توجد طلبات' : 'No orders',
      itemBuilder: (c, o, reload) => Card(child: ListTile(
        leading: _mark('${o['provider']}', '${o['provider']}', size: 36),
        title: Row(children: [Expanded(child: Text('#${o['number']}', textDirection: TextDirection.ltr, textAlign: TextAlign.start, style: const TextStyle(fontWeight: FontWeight.w900))), StatusPill(_pill('${o['stage']}'))]),
        subtitle: Text('${o['customerName'] ?? '-'} · ${fmtDate(o['placedAt'] as String?)}\n${s.money(o['totalMinor'] as num, '${o['currency']}')}'),
        isThreeLine: true,
        onTap: () async {
          await Navigator.of(c).push(MaterialPageRoute(builder: (_) => ChannelOrderDetail(ctx: ctx, id: '${o['id']}')));
          await reload();
        },
      )),
    );
  }

  static String _pill(String stage) => switch (stage) { 'NEW' => 'REQUESTED', 'PROCESSING' => 'PREPARING', 'DELIVERED' => 'ARRIVED', _ => stage };
}

class ChannelOrderDetail extends StatelessWidget {
  const ChannelOrderDetail({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final String id;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'طلب من منصة' : 'Platform order')),
      body: Async<Map<String, dynamic>>(
        load: () async => await ctx.api.get('store/integrations/orders/$id') as Map<String, dynamic>,
        builder: (context, o, reload) {
          final cur = '${o['currency']}';
          final addr = (o['address'] as Map?)?.cast<String, dynamic>() ?? {};
          final addrLine = [addr['line1'], addr['line2'], addr['city'], addr['region'], addr['country']].where((e) => e != null && '$e'.isNotEmpty).join(', ');
          final actions = _maps(o['actions']);
          final canAct = ctx.canWrite('integrations');
          return ListView(padding: const EdgeInsets.all(16), children: [
            Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [_mark('${o['provider']}', '${o['providerName']}'), const SizedBox(width: 10), Expanded(child: Text('${o['providerName']} #${o['number']}', textDirection: TextDirection.ltr, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900))), StatusPill(_ChannelOrders._pill('${o['stage']}'))]),
              const SizedBox(height: 8),
              kv(ar ? 'العميل' : 'Customer', '${o['customerName'] ?? ''}'),
              kv(s.phone, '${o['customerPhone'] ?? ''}', ltr: true),
              if ('${o['customerEmail'] ?? ''}'.isNotEmpty) kv(ar ? 'البريد' : 'Email', '${o['customerEmail']}'),
              kv(ar ? 'العنوان' : 'Address', addrLine),
              kv(ar ? 'الإجمالي' : 'Total', s.money(o['totalMinor'] as num, cur)),
              kv(ar ? 'التاريخ' : 'Date', fmtDate(o['placedAt'] as String?)),
              if ('${o['trackingNumber'] ?? ''}'.isNotEmpty) kv(ar ? 'الشحنة' : 'Tracking', '${o['carrier'] ?? ''} ${o['trackingNumber']}'),
            ]))),
            Row(children: [
              Expanded(child: OutlinedButton.icon(onPressed: () => dial('${o['customerPhone'] ?? ''}'), icon: const Icon(Icons.call_rounded), label: Text(ar ? 'اتصال' : 'Call'))),
              const SizedBox(width: 8),
              Expanded(child: OutlinedButton.icon(onPressed: () => whatsapp('${o['customerPhone'] ?? ''}'), icon: const Icon(Icons.chat_rounded), label: const Text('WhatsApp'))),
            ]),
            const SizedBox(height: 8),
            Text(ar ? 'المنتجات' : 'Items', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
            for (final it in _maps(o['items'])) Card(child: ListTile(dense: true, title: Text('${it['title'] ?? it['sku']}'), subtitle: Text('${it['sku'] ?? ''}'), trailing: Text('× ${it['quantity'] ?? 1}  ${s.money((it['totalMinor'] as num?) ?? 0, cur)}'))),
            for (final sh in _maps(o['shipments'])) Card(child: ListTile(dense: true, leading: const Icon(Icons.local_shipping_outlined), title: Text('${sh['carrier']} · ${sh['trackingNumber']}', textDirection: TextDirection.ltr), subtitle: Text('${sh['lastEvent'] ?? statusLabel('${sh['status']}')}'), trailing: sh['labelUrl'] != null ? IconButton(icon: const Icon(Icons.print_outlined), onPressed: () => launch('${sh['labelUrl']}')) : null)),
            if (o['adminUrl'] == null) const SizedBox.shrink(),
            if (canAct) ...[
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final a in actions) FilledButton.tonal(
                  style: FilledButton.styleFrom(minimumSize: const Size(0, 44)),
                  onPressed: () async {
                    final tracking = TextEditingController(), carrier = TextEditingController();
                    var params = <String, String>{};
                    if (a['needsTracking'] == true) {
                      final ok = await formSheet(context, title: _actionLabel(a, ar), canSave: () => tracking.text.trim().isNotEmpty, body: (x, set) => [tf(tracking, ar ? 'رقم الشحنة' : 'Tracking number', ltr: true, onChanged: (_) => set(() {})), tf(carrier, ar ? 'شركة الشحن' : 'Carrier')], onSave: () async {
                        params = {'trackingNumber': tracking.text.trim(), 'carrier': carrier.text.trim()};
                        await ctx.api.post('store/integrations/orders/$id/actions', {'action': a['key'], 'params': params});
                      });
                      if (ok) await reload();
                    } else if (context.mounted && await run(context, () => ctx.api.post('store/integrations/orders/$id/actions', {'action': a['key'], 'params': params}))) {
                      await reload();
                    }
                  },
                  child: Text(_actionLabel(a, ar)),
                ),
                if (['NEW', 'PROCESSING'].contains(o['stage']))
                  FilledButton.icon(
                    style: FilledButton.styleFrom(minimumSize: const Size(0, 44)),
                    icon: const Icon(Icons.local_shipping_rounded),
                    label: Text(ar ? 'اشحن مع شركة شحن' : 'Ship with a carrier'),
                    onPressed: () async { if (await shipSheet(context, ctx, channelOrderId: id, codMinor: o['totalMinor'] as num?, currency: cur, country: '${o['country']}')) await reload(); },
                  ),
              ]),
            ],
          ]);
        },
      ),
    );
  }
}

class _Platforms extends StatelessWidget {
  const _Platforms({required this.ctx, required this.accounts, required this.onChanged});
  final Ctx ctx;
  final List<Map<String, dynamic>> accounts;
  final VoidCallback onChanged;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final canManage = ctx.canWrite('integrations');
    return ListView(padding: const EdgeInsets.all(16), children: [
      for (final a in accounts) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          _mark('${a['provider']}', '${a['name']}', size: 44),
          const SizedBox(width: 10),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text('${a['name']} ${_flags[a['country']] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)), Text('${a['label']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12))])),
          StatusPill(a['status'] == 'ERROR' ? 'REJECTED' : 'ACTIVE'),
        ]),
        if (a['status'] == 'ERROR' && a['lastError'] != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text('${a['lastError']}', style: const TextStyle(color: Color(0xFFD14343), fontSize: 13))),
        const SizedBox(height: 6),
        kv(ar ? 'المنتجات' : 'Products', '${a['listings']}'),
        kv(ar ? 'الطلبات' : 'Orders', '${a['orders']}'),
        kv(ar ? 'آخر مزامنة' : 'Last sync', a['lastSyncAt'] == null ? (ar ? 'لم تتم بعد' : 'Not yet') : fmtDate('${a['lastSyncAt']}')),
        Wrap(spacing: 8, children: [
          if (a['adminUrl'] != null) OutlinedButton.icon(onPressed: () => launch('${a['adminUrl']}'), icon: const Icon(Icons.open_in_new_rounded, size: 18), label: Text(ar ? 'افتح ${a['name']}' : 'Open ${a['name']}')),
          if (canManage) OutlinedButton.icon(onPressed: () async { if (await run(context, () => ctx.api.post('store/integrations/accounts/${a['id']}/sync', {'what': 'all'})) && context.mounted) { toast(context, ar ? 'بدأت المزامنة' : 'Sync started'); onChanged(); } }, icon: const Icon(Icons.sync_rounded, size: 18), label: Text(ar ? 'زامن الآن' : 'Sync now')),
          if (canManage) TextButton(onPressed: () async {
            if (!await confirm(context, ar ? 'فصل ${a['name']}؟ لا يُحذف شيء في المنصة.' : 'Disconnect ${a['name']}? Nothing is deleted on the platform.') || !context.mounted) return;
            if (await run(context, () => ctx.api.delete('store/integrations/accounts/${a['id']}'))) onChanged();
          }, child: Text(ar ? 'فصل' : 'Disconnect', style: const TextStyle(color: Color(0xFFD14343)))),
        ]),
      ]))),
      if (canManage) OutlinedButton.icon(onPressed: () async { if (await openConnect(context, ctx, carrier: false)) onChanged(); }, icon: const Icon(Icons.add_link_rounded), label: Text(ar ? 'اربط منصة أخرى' : 'Connect another platform')),
    ]);
  }
}

// ------------------------------------------------------------------ Shipping

/// Pick a connected carrier and create the shipment for a shop order or a channel order.
Future<bool> shipSheet(BuildContext context, Ctx ctx, {String? orderId, String? channelOrderId, num? codMinor, String? currency, String? country}) async {
  final ar = S.of(context).ar;
  List<Map<String, dynamic>> carriers;
  try { carriers = _maps(await ctx.api.get('store/shipping/carriers')).where((c) => c['status'] != 'PAUSED').toList(); } catch (e) { if (context.mounted) toast(context, errorText(e), isError: true); return false; }
  if (!context.mounted) return false;
  if (carriers.isEmpty) {
    toast(context, ar ? 'اربط شركة شحن أولاً' : 'Connect a shipping company first');
    await openConnect(context, ctx, carrier: true);
    return false;
  }
  carriers.sort((a, b) => (b['country'] == country ? 1 : 0) - (a['country'] == country ? 1 : 0));
  String account = '${carriers.first['id']}';
  final weight = TextEditingController(text: '1'), pieces = TextEditingController(text: '1'), cod = TextEditingController(text: codMinor == null ? '' : fmtMoney(codMinor)), desc = TextEditingController();
  return formSheet(context, title: ar ? 'اشحن مع شركة شحن' : 'Ship with a carrier', saveLabel: ar ? 'أنشئ الشحنة' : 'Create shipment', body: (x, set) => [
    for (final c in carriers) ListTile(contentPadding: EdgeInsets.zero, selected: account == '${c['id']}', onTap: () => set(() => account = '${c['id']}'), leading: Icon(account == '${c['id']}' ? Icons.radio_button_checked_rounded : Icons.radio_button_off_rounded), title: Text('${c['name']} ${_flags[c['country']] ?? ''}'), subtitle: Text('${c['label']}')),
    tf(weight, ar ? 'الوزن (كجم)' : 'Weight (kg)', number: true, ltr: true),
    tf(pieces, ar ? 'عدد القطع' : 'Pieces', number: true, ltr: true),
    tf(cod, '${ar ? 'المبلغ المطلوب تحصيله' : 'Cash to collect'}${currency == null ? '' : ' ($currency)'}', number: true, ltr: true, hint: ar ? 'اتركه فارغاً لقاعدة الطلب' : 'Leave empty to follow the order'),
    tf(desc, ar ? 'وصف الطرد' : 'Parcel description'),
  ], onSave: () async {
    await ctx.api.post('store/shipping/shipments', {
      if (orderId != null) 'orderId': orderId, if (channelOrderId != null) 'channelOrderId': channelOrderId, 'accountId': account,
      'weightKg': double.tryParse(weight.text.replaceAll(',', '.')) ?? 1, 'pieces': int.tryParse(pieces.text) ?? 1,
      if (cod.text.trim().isNotEmpty) 'codMinor': toMinor(cod.text), if (desc.text.trim().isNotEmpty) 'description': desc.text.trim(), 'markShipped': true,
    });
  });
}

class ShippingScreen extends StatelessWidget {
  const ShippingScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return PagedList(
      api: ctx.api,
      path: 'store/shipping/shipments',
      searchHint: ar ? 'رقم الشحنة أو الطلب' : 'Tracking or order number',
      emptyText: ar ? 'لا توجد شحنات بعد. اشحن طلباً من صفحة الطلب.' : 'No shipments yet. Ship an order from its page.',
      header: Padding(padding: const EdgeInsets.fromLTRB(16, 4, 16, 4), child: OutlinedButton.icon(onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => CarriersScreen(ctx: ctx))), icon: const Icon(Icons.local_shipping_outlined), label: Text(ar ? 'شركات الشحن المربوطة' : 'My shipping companies'))),
      itemBuilder: (c, sh, reload) => Card(child: ListTile(
        leading: _mark('${sh['provider']}', '${sh['carrier']}', size: 38),
        title: Row(children: [Expanded(child: Text('${sh['trackingNumber']}', textDirection: TextDirection.ltr, textAlign: TextAlign.start, style: const TextStyle(fontWeight: FontWeight.w900))), StatusPill(_shipPill('${sh['status']}'))]),
        subtitle: Text('${sh['carrier']} · ${sh['orderNumber'] ?? (sh['channelOrderNumber'] == null ? '' : '#${sh['channelOrderNumber']}')}\n${sh['lastEvent'] ?? ''}'),
        isThreeLine: true,
        trailing: PopupMenuButton<String>(onSelected: (v) async {
          if (v == 'label' && sh['labelUrl'] != null) { await launch('${sh['labelUrl']}'); return; }
          if (v == 'track' && sh['trackingUrl'] != null) { await launch('${sh['trackingUrl']}'); return; }
          if (v == 'cancel' && !await confirm(c, ar ? 'إلغاء الشحنة لدى شركة الشحن؟' : 'Cancel this shipment with the carrier?')) return;
          if (!c.mounted) return;
          if (await run(c, () => ctx.api.post('store/shipping/shipments/${sh['id']}/${v == 'cancel' ? 'cancel' : 'refresh'}', {}))) await reload();
        }, itemBuilder: (_) => [
          PopupMenuItem(value: 'refresh', child: Text(ar ? 'تحديث الحالة' : 'Refresh status')),
          if (sh['labelUrl'] != null) PopupMenuItem(value: 'label', child: Text(ar ? 'البوليصة' : 'Label')),
          if (sh['trackingUrl'] != null) PopupMenuItem(value: 'track', child: Text(ar ? 'صفحة التتبع' : 'Tracking page')),
          if (ctx.canWrite('shipping') && !['DELIVERED', 'RETURNED', 'CANCELLED'].contains(sh['status'])) PopupMenuItem(value: 'cancel', child: Text(ar ? 'إلغاء الشحنة' : 'Cancel shipment')),
        ]),
      )),
    );
  }

  static String _shipPill(String st) => switch (st) { 'CREATED' => 'REQUESTED', 'PICKED_UP' || 'IN_TRANSIT' || 'OUT_FOR_DELIVERY' => 'SHIPPED', 'DELIVERED' => 'ARRIVED', 'EXCEPTION' || 'FAILED' => 'CANCELLED', _ => st };
}

// ------------------------------------------------------------------ Marketing

class MarketingScreen extends StatefulWidget {
  const MarketingScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<MarketingScreen> createState() => _MarketingScreenState();
}

/// Starts the Facebook login (Meta needs its own page in the browser, then the page / Instagram picker) and tells the owner how to finish.
Future<void> connectMeta(BuildContext context, Ctx ctx) async {
  final ar = S.of(context).ar;
  Map<String, dynamic>? r;
  if (await run(context, () async { r = await ctx.api.post('store/ai/meta/start', {}) as Map<String, dynamic>; }) && r != null) {
    await launch('${r!['url']}');
    if (context.mounted) toast(context, ar ? 'أكمل الربط واختر الصفحة في المتصفح ثم ارجع هنا' : 'Finish the login and pick your page in the browser, then come back here');
  }
}

class _MarketingScreenState extends State<MarketingScreen> {
  int _gen = 0;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final ctx = widget.ctx;
    return ResumeReload(onResume: () => setState(() => _gen++), child: Async<Map<String, dynamic>>(
      key: ValueKey('mk$_gen'),
      load: () async => await ctx.api.get('store/marketing/overview') as Map<String, dynamic>,
      builder: (context, ov, reload) {
        if (_maps(ov['channels']).isEmpty) {
          return ListView(padding: const EdgeInsets.all(24), children: [
            emptyState(ar ? 'اربط صفحة فيسبوك وحساب إنستجرام لتنشر منشوراتك وإعلاناتك.' : 'Connect your Facebook page and Instagram account to publish posts and ads.', icon: Icons.campaign_outlined),
            if (ctx.canWrite('marketing')) FilledButton.icon(onPressed: () => connectMeta(context, ctx), icon: const Icon(Icons.facebook_rounded), label: Text(ar ? 'اربط فيسبوك وإنستجرام' : 'Connect Facebook and Instagram')),
          ]);
        }
        return DefaultTabController(length: 2, child: Column(children: [
          TabBar(tabs: [Tab(text: ar ? 'المنشورات' : 'Posts'), Tab(text: ar ? 'الحملات' : 'Campaigns')]),
          Expanded(child: TabBarView(children: [_Posts(ctx: ctx, ov: ov), _Campaigns(ctx: ctx, ov: ov)])),
        ]));
      },
    ));
  }
}

class _Posts extends StatefulWidget {
  const _Posts({required this.ctx, required this.ov});
  final Ctx ctx;
  final Map<String, dynamic> ov;
  @override
  State<_Posts> createState() => _PostsState();
}

class _PostsState extends State<_Posts> {
  int _gen = 0;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final ctx = widget.ctx;
    final chans = _maps(widget.ov['channels']).map((c) => '${c['platform']}').toSet().toList();
    return Scaffold(
      backgroundColor: Colors.transparent,
      floatingActionButton: ctx.canWrite('marketing') ? FloatingActionButton.extended(
        onPressed: () async {
          final id = await _newPostSheet(context, ctx, chans, widget.ov['imagesAvailable'] == true);
          if (id != null && context.mounted) { await Navigator.of(context).push(MaterialPageRoute(builder: (_) => PostEditor(ctx: ctx, id: id, imagesAvailable: widget.ov['imagesAvailable'] == true))); setState(() => _gen++); }
        },
        icon: const Icon(Icons.auto_awesome_rounded), label: Text(ar ? 'منشور بالذكاء الاصطناعي' : 'AI post'),
      ) : null,
      body: PagedList(
        key: ValueKey(_gen), api: ctx.api, path: 'store/marketing/posts', emptyText: ar ? 'لا توجد منشورات بعد' : 'No posts yet',
        itemBuilder: (c, p, reload) => Card(child: ListTile(
          leading: StatusPill(_postPill('${p['status']}')),
          title: Text('${p['caption']}', maxLines: 2, overflow: TextOverflow.ellipsis),
          subtitle: Text('${p['kind'] == 'SHOP' ? (ar ? 'المتجر' : 'Shop') : p['productName'] ?? ''} · ${(p['channels'] as List).join(' + ')}'),
          onTap: () async { await Navigator.of(c).push(MaterialPageRoute(builder: (_) => PostEditor(ctx: ctx, id: '${p['id']}', imagesAvailable: widget.ov['imagesAvailable'] == true))); await reload(); },
        )),
      ),
    );
  }

  static String _postPill(String st) => switch (st) { 'POSTED' => 'DONE', 'SCHEDULED' || 'POSTING' => 'SHIPPED', 'FAILED' => 'CANCELLED', 'PARTIAL' => 'PREPARING', _ => 'DRAFT' };
}

Future<String?> _newPostSheet(BuildContext context, Ctx ctx, List<String> chans, bool images) async {
  final ar = S.of(context).ar;
  var kind = 'PRODUCT', tone = 'friendly', withImage = images;
  Map<String, dynamic>? product;
  final selected = {...chans};
  final q = TextEditingController();
  List<Map<String, dynamic>> found = [];
  String? created;
  Future<void> search(StateSetter set) async {
    try { final r = await ctx.api.get('store/products', query: {'q': q.text.trim(), 'page': '1'}); found = _maps((r as Map)['data']); } catch (_) {}
    set(() {});
  }
  final ok = await formSheet(context, title: ar ? 'منشور بالذكاء الاصطناعي' : 'Post with AI', saveLabel: ar ? 'أنشئ المنشور' : 'Generate', canSave: () => selected.isNotEmpty && (kind == 'SHOP' || product != null), body: (x, set) => [
    SegmentedButton<String>(segments: [ButtonSegment(value: 'PRODUCT', label: Text(ar ? 'منتج' : 'Product')), ButtonSegment(value: 'SHOP', label: Text(ar ? 'متجري' : 'My shop'))], selected: {kind}, onSelectionChanged: (v) => set(() => kind = v.first)),
    if (kind == 'PRODUCT') ...[
      if (product != null) ListTile(contentPadding: EdgeInsets.zero, title: Text('${product!['name']}', style: const TextStyle(fontWeight: FontWeight.w800)), trailing: TextButton(onPressed: () => set(() => product = null), child: Text(ar ? 'تغيير' : 'Change')))
      else ...[tf(q, ar ? 'ابحث في منتجاتك' : 'Search your products', onChanged: (_) => search(set)), for (final p in found.take(6)) ListTile(dense: true, title: Text('${p['name']}'), onTap: () => set(() => product = p))],
    ],
    DropdownButtonFormField<String>(initialValue: tone, decoration: InputDecoration(labelText: ar ? 'الأسلوب' : 'Tone'), items: [for (final t in ['friendly', 'professional', 'playful', 'luxury', 'urgent']) DropdownMenuItem(value: t, child: Text(t))], onChanged: (v) => set(() => tone = v!)),
    for (final p in chans) CheckboxListTile(contentPadding: EdgeInsets.zero, value: selected.contains(p), onChanged: (v) => set(() => v! ? selected.add(p) : selected.remove(p)), title: Text(p == 'FACEBOOK' ? (ar ? 'صفحة فيسبوك' : 'Facebook page') : p == 'INSTAGRAM' ? 'Instagram' : p)),
    if (images) SwitchListTile(contentPadding: EdgeInsets.zero, value: withImage, onChanged: (v) => set(() => withImage = v), title: Text(ar ? 'اصنع صورة بالذكاء الاصطناعي' : 'Create a picture with AI')),
  ], onSave: () async {
    final r = await ctx.api.post('store/marketing/posts/draft', {'kind': kind, if (kind == 'PRODUCT') 'productId': product!['id'], 'language': ar ? 'ar' : 'en', 'tone': tone, 'withImage': withImage, 'channels': selected.toList()});
    created = '${(r as Map)['id']}';
  });
  return ok ? created : null;
}

class PostEditor extends StatelessWidget {
  const PostEditor({super.key, required this.ctx, required this.id, required this.imagesAvailable});
  final Ctx ctx;
  final String id;
  final bool imagesAvailable;
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'منشور' : 'Post')),
      body: Async<Map<String, dynamic>>(
        load: () async => await ctx.api.get('store/marketing/posts/$id') as Map<String, dynamic>,
        builder: (context, p, reload) {
          final locked = p['status'] == 'POSTED' || p['status'] == 'POSTING';
          final caption = TextEditingController(text: '${p['caption']}');
          final image = p['imageFileId'] != null ? fileUrl(ctx, '${p['imageFileId']}') : p['imageUrl'] as String?;
          final channels = (p['channels'] as List).cast<String>();
          Future<void> saveCaption() async { if (caption.text != '${p['caption']}') await ctx.api.patch('store/marketing/posts/$id', {'caption': caption.text}); }
          return ListView(padding: const EdgeInsets.all(16), children: [
            if (image != null) ClipRRect(borderRadius: BorderRadius.circular(16), child: AspectRatio(aspectRatio: 1, child: Image.network(image, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(color: const Color(0xFFEFF3F7), child: const Icon(Icons.image_not_supported_outlined))))),
            const SizedBox(height: 10),
            TextField(controller: caption, enabled: !locked, minLines: 5, maxLines: 10, decoration: InputDecoration(labelText: ar ? 'النص' : 'Text', border: const OutlineInputBorder())),
            if (!locked) Wrap(spacing: 8, children: [
              TextButton.icon(onPressed: () async { if (await run(context, () async { await saveCaption(); await ctx.api.post('store/marketing/posts/$id/regenerate', {'what': 'caption'}); })) await reload(); }, icon: const Icon(Icons.auto_awesome_rounded, size: 18), label: Text(ar ? 'أعد كتابة النص' : 'Rewrite')),
              if (imagesAvailable) TextButton.icon(onPressed: () async { if (await run(context, () async { await saveCaption(); await ctx.api.post('store/marketing/posts/$id/regenerate', {'what': 'image'}); })) await reload(); }, icon: const Icon(Icons.image_outlined, size: 18), label: Text(ar ? 'صورة جديدة' : 'New picture')),
            ]),
            for (final plat in ['FACEBOOK', 'INSTAGRAM']) CheckboxListTile(contentPadding: EdgeInsets.zero, value: channels.contains(plat), onChanged: locked ? null : (v) async { if (await run(context, () => ctx.api.patch('store/marketing/posts/$id', {'channels': v! ? [...channels, plat] : channels.where((c) => c != plat).toList()}))) await reload(); }, title: Text(plat == 'FACEBOOK' ? (ar ? 'صفحة فيسبوك' : 'Facebook page') : 'Instagram')),
            if (channels.contains('INSTAGRAM') && image == null) Text(ar ? 'إنستجرام يحتاج صورة.' : 'Instagram needs a picture.', style: const TextStyle(color: Color(0xFFD98200))),
            for (final r in _maps(p['results']).where((r) => r['channel'] != '-' || r['ok'] != true)) Padding(padding: const EdgeInsets.only(top: 6), child: Text('${r['channel']}: ${r['ok'] == true ? (ar ? 'تم النشر' : 'posted') : '${ar ? 'تعذّر' : 'failed'} - ${r['error']}'}', style: TextStyle(color: r['ok'] == true ? const Color(0xFF1E9E5A) : const Color(0xFFD14343)))),
            const SizedBox(height: 14),
            if (!locked && ctx.canWrite('marketing')) Row(children: [
              Expanded(child: FilledButton.icon(onPressed: channels.isEmpty ? null : () async { if (await run(context, () async { await saveCaption(); await ctx.api.post('store/marketing/posts/$id/publish', {}); })) await reload(); }, icon: const Icon(Icons.send_rounded), label: Text(ar ? 'انشر الآن' : 'Post now'))),
              const SizedBox(width: 8),
              OutlinedButton(onPressed: () async { if (await confirm(context, ar ? 'حذف المنشور؟' : 'Delete this post?') && context.mounted && await run(context, () => ctx.api.delete('store/marketing/posts/$id')) && context.mounted) Navigator.of(context).pop(); }, child: Text(ar ? 'حذف' : 'Delete')),
            ]),
          ]);
        },
      ),
    );
  }
}

class _Campaigns extends StatelessWidget {
  const _Campaigns({required this.ctx, required this.ov});
  final Ctx ctx;
  final Map<String, dynamic> ov;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    if (_maps(ov['adAccounts']).isEmpty) {
      return ListView(padding: const EdgeInsets.all(24), children: [
        emptyState(ar ? 'لا يوجد حساب إعلانات. أعد ربط فيسبوك واسمح بإدارة الإعلانات لتنشئ الحملات.' : 'No ad account found. Connect Facebook again and allow ad management to create campaigns.', icon: Icons.ads_click_rounded),
        if (ctx.canWrite('marketing')) FilledButton.icon(onPressed: () => connectMeta(context, ctx), icon: const Icon(Icons.facebook_rounded), label: Text(ar ? 'اربط فيسبوك' : 'Connect Facebook')),
      ]);
    }
    return Async<List<Map<String, dynamic>>>(
      load: () async => _maps(await ctx.api.get('store/marketing/campaigns')),
      builder: (context, list, reload) => RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
        if (ctx.canWrite('marketing')) FilledButton.icon(onPressed: () async { if (await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => NewCampaignScreen(ctx: ctx, overview: ov))) == true) await reload(); }, icon: const Icon(Icons.add_rounded), label: Text(ar ? 'حملة جديدة باقتراحات الذكاء الاصطناعي' : 'New campaign with AI suggestions')),
        if (list.isEmpty) emptyState(ar ? 'لا توجد حملات بعد' : 'No campaigns yet'),
        for (final c in list) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [Expanded(child: Text('${c['name']}', style: const TextStyle(fontWeight: FontWeight.w900))), StatusPill(switch ('${c['status']}') { 'ACTIVE' => 'ACTIVE', 'FAILED' => 'CANCELLED', 'ENDED' => 'DONE', _ => 'DRAFT' })]),
          Text('${s.money(c['dailyBudgetMinor'] as num, '${c['currency']}')} ${ar ? 'يومياً' : 'per day'}', style: const TextStyle(color: Color(0xFF6B7C8D))),
          if ((c['insights'] as Map?)?.isNotEmpty == true) Padding(padding: const EdgeInsets.only(top: 6), child: Text('${ar ? 'وصول' : 'Reach'} ${(c['insights'] as Map)['reach'] ?? '-'} · ${ar ? 'نقرات' : 'Clicks'} ${(c['insights'] as Map)['clicks'] ?? '-'} · ${ar ? 'مصروف' : 'Spent'} ${(c['insights'] as Map)['spend'] ?? '-'}')),
          if (c['lastError'] != null) Text('${c['lastError']}', style: const TextStyle(color: Color(0xFFD14343), fontSize: 12)),
          if (ctx.canWrite('marketing')) Wrap(spacing: 8, children: [
            if (c['status'] == 'ACTIVE') OutlinedButton(onPressed: () async { if (await run(context, () => ctx.api.post('store/marketing/campaigns/${c['id']}/pause', {}))) await reload(); }, child: Text(ar ? 'أوقف' : 'Pause'))
            else if (['PAUSED', 'DRAFT', 'FAILED'].contains(c['status'])) FilledButton(onPressed: () async { if (await run(context, () => ctx.api.post('store/marketing/campaigns/${c['id']}/launch', {}))) await reload(); }, child: Text(ar ? 'ابدأ' : 'Start')),
            TextButton(onPressed: () async { if (await run(context, () => ctx.api.post('store/marketing/campaigns/${c['id']}/refresh', {}))) await reload(); }, child: Text(ar ? 'حدّث النتائج' : 'Refresh')),
          ]),
        ]))),
      ])),
    );
  }
}

// ------------------------------------------------------------------ Publish a product to platforms

class PublishScreen extends StatefulWidget {
  const PublishScreen({super.key, required this.ctx, required this.productId});
  final Ctx ctx;
  final String productId;
  @override
  State<PublishScreen> createState() => _PublishScreenState();
}

class _PublishScreenState extends State<PublishScreen> {
  final Map<String, bool> _on = {};
  final Map<String, TextEditingController> _price = {}, _qty = {};
  bool _busy = false;
  List<String> _messages = [];

  TextEditingController _c(Map<String, TextEditingController> m, String k, String v) => m.putIfAbsent(k, () => TextEditingController(text: v));

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final ctx = widget.ctx;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'النشر على المنصات' : 'Publish to platforms')),
      body: Async<Map<String, dynamic>>(
        load: () async => await ctx.api.get('store/integrations/publish-plan', query: {'productId': widget.productId}) as Map<String, dynamic>,
        builder: (context, plan, reload) {
          final variants = _maps(plan['variants']);
          final accounts = _maps(plan['accounts']);
          if (accounts.isEmpty) return ListView(padding: const EdgeInsets.all(24), children: [emptyState(ar ? 'اربط سوقاً أو منصة متاجر لتبيع المنتج هناك أيضاً.' : 'Connect a marketplace or store platform to sell this product there too.'), FilledButton(onPressed: () async { if (await openConnect(context, ctx, carrier: false)) reload(); }, child: Text(ar ? 'اربط منصة' : 'Connect a platform'))]);
          return ListView(padding: const EdgeInsets.all(16), children: [
            Text(ar ? 'لكل منصة سعرها وكميتها الخاصة.' : 'Each platform has its own price and quantity.', style: const TextStyle(color: Color(0xFF6B7C8D))),
            for (final a in accounts) Builder(builder: (_) {
              final id = '${a['id']}';
              final listings = _maps(a['listings']);
              final on = _on[id] ?? listings.isNotEmpty;
              return Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Switch(value: on, onChanged: (v) => setState(() => _on[id] = v)),
                  _mark('${a['provider']}', '${a['name']}', size: 34), const SizedBox(width: 8),
                  Expanded(child: Text('${a['name']} ${_flags[a['country']] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w800))),
                  StatusPill(listings.isNotEmpty ? 'ACTIVE' : 'DRAFT'),
                ]),
                if (a['canCreate'] != true && listings.isEmpty) Text(ar ? 'نون: ننشر سعر ومخزون الـ SKU الموجود لديك هناك.' : 'noon: we set the price and stock of the SKU you already have there.', style: const TextStyle(fontSize: 12, color: Color(0xFFD98200))),
                if (on) for (final v in variants) Builder(builder: (_) {
                  final l = listings.where((x) => x['variantId'] == v['id']).cast<Map<String, dynamic>?>().firstWhere((_) => true, orElse: () => null);
                  final key = '$id|${v['id']}';
                  return Padding(padding: const EdgeInsets.only(top: 8), child: Row(children: [
                    Expanded(flex: 3, child: Text('${(v['comboKey'] ?? '').toString().isEmpty ? v['sku'] : comboLabel(v['comboKey'])}', maxLines: 1, overflow: TextOverflow.ellipsis)),
                    const SizedBox(width: 8),
                    Expanded(flex: 3, child: TextField(controller: _c(_price, key, fmtMoney((l?['priceMinor'] ?? v['priceMinor']) as num?)), keyboardType: const TextInputType.numberWithOptions(decimal: true), decoration: InputDecoration(labelText: '${ar ? 'السعر' : 'Price'} (${a['currency']})', isDense: true))),
                    const SizedBox(width: 8),
                    Expanded(flex: 2, child: TextField(controller: _c(_qty, key, '${l?['quantity'] ?? v['available']}'), keyboardType: TextInputType.number, decoration: InputDecoration(labelText: ar ? 'الكمية' : 'Qty', isDense: true))),
                  ]));
                }),
              ])));
            }),
            for (final m in _messages) Padding(padding: const EdgeInsets.only(bottom: 4), child: Text(m)),
            FilledButton.icon(
              onPressed: _busy || !ctx.canWrite('integrations') ? null : () async {
                final targets = [for (final a in accounts) if (_on['${a['id']}'] ?? _maps(a['listings']).isNotEmpty) {
                  'accountId': a['id'],
                  'variants': [for (final v in variants) {'variantId': v['id'], 'priceMinor': toMinor(_c(_price, '${a['id']}|${v['id']}', '').text), 'quantity': int.tryParse(_c(_qty, '${a['id']}|${v['id']}', '0').text.trim()) ?? 0}],
                }];
                if (targets.isEmpty) return;
                setState(() => _busy = true);
                try {
                  final res = _maps(await ctx.api.post('store/integrations/publish', {'productId': widget.productId, 'targets': targets}));
                  _messages = [for (final r in res) '${accounts.firstWhere((a) => a['id'] == r['accountId'], orElse: () => {'name': r['provider']})['name']}: ${r['ok'] == true ? (ar ? 'تم' : 'done') : '${ar ? 'تعذّر' : 'failed'} - ${r['error']}'}'];
                  _price.clear(); _qty.clear(); _on.clear();
                  await reload();
                } catch (e) { if (context.mounted) toast(context, errorText(e), isError: true); }
                if (mounted) setState(() => _busy = false);
              },
              icon: const Icon(Icons.publish_rounded), label: Text(ar ? 'انشر / حدّث' : 'Publish / update'),
            ),
          ]);
        },
      ),
    );
  }
}
