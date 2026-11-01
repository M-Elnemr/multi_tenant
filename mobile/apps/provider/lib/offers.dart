import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';
import 'products.dart' show TaxonomyPage;

/// Every kind of offer the shop can run: (emoji, colour A, colour B, Arabic name, English name, Arabic description, English description).
const kOfferTypes = <String, List<Object>>{
  'PERCENT_OFF': ['🏷️', 0xFFF43F5E, 0xFFFB923C, 'خصم بالنسبة', 'Percentage off', 'نسبة خصم على منتجات أو أقسام أو المتجر كله', 'A % off products, categories or everything'],
  'FIXED_OFF': ['💸', 0xFF10B981, 0xFF14B8A6, 'خصم بمبلغ', 'Amount off', 'مبلغ ثابت يُخصم من كل قطعة', 'A fixed amount off each item'],
  'FLASH_SALE': ['⚡', 0xFFF59E0B, 0xFFEF4444, 'عرض خاطف', 'Flash sale', 'تخفيض لفترة قصيرة مع عداد تنازلي', 'A short sale with a countdown'],
  'BUY_X_GET_Y': ['🎁', 0xFF8B5CF6, 0xFFEC4899, 'اشترِ واحصل على', 'Buy X get Y', 'اشترِ عددًا واحصل على قطع مجانًا أو بخصم', 'Buy some, get more free'],
  'BUNDLE_PRICE': ['📦', 0xFF0EA5E9, 0xFF6366F1, 'سعر الباقة', 'Bundle price', 'مجموعة منتجات معًا بسعر واحد', 'A set of products for one price'],
  'TIERED_QTY': ['📈', 0xFF22C55E, 0xFF84CC16, 'خصم الكميات', 'Quantity tiers', 'كلما زادت الكمية زاد الخصم', 'Buy more, save more'],
  'ORDER_THRESHOLD': ['🛒', 0xFF06B6D4, 0xFF3B82F6, 'اشترِ بأكثر ووفّر', 'Spend & save', 'خصم عند وصول السلة لمبلغ معين', 'A discount above an amount'],
  'FREE_SHIPPING': ['🚚', 0xFF0D9488, 0xFF22D3EE, 'توصيل مجاني', 'Free delivery', 'بدون مصاريف شحن، فوق مبلغ إن أردت', 'No delivery fee'],
  'GIFT_WITH_PURCHASE': ['🎀', 0xFFEC4899, 0xFFF472B6, 'هدية مجانية', 'Free gift', 'قطعة مجانية مع الطلب طالما متوفرة', 'A free item with the order'],
  'FIRST_ORDER': ['👋', 0xFF6366F1, 0xFFA855F7, 'أول طلب', 'First order', 'رحّب بالعملاء الجدد بخصم', 'Welcome new customers'],
  'COUPON': ['🎟️', 0xFF475569, 0xFF0F172A, 'كوبون خصم', 'Coupon code', 'كود يكتبه العميل عند الطلب', 'A code typed at checkout'],
};

String offerName(String type, bool ar) => '${kOfferTypes[type]?[ar ? 3 : 4] ?? type}';
LinearGradient offerGradient(String type) => LinearGradient(colors: [Color(kOfferTypes[type]?[1] as int? ?? 0xFF64748B), Color(kOfferTypes[type]?[2] as int? ?? 0xFF0F172A)]);

/// "Buy 2 get 1 free", "20% off"... from the offer's settings.
String offerSummary(Map<String, dynamic> o, bool ar, String Function(num) money) {
  final c = Map<String, dynamic>.from((o['config'] as Map?) ?? const {});
  num n(String k) => (c[k] as num?) ?? 0;
  String v() => c['discountType'] == 'FIXED' ? money(n('value')) : '${n('value')}%';
  switch (o['type']) {
    case 'PERCENT_OFF': case 'FLASH_SALE': return ar ? 'خصم ${n('percent')}%' : '${n('percent')}% off';
    case 'FIXED_OFF': return ar ? 'خصم ${money(n('amountMinor'))} على كل قطعة' : '${money(n('amountMinor'))} off each';
    case 'BUY_X_GET_Y': return n('getPercent') >= 100 || c['getPercent'] == null ? (ar ? 'اشترِ ${n('buyQty')} واحصل على ${n('getQty')} مجانًا' : 'Buy ${n('buyQty')}, get ${n('getQty')} free') : (ar ? 'اشترِ ${n('buyQty')} واحصل على ${n('getQty')} بخصم ${n('getPercent')}%' : 'Buy ${n('buyQty')}, get ${n('getQty')} at ${n('getPercent')}% off');
    case 'BUNDLE_PRICE': return ar ? 'معًا بـ ${money(n('bundlePriceMinor'))}' : 'Together for ${money(n('bundlePriceMinor'))}';
    case 'TIERED_QTY': return ((c['tiers'] as List?) ?? const []).map((t) => ar ? '${t['minQty']}+ قطع −${t['percent']}%' : '${t['minQty']}+ −${t['percent']}%').join(' · ');
    case 'ORDER_THRESHOLD': return ar ? 'اشترِ بـ ${money(n('minSubtotalMinor'))} ووفّر ${v()}' : 'Spend ${money(n('minSubtotalMinor'))}, save ${v()}';
    case 'FREE_SHIPPING': return n('minSubtotalMinor') > 0 ? (ar ? 'توصيل مجاني فوق ${money(n('minSubtotalMinor'))}' : 'Free delivery above ${money(n('minSubtotalMinor'))}') : (ar ? 'توصيل مجاني لكل الطلبات' : 'Free delivery on every order');
    case 'GIFT_WITH_PURCHASE': return n('minSubtotalMinor') > 0 ? (ar ? 'هدية فوق ${money(n('minSubtotalMinor'))}' : 'Gift above ${money(n('minSubtotalMinor'))}') : (ar ? 'هدية مع كل طلب' : 'A gift with every order');
    case 'FIRST_ORDER': return ar ? 'خصم ${v()} على أول طلب' : '${v()} off the first order';
    case 'COUPON': return ar ? 'خصم ${v()} بالكود' : '${v()} off with the code';
  }
  return '';
}

const _states = ['ALL', 'ACTIVE', 'SCHEDULED', 'PAUSED', 'DRAFT', 'ENDED'];
const _stateAr = {'ALL': 'الكل', 'ACTIVE': 'يعمل', 'SCHEDULED': 'مجدول', 'PAUSED': 'متوقف', 'DRAFT': 'مسودة', 'ENDED': 'انتهى'};
const _stateEn = {'ALL': 'All', 'ACTIVE': 'Running', 'SCHEDULED': 'Scheduled', 'PAUSED': 'Paused', 'DRAFT': 'Draft', 'ENDED': 'Ended'};

class OffersScreen extends StatefulWidget {
  const OffersScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<OffersScreen> createState() => _OffersScreenState();
}

class _OffersScreenState extends State<OffersScreen> {
  String _state = 'ALL';
  DateTimeRange? _range;
  int _n = 0;

  Future<void> _new() async {
    final type = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => const OfferTypePage()));
    if (type == null || !mounted) return;
    final saved = await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => OfferFormPage(ctx: widget.ctx, type: type)));
    if (saved == true && mounted) setState(() => _n++);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final ctx = widget.ctx;
    String money(num m) => s.money(m, ctx.currency);
    return Async<List<dynamic>>(
      key: ValueKey('offers-$_state-$_range-$_n'),
      load: () async {
        final list = await ctx.api.get('store/offers', query: {if (_state != 'ALL') 'state': _state, ...rangeQuery(_range)}) as List;
        final stats = await ctx.api.get('store/offers/stats');
        return [stats, list];
      },
      builder: (context, both, reload) {
        final stats = Map<String, dynamic>.from(both[0] as Map);
        final items = (both[1] as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
        final counts = Map<String, dynamic>.from((stats['states'] as Map?) ?? const {});
        return Scaffold(
          backgroundColor: Colors.transparent,
          floatingActionButton: ctx.can('coupon.manage') ? FloatingActionButton.extended(onPressed: _new, icon: const Icon(Icons.add_rounded), label: Text(ar ? 'عرض جديد' : 'New offer')) : null,
          body: RefreshIndicator(
            onRefresh: reload,
            child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 96), children: [
              Row(children: [
                Expanded(child: _stat(ar ? 'تعمل الآن' : 'Running', '${counts['ACTIVE'] ?? 0}', Icons.bolt_rounded, const Color(0xFF1F6A99))),
                const SizedBox(width: 8),
                Expanded(child: _stat(ar ? 'طلبات بعروض (30 يوم)' : 'Orders (30d)', '${stats['orders30d'] ?? 0}', Icons.shopping_bag_rounded, const Color(0xFF1E9E5A))),
              ]),
              const SizedBox(height: 8),
              Row(children: [
                Expanded(child: _stat(ar ? 'مبيعاتها' : 'Their sales', money((stats['revenue30dMinor'] as num?) ?? 0), Icons.trending_up_rounded, const Color(0xFF1E9E5A))),
                const SizedBox(width: 8),
                Expanded(child: _stat(ar ? 'خصومات ممنوحة' : 'Discounts given', money((stats['discount30dMinor'] as num?) ?? 0), Icons.local_offer_rounded, const Color(0xFFD98200))),
              ]),
              const SizedBox(height: 10),
              SizedBox(height: 48, child: ListView(scrollDirection: Axis.horizontal, children: [
                Padding(padding: const EdgeInsetsDirectional.only(end: 6), child: DateRangeChip(value: _range, label: ar ? 'تعمل خلال' : 'Running in', onChanged: (r) => setState(() => _range = r))),
                for (final st in _states) Padding(padding: const EdgeInsetsDirectional.only(end: 6), child: ChoiceChip(
                  label: Text('${(ar ? _stateAr : _stateEn)[st]}${st != 'ALL' && counts[st] != null ? ' (${counts[st]})' : ''}'), selected: _state == st, showCheckmark: false, onSelected: (_) => setState(() => _state = st))),
              ])),
              const SizedBox(height: 6),
              if (items.isEmpty) Padding(padding: const EdgeInsets.all(30), child: Column(children: [
                const Text('🎁', style: TextStyle(fontSize: 48)),
                const SizedBox(height: 8),
                Text(ar ? 'لا توجد عروض هنا بعد' : 'No offers here yet', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
                const SizedBox(height: 4),
                Text(ar ? 'العروض تحوّل الزوار لمشترين: ابدأ بتخفيض أو هدية أو توصيل مجاني.' : 'Offers turn visitors into buyers.', textAlign: TextAlign.center, style: const TextStyle(color: Color(0xFF6B7C8D))),
              ])),
              for (var i = 0; i < items.length; i++) FadeSlideIn(index: i < 8 ? i : 0, child: _card(context, items[i], reload, money, ar)),
            ]),
          ),
        );
      },
    );
  }

  Widget _stat(String label, String value, IconData icon, Color color) => Card(child: Padding(padding: const EdgeInsets.all(12), child: Row(children: [
        Container(width: 38, height: 38, decoration: BoxDecoration(color: color.withValues(alpha: .12), borderRadius: BorderRadius.circular(12)), child: Icon(icon, color: color, size: 20)),
        const SizedBox(width: 10),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 11, color: Color(0xFF6B7C8D))),
          Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        ])),
      ])));

  Widget _card(BuildContext context, Map<String, dynamic> o, Future<void> Function() reload, String Function(num) money, bool ar) {
    final ctx = widget.ctx;
    final type = '${o['type']}';
    final coupon = o['kind'] == 'COUPON';
    final base = coupon ? 'store/coupons/${o['id']}' : 'store/offers/${o['id']}';
    final state = '${o['state']}';
    final ends = DateTime.tryParse('${o['endsAt'] ?? ''}')?.toLocal();
    final left = ends?.difference(DateTime.now());
    return Card(clipBehavior: Clip.antiAlias, margin: const EdgeInsets.only(bottom: 10), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(gradient: offerGradient(type)),
        child: Row(children: [
          Text('${kOfferTypes[type]?[0] ?? '🎁'}', style: const TextStyle(fontSize: 30)),
          const SizedBox(width: 10),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(offerName(type, ar), style: const TextStyle(color: Colors.white70, fontSize: 12, fontWeight: FontWeight.w700)),
            Text(coupon ? '${o['code']}' : '${o['name']}', style: const TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.w900)),
          ])),
          Container(padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4), decoration: BoxDecoration(color: state == 'ACTIVE' ? Colors.white : Colors.black26, borderRadius: BorderRadius.circular(20)),
              child: Text((ar ? _stateAr : _stateEn)[state] ?? state, style: TextStyle(fontWeight: FontWeight.w900, fontSize: 11, color: state == 'ACTIVE' ? const Color(0xFF1E9E5A) : Colors.white))),
        ]),
      ),
      Padding(padding: const EdgeInsets.fromLTRB(14, 10, 14, 4), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(offerSummary(o, ar, money), style: const TextStyle(fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        Text(dotted([
          if (left != null && !left.isNegative) (left.inDays > 0 ? (ar ? 'متبقي ${left.inDays} يوم' : '${left.inDays}d left') : (ar ? 'متبقي ${left.inHours} ساعة' : '${left.inHours}h left')),
          '${ar ? 'استُخدم' : 'Used'} ${o['usageCount'] ?? 0}${o['maxUses'] != null ? ' / ${o['maxUses']}' : ''}',
          if (((o['discountGivenMinor'] as num?) ?? 0) > 0) '${ar ? 'مُنح' : 'Given'} ${money(o['discountGivenMinor'] as num)}',
        ]), style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
      ])),
      if (ctx.can('coupon.manage')) Padding(padding: const EdgeInsets.fromLTRB(6, 0, 6, 6), child: Row(children: [
        if (!coupon) TextButton.icon(onPressed: () async {
          final full = Map<String, dynamic>.from(await ctx.api.get('store/offers/${o['id']}') as Map);
          if (!context.mounted) return;
          if (await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => OfferFormPage(ctx: ctx, type: type, initial: full))) == true) await reload();
        }, icon: const Icon(Icons.edit_rounded, size: 18), label: Text(ar ? 'تعديل' : 'Edit')),
        if (state != 'ENDED') TextButton(onPressed: () async { if (await run(context, () => ctx.api.put('$base/active', {'active': !(o['active'] == true && o['draft'] != true)}))) await reload(); },
            child: Text(o['active'] == true && o['draft'] != true ? (ar ? '⏸ إيقاف' : '⏸ Pause') : (ar ? '▶ تشغيل' : '▶ Start'))),
        if (!coupon) TextButton(onPressed: () async { if (await run(context, () => ctx.api.post('store/offers/${o['id']}/duplicate', {}))) await reload(); }, child: Text(ar ? 'نسخ' : 'Copy')),
        const Spacer(),
        IconButton(icon: const Icon(Icons.delete_outline_rounded, color: Color(0xFFD14343)), onPressed: () async {
          if (await confirm(context, ar ? 'حذف هذا العرض؟' : 'Delete this offer?') && context.mounted && await run(context, () => ctx.api.delete(base))) await reload();
        }),
      ])),
    ]));
  }
}

/// The type gallery.
class OfferTypePage extends StatelessWidget {
  const OfferTypePage({super.key});
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'ما نوع العرض؟' : 'What kind of offer?')),
      body: GridView.count(
        crossAxisCount: MediaQuery.of(context).size.width > 600 ? 3 : 2,
        padding: const EdgeInsets.all(14),
        mainAxisSpacing: 12, crossAxisSpacing: 12, childAspectRatio: .82,
        children: [
          for (final e in kOfferTypes.entries) InkWell(
            borderRadius: BorderRadius.circular(20),
            onTap: () => Navigator.pop(context, e.key),
            child: Ink(
              decoration: BoxDecoration(color: Theme.of(context).cardColor, borderRadius: BorderRadius.circular(20), boxShadow: const [BoxShadow(color: Color(0x14000000), blurRadius: 14, offset: Offset(0, 6))]),
              child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Container(width: 48, height: 48, alignment: Alignment.center, decoration: BoxDecoration(gradient: offerGradient(e.key), borderRadius: BorderRadius.circular(14)), child: Text('${e.value[0]}', style: const TextStyle(fontSize: 24))),
                const SizedBox(height: 10),
                Text('${e.value[ar ? 3 : 4]}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)),
                const SizedBox(height: 4),
                Expanded(child: Text('${e.value[ar ? 5 : 6]}', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D), height: 1.35))),
              ])),
            ),
          ),
        ],
      ),
    );
  }
}

/// Create / edit one offer; a coupon goes to the coupons API.
class OfferFormPage extends StatefulWidget {
  const OfferFormPage({super.key, required this.ctx, required this.type, this.initial});
  final Ctx ctx;
  final String type;
  final Map<String, dynamic>? initial;
  @override
  State<OfferFormPage> createState() => _OfferFormPageState();
}

class _OfferFormPageState extends State<OfferFormPage> {
  final fe = FieldErrors();
  late final Map<String, dynamic> c0 = Map<String, dynamic>.from((widget.initial?['config'] as Map?) ?? const {});
  late final name = TextEditingController(text: '${widget.initial?['name'] ?? ''}');
  late final badge = TextEditingController(text: '${widget.initial?['badgeText'] ?? ''}');
  late final desc = TextEditingController(text: '${widget.initial?['description'] ?? ''}');
  late final percent = TextEditingController(text: '${c0['percent'] ?? ''}');
  late final amount = TextEditingController(text: c0['amountMinor'] == null ? '' : fmtMoney(c0['amountMinor'] as num));
  late final buyQty = TextEditingController(text: '${c0['buyQty'] ?? 1}');
  late final getQty = TextEditingController(text: '${c0['getQty'] ?? 1}');
  late final getPercent = TextEditingController(text: '${c0['getPercent'] ?? 100}');
  late final bundle = TextEditingController(text: c0['bundlePriceMinor'] == null ? '' : fmtMoney(c0['bundlePriceMinor'] as num));
  late final minSub = TextEditingController(text: c0['minSubtotalMinor'] == null ? '' : fmtMoney(c0['minSubtotalMinor'] as num));
  late final value = TextEditingController(text: c0['value'] == null ? '' : (c0['discountType'] == 'FIXED' ? fmtMoney(c0['value'] as num) : '${c0['value']}'));
  late final maxDiscount = TextEditingController(text: c0['maxDiscountMinor'] == null ? '' : fmtMoney(c0['maxDiscountMinor'] as num));
  late final giftQty = TextEditingController(text: '${c0['giftQty'] ?? 1}');
  late final maxUses = TextEditingController(text: '${widget.initial?['maxUses'] ?? ''}');
  final code = TextEditingController(), perCustomer = TextEditingController();
  late String discountType = '${c0['discountType'] ?? 'PERCENT'}';
  late String appliesTo = '${widget.initial?['appliesTo'] ?? 'ALL'}';
  late bool stackable = widget.initial?['stackable'] == true, showBanner = widget.initial?['showBanner'] == true || widget.type == 'FLASH_SALE';
  late DateTime? starts = DateTime.tryParse('${widget.initial?['startsAt'] ?? ''}')?.toLocal(), ends = DateTime.tryParse('${widget.initial?['endsAt'] ?? ''}')?.toLocal();
  late List<Map<String, String>> tiers = ((c0['tiers'] as List?) ?? const [{'minQty': 2, 'percent': 10}, {'minQty': 3, 'percent': 15}]).map((t) => {'minQty': '${t['minQty']}', 'percent': '${t['percent']}'}).toList();
  late List<Map<String, dynamic>> products = ((widget.initial?['products'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  late List<Map<String, dynamic>> cats = ((widget.initial?['categoryIds'] as List?) ?? const []).map((id) => <String, dynamic>{'id': id, 'name': '#$id'}).toList();
  Map<String, dynamic>? gift;
  bool busy = false;
  String get type => widget.type;

  @override
  void initState() { super.initState(); fe.addListener(() { if (mounted) setState(() {}); }); }

  Future<DateTime?> _pickDateTime(DateTime? cur) async {
    final d = await showDatePicker(context: context, firstDate: DateTime.now().subtract(const Duration(days: 1)), lastDate: DateTime.now().add(const Duration(days: 730)), initialDate: cur ?? DateTime.now());
    if (d == null || !mounted) return cur;
    final t = await showTimePicker(context: context, initialTime: TimeOfDay.fromDateTime(cur ?? DateTime.now()));
    return DateTime(d.year, d.month, d.day, t?.hour ?? 0, t?.minute ?? 0);
  }

  Future<void> _pickProducts({bool single = false}) async {
    final picked = await showModalBottomSheet<List<Map<String, dynamic>>>(context: context, isScrollControlled: true, showDragHandle: true, builder: (_) => _ProductPicker(ctx: widget.ctx, initial: single ? const [] : products, single: single));
    if (picked == null) return;
    if (single) {
      if (picked.isEmpty) return;
      final d = Map<String, dynamic>.from(await widget.ctx.api.get('store/products/${picked.first['id']}') as Map);
      final vs = ((d['variants'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).where((v) => v['status'] == 'ACTIVE').toList();
      if (vs.isEmpty || !mounted) return;
      final v = vs.length == 1 ? vs.first : await showModalBottomSheet<Map<String, dynamic>>(context: context, showDragHandle: true, builder: (c) => ListView(shrinkWrap: true, children: [for (final x in vs) ListTile(title: Text(comboLabel(x['comboKey']).isEmpty ? '${x['sku']}' : comboLabel(x['comboKey'])), onTap: () => Navigator.pop(c, x))]));
      if (v != null) setState(() { gift = {...v, 'productName': d['name']}; fe.clear('config.giftVariantId'); });
    } else {
      setState(() { products = picked; fe.clear('productIds'); });
    }
  }

  Map<String, dynamic> _config() => switch (type) {
        'PERCENT_OFF' || 'FLASH_SALE' => {'percent': int.tryParse(percent.text.trim())},
        'FIXED_OFF' => {'amountMinor': toMinor(amount.text)},
        'BUY_X_GET_Y' => {'buyQty': int.tryParse(buyQty.text.trim()), 'getQty': int.tryParse(getQty.text.trim()), 'getPercent': int.tryParse(getPercent.text.trim()) ?? 100},
        'BUNDLE_PRICE' => {'bundlePriceMinor': toMinor(bundle.text)},
        'TIERED_QTY' => {'tiers': [for (final t in tiers) {'minQty': int.tryParse(t['minQty']!), 'percent': int.tryParse(t['percent']!)}]},
        'ORDER_THRESHOLD' => {'minSubtotalMinor': toMinor(minSub.text), 'discountType': discountType, 'value': discountType == 'FIXED' ? toMinor(value.text) : int.tryParse(value.text.trim()), if (maxDiscount.text.trim().isNotEmpty) 'maxDiscountMinor': toMinor(maxDiscount.text)},
        'FREE_SHIPPING' => {'minSubtotalMinor': minSub.text.trim().isEmpty ? 0 : toMinor(minSub.text)},
        'GIFT_WITH_PURCHASE' => {'minSubtotalMinor': minSub.text.trim().isEmpty ? 0 : toMinor(minSub.text), 'giftVariantId': gift?['id'] ?? c0['giftVariantId'], 'giftQty': int.tryParse(giftQty.text.trim()) ?? 1},
        _ => {'discountType': discountType, 'value': discountType == 'FIXED' ? toMinor(value.text) : int.tryParse(value.text.trim())},
      };

  Future<void> _save({bool draft = false}) async {
    final ar = AppLocale.isAr;
    if (!fe.check({
      if (type != 'COUPON' && name.text.trim().isEmpty) 'name': ar ? 'اكتب اسم العرض' : 'Give the offer a name',
      if (type == 'COUPON' && !RegExp(r'^[A-Za-z0-9_-]{3,40}$').hasMatch(code.text.trim())) 'code': ar ? 'استخدم من 3 إلى 40 حرفًا أو رقمًا' : 'Use 3–40 letters or numbers',
      if (type == 'FLASH_SALE' && ends == null) 'endsAt': ar ? 'العرض الخاطف يحتاج وقت انتهاء للعداد' : 'A flash sale needs an end time',
      if (starts != null && ends != null && !ends!.isAfter(starts!)) 'endsAt': ar ? 'النهاية يجب أن تكون بعد البداية' : 'The end must be after the start',
    })) { toast(context, ar ? 'من فضلك صحّح الحقول المعلَّمة' : 'Please fix the marked fields', isError: true); return; }
    setState(() => busy = true);
    final api = widget.ctx.api;
    final ok = await runForm(context, fe, () async {
      if (type == 'COUPON') {
        await api.post('store/coupons', {'code': code.text.trim().toUpperCase(), 'discountType': discountType, 'value': discountType == 'PERCENT' ? (int.tryParse(value.text.trim()) ?? 0) : toMinor(value.text),
          'minOrderMinor': minSub.text.trim().isEmpty ? 0 : toMinor(minSub.text), if (starts != null) 'startsAt': starts!.toUtc().toIso8601String(), if (ends != null) 'endsAt': ends!.toUtc().toIso8601String(),
          if (maxUses.text.trim().isNotEmpty) 'maxRedemptions': int.tryParse(maxUses.text.trim()), if (perCustomer.text.trim().isNotEmpty) 'perCustomerLimit': int.tryParse(perCustomer.text.trim())});
      } else {
        final body = {'name': name.text.trim(), 'type': type, 'active': !draft, 'draft': draft, 'startsAt': starts?.toUtc().toIso8601String(), 'endsAt': ends?.toUtc().toIso8601String(), 'stackable': stackable,
          'appliesTo': type == 'BUNDLE_PRICE' ? 'PRODUCTS' : appliesTo, 'productIds': [for (final p in products) '${p['id']}'], 'categoryIds': [for (final c in cats) c['id']], 'badgeText': badge.text.trim(),
          'description': desc.text.trim(), 'showBanner': showBanner, 'config': _config(), 'maxUses': maxUses.text.trim().isEmpty ? null : int.tryParse(maxUses.text.trim())};
        if (widget.initial != null) { await api.put('store/offers/${widget.initial!['id']}', body); } else { await api.post('store/offers', body); }
      }
    });
    if (!mounted) return;
    setState(() => busy = false);
    if (ok) { toast(context, ar ? 'تم حفظ العرض' : 'Offer saved'); Navigator.pop(context, true); }
  }

  Widget _section(String title, List<Widget> children) => Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(title, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)), const SizedBox(height: 10), ...children,
      ])));

  Widget _err(String path) => fe.of(path) == null ? const SizedBox.shrink() : Padding(padding: const EdgeInsets.only(bottom: 6), child: Text(fe.of(path)!, style: const TextStyle(color: Color(0xFFD14343), fontWeight: FontWeight.w700, fontSize: 12)));

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final cur = widget.ctx.currency;
    String when(DateTime? d) => d == null ? (ar ? 'غير محدد' : 'Not set') : '${d.day}/${d.month}/${d.year}  ${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
    final itemScope = ['PERCENT_OFF', 'FIXED_OFF', 'FLASH_SALE', 'BUY_X_GET_Y', 'TIERED_QTY', 'FREE_SHIPPING', 'GIFT_WITH_PURCHASE'].contains(type);
    return Scaffold(
      appBar: AppBar(title: Text(widget.initial != null ? '${widget.initial!['name']}' : offerName(type, ar))),
      body: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 120), children: [
        Container(
          padding: const EdgeInsets.all(16), margin: const EdgeInsets.only(bottom: 10),
          decoration: BoxDecoration(gradient: offerGradient(type), borderRadius: BorderRadius.circular(20)),
          child: Row(children: [
            Text('${kOfferTypes[type]?[0]}', style: const TextStyle(fontSize: 34)),
            const SizedBox(width: 12),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(offerName(type, ar), style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 18)),
              Text('${kOfferTypes[type]?[ar ? 5 : 6]}', style: const TextStyle(color: Colors.white70)),
            ])),
          ]),
        ),
        _section(ar ? 'الاسم' : 'Name', [
          if (type == 'COUPON') tf(code, ar ? 'كود الكوبون' : 'Coupon code', ltr: true, fe: fe, field: 'code', hint: 'RAMADAN25')
          else ...[tf(name, ar ? 'اسم العرض' : 'Offer name', fe: fe, field: 'name'), tf(desc, ar ? 'وصف قصير (اختياري)' : 'Short description (optional)', lines: 2)],
        ]),
        _section(ar ? 'تفاصيل العرض' : 'The offer', [
          if (type == 'PERCENT_OFF' || type == 'FLASH_SALE') ...[
            Wrap(spacing: 6, children: [for (final p in [10, 15, 20, 25, 30, 50]) ChoiceChip(label: Text('$p%'), selected: percent.text == '$p', onSelected: (_) => setState(() { percent.text = '$p'; fe.clear('config.percent'); }))]),
            const SizedBox(height: 8),
            tf(percent, ar ? 'نسبة الخصم %' : 'Discount %', number: true, ltr: true, fe: fe, field: 'config.percent', onChanged: (_) => setState(() {})),
          ],
          if (type == 'FIXED_OFF') tf(amount, '${ar ? 'المبلغ المخصوم من كل قطعة' : 'Amount off each piece'} ($cur)', number: true, ltr: true, fe: fe, field: 'config.amountMinor'),
          if (type == 'BUY_X_GET_Y') Row(children: [
            Expanded(child: tf(buyQty, ar ? 'يشتري' : 'Buys', number: true, ltr: true, fe: fe, field: 'config.buyQty')), const SizedBox(width: 8),
            Expanded(child: tf(getQty, ar ? 'ويحصل على' : 'Gets', number: true, ltr: true, fe: fe, field: 'config.getQty')), const SizedBox(width: 8),
            Expanded(child: tf(getPercent, ar ? 'خصم عليها % (100 = مجاني)' : '% off (100 = free)', number: true, ltr: true, fe: fe, field: 'config.getPercent')),
          ]),
          if (type == 'BUNDLE_PRICE') tf(bundle, '${ar ? 'سعر الباقة' : 'Bundle price'} ($cur)', number: true, ltr: true, fe: fe, field: 'config.bundlePriceMinor'),
          if (type == 'TIERED_QTY') ...[
            _err('config.tiers'),
            for (var i = 0; i < tiers.length; i++) Row(key: i == 0 ? fe.key('config.tiers') : null, children: [
              Text(ar ? 'من ' : 'From '), SizedBox(width: 60, child: TextField(controller: TextEditingController(text: tiers[i]['minQty']), keyboardType: TextInputType.number, onChanged: (v) => tiers[i]['minQty'] = v)),
              Text(ar ? ' قطع ← خصم ' : ' pieces → '), SizedBox(width: 60, child: TextField(controller: TextEditingController(text: tiers[i]['percent']), keyboardType: TextInputType.number, onChanged: (v) => tiers[i]['percent'] = v)), const Text(' %'),
              IconButton(onPressed: () => setState(() => tiers.removeAt(i)), icon: const Icon(Icons.close_rounded)),
            ]),
            TextButton.icon(onPressed: () => setState(() => tiers.add({'minQty': '${(int.tryParse(tiers.isEmpty ? '1' : tiers.last['minQty']!) ?? 1) + 2}', 'percent': ''})), icon: const Icon(Icons.add_rounded), label: Text(ar ? 'إضافة شريحة' : 'Add a tier')),
          ],
          if (['ORDER_THRESHOLD', 'FREE_SHIPPING', 'GIFT_WITH_PURCHASE', 'COUPON'].contains(type)) tf(minSub, '${ar ? 'قيمة السلة من' : 'Cart amount from'} ($cur)', number: true, ltr: true, fe: fe, field: 'config.minSubtotalMinor', hint: ar ? 'فارغ = أي طلب' : 'Empty = any order'),
          if (['ORDER_THRESHOLD', 'FIRST_ORDER', 'COUPON'].contains(type)) ...[
            SegmentedButton<String>(segments: [ButtonSegment(value: 'PERCENT', label: Text(ar ? 'نسبة %' : 'Percent')), ButtonSegment(value: 'FIXED', label: Text(ar ? 'مبلغ' : 'Amount'))], selected: {discountType}, onSelectionChanged: (v) => setState(() => discountType = v.first)),
            const SizedBox(height: 10),
            tf(value, discountType == 'PERCENT' ? (ar ? 'النسبة %' : 'Percent %') : '${ar ? 'المبلغ' : 'Amount'} ($cur)', number: true, ltr: true, fe: fe, field: 'config.value'),
            if (type == 'ORDER_THRESHOLD' && discountType == 'PERCENT') tf(maxDiscount, '${ar ? 'أقصى خصم (اختياري)' : 'Max discount (optional)'} ($cur)', number: true, ltr: true),
          ],
          if (type == 'GIFT_WITH_PURCHASE') ...[
            _err('config.giftVariantId'),
            OutlinedButton.icon(key: fe.key('config.giftVariantId'), onPressed: () => _pickProducts(single: true), icon: const Icon(Icons.card_giftcard_rounded), label: Text(gift != null ? '${gift!['productName']} ${comboLabel(gift!['comboKey'])}' : (c0['giftVariantId'] != null ? (ar ? 'تم اختيار الهدية (تغيير)' : 'Gift chosen (change)') : (ar ? 'اختر الهدية' : 'Choose the gift')))),
            const SizedBox(height: 8),
            tf(giftQty, ar ? 'عدد قطع الهدية' : 'Gift pieces', number: true, ltr: true),
          ],
        ]),
        if (itemScope || type == 'BUNDLE_PRICE') _section(ar ? 'على أي منتجات' : 'Which products', [
          if (type != 'BUNDLE_PRICE') Wrap(spacing: 6, children: [for (final a in const ['ALL', 'PRODUCTS', 'CATEGORIES']) ChoiceChip(label: Text(ar ? {'ALL': 'المتجر كله', 'PRODUCTS': 'منتجات محددة', 'CATEGORIES': 'أقسام محددة'}[a]! : {'ALL': 'Whole shop', 'PRODUCTS': 'Products', 'CATEGORIES': 'Categories'}[a]!), selected: appliesTo == a, onSelected: (_) => setState(() => appliesTo = a))]),
          if (appliesTo == 'PRODUCTS' || type == 'BUNDLE_PRICE') ...[
            const SizedBox(height: 8), _err('productIds'),
            Wrap(spacing: 6, runSpacing: 6, children: [for (final p in products) InputChip(label: Text('${p['name']}'), onDeleted: () => setState(() => products.remove(p)))]),
            OutlinedButton.icon(key: fe.key('productIds'), onPressed: _pickProducts, icon: const Icon(Icons.add_rounded), label: Text(ar ? 'اختر المنتجات' : 'Choose products')),
          ],
          if (appliesTo == 'CATEGORIES' && type != 'BUNDLE_PRICE') ...[
            const SizedBox(height: 8), _err('categoryIds'),
            Wrap(spacing: 6, runSpacing: 6, children: [for (final c in cats) InputChip(label: Text('${c['name']}'), onDeleted: () => setState(() => cats.remove(c)))]),
            OutlinedButton.icon(key: fe.key('categoryIds'), onPressed: () async {
              final n = await Navigator.of(context).push<Map<String, dynamic>>(MaterialPageRoute(builder: (_) => TaxonomyPage(ctx: widget.ctx)));
              if (n != null && mounted && !cats.any((c) => c['id'] == n['id'])) setState(() { cats.add({'id': n['id'], 'name': n['name']}); fe.clear('categoryIds'); });
            }, icon: const Icon(Icons.add_rounded), label: Text(ar ? 'اختر قسمًا' : 'Choose a category')),
          ],
        ]),
        _section(ar ? 'المدة' : 'When', [
          ListTile(contentPadding: EdgeInsets.zero, leading: const Icon(Icons.play_circle_outline_rounded), title: Text(ar ? 'يبدأ' : 'Starts'), subtitle: Text(starts == null ? (ar ? 'الآن' : 'Now') : when(starts)), onTap: () async { final d = await _pickDateTime(starts); setState(() => starts = d); }, trailing: starts == null ? null : IconButton(icon: const Icon(Icons.close_rounded), onPressed: () => setState(() => starts = null))),
          ListTile(key: fe.key('endsAt'), contentPadding: EdgeInsets.zero, leading: const Icon(Icons.stop_circle_outlined), title: Text(ar ? 'ينتهي' : 'Ends'), subtitle: Text(ends == null ? (ar ? 'حتى توقفه' : 'Until you stop it') : when(ends)), onTap: () async { final d = await _pickDateTime(ends); setState(() { ends = d; fe.clear('endsAt'); }); }, trailing: ends == null ? null : IconButton(icon: const Icon(Icons.close_rounded), onPressed: () => setState(() => ends = null))),
          _err('endsAt'),
          Wrap(spacing: 6, children: [for (final h in const [24, 72, 168, 720]) ActionChip(label: Text(ar ? {24: '24 ساعة', 72: '3 أيام', 168: 'أسبوع', 720: 'شهر'}[h]! : {24: '24h', 72: '3 days', 168: 'A week', 720: 'A month'}[h]!), onPressed: () => setState(() { final st = starts ?? DateTime.now(); ends = st.add(Duration(hours: h)); fe.clear('endsAt'); }))]),
          const SizedBox(height: 10),
          tf(maxUses, type == 'COUPON' ? (ar ? 'إجمالي مرات الاستخدام (اختياري)' : 'Total uses (optional)') : (ar ? 'يتوقف بعد عدد طلبات (اختياري)' : 'Stop after orders (optional)'), number: true, ltr: true, fe: fe, field: 'maxUses'),
          if (type == 'COUPON') tf(perCustomer, ar ? 'مرات لكل عميل (اختياري)' : 'Uses per customer (optional)', number: true, ltr: true),
        ]),
        if (type != 'COUPON') _section(ar ? 'طريقة الظهور' : 'How it shows', [
          tf(badge, ar ? 'شارة على المنتجات (مثال: −20%)' : 'Badge on products (e.g. −20%)', fe: fe, field: 'badgeText'),
          SwitchListTile(contentPadding: EdgeInsets.zero, value: showBanner, onChanged: (v) => setState(() => showBanner = v), title: Text(ar ? 'أعلن عنه في الصفحة الرئيسية' : 'Announce it on the home page')),
          if (!itemScope || ['BUY_X_GET_Y', 'TIERED_QTY'].contains(type)) SwitchListTile(contentPadding: EdgeInsets.zero, value: stackable, onChanged: (v) => setState(() => stackable = v), title: Text(ar ? 'يمكن جمعه مع عروض أخرى' : 'Can add up with other offers')),
        ]),
      ]),
      bottomNavigationBar: SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(16, 6, 16, 10), child: Row(children: [
        Expanded(child: FilledButton(onPressed: busy ? null : () => _save(), child: busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(widget.initial != null ? (ar ? 'حفظ' : 'Save') : type == 'COUPON' ? (ar ? 'إنشاء الكوبون' : 'Create coupon') : (ar ? 'تشغيل العرض' : 'Launch offer')))),
        if (type != 'COUPON' && widget.initial == null) ...[const SizedBox(width: 10), OutlinedButton(onPressed: busy ? null : () => _save(draft: true), child: Text(ar ? 'مسودة' : 'Draft'))],
      ]))),
    );
  }
}

class _ProductPicker extends StatefulWidget {
  const _ProductPicker({required this.ctx, required this.initial, required this.single});
  final Ctx ctx;
  final List<Map<String, dynamic>> initial;
  final bool single;
  @override
  State<_ProductPicker> createState() => _ProductPickerState();
}

class _ProductPickerState extends State<_ProductPicker> {
  late final picked = [...widget.initial];
  final q = TextEditingController();
  List<Map<String, dynamic>> res = [];

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try {
      final r = await widget.ctx.api.get('store/products', query: {'pageSize': '30', if (q.text.trim().isNotEmpty) 'q': q.text.trim()}) as Map;
      if (mounted) setState(() => res = ((r['data'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList());
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return SizedBox(height: MediaQuery.of(context).size.height * .8, child: Column(children: [
      Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: TextField(controller: q, onChanged: (_) => _load(), decoration: InputDecoration(prefixIcon: const Icon(Icons.search_rounded), hintText: ar ? 'ابحث في منتجاتك' : 'Search your products'))),
      Expanded(child: ListView(children: [
        for (final p in res) CheckboxListTile(
          value: picked.any((x) => x['id'] == p['id']),
          title: Text('${p['name']}'),
          onChanged: (v) {
            if (widget.single) { Navigator.pop(context, [p]); return; }
            setState(() => v == true ? picked.add(p) : picked.removeWhere((x) => x['id'] == p['id']));
          },
        ),
      ])),
      if (!widget.single) SafeArea(child: Padding(padding: const EdgeInsets.all(12), child: SizedBox(width: double.infinity, child: FilledButton(onPressed: () => Navigator.pop(context, picked), child: Text(ar ? 'تم (${picked.length})' : 'Done (${picked.length})'))))),
    ]));
  }
}
