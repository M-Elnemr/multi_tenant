// ignore_for_file: use_build_context_synchronously
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';
import 'settings.dart';
import 'theme/templates.dart';

// Customer segments and broadcasts of the shop: pick segments and platforms, see who can be reached, write one message and watch the live queue.
// Every outgoing message is charged (messagePriceMinor from the API, 0.1 EGP by default). The shop's own WhatsApp number is the only WhatsApp route.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

/// Pairs written {masculine|feminine}: the server picks the side that fits each customer. (label on the chip, text inserted)
const _forms = [('أهلاً بيك/بيكي', 'أهلاً {بيك|بيكي}'), ('ليك/ليكي', '{ليك|ليكي}'), ('تحب/تحبي', '{تحب|تحبي}'), ('اتفضل/اتفضلي', '{اتفضل|اتفضلي}'), ('تعالى/تعالي', '{تعالى|تعالي}'), ('شوف/شوفي', '{شوف|شوفي}'), ('كمّل/كمّلي', '{كمّل|كمّلي}'), ('معاك/معاكي', '{معاك|معاكي}')];

/// {{a|b|c}}: each customer gets one of the wordings, so the messages are not all identical. (label on the chip, text inserted)
const _spins = [('هلا|أهلاً|مرحباً', '{{هلا|أهلاً|مرحباً}}'), ('عندنا|متاح عندنا|وصلنا', '{{عندنا|متاح عندنا|وصلنا}}'), ('شكراً|ميرسي|تسلم', '{{شكراً|ميرسي|تسلم}}'), ('قريب|دلوقتي|النهارده', '{{قريب|دلوقتي|النهارده}}')];

const _emoji = ['😀', '😍', '🥰', '😎', '🤩', '🙏', '👍', '👏', '🔥', '✨', '🎉', '🎁', '💥', '💯', '❤️', '🛍️', '🛒', '🏷️', '💸', '💰', '⭐', '🌟', '⏰', '📣', '📦', '🚚', '✅', '👇', '👉', '🌸', '☀️', '🌙', '🎊', '🥳', '😉', '🤝', '💐', '🧡', '💙', '🆕'];

const _segmentKeys = ['ALL', 'NEW', 'REPEAT', 'VIP', 'AT_RISK', 'LAPSED'];
const _segmentNames = {'ALL': ('كل العملاء', 'All customers'), 'NEW': ('جدد', 'New'), 'REPEAT': ('متكررين', 'Repeat'), 'VIP': ('VIP', 'VIP'), 'AT_RISK': ('معرضين للفقد', 'At risk'), 'LAPSED': ('غايبين', 'Lapsed')};
const _platformKeys = ['WHATSAPP', 'FACEBOOK', 'INSTAGRAM'];
const _platformNames = {'WHATSAPP': ('واتساب', 'WhatsApp'), 'FACEBOOK': ('ماسنجر', 'Messenger'), 'INSTAGRAM': ('إنستجرام', 'Instagram')};

Color _platformColor(String p) => switch (p) { 'WHATSAPP' => const Color(0xFF1E9E5A), 'FACEBOOK' => const Color(0xFF1877F2), 'INSTAGRAM' => const Color(0xFFC13584), _ => const Color(0xFF6B7C8D) };
IconData _platformIcon(String p) => switch (p) { 'WHATSAPP' => Icons.chat_rounded, 'FACEBOOK' => Icons.messenger_rounded, 'INSTAGRAM' => Icons.camera_alt_rounded, _ => Icons.forum_rounded };
String _platformLabel(String p, bool ar) { final n = _platformNames[p]; return n == null ? p : (ar ? n.$1 : n.$2); }

/// Small coloured platform tag next to a customer or a recipient.
class PlatformTag extends StatelessWidget {
  const PlatformTag(this.platform, {super.key});
  final String platform;
  @override
  Widget build(BuildContext context) {
    final c = _platformColor(platform);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(10)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(_platformIcon(platform), size: 12, color: c), const SizedBox(width: 3), Text(_platformLabel(platform, AppLocale.isAr), style: TextStyle(fontSize: 10.5, fontWeight: FontWeight.w800, color: c))]),
    );
  }
}

/// Three small dots that pulse one after the other (the recipient being sent right now).
class LoadingDots extends StatefulWidget {
  const LoadingDots({super.key, this.color = const Color(0xFFB45309), this.size = 5});
  final Color color;
  final double size;
  @override
  State<LoadingDots> createState() => _LoadingDotsState();
}

class _LoadingDotsState extends State<LoadingDots> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1000))..repeat();
  @override
  void dispose() { _c.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _c,
        builder: (context, _) => Row(mainAxisSize: MainAxisSize.min, children: [
          for (var i = 0; i < 3; i++)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 1.5),
              child: Opacity(
                opacity: 0.25 + 0.75 * (1 - ((_c.value * 3 - i) % 3).clamp(0.0, 1.0)).clamp(0.0, 1.0),
                child: Container(width: widget.size, height: widget.size, decoration: BoxDecoration(color: widget.color, shape: BoxShape.circle)),
              ),
            ),
        ]),
      );
}

class SegmentsScreen extends StatefulWidget {
  const SegmentsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<SegmentsScreen> createState() => _SegmentsScreenState();
}

class _SegmentsScreenState extends State<SegmentsScreen> with WidgetsBindingObserver {
  // audience
  final _segs = <String>{'ALL'};
  final _plats = <String>{..._platformKeys};
  final _search = TextEditingController();
  Timer? _debounce, _poll;
  Map<String, dynamic>? _top, _list;
  List<Map<String, dynamic>> _customers = [];
  List<Map<String, dynamic>> _history = [];
  Object? _error;
  bool _loading = true, _listLoading = false, _moreLoading = false;
  AppLifecycleState _life = AppLifecycleState.resumed;
  // composer
  final _msg = TextEditingController(), _pushTitle = TextEditingController();
  String? _image;
  final _versions = <String>[];
  bool _push = false, _busy = false, _female = true;
  // live queue
  String? _watchId;
  List<Map<String, dynamic>> _recipients = [];
  Map<String, dynamic> _counts = const {};
  int _recTotal = 0;
  String _recFilter = '';

  bool get _write => widget.ctx.can('growth.manage');

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _tick());
    _loadAll();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _debounce?.cancel();
    _poll?.cancel();
    _search.dispose(); _msg.dispose(); _pushTitle.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _life = state;
    if (state == AppLifecycleState.resumed && mounted) _tick(force: true);
  }

  Map<String, dynamic>? get _watched {
    for (final b in _history) { if ('${b['id']}' == _watchId) return b; }
    return null;
  }

  /// The broadcast shown in the live queue: the one picked from the history, otherwise the one sending (or paused) now.
  Map<String, dynamic>? get _live {
    final w = _watched;
    if (w != null) return w;
    for (final b in _history) { if (b['status'] == 'SENDING') return b; }
    for (final b in _history) { if (b['status'] == 'PAUSED') return b; }
    return null;
  }

  bool get _visible => mounted && _life == AppLifecycleState.resumed && (ModalRoute.of(context)?.isCurrent ?? true);

  /// Every 3 seconds, but only while a broadcast is SENDING and the screen is in front.
  Future<void> _tick({bool force = false}) async {
    if (!_visible) return;
    final live = _live;
    if (live == null || (live['status'] != 'SENDING' && !force)) return;
    await _loadHistory();
    await _loadRecipients();
  }

  String _csv(Set<String> s) => s.join(',');
  Map<String, String?> get _audienceQuery => {'segments': _csv(_segs), 'platforms': _csv(_plats)};

  Future<void> _loadAll() async {
    setState(() { _loading = _top == null; _error = null; });
    try {
      await Future.wait([_loadTop(), _loadCustomers(), _loadHistory()]);
      await _loadRecipients();
    } catch (e) {
      if (mounted) setState(() { if (_top == null) _error = e; });
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _loadTop() async {
    final r = await widget.ctx.api.get('store/segments', query: {'platforms': _csv(_plats)}) as Map;
    if (mounted) setState(() => _top = Map<String, dynamic>.from(r));
  }

  Future<void> _loadCustomers({bool more = false}) async {
    if (!more && mounted) setState(() => _listLoading = true);
    final r = await widget.ctx.api.get('store/segments/customers', query: {..._audienceQuery, 'q': _search.text.trim(), 'limit': '50', 'offset': more ? '${_customers.length}' : '0'}) as Map;
    if (!mounted) return;
    setState(() {
      _list = Map<String, dynamic>.from(r);
      _customers = more ? [..._customers, ..._maps(r['data'])] : _maps(r['data']);
      _listLoading = false;
    });
  }

  Future<void> _loadHistory() async {
    final r = await widget.ctx.api.get('store/broadcasts');
    if (mounted) setState(() => _history = _maps(r is Map ? r['data'] : r));
  }

  Future<void> _loadRecipients({bool more = false}) async {
    final live = _live;
    if (live == null) { if (mounted) setState(() { _recipients = []; _counts = const {}; _recTotal = 0; }); return; }
    try {
      final r = await widget.ctx.api.get('store/broadcasts/${live['id']}/recipients', query: {if (_recFilter.isNotEmpty) 'status': _recFilter, 'limit': '100', 'offset': more ? '${_recipients.length}' : '0'}) as Map;
      if (!mounted) return;
      setState(() {
        _recipients = more ? [..._recipients, ..._maps(r['data'])] : _maps(r['data']);
        _counts = Map<String, dynamic>.from((r['counts'] as Map?) ?? const {});
        _recTotal = (r['total'] as num?)?.toInt() ?? _recipients.length;
      });
    } catch (_) { /* the next tick tries again */ }
  }

  void _audienceChanged() {
    setState(() {});
    _loadTop().catchError((_) {});
    _loadCustomers().catchError((_) { if (mounted) setState(() => _listLoading = false); });
  }

  void _toggleSegment(String k) {
    if (k == 'ALL') { _segs..clear()..add('ALL'); }
    else {
      _segs.remove('ALL');
      if (!_segs.remove(k)) _segs.add(k);
      if (_segs.isEmpty) _segs.add('ALL');
    }
    _audienceChanged();
  }

  void _togglePlatform(String k) {
    if (_plats.contains(k)) { if (_plats.length > 1) _plats.remove(k); } else { _plats.add(k); }
    _audienceChanged();
  }

  // ---- composer helpers ----

  void _insert(String t) {
    final v = _msg.value;
    final a = v.selection.isValid ? v.selection.start : v.text.length, b = v.selection.isValid ? v.selection.end : v.text.length;
    final text = v.text.replaceRange(a, b, t);
    _msg.value = TextEditingValue(text: text.length > 1000 ? text.substring(0, 1000) : text, selection: TextSelection.collapsed(offset: a + t.length));
    setState(() {});
  }

  void _wrap(String mark) {
    final v = _msg.value;
    if (!v.selection.isValid) return;
    final a = v.selection.start, b = v.selection.end;
    _msg.value = TextEditingValue(text: '${v.text.substring(0, a)}$mark${v.text.substring(a, b)}$mark${v.text.substring(b)}', selection: TextSelection(baseOffset: a + mark.length, extentOffset: b + mark.length));
    setState(() {});
  }

  Future<void> _customSpin() async {
    final ar = S.of(context).ar;
    final c = TextEditingController();
    final ok = await showDialog<bool>(context: context, builder: (d) => AlertDialog(
      title: Text(ar ? 'كلمات متنوعة' : 'Different wording'),
      content: TextField(controller: c, decoration: InputDecoration(hintText: ar ? 'كلمات بينها فاصلة، مثال: هلا، أهلاً، مرحباً' : 'Words separated by commas, e.g. hi, hello, welcome')),
      actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'أضف' : 'Add'))]));
    final parts = c.text.replaceAll(RegExp(r'[{}]'), '').split(RegExp(r'[|،,]')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList();
    c.dispose();
    if (ok == true && parts.length >= 2) _insert('{{${parts.join('|')}}}');
  }

  Future<void> _customForm() async {
    final ar = S.of(context).ar;
    final m = TextEditingController(), f = TextEditingController();
    final ok = await showDialog<bool>(context: context, builder: (d) => AlertDialog(
      title: Text(ar ? 'صيغة من عندك' : 'Your own form'),
      content: Column(mainAxisSize: MainAxisSize.min, children: [
        TextField(controller: m, decoration: InputDecoration(labelText: ar ? 'للرجل، مثال تحب' : 'For a man, e.g. تحب')),
        TextField(controller: f, decoration: InputDecoration(labelText: ar ? 'للست، مثال تحبي' : 'For a woman, e.g. تحبي')),
      ]),
      actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'أضف' : 'Add'))]));
    final a = m.text.replaceAll(RegExp(r'[{}|]'), '').trim(), b = f.text.replaceAll(RegExp(r'[{}|]'), '').trim();
    m.dispose(); f.dispose();
    if (ok == true && a.isNotEmpty && b.isNotEmpty) _insert('{$a|$b}');
  }

  Future<void> _suggest() async {
    final ar = S.of(context).ar;
    Map<String, dynamic>? r;
    if (await run(context, () async { r = await widget.ctx.api.post('store/broadcasts/variants', {'message': _msg.text, 'count': 3}) as Map<String, dynamic>; }) && mounted) {
      final list = ((r!['variants'] as List?) ?? const []).cast<String>();
      setState(() { _versions.addAll(list); if (_versions.length > 5) _versions.removeRange(5, _versions.length); });
      if (list.isEmpty) toast(context, ar ? 'ماقدرناش نقترح صيغ جديدة. جرّب تعدّل الرسالة.' : 'No new wording came back. Try changing the message.');
    }
  }

  Future<void> _emojiSheet() => showModalBottomSheet<void>(context: context, builder: (c) => Padding(padding: const EdgeInsets.all(12), child: GridView.count(crossAxisCount: 8, shrinkWrap: true, children: [for (final e in _emoji) InkWell(onTap: () { Navigator.pop(c); _insert(e); }, child: Center(child: Text(e, style: const TextStyle(fontSize: 26))))])));

  Future<void> _pickImage() async {
    final id = await pickAndUpload(context, widget.ctx.api, 'PRODUCT_IMAGE', aspect: 4 / 3);
    if (id != null && mounted) setState(() => _image = id);
  }

  void _openOwnNumber() {
    final ar = S.of(context).ar;
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(ar ? 'الإعدادات' : 'Settings')), body: StoreSettings(ctx: widget.ctx, initialTab: 'whatsapp'))));
  }

  // ---- numbers ----

  num get _price => (_top?['messagePriceMinor'] as num?) ?? 10;
  num get _balance => (_top?['balanceMinor'] as num?) ?? 0;
  int get _reachable => ((_list?['reachable'] ?? _top?['reachable']) as num?)?.toInt() ?? 0;
  num get _cost => _reachable * _price;
  bool get _ownLinked => _top?['ownNumberLinked'] == true;

  /// Reachable count of one platform, from the customer list answer when it has it, otherwise from the segment summary.
  int _reachableOf(String p) {
    for (final src in [_list?['byPlatform'], _top?['byPlatform']]) {
      final v = (src as Map?)?[p];
      if (v is Map && v['reachable'] is num) return (v['reachable'] as num).toInt();
      if (v is num) return v.toInt();
    }
    return 0;
  }

  int _platformTotal(String p) {
    final v = (_top?['byPlatform'] as Map?)?[p];
    return v is Map ? ((v['total'] as num?)?.toInt() ?? 0) : 0;
  }

  int _segmentCount(String k) {
    for (final s in _maps(_top?['segments'])) { if (s['key'] == k) return (s['count'] as num?)?.toInt() ?? 0; }
    return 0;
  }

  String _eta(int n) {
    final ar = AppLocale.isAr;
    if (n <= 0) return '-';
    final minutes = (n * 17 / 60).ceil();
    if (minutes < 60) return ar ? 'حوالي $minutes دقيقة' : 'about $minutes min';
    final h = minutes ~/ 60, m = minutes % 60;
    return ar ? 'حوالي $h ساعة${m > 0 ? ' و$m دقيقة' : ''}' : 'about ${h}h${m > 0 ? ' ${m}m' : ''}';
  }

  String _minutes(Object? m) {
    final ar = AppLocale.isAr;
    final n = (m as num?)?.toInt() ?? 0;
    if (n <= 0) return '-';
    if (n < 60) return ar ? '$n دقيقة' : '$n min';
    return ar ? '${n ~/ 60} ساعة${n % 60 > 0 ? ' و${n % 60} د' : ''}' : '${n ~/ 60}h${n % 60 > 0 ? ' ${n % 60}m' : ''}';
  }

  static String reasonText(Object? r, bool ar) => switch ('$r') {
        'OUTSIDE_WINDOW' => ar ? 'خارج نافذة الرد (٢٤ ساعة)' : 'Outside the reply window (24 h)',
        'NO_OWN_NUMBER' => ar ? 'رقم المتجر مش مربوط' : 'Shop number not linked',
        'OPTED_OUT' => ar ? 'طلب إيقاف الرسائل' : 'Asked to stop messages',
        'NO_PHONE' => ar ? 'مفيش رقم' : 'No phone number',
        _ => '$r',
      };

  String _pauseText(String r, bool ar) => switch (r) {
        'QUIET_HOURS' => ar ? 'استراحة الليل' : 'night break',
        'DAILY_CAP' => ar ? 'وصل الحد اليومي' : 'daily limit reached',
        'FAILURES' => ar ? 'اتوقف: الإرسال بيفشل' : 'stopped: sends keep failing',
        'OPT_OUTS' => ar ? 'اتوقف: عملاء طلبوا الإيقاف' : 'stopped: customers asked to stop',
        _ => r,
      };

  // ---- actions ----

  String _sendError(Object e, bool ar) {
    if (e is ApiException) {
      switch (e.code) {
        case 'NO_CREDIT': return ar ? 'الرصيد مش كفاية للإرسال. اشحن رصيدك وجرّب تاني.' : 'Not enough credit to send. Top up and try again.';
        case 'NO_OWN_NUMBER': return ar ? 'اربط رقم واتساب المتجر الأول.' : "Link the shop's WhatsApp number first.";
        case 'BROADCAST_RUNNING': return ar ? 'فيه رسالة جماعية شغالة دلوقتي. استنى لما تخلص.' : 'A broadcast is already running. Wait until it finishes.';
        case 'BROADCAST_LIMIT': return ar ? 'وصلت الحد المسموح من الرسائل الجماعية.' : 'You reached the broadcast limit.';
      }
    }
    return errorText(e);
  }

  Future<void> _send() async {
    final ar = S.of(context).ar;
    final s = S.of(context);
    setState(() => _busy = true);
    try {
      final r = await widget.ctx.api.post('store/broadcasts', {
        'segments': _segs.toList(), 'platforms': _plats.toList(), 'message': _msg.text,
        if (_image != null) 'imageFileId': _image,
        'variants': [for (final v in _versions) if (v.trim().length >= 3) v.trim()],
        'push': _push, if (_pushTitle.text.trim().isNotEmpty) 'pushTitle': _pushTitle.text.trim(),
      }) as Map;
      if (!mounted) return;
      final skipped = (r['skipped'] as num?)?.toInt() ?? 0;
      toast(context, ar ? 'اتجهّزت ${r['reachable']} رسالة بتكلفة ${s.money(r['costMinor'] as num?, widget.ctx.currency)}${skipped > 0 ? '، وتخطّينا $skipped' : ''}.' : 'Queued ${r['reachable']} messages, cost ${s.money(r['costMinor'] as num?, widget.ctx.currency)}${skipped > 0 ? ', $skipped skipped' : ''}.');
      _msg.clear(); _image = null; _versions.clear(); _watchId = '${r['id']}'; _recFilter = '';
      await _loadAll();
    } catch (e) {
      if (mounted) {
        toast(context, _sendError(e, ar), isError: true);
        if (e is ApiException && e.code == 'NO_OWN_NUMBER') _openOwnNumber();
      }
    }
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _control(String id, String action) async {
    if (action == 'cancel') {
      final ar = S.of(context).ar;
      final sure = await showDialog<bool>(context: context, builder: (d) => AlertDialog(
        content: Text(ar ? 'تلغي الإرسال؟ اللي اتبعت مش هيرجع واللي لسه مابعتش هيتوقف.' : 'Cancel the broadcast? What was sent stays sent; the rest is stopped.'),
        actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'لا' : 'No')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'ألغِ' : 'Cancel it'))]));
      if (sure != true) return;
    }
    if (await run(context, () async { await widget.ctx.api.post('store/broadcasts/$id/$action', {}); }) && mounted) {
      await _loadHistory();
      await _loadRecipients();
    }
  }

  // ---- UI ----

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final t = AppTemplate.of(context);
    if (_loading && _top == null) return const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(), SizedBox(height: 12), Skeleton(), SizedBox(height: 12), Skeleton()]));
    if (_error != null && _top == null) return errorState(_error!, _loadAll);
    final live = _live;
    return RefreshIndicator(
      onRefresh: _loadAll,
      child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 40), children: [
        if (live != null) ...[_liveCard(live, ar, s, t)],
        TemplateSectionTitle(ar ? 'اختر الجمهور' : 'Choose the audience'),
        _audienceCard(ar, t),
        TemplateSectionTitle(ar ? 'العملاء المختارين' : 'Selected customers'),
        _customersCard(ar, s, t),
        if (_write) ...[
          TemplateSectionTitle(ar ? 'اكتب الرسالة' : 'Write the message'),
          _composerCard(ar, s, t),
          const SizedBox(height: 12),
          _summaryCard(ar, s, t),
        ],
        if (_history.isNotEmpty) ...[
          TemplateSectionTitle(ar ? 'الرسائل السابقة' : 'Past broadcasts'),
          for (final h in _history.take(10)) _historyTile(h, ar, t),
        ],
      ]),
    );
  }

  Widget _card(AppTemplate t, Widget child, {Color? color, EdgeInsets padding = const EdgeInsets.all(14)}) => Container(width: double.infinity, padding: padding, decoration: t.cardDecoration(color: color), child: child);

  Widget _audienceCard(bool ar, AppTemplate t) => _card(t, Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(ar ? 'الشرائح (تقدر تختار أكتر من واحدة)' : 'Segments (pick one or more)', style: const TextStyle(fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 4, children: [
          for (final k in _segmentKeys) FilterChip(
            label: Text('${ar ? _segmentNames[k]!.$1 : _segmentNames[k]!.$2}  ${_segmentCount(k)}'),
            selected: _segs.contains(k), onSelected: (_) => _toggleSegment(k)),
        ]),
        const SizedBox(height: 12),
        Text(ar ? 'المنصات' : 'Platforms', style: const TextStyle(fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 4, children: [
          for (final p in _platformKeys) FilterChip(
            avatar: Icon(_platformIcon(p), size: 16, color: _plats.contains(p) ? _platformColor(p) : null),
            label: Text('${_platformLabel(p, ar)}  ${_platformTotal(p)}'),
            selected: _plats.contains(p), onSelected: (_) => _togglePlatform(p)),
        ]),
      ]));

  Widget _customersCard(bool ar, S s, AppTemplate t) {
    final total = (_list?['total'] as num?)?.toInt() ?? _customers.length;
    final outside = (_list?['outsideWindow'] as num?)?.toInt() ?? 0;
    return _card(t, Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        Expanded(child: Text(ar ? '$total عميل · $_reachable ينفع يوصلهم' : '$total customers · $_reachable reachable', style: const TextStyle(fontWeight: FontWeight.w900))),
        if (_listLoading) const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
      ]),
      if (outside > 0) Padding(padding: const EdgeInsets.only(top: 4), child: Text(ar ? '$outside خارج نافذة الرد' : '$outside outside the reply window', style: TextStyle(fontSize: 12, color: t.muted))),
      const SizedBox(height: 8),
      TextField(controller: _search, onChanged: (_) { _debounce?.cancel(); _debounce = Timer(const Duration(milliseconds: 400), () => _loadCustomers().catchError((_) {})); }, decoration: InputDecoration(isDense: true, hintText: ar ? 'ابحث بالاسم أو الرقم' : 'Search by name or phone', prefixIcon: const Icon(Icons.search_rounded))),
      const SizedBox(height: 6),
      if (_customers.isEmpty && !_listLoading) emptyState(ar ? 'مفيش عملاء في الاختيار ده.' : 'No customers in this selection.', icon: Icons.people_outline_rounded),
      for (final c in _customers) _customerRow(c, ar, s, t),
      if (_customers.length < total) TextButton(onPressed: _moreLoading ? null : () async { setState(() => _moreLoading = true); try { await _loadCustomers(more: true); } catch (_) {} if (mounted) setState(() => _moreLoading = false); }, child: Text(ar ? 'اعرض المزيد' : 'Show more')),
    ]));
  }

  Widget _customerRow(Map<String, dynamic> c, bool ar, S s, AppTemplate t) {
    final reachable = c['reachable'] == true;
    final plat = '${c['platform'] ?? ''}';
    final phone = '${c['phone'] ?? ''}';
    return Padding(padding: const EdgeInsets.symmetric(vertical: 6), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Icon(reachable ? Icons.check_circle_rounded : Icons.block_rounded, size: 20, color: reachable ? const Color(0xFF1E9E5A) : const Color(0xFFD14343)),
      const SizedBox(width: 10),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [Flexible(child: Text('${c['name'] ?? '-'}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800))), const SizedBox(width: 6), if (plat.isNotEmpty) PlatformTag(plat)]),
        if (phone.isNotEmpty) Text(localPhone(phone), textDirection: TextDirection.ltr, style: TextStyle(fontSize: 12, color: t.muted)),
        if (!reachable && c['reason'] != null) Text(reasonText(c['reason'], ar), style: const TextStyle(fontSize: 11.5, color: Color(0xFFB91C1C))),
      ])),
      Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
        Text('${c['orders'] ?? 0} ${ar ? 'طلب' : 'orders'}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
        Text(s.money(c['spentMinor'] as num? ?? 0, widget.ctx.currency), style: TextStyle(fontSize: 11.5, color: t.muted)),
      ]),
    ]));
  }

  Widget _composerCard(bool ar, S s, AppTemplate t) {
    final name = ar ? 'منى' : 'Mona';
    final preview = _msg.text.replaceAll('{name}', name).replaceAll('{shop}', ar ? 'متجرك' : 'your shop').replaceAll('{last_product}', ar ? 'قميص كتان' : 'Linen shirt').replaceAll('{last_order}', 'ST-20261001-000012').replaceAll('{last_date}', '1/10/2026').replaceAll('{orders_count}', '3').replaceAllMapped(RegExp(r'\{\{([^{}]{1,300})\}\}'), (m) => m.group(1)!.split('|').first.trim()).replaceAllMapped(RegExp(r'\{([^{}|]{1,40})\|([^{}|]{1,40})\}'), (m) => _female ? m.group(2)! : m.group(1)!);
    const mute = TextStyle(fontSize: 12, color: Color(0xFF6B7C8D));
    return _card(t, Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Wrap(spacing: 6, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
        ActionChip(avatar: const Icon(Icons.add, size: 16), label: Text(ar ? 'اسم العميل' : 'Customer name'), onPressed: () => _insert('{name}')),
        ActionChip(avatar: const Icon(Icons.add, size: 16), label: Text(ar ? 'اسم المتجر' : 'Shop name'), onPressed: () => _insert('{shop}')),
        ActionChip(avatar: const Icon(Icons.add, size: 16), label: Text(ar ? 'آخر منتج اشتراه' : 'Last product'), onPressed: () => _insert('{last_product}')),
        ActionChip(avatar: const Icon(Icons.add, size: 16), label: Text(ar ? 'رقم آخر طلب' : 'Last order no.'), onPressed: () => _insert('{last_order}')),
        ActionChip(avatar: const Icon(Icons.add, size: 16), label: Text(ar ? 'تاريخ آخر طلب' : 'Last order date'), onPressed: () => _insert('{last_date}')),
        ActionChip(avatar: const Icon(Icons.add, size: 16), label: Text(ar ? 'عدد طلباته' : 'Orders count'), onPressed: () => _insert('{orders_count}')),
        IconButton.outlined(visualDensity: VisualDensity.compact, onPressed: () => _wrap('*'), icon: const Icon(Icons.format_bold_rounded)),
        IconButton.outlined(visualDensity: VisualDensity.compact, onPressed: () => _wrap('_'), icon: const Icon(Icons.format_italic_rounded)),
        IconButton.outlined(visualDensity: VisualDensity.compact, onPressed: _emojiSheet, icon: const Icon(Icons.emoji_emotions_outlined)),
        IconButton.outlined(visualDensity: VisualDensity.compact, onPressed: _pickImage, icon: const Icon(Icons.image_outlined)),
      ]),
      const SizedBox(height: 6),
      Text(ar ? 'كلمات متنوعة (كل عميل يستلم واحدة):' : 'Different wording (each customer gets one):', style: mute),
      Wrap(spacing: 6, runSpacing: 4, children: [
        for (final f in _spins) ActionChip(backgroundColor: const Color(0xFFE0F2FE), label: Text(f.$1, style: const TextStyle(fontSize: 12.5)), onPressed: () => _insert(f.$2)),
        ActionChip(avatar: const Icon(Icons.add, size: 14), label: Text(ar ? 'من عندك' : 'Your own', style: const TextStyle(fontSize: 12.5)), onPressed: _customSpin),
      ]),
      const SizedBox(height: 4),
      Text(ar ? 'صيغ المذكر / المؤنث (بتتحول لكل عميل حسب نوعه):' : 'Man / woman forms (each customer gets theirs):', style: mute),
      Wrap(spacing: 6, runSpacing: 4, children: [
        for (final f in _forms) ActionChip(backgroundColor: const Color(0xFFEDE9FE), label: Text(f.$1, style: const TextStyle(fontSize: 12.5)), onPressed: () => _insert(f.$2)),
        ActionChip(avatar: const Icon(Icons.add, size: 14), label: Text(ar ? 'صيغة من عندك' : 'Your own', style: const TextStyle(fontSize: 12.5)), onPressed: _customForm),
      ]),
      const SizedBox(height: 8),
      TextField(controller: _msg, maxLines: 5, maxLength: 1000, onChanged: (_) => setState(() {}), decoration: InputDecoration(hintText: ar ? 'اكتب رسالتك. اضغط «اسم العميل» لإضافة الاسم الأول لكل عميل.' : 'Write your message. Tap “Customer name” to add each customer\'s first name.')),
      if (_image != null) Align(alignment: AlignmentDirectional.centerStart, child: Stack(clipBehavior: Clip.none, children: [
        ClipRRect(borderRadius: BorderRadius.circular(12), child: Image.network(fileUrl(widget.ctx, _image!), width: 96, height: 96, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(width: 96, height: 96, color: const Color(0xFFE8EEF4), child: const Icon(Icons.image)))),
        PositionedDirectional(end: -8, top: -8, child: InkWell(onTap: () => setState(() => _image = null), child: const CircleAvatar(radius: 12, backgroundColor: Color(0xFFDC2626), child: Icon(Icons.close, size: 14, color: Colors.white)))),
      ])),
      const SizedBox(height: 8),
      Row(children: [Text(ar ? 'المعاينة لـ:' : 'Preview for:', style: mute), const SizedBox(width: 6), ChoiceChip(visualDensity: VisualDensity.compact, label: Text(ar ? 'ذكر' : 'Male'), selected: !_female, onSelected: (_) => setState(() => _female = false)), const SizedBox(width: 4), ChoiceChip(visualDensity: VisualDensity.compact, label: Text(ar ? 'أنثى' : 'Female'), selected: _female, onSelected: (_) => setState(() => _female = true))]),
      Container(margin: const EdgeInsets.only(top: 4), padding: const EdgeInsets.all(10), decoration: BoxDecoration(color: const Color(0xFFDCF8C6), borderRadius: BorderRadius.circular(14)), child: Text(preview.isEmpty ? (ar ? 'رسالتك هتظهر هنا' : 'Your message appears here') : '$preview\n\nلإيقاف الرسائل اكتب: إيقاف · Reply STOP to unsubscribe', style: TextStyle(color: preview.isEmpty ? Colors.black45 : Colors.black87))),
      const SizedBox(height: 12),
      Text(ar ? 'صيغ تانية للرسالة (كل عميل يستلم صيغة منهم)' : 'Other wordings (each customer gets one)', style: const TextStyle(fontWeight: FontWeight.w800)),
      for (var i = 0; i < _versions.length; i++) Padding(padding: const EdgeInsets.only(top: 6), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(child: TextFormField(key: ValueKey('v$i${_versions[i].hashCode}'), initialValue: _versions[i], maxLines: 2, maxLength: 1000, onChanged: (v) => _versions[i] = v, decoration: const InputDecoration(isDense: true))),
        IconButton(onPressed: () => setState(() => _versions.removeAt(i)), icon: const Icon(Icons.close_rounded)),
      ])),
      Wrap(spacing: 8, children: [
        OutlinedButton.icon(onPressed: _versions.length >= 5 ? null : () => setState(() => _versions.add('')), icon: const Icon(Icons.add), label: Text(ar ? 'أضف صيغة' : 'Add a wording')),
        OutlinedButton.icon(onPressed: _versions.length >= 5 || _msg.text.trim().length < 3 ? null : _suggest, icon: const Icon(Icons.auto_awesome_rounded), label: Text(ar ? 'اقترح بالذكاء الاصطناعي' : 'Suggest with AI')),
      ]),
      SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(ar ? 'ابعت كمان كإشعار على التليفون' : 'Also send as a phone notification'), value: _push, onChanged: (v) => setState(() => _push = v)),
      if (_push) TextField(controller: _pushTitle, maxLength: 80, decoration: InputDecoration(labelText: ar ? 'عنوان الإشعار (فاضي = اسم متجرك)' : 'Notification title (empty = shop name)', isDense: true)),
    ]));
  }

  Widget _summaryCard(bool ar, S s, AppTemplate t) {
    final cur = widget.ctx.currency;
    final low = _cost > _balance;
    final whatsappPicked = _plats.contains('WHATSAPP');
    final needsLink = whatsappPicked && !_ownLinked;
    final canSend = !_busy && _msg.text.trim().length >= 3 && _reachable > 0 && !low;
    return _card(t, Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(ar ? 'ملخص الإرسال' : 'Send summary', style: t.heading(15)),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 6, children: [for (final p in _plats) Container(padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6), decoration: BoxDecoration(color: _platformColor(p).withValues(alpha: 0.1), borderRadius: BorderRadius.circular(12)), child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(_platformIcon(p), size: 15, color: _platformColor(p)), const SizedBox(width: 5), Text('${_platformLabel(p, ar)}: ${_reachableOf(p)}', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 12.5, color: _platformColor(p)))]))]),
      const SizedBox(height: 10),
      kv(ar ? 'ينفع يوصلهم' : 'Reachable', '$_reachable'),
      kv(ar ? 'سعر الرسالة' : 'Per message', s.money(_price, cur)),
      kv(ar ? 'التكلفة' : 'Cost', s.money(_cost, cur)),
      kv(ar ? 'رصيدك' : 'Your balance', s.money(_balance, cur)),
      kv(ar ? 'الوقت المتوقع' : 'Estimated time', _eta(_reachable)),
      if (needsLink) Padding(padding: const EdgeInsets.only(top: 6), child: Row(children: [
        Expanded(child: Text(ar ? 'اربط رقم واتساب المتجر عشان نوصل لعملاء واتساب.' : "Link the shop's WhatsApp number to reach WhatsApp customers.", style: const TextStyle(color: Color(0xFFB45309), fontWeight: FontWeight.w700, fontSize: 12.5))),
        TextButton(onPressed: _openOwnNumber, child: Text(ar ? 'اربط' : 'Link')),
      ])),
      if (low) Padding(padding: const EdgeInsets.only(top: 6), child: Text(ar ? 'الرصيد مش كفاية: المطلوب ${s.money(_cost, cur)} ورصيدك ${s.money(_balance, cur)}.' : 'Not enough credit: needs ${s.money(_cost, cur)}, you have ${s.money(_balance, cur)}.', style: const TextStyle(color: Color(0xFFB91C1C), fontWeight: FontWeight.w700))),
      const SizedBox(height: 10),
      FilledButton.icon(onPressed: canSend ? _send : null, icon: _busy ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : const Icon(Icons.send_rounded), label: Text(ar ? 'ابعت لـ $_reachable عميل' : 'Send to $_reachable customers')),
    ]));
  }

  // ---- live queue ----

  Widget _liveCard(Map<String, dynamic> b, bool ar, S s, AppTemplate t) {
    final cur = widget.ctx.currency;
    final status = '${b['status']}';
    final sent = (b['sentCount'] as num?)?.toInt() ?? 0, failed = (b['failedCount'] as num?)?.toInt() ?? 0, pending = (b['pendingCount'] as num?)?.toInt() ?? 0;
    final total = sent + failed + pending;
    final progress = total == 0 ? 0.0 : (sent + failed) / total;
    final color = switch (status) { 'SENDING' => const Color(0xFFD98200), 'PAUSED' => const Color(0xFF6B7C8D), 'DONE' => const Color(0xFF1E9E5A), _ => const Color(0xFFD14343) };
    final label = switch (status) { 'SENDING' => ar ? 'بيتبعت الآن' : 'Sending now', 'PAUSED' => ar ? 'متوقف' : 'Paused', 'DONE' => ar ? 'اكتمل' : 'Done', _ => ar ? 'ملغي' : 'Canceled' };
    final id = '${b['id']}';
    final filters = <(String, String, Object?)>[('', ar ? 'الكل' : 'All', total), ('SENDING', ar ? 'بيتبعت' : 'Sending', _counts['SENDING']), ('PENDING', ar ? 'منتظر' : 'Pending', _counts['PENDING'] ?? pending), ('SENT', ar ? 'اتبعت' : 'Sent', _counts['SENT'] ?? sent), ('FAILED', ar ? 'فشل' : 'Failed', _counts['FAILED'] ?? failed)];
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(14),
      decoration: t.cardDecoration().copyWith(border: Border.all(color: color.withValues(alpha: 0.6), width: 1.5)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          if (status == 'SENDING') const Padding(padding: EdgeInsetsDirectional.only(end: 8), child: LoadingDots()),
          Expanded(child: Text(ar ? 'طابور الإرسال المباشر' : 'Live sending queue', style: t.heading(15.5))),
          Container(padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4), decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(20)), child: Text(label, style: TextStyle(color: color, fontWeight: FontWeight.w800, fontSize: 12))),
        ]),
        const SizedBox(height: 6),
        Text('${b['message']}', maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.muted, fontSize: 12.5)),
        if (b['pauseReason'] != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(_pauseText('${b['pauseReason']}', ar), style: const TextStyle(color: Color(0xFFB45309), fontWeight: FontWeight.w700, fontSize: 12.5))),
        const SizedBox(height: 10),
        ClipRRect(borderRadius: BorderRadius.circular(8), child: LinearProgressIndicator(value: progress, minHeight: 8, color: const Color(0xFF1E9E5A), backgroundColor: const Color(0xFFE5EAF0))),
        const SizedBox(height: 6),
        Wrap(spacing: 14, runSpacing: 2, children: [
          Text(ar ? '${sent + failed} من $total' : '${sent + failed} of $total', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 12.5)),
          Text('✓ $sent', style: const TextStyle(color: Color(0xFF1E9E5A), fontWeight: FontWeight.w800, fontSize: 12.5)),
          if (failed > 0) Text('✗ $failed', style: const TextStyle(color: Color(0xFFD14343), fontWeight: FontWeight.w800, fontSize: 12.5)),
          Text(ar ? 'فاضل $pending' : '$pending left', style: TextStyle(color: t.muted, fontSize: 12.5)),
          if (status == 'SENDING') Text(ar ? 'الوقت المتبقي ${_minutes(b['etaMinutes'])}' : 'ETA ${_minutes(b['etaMinutes'])}', style: TextStyle(color: t.muted, fontSize: 12.5)),
          Text(ar ? 'التكلفة لحد الآن ${s.money(b['chargedMinor'] as num? ?? 0, cur)}' : 'Cost so far ${s.money(b['chargedMinor'] as num? ?? 0, cur)}', style: TextStyle(color: t.muted, fontSize: 12.5)),
        ]),
        if (_write && (status == 'SENDING' || status == 'PAUSED')) Padding(padding: const EdgeInsets.only(top: 8), child: Row(children: [
          if (status == 'PAUSED') Expanded(child: FilledButton.icon(onPressed: () => _control(id, 'resume'), icon: const Icon(Icons.play_arrow_rounded), label: Text(ar ? 'كمّل' : 'Resume'))),
          if (status == 'PAUSED') const SizedBox(width: 8),
          Expanded(child: OutlinedButton.icon(onPressed: () => _control(id, 'cancel'), style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFB91C1C)), icon: const Icon(Icons.stop_rounded), label: Text(ar ? 'ألغِ الإرسال' : 'Cancel'))),
        ])),
        const SizedBox(height: 8),
        SizedBox(height: 40, child: ListView(scrollDirection: Axis.horizontal, children: [
          for (final f in filters) Padding(padding: const EdgeInsetsDirectional.only(end: 6), child: ChoiceChip(visualDensity: VisualDensity.compact, label: Text('${f.$2} ${f.$3 ?? 0}', style: const TextStyle(fontSize: 12)), selected: _recFilter == f.$1, showCheckmark: false, onSelected: (_) { setState(() => _recFilter = f.$1); _loadRecipients(); })),
        ])),
        if (_recipients.isEmpty) Padding(padding: const EdgeInsets.all(12), child: Center(child: Text(ar ? 'مفيش مستلمين في العرض ده.' : 'No recipients in this view.', style: TextStyle(color: t.muted)))),
        for (final r in _recipients) _recipientRow(r, ar, s, t),
        if (_recipients.length < _recTotal) TextButton(onPressed: () => _loadRecipients(more: true), child: Text(ar ? 'اعرض المزيد' : 'Show more')),
        if (_watchId != null) TextButton(onPressed: () { setState(() { _watchId = null; _recipients = []; }); _loadRecipients(); }, child: Text(ar ? 'إخفاء' : 'Hide')),
      ]),
    );
  }

  Widget _recipientRow(Map<String, dynamic> r, bool ar, S s, AppTemplate t) {
    final st = '${r['status']}';
    final (Color fg, Color bg, Widget icon) = switch (st) {
      'SENDING' => (const Color(0xFF92400E), const Color(0xFFFEF3C7), const LoadingDots(color: Color(0xFFB45309))),
      'SENT' => (const Color(0xFF166534), const Color(0xFFDCFCE7), const Icon(Icons.check_circle_rounded, size: 18, color: Color(0xFF1E9E5A))),
      'FAILED' => (const Color(0xFF991B1B), const Color(0xFFFEE2E2), const Icon(Icons.error_rounded, size: 18, color: Color(0xFFD14343))),
      'SKIPPED' || 'STOPPED' => (const Color(0xFF6B7C8D), const Color(0xFFF1F5F9), const Icon(Icons.remove_circle_outline_rounded, size: 18, color: Color(0xFF9AA9BA))),
      _ => (const Color(0xFF475569), const Color(0xFFF1F5F9), const Icon(Icons.schedule_rounded, size: 18, color: Color(0xFF9AA9BA))),
    };
    final stText = switch (st) { 'SENDING' => ar ? 'بيتبعت' : 'Sending', 'SENT' => ar ? 'اتبعت' : 'Sent', 'FAILED' => ar ? 'فشل' : 'Failed', 'STOPPED' => ar ? 'اتوقف' : 'Stopped', 'SKIPPED' => ar ? 'اتخطّى' : 'Skipped', _ => ar ? 'منتظر' : 'Pending' };
    final ch = '${r['channel'] ?? ''}';
    return Container(
      margin: const EdgeInsets.only(top: 5),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12), border: st == 'SENDING' ? Border.all(color: const Color(0xFFD98200), width: 1.2) : null),
      child: Row(children: [
        SizedBox(width: 24, child: Center(child: icon)),
        const SizedBox(width: 8),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [Flexible(child: Text('${r['name'] ?? r['phone'] ?? '-'}', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: FontWeight.w800, color: fg, fontSize: 13.5))), const SizedBox(width: 6), if (ch.isNotEmpty) PlatformTag(ch)]),
          if (st == 'FAILED' && r['error'] != null) Text('${r['error']}', maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 11.5, color: Color(0xFFB91C1C))),
          if (st == 'SENT' && r['sentAt'] != null) Text(fmtDate('${r['sentAt']}'), style: TextStyle(fontSize: 11, color: fg.withValues(alpha: 0.8))),
        ])),
        Text(stText, style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800, color: fg)),
      ]),
    );
  }

  Widget _historyTile(Map<String, dynamic> h, bool ar, AppTemplate t) {
    final status = '${h['status']}';
    final color = switch (status) { 'SENDING' => const Color(0xFFD98200), 'DONE' => const Color(0xFF1E9E5A), 'PAUSED' => const Color(0xFF6B7C8D), _ => const Color(0xFFD14343) };
    final st = switch (status) { 'SENDING' => ar ? 'بيتبعت' : 'Sending', 'DONE' => ar ? 'اكتمل' : 'Done', 'PAUSED' => ar ? 'متوقف' : 'Paused', _ => ar ? 'ملغي' : 'Canceled' };
    final platforms = ((h['platforms'] as List?) ?? const []).cast<String>();
    return Padding(padding: const EdgeInsets.only(bottom: 8), child: InkWell(
      borderRadius: BorderRadius.circular(t.cardRadius),
      onTap: () { setState(() { _watchId = '${h['id']}'; _recFilter = ''; _recipients = []; }); _loadRecipients(); },
      child: _card(t, Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('${h['message']}', maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700))),
          const SizedBox(width: 8),
          Container(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3), decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(20)), child: Text(st, style: TextStyle(color: color, fontWeight: FontWeight.w800, fontSize: 11.5))),
        ]),
        const SizedBox(height: 4),
        Wrap(spacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
          for (final p in platforms) PlatformTag(p),
          Text('${h['sentCount'] ?? 0} ✓${((h['failedCount'] as num?) ?? 0) > 0 ? '  ${h['failedCount']} ✗' : ''}${status == 'SENDING' ? '  ${ar ? 'فاضل' : 'left'} ${h['pendingCount']}' : ''}  ·  ${fmtDate(h['createdAt'] as String?, time: false)}', style: TextStyle(fontSize: 12, color: t.muted)),
        ]),
      ])),
    ));
  }
}
