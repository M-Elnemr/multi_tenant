import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import '../common.dart';
import '../theme/templates.dart';
import 'chat_screen.dart';
import 'inbox_common.dart';
import 'inbox_filters.dart';

/// The conversation list: search, quick status chips with counts, a filter sheet with removable chips, endless paging and a quiet refresh every 20 seconds.
class ChatListTab extends StatefulWidget {
  const ChatListTab({super.key, required this.ctx, required this.counts, required this.onChanged});
  final Ctx ctx;
  final Map<String, dynamic> counts;
  /// Called after something changed (a chat opened / acted on) so the counters are refreshed.
  final VoidCallback onChanged;
  @override
  State<ChatListTab> createState() => _ChatListTabState();
}

class _ChatListTabState extends State<ChatListTab> {
  static const _limit = 30;
  final _scroll = ScrollController();
  final _search = TextEditingController();
  Timer? _debounce, _poll;
  InboxFilters _f = InboxFilters();
  List<Map<String, dynamic>> _items = [];
  int? _next;
  int _total = 0;
  bool _loading = true, _more = false;
  Object? _error;
  int _gen = 0;
  final Set<String> _sel = {};
  bool _acting = false;

  bool get _write => widget.ctx.can('ai.manage');

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() { if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 300 && _next != null && !_more && !_loading) _loadMore(); });
    _reload();
    _poll = Timer.periodic(const Duration(seconds: 20), (_) => _quiet());
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _poll?.cancel();
    _scroll.dispose();
    _search.dispose();
    super.dispose();
  }

  Map<String, String?> _q([int? cursor]) => {
        'page': 'true',
        'limit': '$_limit',
        ..._f.query(),
        if (_search.text.trim().isNotEmpty) 'q': _search.text.trim(),
        if (cursor != null) 'cursor': '$cursor',
      };

  Future<Map<String, dynamic>> _fetch([int? cursor]) async => Map<String, dynamic>.from(await widget.ctx.api.get('store/chat/conversations', query: _q(cursor)) as Map);

  Future<void> _reload() async {
    final gen = ++_gen;
    setState(() { _loading = _items.isEmpty; _error = null; });
    try {
      final r = await _fetch();
      if (!mounted || gen != _gen) return;
      setState(() { _items = maps(r['data']); _next = (r['nextCursor'] as num?)?.toInt(); _total = (r['total'] as num?)?.toInt() ?? _items.length; _loading = false; });
    } catch (e) {
      if (mounted && gen == _gen) setState(() { if (_items.isEmpty) _error = e; _loading = false; });
    }
  }

  /// A background refresh of the first page only while the owner has not scrolled to older pages (so the list never jumps).
  Future<void> _quiet() async {
    if (!mounted || _loading || _more || _items.length > _limit) return;
    final gen = _gen;
    try {
      final r = await _fetch();
      if (!mounted || gen != _gen || _items.length > _limit) return;
      setState(() { _items = maps(r['data']); _next = (r['nextCursor'] as num?)?.toInt(); _total = (r['total'] as num?)?.toInt() ?? _items.length; });
    } catch (_) {}
  }

  Future<void> _loadMore() async {
    final cursor = _next;
    if (cursor == null) return;
    setState(() => _more = true);
    final gen = _gen;
    try {
      final r = await _fetch(cursor);
      if (!mounted || gen != _gen) return;
      final seen = _items.map((e) => e['id']).toSet();
      setState(() { _items = [..._items, ...maps(r['data']).where((e) => !seen.contains(e['id']))]; _next = (r['nextCursor'] as num?)?.toInt(); });
    } catch (_) {}
    if (mounted) setState(() => _more = false);
  }

  void _toggleQuick(String status) {
    setState(() { _f.status.contains(status) ? _f.status.remove(status) : _f.status.add(status); });
    _reload();
  }

  Future<void> _openFilters() async {
    final r = await showInboxFilters(context, _f);
    if (r == null || !mounted) return;
    setState(() => _f = r);
    _reload();
  }

  void _toggleSel(String id) => setState(() { if (!_sel.remove(id)) { if (_sel.length < 200) _sel.add(id); } });

  /// Bulk actions on the selected chats: read | unread | close | takeover | resume (the server accepts up to 200).
  Future<void> _bulk(String action) async {
    if (_sel.isEmpty) return;
    setState(() => _acting = true);
    Map? r;
    final ok = await run(context, () async { r = await widget.ctx.api.post('store/chat/conversations/bulk', {'ids': _sel.toList(), 'action': action}) as Map?; });
    if (!mounted) return;
    setState(() => _acting = false);
    if (ok) {
      toast(context, L('اتنفّذ على ${r?['changed'] ?? _sel.length} محادثة', 'Done on ${r?['changed'] ?? _sel.length} chats'));
      setState(_sel.clear);
      widget.onChanged();
      _reload();
    }
  }

  Widget _selectionBar(AppTemplate t) => Padding(
        padding: const EdgeInsets.fromLTRB(8, 6, 8, 2),
        child: Container(
          padding: const EdgeInsetsDirectional.fromSTEB(4, 2, 4, 2),
          decoration: BoxDecoration(color: accentOf(t).withValues(alpha: 0.10), borderRadius: BorderRadius.circular(14)),
          child: Row(children: [
            IconButton(tooltip: L('إلغاء التحديد', 'Cancel selection'), onPressed: () => setState(_sel.clear), icon: const Icon(Icons.close_rounded)),
            Expanded(child: Text(L('${_sel.length} محدد', '${_sel.length} selected'), style: const TextStyle(fontWeight: FontWeight.w900))),
            if (_acting) const Padding(padding: EdgeInsets.all(10), child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)))
            else ...[
              IconButton(tooltip: L('تحديد الكل', 'Select all'), onPressed: () => setState(() { for (final e in _items) { if (_sel.length < 200) _sel.add('${e['id']}'); } }), icon: const Icon(Icons.select_all_rounded)),
              IconButton(tooltip: L('علّم كمقروءة', 'Mark read'), onPressed: () => _bulk('read'), icon: const Icon(Icons.mark_chat_read_outlined)),
              IconButton(tooltip: L('قفل', 'Close'), onPressed: () => _bulk('close'), icon: const Icon(Icons.check_circle_outline_rounded)),
              PopupMenuButton<String>(
                onSelected: _bulk,
                itemBuilder: (_) => [
                  PopupMenuItem(value: 'unread', child: Text(L('علّم كغير مقروءة', 'Mark unread'))),
                  PopupMenuItem(value: 'takeover', child: Text(L('استلم المحادثات', 'Take over'))),
                  PopupMenuItem(value: 'resume', child: Text(L('رجّع الذكاء الاصطناعي', 'Resume AI'))),
                ],
              ),
            ],
          ]),
        ),
      );

  Future<void> _open(Map<String, dynamic> row) async {
    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => ChatScreen(ctx: widget.ctx, id: '${row['id']}', initial: row)));
    if (!mounted) return;
    widget.onChanged();
    _reload();
  }

  int _countFor(String s) => switch (s) {
        'NEEDS_REPLY' => (widget.counts['needsReply'] as num?)?.toInt() ?? 0,
        'WAITING' => (widget.counts['waiting'] as num?)?.toInt() ?? 0,
        'UNHAPPY' => (widget.counts['unhappy'] as num?)?.toInt() ?? 0,
        _ => 0,
      };

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final chips = <Widget>[
      for (final v in _f.status) _removable(statusText(v), () { setState(() => _f.status.remove(v)); _reload(); }),
      for (final v in _f.ai) _removable(aiText(v), () { setState(() => _f.ai.remove(v)); _reload(); }),
      for (final v in _f.channel) _removable(channelText(v), () { setState(() => _f.channel.remove(v)); _reload(); }),
      if (_f.since.isNotEmpty) _removable(sinceText(_f.since), () { setState(() => _f.since = ''); _reload(); }),
      if (_f.sort != 'newest') _removable(sortText(_f.sort), () { setState(() => _f.sort = 'newest'); _reload(); }),
    ];
    return Column(children: [
      if (_sel.isNotEmpty) _selectionBar(t) else
      Padding(
        padding: const EdgeInsets.fromLTRB(14, 8, 14, 4),
        child: Row(children: [
          Expanded(
            child: TextField(
              controller: _search,
              textInputAction: TextInputAction.search,
              onChanged: (_) { setState(() {}); _debounce?.cancel(); _debounce = Timer(const Duration(milliseconds: 400), _reload); },
              decoration: InputDecoration(
                isDense: true,
                hintText: L('ابحث بالاسم أو الرقم أو الرسالة', 'Search name, phone or message'),
                prefixIcon: const Icon(Icons.search_rounded),
                suffixIcon: _search.text.isEmpty ? null : IconButton(icon: const Icon(Icons.close_rounded), onPressed: () { _search.clear(); setState(() {}); _reload(); }),
              ),
            ),
          ),
          const SizedBox(width: 8),
          Badge(
            isLabelVisible: _f.count > 0,
            label: Text('${_f.count}'),
            child: IconButton.filledTonal(onPressed: _openFilters, tooltip: L('فلترة', 'Filters'), icon: const Icon(Icons.tune_rounded)),
          ),
        ]),
      ),
      SizedBox(
        height: 48,
        child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6), children: [
          Padding(padding: const EdgeInsets.symmetric(horizontal: 3), child: ChoiceChip(label: Text(L('الكل', 'All')), selected: _f.status.isEmpty, showCheckmark: false, onSelected: (_) { setState(() => _f.status.clear()); _reload(); })),
          for (final s in const ['NEEDS_REPLY', 'WAITING', 'UNHAPPY', 'TAKEOVER', 'CLOSED'])
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 3),
              child: ChoiceChip(
                label: Text(_countFor(s) > 0 ? '${statusText(s)}  ${_countFor(s)}' : statusText(s)),
                selected: _f.status.contains(s),
                showCheckmark: false,
                avatar: CircleAvatar(radius: 5, backgroundColor: statusColor(s)),
                onSelected: (_) => _toggleQuick(s),
              ),
            ),
        ]),
      ),
      if (chips.isNotEmpty) SizedBox(height: 40, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12), children: chips)),
      Expanded(
        child: _loading
            ? ListView(padding: const EdgeInsets.all(14), children: const [Skeleton(height: 78), SizedBox(height: 10), Skeleton(height: 78), SizedBox(height: 10), Skeleton(height: 78)])
            : _error != null
                ? errorState(_error!, _reload)
                : RefreshIndicator(
                    onRefresh: _reload,
                    child: _items.isEmpty
                        ? ListView(children: [templateEmpty(context, _f.isEmpty && _search.text.isEmpty ? L('لسه مفيش محادثات. لما العملاء يكتبوا على موقعك أو واتساب أو ماسنجر أو إنستجرام هتظهر هنا.', 'No chats yet. They show up here when customers write on your site, WhatsApp, Messenger or Instagram.') : L('مفيش محادثات مطابقة. جرّب فلاتر أقل.', 'No matching chats. Try fewer filters.'), icon: Icons.forum_outlined)])
                        : ListView.builder(
                            controller: _scroll,
                            padding: const EdgeInsets.fromLTRB(14, 4, 14, 90),
                            itemCount: _items.length + 1,
                            itemBuilder: (c, i) {
                              if (i == _items.length) {
                                return Padding(padding: const EdgeInsets.all(14), child: Center(child: _more ? const CircularProgressIndicator() : Text(L('${_items.length} من $_total', '${_items.length} of $_total'), style: TextStyle(color: t.muted, fontSize: 12))));
                              }
                              return FadeSlideIn(index: i < 8 ? i : 0, dy: 10, child: _ChatRow(row: _items[i], selected: _sel.contains('${_items[i]['id']}'), selecting: _sel.isNotEmpty, onTap: () => _sel.isNotEmpty ? _toggleSel('${_items[i]['id']}') : _open(_items[i]), onLongPress: _write ? () => _toggleSel('${_items[i]['id']}') : null));
                            },
                          ),
                  ),
      ),
    ]);
  }

  Widget _removable(String label, VoidCallback onRemove) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 3),
        child: InputChip(label: Text(label, style: const TextStyle(fontSize: 12.5)), onDeleted: onRemove, visualDensity: VisualDensity.compact, deleteIcon: const Icon(Icons.close_rounded, size: 16)),
      );
}

class _ChatRow extends StatelessWidget {
  const _ChatRow({required this.row, required this.onTap, this.onLongPress, this.selected = false, this.selecting = false});
  final Map<String, dynamic> row;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;
  final bool selected, selecting;

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final name = whoName(row);
    final unread = row['unread'] == true;
    final n = (row['unreadCount'] as num?)?.toInt() ?? 0;
    final statuses = ((row['statuses'] as List?) ?? const []).cast<String>().where((s) => s != 'OPEN').toList();
    final last = '${row['lastMessage'] ?? ''}';
    final prefix = row['lastFromCustomer'] == true ? '' : row['lastByOwner'] == true ? L('إنت: ', 'You: ') : row['lastKind'] == 'FOLLOWUP' ? L('متابعة: ', 'Follow-up: ') : L('الذكاء: ', 'AI: ');
    return Card(
      color: selected ? Color.lerp(t.card, accentOf(t), 0.16) : unread ? Color.lerp(t.card, t.isShop ? t.accent : t.accent, 0.06) : null,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        child: Padding(
          padding: EdgeInsets.all(t.isClinic ? 14 : 11),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (selecting) Padding(padding: const EdgeInsetsDirectional.only(end: 8, top: 10), child: Icon(selected ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded, color: selected ? accentOf(t) : t.muted)),
            ChatAvatar(name: name, channel: row['channel']),
            const SizedBox(width: 12),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  Expanded(child: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontWeight: unread ? FontWeight.w900 : FontWeight.w700, fontSize: 15.5))),
                  Text(shortTime('${row['updatedAt']}'), style: TextStyle(fontSize: 11.5, color: unread ? accentOf(t) : t.muted, fontWeight: unread ? FontWeight.w800 : FontWeight.w500)),
                ]),
                const SizedBox(height: 2),
                Row(children: [
                  Expanded(child: Text('$prefix$last', maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(color: unread ? t.text : t.muted, fontSize: 13.5, fontWeight: unread ? FontWeight.w600 : FontWeight.w400))),
                  if (n > 0)
                    Container(
                      margin: const EdgeInsetsDirectional.only(start: 8),
                      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                      constraints: const BoxConstraints(minWidth: 22),
                      decoration: BoxDecoration(borderRadius: BorderRadius.circular(12), gradient: t.isShop ? LinearGradient(colors: t.accentGradient) : null, color: t.isShop ? null : (t.isClinic ? t.ink : t.accent)),
                      child: Text(n > 99 ? '99+' : '$n', textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 11.5, fontWeight: FontWeight.w900)),
                    ),
                ]),
                if (statuses.isNotEmpty || row['aiPaused'] == true || row['priority'] == 'HIGH')
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Wrap(runSpacing: 4, children: [
                      for (final s in statuses) Tag(statusText(s), statusColor(s)),
                      if (row['aiPaused'] == true && !statuses.contains('TAKEOVER')) Tag(L('الذكاء متوقف', 'AI paused'), const Color(0xFF7A8294), icon: Icons.pause_rounded),
                      if (row['priority'] == 'HIGH') Tag(L('أولوية عالية', 'High priority'), const Color(0xFFD64545), icon: Icons.priority_high_rounded),
                    ]),
                  ),
              ]),
            ),
          ]),
        ),
      ),
    );
  }
}
