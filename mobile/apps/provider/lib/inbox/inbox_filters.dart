import 'package:flutter/material.dart';

import '../theme/templates.dart';
import 'inbox_common.dart';

/// What the owner narrowed the list to. Values of one group are OR-ed, groups are AND-ed (the server does that from comma-separated values).
class InboxFilters {
  InboxFilters({Set<String>? status, Set<String>? ai, Set<String>? channel, this.since = '', this.sort = 'newest'})
      : status = status ?? {},
        ai = ai ?? {},
        channel = channel ?? {};
  final Set<String> status, ai, channel;
  String since, sort;

  InboxFilters copy() => InboxFilters(status: {...status}, ai: {...ai}, channel: {...channel}, since: since, sort: sort);
  int get count => status.length + ai.length + channel.length + (since.isEmpty ? 0 : 1) + (sort == 'newest' ? 0 : 1);
  bool get isEmpty => count == 0;

  Map<String, String?> query() => {
        if (status.isNotEmpty) 'status': status.join(','),
        if (ai.isNotEmpty) 'ai': ai.join(','),
        if (channel.isNotEmpty) 'channel': channel.join(','),
        if (since.isNotEmpty) 'since': since,
        if (sort != 'newest') 'sort': sort,
      };
}

String aiText(String v) => v == 'ACTIVE' ? L('الذكاء شغال', 'AI active') : L('الذكاء متوقف', 'AI paused');
String sinceText(String v) => switch (v) { '30m' => L('آخر ٣٠ دقيقة', 'Last 30 min'), '1h' => L('آخر ساعة', 'Last hour'), 'today' => L('النهارده', 'Today'), '7d' => L('آخر ٧ أيام', 'Last 7 days'), _ => v };
String sortText(String v) => switch (v) { 'oldest' => L('الأقدم', 'Oldest'), 'unread' => L('غير المقروءة أولاً', 'Unread first'), 'priority' => L('الأولوية', 'Priority'), _ => L('الأحدث', 'Newest') };

/// Bottom sheet: multi-select status, AI and channel, plus time and sort. Returns the new filters (null when dismissed).
Future<InboxFilters?> showInboxFilters(BuildContext context, InboxFilters current) {
  return showModalBottomSheet<InboxFilters>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (c) => _FiltersSheet(initial: current.copy()),
  );
}

class _FiltersSheet extends StatefulWidget {
  const _FiltersSheet({required this.initial});
  final InboxFilters initial;
  @override
  State<_FiltersSheet> createState() => _FiltersSheetState();
}

class _FiltersSheetState extends State<_FiltersSheet> {
  late final InboxFilters f = widget.initial;

  Widget _group(String title, List<String> values, Set<String> picked, String Function(String) label, {Color Function(String)? tone}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: TextStyle(fontWeight: FontWeight.w900, fontSize: 12.5, color: AppTemplate.of(context).muted)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final v in values)
            FilterChip(
              label: Text(label(v)),
              selected: picked.contains(v),
              showCheckmark: false,
              avatar: tone == null ? null : CircleAvatar(radius: 5, backgroundColor: tone(v)),
              onSelected: (on) => setState(() => on ? picked.add(v) : picked.remove(v)),
            ),
        ]),
      ]),
    );
  }

  Widget _single(String title, List<String> values, String current, String Function(String) label, void Function(String) set, {bool clearable = false}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(title, style: TextStyle(fontWeight: FontWeight.w900, fontSize: 12.5, color: AppTemplate.of(context).muted)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final v in values) ChoiceChip(label: Text(label(v)), selected: current == v, showCheckmark: false, onSelected: (_) => setState(() => set(clearable && current == v ? '' : v))),
        ]),
      ]),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.85),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
            child: Row(children: [
              Expanded(child: Text(L('فلترة المحادثات', 'Filter chats'), style: AppTemplate.of(context).heading(18))),
              TextButton(onPressed: () => setState(() { f.status.clear(); f.ai.clear(); f.channel.clear(); f.since = ''; f.sort = 'newest'; }), child: Text(L('مسح الكل', 'Reset'))),
            ]),
          ),
          Flexible(
            child: ListView(padding: const EdgeInsets.fromLTRB(20, 4, 20, 8), shrinkWrap: true, children: [
              _group(L('الحالة', 'Status'), kStatuses, f.status, statusText, tone: statusColor),
              _group(L('الذكاء الاصطناعي', 'AI'), const ['ACTIVE', 'PAUSED'], f.ai, aiText),
              _group(L('القناة', 'Channel'), kChannels, f.channel, channelText, tone: channelColor),
              _single(L('الفترة', 'Time'), const ['30m', '1h', 'today', '7d'], f.since, sinceText, (v) => f.since = v, clearable: true),
              _single(L('الترتيب', 'Sort'), const ['newest', 'oldest', 'unread', 'priority'], f.sort, sortText, (v) => f.sort = v),
            ]),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
            child: FilledButton(onPressed: () => Navigator.pop(context, f), child: Text(L('عرض النتائج', 'Show results'))),
          ),
        ]),
      ),
    );
  }
}
