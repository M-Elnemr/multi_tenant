// ignore_for_file: use_build_context_synchronously
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';
import 'segments.dart';
import 'theme/templates.dart';

// New customers targeting: people who follow the shop's Instagram or commented on its posts (and Messenger commenters) become a message audience.
// Instagram and Messenger only. Connecting the accounts stays in the web dashboard. Sending, queue and pricing are the same as for existing customers.

List<Map<String, dynamic>> _rows(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

const _prospectPlatforms = ['INSTAGRAM', 'FACEBOOK'];
const _sourceNames = {'FOLLOWER': ('متابعين', 'Followers'), 'COMMENTER': ('معلّقين', 'Commenters')};

class ProspectsScreen extends StatefulWidget {
  const ProspectsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<ProspectsScreen> createState() => _ProspectsScreenState();
}

class _ProspectsScreenState extends State<ProspectsScreen> with WidgetsBindingObserver {
  final _plats = <String>{'INSTAGRAM'};
  final _sources = <String>{};
  final _search = TextEditingController();
  Timer? _debounce, _poll, _syncPoll, _quoteDebounce;
  Map<String, dynamic>? _data;
  List<Map<String, dynamic>> _items = [];
  List<Map<String, dynamic>> _history = [];
  Map<String, dynamic> _sync = const {};
  Object? _error;
  bool _loading = true, _listLoading = false, _moreLoading = false, _busy = false, _syncStarting = false;
  AppLifecycleState _life = AppLifecycleState.resumed;
  // selection: all matching (minus excluded), or only the picked ids
  bool _all = true;
  final _picked = <String>{}, _excluded = <String>{};
  // composer
  final _msg = TextEditingController();
  String? _image;
  final _versions = <String>[];
  Map<String, dynamic>? _quote;
  // live queue
  String? _watchId;
  List<Map<String, dynamic>> _recipients = [];
  Map<String, dynamic> _counts = const {};
  int _recTotal = 0;
  String _recFilter = '';

  bool get _write => widget.ctx.can('growth.manage');
  bool get _visible => mounted && _life == AppLifecycleState.resumed && (ModalRoute.of(context)?.isCurrent ?? true);
  String get _syncPlatform => _plats.length == 1 ? _plats.first : 'INSTAGRAM';

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
    _syncPoll?.cancel();
    _quoteDebounce?.cancel();
    _search.dispose();
    _msg.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _life = state;
    if (state == AppLifecycleState.resumed && mounted) _tick(force: true);
  }

  Map<String, dynamic>? get _live {
    for (final b in _history) { if ('${b['id']}' == _watchId) return b; }
    for (final b in _history) { if (b['status'] == 'SENDING') return b; }
    for (final b in _history) { if (b['status'] == 'PAUSED') return b; }
    return null;
  }

  /// Every 3 seconds, but only while a broadcast is SENDING and the screen is in front.
  Future<void> _tick({bool force = false}) async {
    if (!_visible) return;
    final live = _live;
    if (live == null || (live['status'] != 'SENDING' && !force)) return;
    await _loadHistory();
    await _loadRecipients();
  }

  Map<String, String?> get _query => {
        'platform': _plats.length == 1 ? _plats.first : null,
        if (_sources.length == 1) 'source': _sources.first,
        if (_search.text.trim().isNotEmpty) 'q': _search.text.trim(),
      };

  Future<void> _loadAll() async {
    setState(() { _loading = _data == null; _error = null; });
    try {
      await Future.wait([_loadList(), _loadHistory(), _loadSyncStatus()]);
      await _loadRecipients();
    } catch (e) {
      if (mounted) setState(() { if (_data == null) _error = e; });
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _loadList({bool more = false}) async {
    if (!more) setState(() => _listLoading = true);
    final r = await widget.ctx.api.get('store/prospects', query: {..._query, 'limit': '50', 'offset': more ? '${_items.length}' : '0'}) as Map;
    if (!mounted) return;
    setState(() {
      _data = Map<String, dynamic>.from(r);
      _items = more ? [..._items, ..._rows(r['items'])] : _rows(r['items']);
      _listLoading = false;
    });
    _scheduleQuote();
  }

  Future<void> _loadHistory() async {
    final r = await widget.ctx.api.get('store/broadcasts');
    if (mounted) setState(() => _history = _rows(r is Map ? r['data'] : r).where((h) => h['kind'] == 'PROSPECTS').toList());
  }

  Future<void> _loadRecipients({bool more = false}) async {
    final live = _live;
    if (live == null) { if (mounted) setState(() { _recipients = []; _counts = const {}; _recTotal = 0; }); return; }
    try {
      final r = await widget.ctx.api.get('store/broadcasts/${live['id']}/recipients', query: {if (_recFilter.isNotEmpty) 'status': _recFilter, 'limit': '100', 'offset': more ? '${_recipients.length}' : '0'}) as Map;
      if (!mounted) return;
      setState(() {
        _recipients = more ? [..._recipients, ..._rows(r['data'])] : _rows(r['data']);
        _counts = Map<String, dynamic>.from((r['counts'] as Map?) ?? const {});
        _recTotal = (r['total'] as num?)?.toInt() ?? _recipients.length;
      });
    } catch (_) { /* the next tick tries again */ }
  }

  // ---- sync ----

  Future<void> _loadSyncStatus() async {
    try {
      final r = await widget.ctx.api.get('store/prospects/sync/status', query: {'platform': _syncPlatform}) as Map;
      if (!mounted) return;
      setState(() => _sync = Map<String, dynamic>.from(r));
      if (_sync['status'] == 'RUNNING') { _startSyncPoll(); } else { _syncPoll?.cancel(); _syncPoll = null; }
    } catch (_) { /* optional */ }
  }

  void _startSyncPoll() {
    _syncPoll ??= Timer.periodic(const Duration(seconds: 2), (_) async {
      if (!mounted) return;
      final before = _sync['status'];
      await _loadSyncStatus();
      if (before == 'RUNNING' && _sync['status'] != 'RUNNING') {
        if (_sync['status'] == 'DONE') await _loadList().catchError((_) {});
        if (_sync['status'] == 'FAILED' && mounted) toast(context, _syncError('${_sync['error']}', S.of(context).ar), isError: true);
      }
    });
  }

  String _syncError(String code, bool ar) => switch (code) {
        'NEEDS_LOGIN' => ar ? 'الحساب مش مربوط. اربطه من لوحة التحكم على الموقع.' : 'The account is not connected. Connect it from the dashboard.',
        'BRIDGE_UNREACHABLE' => ar ? 'الخدمة مش متاحة دلوقتي. جرّب بعد شوية.' : 'The service is unavailable right now. Try again later.',
        'CHALLENGE_REQUIRED' => ar ? 'الحساب محتاج تأكيد. افتحه من لوحة التحكم.' : 'The account needs verification. Open it from the dashboard.',
        _ => code.isEmpty || code == 'null' ? (ar ? 'فشلت المزامنة.' : 'Sync failed.') : code,
      };

  Future<void> _startSync() async {
    final ar = S.of(context).ar;
    setState(() => _syncStarting = true);
    try {
      await widget.ctx.api.post('store/prospects/sync', {'platform': _syncPlatform});
      await _loadSyncStatus();
      _startSyncPoll();
    } catch (e) {
      if (mounted) toast(context, e is ApiException ? _syncError(e.code, ar) : errorText(e), isError: true);
    }
    if (mounted) setState(() => _syncStarting = false);
  }

  // ---- audience / quote ----

  void _audienceChanged() {
    _picked.clear();
    _excluded.clear();
    _all = true;
    setState(() {});
    _loadList().catchError((_) { if (mounted) setState(() => _listLoading = false); });
    _loadSyncStatus();
  }

  void _togglePlatform(String k) {
    if (_plats.contains(k)) { if (_plats.length > 1) _plats.remove(k); } else { _plats.add(k); }
    if (!_plats.contains('INSTAGRAM')) _sources.remove('FOLLOWER');
    _audienceChanged();
  }

  void _toggleSource(String k) {
    if (!_sources.remove(k)) _sources.add(k);
    _audienceChanged();
  }

  int get _total => (_data?['total'] as num?)?.toInt() ?? _items.length;
  int get _selectedCount => _all ? (_total - _excluded.length).clamp(0, _total) : _picked.length;

  Map<String, dynamic> get _body => {
        'platforms': _plats.toList(),
        'sources': _sources.isEmpty ? ['FOLLOWER', 'COMMENTER'] : _sources.toList(),
        if (_all) 'all': true,
        if (_all && _excluded.isNotEmpty) 'excludeIds': _excluded.toList(),
        if (!_all) 'ids': _picked.toList(),
        if (_search.text.trim().isNotEmpty) 'q': _search.text.trim(),
        'message': _msg.text,
        if (_image != null) 'imageFileId': _image,
        'variants': [for (final v in _versions) if (v.trim().length >= 3) v.trim()],
      };

  void _scheduleQuote() {
    _quoteDebounce?.cancel();
    if (!_write) return;
    _quoteDebounce = Timer(const Duration(milliseconds: 500), () async {
      if (_selectedCount <= 0 || _msg.text.trim().length < 3) { if (mounted) setState(() => _quote = null); return; }
      try {
        final r = await widget.ctx.api.post('store/prospects/broadcasts/quote', _body) as Map;
        if (mounted) setState(() => _quote = Map<String, dynamic>.from(r));
      } catch (_) { if (mounted) setState(() => _quote = null); }
    });
  }

  bool _isSelected(String id) => _all ? !_excluded.contains(id) : _picked.contains(id);

  void _toggleRow(String id) {
    setState(() {
      if (_all) { if (!_excluded.remove(id)) _excluded.add(id); } else { if (!_picked.remove(id)) _picked.add(id); }
    });
    _scheduleQuote();
  }

  // ---- composer helpers ----

  void _insert(String t) {
    final v = _msg.value;
    final a = v.selection.isValid ? v.selection.start : v.text.length, b = v.selection.isValid ? v.selection.end : v.text.length;
    final text = v.text.replaceRange(a, b, t);
    _msg.value = TextEditingValue(text: text.length > 1000 ? text.substring(0, 1000) : text, selection: TextSelection.collapsed(offset: a + t.length));
    setState(() {});
    _scheduleQuote();
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

  Future<void> _pickImage() async {
    final id = await pickAndUpload(context, widget.ctx.api, 'PRODUCT_IMAGE', aspect: 4 / 3);
    if (id != null && mounted) setState(() => _image = id);
  }

  Future<void> _emojiSheet() => showModalBottomSheet<void>(context: context, builder: (c) => Padding(padding: const EdgeInsets.all(12), child: GridView.count(crossAxisCount: 8, shrinkWrap: true, children: [for (final e in broadcastEmoji) InkWell(onTap: () { Navigator.pop(c); _insert(e); }, child: Center(child: Text(e, style: const TextStyle(fontSize: 24))))])));

  // ---- actions ----

  String _sendError(Object e, bool ar) {
    if (e is ApiException) {
      switch (e.code) {
        case 'NO_CREDIT': return ar ? 'الرصيد مش كفاية للإرسال. اشحن رصيدك وجرّب تاني.' : 'Not enough credit to send. Top up and try again.';
        case 'BROADCAST_RUNNING': return ar ? 'فيه رسالة جماعية شغالة دلوقتي. استنى لما تخلص.' : 'A broadcast is already running. Wait until it finishes.';
        case 'BROADCAST_LIMIT': return ar ? 'وصلت الحد المسموح من الرسائل الجماعية.' : 'You reached the broadcast limit.';
        case 'NEEDS_LOGIN': return ar ? 'اربط الحساب من لوحة التحكم على الموقع الأول.' : 'Connect the account from the dashboard first.';
      }
    }
    return errorText(e);
  }

  Future<void> _send() async {
    final s = S.of(context);
    final ar = s.ar;
    final n = (_quote?['reachable'] as num?)?.toInt() ?? _selectedCount;
    final cost = s.money(_quote?['costMinor'] as num?, widget.ctx.currency);
    final sure = await showDialog<bool>(context: context, builder: (d) => AlertDialog(
      title: Text(ar ? 'تأكيد الإرسال' : 'Confirm sending'),
      content: Text(ar ? 'هتتبعت الرسالة لـ $n شخص بتكلفة $cost. تكمّل؟' : 'The message goes to $n people, cost $cost. Continue?'),
      actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'لا' : 'No')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'ابعت' : 'Send'))]));
    if (sure != true) return;
    setState(() => _busy = true);
    try {
      final r = await widget.ctx.api.post('store/prospects/broadcasts', _body) as Map;
      if (!mounted) return;
      toast(context, ar ? 'اتجهّزت ${r['reachable']} رسالة بتكلفة ${s.money(r['costMinor'] as num?, widget.ctx.currency)}.' : 'Queued ${r['reachable']} messages, cost ${s.money(r['costMinor'] as num?, widget.ctx.currency)}.');
      _msg.clear();
      _image = null;
      _versions.clear();
      _quote = null;
      _watchId = '${r['id']}';
      _recFilter = '';
      await _loadAll();
    } catch (e) {
      if (mounted) toast(context, _sendError(e, ar), isError: true);
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

  Widget _card(AppTemplate t, Widget child) => Container(width: double.infinity, padding: const EdgeInsets.all(14), decoration: t.cardDecoration(), child: child);

  int _count(String platform, String source) {
    final c = (_data?['counts'] as Map?)?[platform];
    return c is Map ? ((c[source] as num?)?.toInt() ?? 0) : 0;
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final t = AppTemplate.of(context);
    if (_loading && _data == null) return const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(), SizedBox(height: 12), Skeleton(), SizedBox(height: 12), Skeleton()]));
    if (_error != null && _data == null) return errorState(_error!, _loadAll);
    final live = _live;
    return RefreshIndicator(
      onRefresh: _loadAll,
      child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 40), children: [
        if (live != null) BroadcastQueueCard(b: live, counts: _counts, recipients: _recipients, recTotal: _recTotal, recFilter: _recFilter, write: _write, currency: widget.ctx.currency,
            onControl: _control, onFilter: (f) { setState(() => _recFilter = f); _loadRecipients(); }, onMore: () => _loadRecipients(more: true),
            onHide: _watchId == null ? null : () { setState(() { _watchId = null; _recipients = []; }); _loadRecipients(); }),
        TemplateSectionTitle(ar ? 'اختر الجمهور' : 'Choose the audience'),
        _audienceCard(ar, t),
        TemplateSectionTitle(ar ? 'الأشخاص' : 'People'),
        _listCard(ar, t),
        if (_write) ...[
          TemplateSectionTitle(ar ? 'اكتب الرسالة' : 'Write the message'),
          _composerCard(ar, t),
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

  Widget _audienceCard(bool ar, AppTemplate t) {
    final running = _sync['status'] == 'RUNNING';
    final fetched = (_sync['fetched'] as num?)?.toInt() ?? 0, total = (_sync['total'] as num?)?.toInt() ?? 0;
    final last = _sync['lastSyncedAt'];
    return _card(t, Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(ar ? 'المنصات' : 'Platforms', style: const TextStyle(fontWeight: FontWeight.w800)),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 4, children: [
        for (final p in _prospectPlatforms) FilterChip(
          avatar: Icon(platformIcon(p), size: 16, color: _plats.contains(p) ? platformColor(p) : null),
          label: Text(platformLabel(p, ar)),
          selected: _plats.contains(p), onSelected: (_) => _togglePlatform(p)),
      ]),
      const SizedBox(height: 12),
      Text(ar ? 'المصدر' : 'Source', style: const TextStyle(fontWeight: FontWeight.w800)),
      const SizedBox(height: 8),
      Wrap(spacing: 8, runSpacing: 4, children: [
        for (final k in _sourceNames.keys) if (k != 'FOLLOWER' || _plats.contains('INSTAGRAM')) FilterChip(
          label: Text('${ar ? _sourceNames[k]!.$1 : _sourceNames[k]!.$2}  ${_plats.fold<int>(0, (a, p) => a + _count(p, k))}'),
          selected: _sources.contains(k), onSelected: (_) => _toggleSource(k)),
      ]),
      const SizedBox(height: 4),
      Text(ar ? 'من غير اختيار = الاتنين.' : 'None picked = both.', style: TextStyle(fontSize: 11.5, color: t.muted)),
      if (_write) ...[
        const Divider(height: 24),
        Row(children: [
          Expanded(child: Text(last != null ? (ar ? 'آخر مزامنة: ${fmtDate('$last')}' : 'Last sync: ${fmtDate('$last')}') : (ar ? 'لسه ماتعملتش مزامنة' : 'Not synced yet'), style: TextStyle(fontSize: 12.5, color: t.muted))),
          FilledButton.tonalIcon(onPressed: running || _syncStarting ? null : _startSync, icon: running ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.sync_rounded), label: Text(ar ? 'زامن الآن' : 'Sync now')),
        ]),
        if (running) Padding(padding: const EdgeInsets.only(top: 8), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          ClipRRect(borderRadius: BorderRadius.circular(8), child: LinearProgressIndicator(value: total > 0 ? (fetched / total).clamp(0.0, 1.0) : null, minHeight: 8)),
          const SizedBox(height: 4),
          Text(total > 0 ? '$fetched / $total' : (ar ? 'بنجيب البيانات... $fetched' : 'Fetching... $fetched'), style: TextStyle(fontSize: 12, color: t.muted)),
        ])),
        if (_sync['status'] == 'FAILED') Padding(padding: const EdgeInsets.only(top: 6), child: Text(_syncError('${_sync['error']}', ar), style: const TextStyle(color: Color(0xFFB91C1C), fontWeight: FontWeight.w700, fontSize: 12.5))),
        if (_plats.length > 1) Padding(padding: const EdgeInsets.only(top: 4), child: Text(ar ? 'المزامنة بتشتغل على إنستجرام. اختار ماسنجر لوحده لمزامنته.' : 'Sync runs for Instagram. Pick Messenger alone to sync it.', style: TextStyle(fontSize: 11.5, color: t.muted))),
      ],
    ]));
  }

  Widget _listCard(bool ar, AppTemplate t) {
    return _card(t, Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        Expanded(child: Text(ar ? '$_total شخص · $_selectedCount متحدد' : '$_total people · $_selectedCount selected', style: const TextStyle(fontWeight: FontWeight.w900))),
        if (_listLoading) const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
      ]),
      const SizedBox(height: 8),
      TextField(controller: _search, onChanged: (_) { _debounce?.cancel(); _debounce = Timer(const Duration(milliseconds: 400), () { _picked.clear(); _excluded.clear(); _loadList().catchError((_) {}); }); }, decoration: InputDecoration(isDense: true, hintText: ar ? 'ابحث بالاسم أو اليوزر' : 'Search by name or username', prefixIcon: const Icon(Icons.search_rounded))),
      if (_write) Wrap(spacing: 6, children: [
        TextButton(onPressed: () { setState(() { _all = true; _excluded.clear(); _picked.clear(); }); _scheduleQuote(); }, child: Text(ar ? 'اختار الكل' : 'Select all')),
        TextButton(onPressed: () { setState(() { _all = false; _picked.clear(); _excluded.clear(); }); _scheduleQuote(); }, child: Text(ar ? 'إلغاء التحديد' : 'Clear')),
      ]),
      if (_items.isEmpty && !_listLoading) emptyState(ar ? 'مفيش أشخاص هنا. اعمل مزامنة أو غيّر الفلاتر.' : 'Nobody here yet. Run a sync or change the filters.', icon: Icons.person_search_rounded),
      for (final p in _items) _row(p, ar, t),
      if (_items.length < _total) TextButton(onPressed: _moreLoading ? null : () async { setState(() => _moreLoading = true); try { await _loadList(more: true); } catch (_) {} if (mounted) setState(() => _moreLoading = false); }, child: Text(ar ? 'اعرض المزيد' : 'Show more')),
    ]));
  }

  String _statusText(String st, bool ar) => switch (st) {
        'CONTACTED' => ar ? 'اتبعتله' : 'Contacted',
        'REPLIED' => ar ? 'رد' : 'Replied',
        'OPTED_OUT' => ar ? 'طلب الإيقاف' : 'Opted out',
        'BLOCKED' => ar ? 'محظور' : 'Blocked',
        'NOT_FOUND' => ar ? 'مش لاقيينه' : 'Not found',
        _ => '',
      };

  Widget _row(Map<String, dynamic> p, bool ar, AppTemplate t) {
    final id = '${p['id']}';
    final st = '${p['status'] ?? 'NEW'}';
    final sendable = st == 'NEW' || st == 'CONTACTED';
    final name = '${p['displayName'] ?? ''}'.trim();
    final user = '${p['username'] ?? ''}';
    final src = _sourceNames['${p['source']}'];
    final stText = _statusText(st, ar);
    return InkWell(onTap: _write && sendable ? () => _toggleRow(id) : null, child: Padding(padding: const EdgeInsets.symmetric(vertical: 6), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (_write) Checkbox(value: sendable && _isSelected(id), onChanged: sendable ? (_) => _toggleRow(id) : null, visualDensity: VisualDensity.compact),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [Flexible(child: Text(name.isEmpty ? '@$user' : name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800))), const SizedBox(width: 6), PlatformTag('${p['platform']}')]),
        if (name.isNotEmpty) Text('@$user', textDirection: TextDirection.ltr, style: TextStyle(fontSize: 12, color: t.muted)),
        Text([if (src != null) (ar ? src.$1 : src.$2), if (p['isPrivate'] == true) (ar ? 'حساب خاص' : 'private'), if ('${p['lastComment'] ?? ''}'.isNotEmpty) '“${p['lastComment']}”'].join(' · '), maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 11.5, color: t.muted)),
      ])),
      if (stText.isNotEmpty) Text(stText, style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800, color: st == 'REPLIED' ? const Color(0xFF1E9E5A) : const Color(0xFF6B7C8D))),
    ])));
  }

  Widget _composerCard(bool ar, AppTemplate t) {
    final name = ar ? 'منى' : 'Mona';
    final preview = _msg.text.replaceAll('{name}', name).replaceAllMapped(RegExp(r'\{\{([^{}]{1,300})\}\}'), (m) => m.group(1)!.split('|').first.trim());
    const mute = TextStyle(fontSize: 12, color: Color(0xFF6B7C8D));
    return _card(t, Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Wrap(spacing: 6, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
        ActionChip(avatar: const Icon(Icons.add, size: 16), label: Text(ar ? 'الاسم' : 'Name'), onPressed: () => _insert('{name}')),
        IconButton.outlined(visualDensity: VisualDensity.compact, onPressed: _emojiSheet, icon: const Icon(Icons.emoji_emotions_outlined)),
        IconButton.outlined(visualDensity: VisualDensity.compact, onPressed: _pickImage, icon: const Icon(Icons.image_outlined)),
      ]),
      const SizedBox(height: 6),
      Text(ar ? 'كلمات متنوعة (كل شخص يستلم واحدة):' : 'Different wording (each person gets one):', style: mute),
      Wrap(spacing: 6, runSpacing: 4, children: [
        for (final f in broadcastSpins) ActionChip(backgroundColor: const Color(0xFFE0F2FE), label: Text(f.$1, style: const TextStyle(fontSize: 12.5)), onPressed: () => _insert(f.$2)),
      ]),
      const SizedBox(height: 8),
      TextField(controller: _msg, maxLines: 5, maxLength: 1000, onChanged: (_) { setState(() {}); _scheduleQuote(); }, decoration: InputDecoration(hintText: ar ? 'اكتب رسالتك. اضغط «الاسم» لإضافة اسم الشخص.' : 'Write your message. Tap “Name” to add the person\'s name.')),
      if (_image != null) Align(alignment: AlignmentDirectional.centerStart, child: Stack(clipBehavior: Clip.none, children: [
        ClipRRect(borderRadius: BorderRadius.circular(12), child: Image.network(fileUrl(widget.ctx, _image!), width: 96, height: 96, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(width: 96, height: 96, color: const Color(0xFFE8EEF4), child: const Icon(Icons.image)))),
        PositionedDirectional(end: -8, top: -8, child: InkWell(onTap: () => setState(() => _image = null), child: const CircleAvatar(radius: 12, backgroundColor: Color(0xFFDC2626), child: Icon(Icons.close, size: 14, color: Colors.white)))),
      ])),
      Container(margin: const EdgeInsets.only(top: 8), padding: const EdgeInsets.all(10), decoration: BoxDecoration(color: const Color(0xFFDCF8C6), borderRadius: BorderRadius.circular(14)), child: Text(preview.isEmpty ? (ar ? 'رسالتك هتظهر هنا' : 'Your message appears here') : '$preview\n\nلإيقاف الرسائل اكتب: إيقاف · Reply STOP to unsubscribe', style: TextStyle(color: preview.isEmpty ? Colors.black45 : Colors.black87))),
      const SizedBox(height: 12),
      Text(ar ? 'صيغ تانية للرسالة (كل شخص يستلم صيغة منهم)' : 'Other wordings (each person gets one)', style: const TextStyle(fontWeight: FontWeight.w800)),
      Text(ar ? 'يفضل ٣ صيغ على الأقل عشان الرسائل ماتبقاش متطابقة.' : 'At least 3 wordings are best so messages are not identical.', style: mute),
      for (var i = 0; i < _versions.length; i++) Padding(padding: const EdgeInsets.only(top: 6), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Expanded(child: TextFormField(key: ValueKey('v$i${_versions[i].hashCode}'), initialValue: _versions[i], maxLines: 2, maxLength: 1000, onChanged: (v) => _versions[i] = v, decoration: const InputDecoration(isDense: true))),
        IconButton(onPressed: () => setState(() => _versions.removeAt(i)), icon: const Icon(Icons.close_rounded)),
      ])),
      Wrap(spacing: 8, children: [
        OutlinedButton.icon(onPressed: _versions.length >= 5 ? null : () => setState(() => _versions.add('')), icon: const Icon(Icons.add), label: Text(ar ? 'أضف صيغة' : 'Add a wording')),
        OutlinedButton.icon(onPressed: _versions.length >= 5 || _msg.text.trim().length < 3 ? null : _suggest, icon: const Icon(Icons.auto_awesome_rounded), label: Text(ar ? 'اقترح بالذكاء الاصطناعي' : 'Suggest with AI')),
      ]),
    ]));
  }

  Widget _summaryCard(bool ar, S s, AppTemplate t) {
    final cur = widget.ctx.currency;
    final q = _quote;
    final reachable = (q?['reachable'] as num?)?.toInt() ?? 0;
    final enough = q?['enoughCredit'] != false;
    final canSend = !_busy && q != null && _msg.text.trim().length >= 3 && reachable > 0 && enough;
    return _card(t, Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(ar ? 'ملخص الإرسال' : 'Send summary', style: t.heading(15)),
      const SizedBox(height: 8),
      if (q == null) Text(ar ? 'اختار أشخاص واكتب الرسالة عشان نحسب التكلفة.' : 'Pick people and write the message to see the cost.', style: TextStyle(color: t.muted))
      else ...[
        kv(ar ? 'ينفع يوصلهم' : 'Reachable', '$reachable'),
        kv(ar ? 'التكلفة' : 'Cost', s.money(q['costMinor'] as num?, cur)),
        kv(ar ? 'رصيدك' : 'Your balance', s.money(q['balanceMinor'] as num?, cur)),
        kv(ar ? 'الوقت المتوقع' : 'Estimated time', queueMinutes(q['etaMinutes'])),
        if (!enough) Padding(padding: const EdgeInsets.only(top: 6), child: Text(ar ? 'الرصيد مش كفاية. اشحن رصيدك الأول.' : 'Not enough credit. Top up first.', style: const TextStyle(color: Color(0xFFB91C1C), fontWeight: FontWeight.w700))),
      ],
      const SizedBox(height: 10),
      FilledButton.icon(onPressed: canSend ? _send : null, icon: _busy ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : const Icon(Icons.send_rounded), label: Text(ar ? 'ابعت لـ $reachable شخص' : 'Send to $reachable people')),
    ]));
  }

  Widget _historyTile(Map<String, dynamic> h, bool ar, AppTemplate t) {
    final status = '${h['status']}';
    final color = switch (status) { 'SENDING' => const Color(0xFFD98200), 'DONE' => const Color(0xFF1E9E5A), 'PAUSED' => const Color(0xFF6B7C8D), _ => const Color(0xFFD14343) };
    final st = switch (status) { 'SENDING' => ar ? 'بيتبعت' : 'Sending', 'DONE' => ar ? 'اكتمل' : 'Done', 'PAUSED' => ar ? 'متوقف' : 'Paused', _ => ar ? 'ملغي' : 'Canceled' };
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
        Text('${h['sentCount'] ?? 0} ✓${((h['failedCount'] as num?) ?? 0) > 0 ? '  ${h['failedCount']} ✗' : ''}  ·  ${fmtDate(h['createdAt'] as String?, time: false)}', style: TextStyle(fontSize: 12, color: t.muted)),
      ])),
    ));
  }
}
