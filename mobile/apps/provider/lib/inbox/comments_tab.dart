import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import '../common.dart';
import '../theme/templates.dart';
import 'inbox_common.dart';

/// Comments on the shop's Facebook / Instagram posts and ads: reply publicly, send a private message (leave it empty and the AI writes it) or ignore.
class CommentsTab extends StatefulWidget {
  const CommentsTab({super.key, required this.ctx, required this.onChanged});
  final Ctx ctx;
  final VoidCallback onChanged;
  @override
  State<CommentsTab> createState() => _CommentsTabState();
}

class _CommentsTabState extends State<CommentsTab> {
  final _search = TextEditingController();
  Timer? _debounce;
  String _status = 'UNREPLIED', _platform = '';
  List<Map<String, dynamic>> _items = [];
  int _total = 0;
  bool _loading = true;
  Object? _error;
  final Set<String> _busy = {};

  bool get _write => widget.ctx.can('ai.manage');

  @override
  void initState() { super.initState(); _load(); }

  @override
  void dispose() { _debounce?.cancel(); _search.dispose(); super.dispose(); }

  Future<void> _load() async {
    setState(() { _loading = _items.isEmpty; _error = null; });
    try {
      final r = Map<String, dynamic>.from(await widget.ctx.api.get('store/comments', query: {'status': _status, if (_platform.isNotEmpty) 'platform': _platform, if (_search.text.trim().isNotEmpty) 'q': _search.text.trim()}) as Map);
      if (mounted) setState(() { _items = maps(r['data']); _total = (r['total'] as num?)?.toInt() ?? _items.length; _loading = false; });
    } catch (e) {
      if (mounted) setState(() { if (_items.isEmpty) _error = e; _loading = false; });
    }
  }

  Future<String?> _ask(String title, String hint, {bool allowEmpty = false, String? note}) async {
    final c = TextEditingController();
    final r = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => Padding(
        padding: EdgeInsets.fromLTRB(20, 0, 20, 16 + MediaQuery.of(ctx).viewInsets.bottom),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(title, style: AppTemplate.of(ctx).heading(18)),
          if (note != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(note, style: TextStyle(color: AppTemplate.of(ctx).muted, fontSize: 12.5))),
          const SizedBox(height: 12),
          TextField(controller: c, autofocus: true, minLines: 2, maxLines: 6, maxLength: 1000, decoration: InputDecoration(hintText: hint)),
          const SizedBox(height: 8),
          FilledButton(onPressed: () { if (allowEmpty || c.text.trim().isNotEmpty) Navigator.pop(ctx, c.text.trim()); }, child: Text(L('إرسال', 'Send'))),
        ]),
      ),
    );
    c.dispose();
    return r;
  }

  Future<void> _act(Map<String, dynamic> cm, String action, [Map<String, dynamic>? body, String? done]) async {
    final id = '${cm['id']}';
    setState(() => _busy.add(id));
    final ok = await run(context, () => widget.ctx.api.post('store/comments/$id/$action', body ?? {}));
    if (!mounted) return;
    setState(() => _busy.remove(id));
    if (ok) {
      if (done != null) toast(context, done);
      widget.onChanged();
      await _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(14, 8, 14, 4),
        child: TextField(
          controller: _search,
          onChanged: (_) { setState(() {}); _debounce?.cancel(); _debounce = Timer(const Duration(milliseconds: 400), _load); },
          decoration: InputDecoration(isDense: true, hintText: L('ابحث في التعليقات', 'Search comments'), prefixIcon: const Icon(Icons.search_rounded), suffixIcon: _search.text.isEmpty ? null : IconButton(icon: const Icon(Icons.close_rounded), onPressed: () { _search.clear(); setState(() {}); _load(); })),
        ),
      ),
      SizedBox(
        height: 48,
        child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6), children: [
          for (final s in const ['UNREPLIED', 'REPLIED', 'IGNORED'])
            Padding(padding: const EdgeInsets.symmetric(horizontal: 3), child: ChoiceChip(label: Text(_statusText(s)), selected: _status == s, showCheckmark: false, onSelected: (_) { setState(() => _status = s); _load(); })),
          const SizedBox(width: 10),
          for (final p in const ['', 'FACEBOOK', 'INSTAGRAM'])
            Padding(padding: const EdgeInsets.symmetric(horizontal: 3), child: ChoiceChip(label: Text(p.isEmpty ? L('كل المنصات', 'All platforms') : _platformText(p)), selected: _platform == p, showCheckmark: false, onSelected: (_) { setState(() => _platform = p); _load(); })),
        ]),
      ),
      Expanded(
        child: _loading
            ? ListView(padding: const EdgeInsets.all(14), children: const [Skeleton(height: 110), SizedBox(height: 10), Skeleton(height: 110)])
            : _error != null
                ? errorState(_error!, _load)
                : RefreshIndicator(
                    onRefresh: _load,
                    child: _items.isEmpty
                        ? ListView(children: [templateEmpty(context, _status == 'UNREPLIED' ? L('مفيش تعليقات مستنية رد.', 'No comments waiting for a reply.') : L('مفيش تعليقات.', 'No comments.'), icon: Icons.mode_comment_outlined)])
                        : ListView.builder(
                            padding: const EdgeInsets.fromLTRB(14, 4, 14, 90),
                            itemCount: _items.length + 1,
                            itemBuilder: (c, i) => i == _items.length ? Padding(padding: const EdgeInsets.all(12), child: Center(child: Text(L('${_items.length} من $_total', '${_items.length} of $_total'), style: TextStyle(color: t.muted, fontSize: 12)))) : _card(_items[i], t),
                          ),
                  ),
      ),
    ]);
  }

  String _statusText(String s) => switch (s) { 'UNREPLIED' => L('مستنية رد', 'Waiting'), 'REPLIED' => L('اتردّ عليها', 'Replied'), _ => L('متجاهلة', 'Ignored') };
  String _platformText(String p) => p == 'FACEBOOK' ? 'Facebook' : 'Instagram';

  Widget _card(Map<String, dynamic> cm, AppTemplate t) {
    final platform = '${cm['platform']}';
    final busy = _busy.contains('${cm['id']}');
    final unreplied = cm['status'] == 'UNREPLIED';
    final dm = '${cm['dmStatus'] ?? ''}';
    return Card(
      child: Padding(
        padding: EdgeInsets.all(t.isClinic ? 16 : 13),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Container(width: 28, height: 28, decoration: BoxDecoration(color: channelColor(platform), shape: BoxShape.circle), child: Icon(channelIcon(platform), size: 15, color: Colors.white)),
            const SizedBox(width: 8),
            Expanded(child: Text('${cm['authorName'] ?? L('زائر', 'Guest')}', style: const TextStyle(fontWeight: FontWeight.w800))),
            if (cm['question'] == true) Tag(L('سؤال', 'Question'), const Color(0xFF6D4AE0), icon: Icons.help_outline_rounded),
            if (cm['isAd'] == true) Tag(L('إعلان', 'Ad'), const Color(0xFFC77700), icon: Icons.campaign_rounded),
            Text(shortTime('${cm['createdAt']}'), style: TextStyle(color: t.muted, fontSize: 11.5)),
          ]),
          const SizedBox(height: 8),
          Text('${cm['text'] ?? ''}', style: const TextStyle(height: 1.4, fontSize: 14.5)),
          if ('${cm['postText'] ?? ''}'.isNotEmpty) Padding(
            padding: const EdgeInsets.only(top: 8),
            child: InkWell(
              onTap: '${cm['postPermalink'] ?? ''}'.isEmpty ? null : () => launch('${cm['postPermalink']}'),
              child: Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(color: t.paper, borderRadius: BorderRadius.circular(10), border: Border.all(color: t.line)),
                child: Row(children: [
                  Icon(Icons.article_outlined, size: 16, color: t.muted),
                  const SizedBox(width: 6),
                  Expanded(child: Text('${cm['postText']}', maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 12, color: t.muted))),
                  if ('${cm['postPermalink'] ?? ''}'.isNotEmpty) Icon(Icons.open_in_new_rounded, size: 14, color: t.muted),
                ]),
              ),
            ),
          ),
          if ('${cm['replyText'] ?? ''}'.isNotEmpty) Container(
            margin: const EdgeInsets.only(top: 8),
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(color: accentOf(t).withValues(alpha: 0.08), borderRadius: BorderRadius.circular(10)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(dotted([L('رد المتجر', 'Shop reply'), if (cm['repliedBy'] != null) '${cm['repliedBy']}']), style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800, color: accentOf(t))),
              const SizedBox(height: 2),
              Text('${cm['replyText']}'),
            ]),
          ),
          if (dm.isNotEmpty && dm != 'NONE') Padding(padding: const EdgeInsets.only(top: 6), child: Tag(L('رسالة خاصة: $dm', 'Private message: $dm'), const Color(0xFF7A8294), icon: Icons.mail_outline_rounded)),
          if (_write && unreplied) ...[
            const SizedBox(height: 10),
            Row(children: [
              Expanded(child: FilledButton.icon(style: FilledButton.styleFrom(minimumSize: const Size(0, 42)), onPressed: busy ? null : () async { final x = await _ask(L('رد علني على التعليق', 'Public reply'), L('اكتب ردك…', 'Write your reply…')); if (x != null) await _act(cm, 'reply', {'text': x}, L('اتنشر الرد', 'Reply posted')); }, icon: const Icon(Icons.reply_rounded, size: 18), label: Text(L('رد', 'Reply')))),
              const SizedBox(width: 6),
              Expanded(child: OutlinedButton.icon(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 42)), onPressed: busy ? null : () async { final x = await _ask(L('رسالة خاصة للعميل', 'Private message'), L('سيبها فاضية والذكاء يكتبها', 'Leave empty and the AI writes it'), allowEmpty: true, note: L('لو سيبتها فاضية الذكاء الاصطناعي هيكتب الرسالة.', 'If you leave it empty the AI writes the message.')); if (x != null) await _act(cm, 'dm', {'text': x}, L('اتبعتت الرسالة', 'Message sent')); }, icon: const Icon(Icons.send_rounded, size: 16), label: Text(L('خاص', 'DM')))),
              IconButton(tooltip: L('تجاهل', 'Ignore'), onPressed: busy ? null : () => _act(cm, 'ignore', {}, L('اتجاهلت', 'Ignored')), icon: const Icon(Icons.visibility_off_outlined)),
            ]),
          ],
        ]),
      ),
    );
  }
}
