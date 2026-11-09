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
  Map<String, dynamic>? _settings;

  bool get _write => widget.ctx.can('ai.manage');

  @override
  void initState() { super.initState(); _load(); _loadSettings(); }

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

  Future<void> _loadSettings() async {
    try {
      final r = Map<String, dynamic>.from(await widget.ctx.api.get('store/comments/settings') as Map);
      if (mounted) setState(() => _settings = r);
    } catch (_) {}
  }

  bool get _autoOn => _settings != null && ('${_settings!['publicReply'] ?? 'OFF'}' != 'OFF' || _settings!['dmEnabled'] == true);

  Future<void> _saveSettings(Map<String, dynamic> next) async {
    final cur = _settings ?? const {};
    final body = {'publicReply': cur['publicReply'] ?? 'OFF', 'stampText': cur['stampText'], 'dmEnabled': cur['dmEnabled'] == true, 'dmOnlyQuestions': cur['dmOnlyQuestions'] == true, ...next};
    Map<String, dynamic>? r;
    if (await run(context, () async { r = Map<String, dynamic>.from(await widget.ctx.api.put('store/comments/settings', body) as Map); }) && mounted) setState(() => _settings = r);
  }

  Future<void> _toggleAutomation(bool on) async {
    if (on) { await _editSettings(forceOn: true); return; }
    await _saveSettings({'publicReply': 'OFF', 'dmEnabled': false});
  }

  Future<void> _editSettings({bool forceOn = false}) async {
    final cur = _settings ?? const {};
    var mode = '${cur['publicReply'] ?? 'OFF'}';
    if (forceOn && mode == 'OFF' && cur['dmEnabled'] != true) mode = 'AI';
    var dm = cur['dmEnabled'] == true;
    var onlyQ = cur['dmOnlyQuestions'] == true;
    final stamp = TextEditingController(text: '${cur['stampText'] ?? ''}');
    final price = S.of(context).money((cur['aiPriceMinor'] as num?) ?? 10, widget.ctx.currency);
    final ok = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheet) => StatefulBuilder(builder: (sheet, set) => SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(20, 0, 20, 16 + MediaQuery.of(sheet).viewInsets.bottom),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(L('الرد التلقائي على التعليقات', 'Automatic comment replies'), style: AppTemplate.of(sheet).heading(18)),
          const SizedBox(height: 10),
          Text(L('الرد العلني', 'Public reply'), style: const TextStyle(fontWeight: FontWeight.w800)),
          const SizedBox(height: 6),
          SegmentedButton<String>(
            showSelectedIcon: false,
            segments: [ButtonSegment(value: 'OFF', label: Text(L('بدون', 'Off'))), ButtonSegment(value: 'STAMP', label: Text(L('نص ثابت', 'Fixed text'))), ButtonSegment(value: 'AI', label: Text(L('ذكاء اصطناعي', 'AI')))],
            selected: {mode},
            onSelectionChanged: (v) => set(() => mode = v.first),
          ),
          if (mode == 'STAMP') Padding(padding: const EdgeInsets.only(top: 8), child: TextField(controller: stamp, maxLength: 300, minLines: 2, maxLines: 4, decoration: InputDecoration(hintText: L('اكتب الرد الثابت…', 'Write the fixed reply…')))),
          if (mode == 'AI') Padding(padding: const EdgeInsets.only(top: 4), child: Text(L('كل رد بالذكاء الاصطناعي بـ $price.', 'Each AI reply costs $price.'), style: TextStyle(color: AppTemplate.of(sheet).muted, fontSize: 12.5))),
          const Divider(height: 24),
          SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(L('رسالة خاصة للعميل', 'Private message to the commenter')), subtitle: Text(L('الذكاء الاصطناعي يبعت أول رسالة خاصة. كل رسالة بـ $price.', 'The AI sends the first private message. Each costs $price.')), value: dm, onChanged: (v) => set(() => dm = v)),
          if (dm) SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(L('للأسئلة بس', 'Only for questions')), value: onlyQ, onChanged: (v) => set(() => onlyQ = v)),
          const SizedBox(height: 8),
          FilledButton(onPressed: () { if (mode == 'STAMP' && stamp.text.trim().isEmpty) return; Navigator.pop(sheet, true); }, child: Text(L('حفظ', 'Save'))),
        ]),
      )),
    );
    final text = stamp.text.trim();
    stamp.dispose();
    if (ok == true && mounted) await _saveSettings({'publicReply': mode, 'stampText': text.isEmpty ? null : text, 'dmEnabled': dm, 'dmOnlyQuestions': dm && onlyQ});
  }

  Widget _automationCard(AppTemplate t) {
    final st = _settings;
    if (st == null) return const SizedBox.shrink();
    final on = _autoOn;
    final mode = '${st['publicReply'] ?? 'OFF'}';
    final dm = st['dmEnabled'] == true;
    final money = S.of(context);
    final price = money.money((st['aiPriceMinor'] as num?) ?? 10, widget.ctx.currency);
    final replies = (st['todayReplies'] as num?)?.toInt(), dms = (st['todayDms'] as num?)?.toInt();
    final cost = st['todayCostMinor'] as num?;
    final modeText = switch (mode) { 'STAMP' => L('رد ثابت', 'Fixed reply'), 'AI' => L('رد بالذكاء الاصطناعي', 'AI reply'), _ => L('بدون رد علني', 'No public reply') };
    final color = on ? const Color(0xFF1E9E5A) : t.muted;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: t.cardDecoration().copyWith(border: Border.all(color: on ? const Color(0xFF1E9E5A).withValues(alpha: 0.55) : t.line, width: on ? 1.5 : 1)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Icon(on ? Icons.bolt_rounded : Icons.bolt_outlined, color: color),
            const SizedBox(width: 8),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(L('الرد التلقائي على التعليقات', 'Automatic comment replies'), style: t.heading(15)),
              Text(on ? L('شغّال', 'On') : L('متوقف', 'Off'), style: TextStyle(color: color, fontWeight: FontWeight.w800, fontSize: 12.5)),
            ])),
            if (_write) Switch(value: on, onChanged: _toggleAutomation),
          ]),
          if (on) ...[
            const SizedBox(height: 8),
            Wrap(spacing: 6, runSpacing: 4, children: [
              Tag(modeText, accentOf(t), icon: mode == 'AI' ? Icons.auto_awesome_rounded : Icons.push_pin_outlined),
              Tag(dm ? L('رسالة خاصة: شغّالة', 'Private message: on') : L('رسالة خاصة: متوقفة', 'Private message: off'), dm ? const Color(0xFF1E9E5A) : const Color(0xFF7A8294), icon: Icons.mail_outline_rounded),
            ]),
          ],
          if (replies != null || dms != null || cost != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(
            L('النهارده: ${replies ?? 0} رد · ${dms ?? 0} رسالة خاصة${cost != null ? ' · ${money.money(cost, widget.ctx.currency)}' : ''}', 'Today: ${replies ?? 0} replies · ${dms ?? 0} private messages${cost != null ? ' · ${money.money(cost, widget.ctx.currency)}' : ''}'),
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13))),
          const SizedBox(height: 4),
          Text(L('رد الذكاء الاصطناعي على تعليق: $price. رسالة خاصة بالذكاء الاصطناعي: $price.', 'AI comment reply: $price. AI private message: $price.'), style: TextStyle(color: t.muted, fontSize: 12)),
          if (_write) Align(alignment: AlignmentDirectional.centerEnd, child: TextButton.icon(onPressed: _editSettings, icon: const Icon(Icons.edit_rounded, size: 16), label: Text(L('تعديل', 'Edit')))),
        ]),
      ),
    );
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
      _automationCard(t),
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
