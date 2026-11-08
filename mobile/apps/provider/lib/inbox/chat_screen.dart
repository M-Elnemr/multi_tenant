import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show Clipboard, ClipboardData;
import 'package:platform_core/platform_core.dart';

import '../common.dart';
import '../theme/templates.dart';
import 'composer.dart';
import 'customer_sheet.dart';
import 'inbox_common.dart';

/// One conversation: bubbles (customer / AI / team / follow-up), system events, internal notes, take over / resume AI, close,
/// the channel-window banner and the composer. It refreshes quietly every 8 seconds (without marking anything read again).
class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, required this.ctx, required this.id, this.initial});
  final Ctx ctx;
  final String id;
  final Map<String, dynamic>? initial;
  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  Map<String, dynamic>? _c;
  Object? _error;
  bool _busy = false, _loading = false, _full = false;
  Timer? _timer;

  String get _base => 'store/chat/conversations/${widget.id}';
  bool get _write => widget.ctx.can('ai.manage');

  @override
  void initState() {
    super.initState();
    _c = widget.initial == null ? null : {...widget.initial!, 'messages': const [], 'events': const [], 'notes': const [], 'window': const {'canSend': false}};
    _load(first: true);
    _timer = Timer.periodic(const Duration(seconds: 8), (_) => _load());
  }

  @override
  void dispose() { _timer?.cancel(); super.dispose(); }

  Future<void> _load({bool first = false}) async {
    if (_loading) return;
    _loading = true;
    try {
      final c = Map<String, dynamic>.from(await widget.ctx.api.get(_base, query: first ? null : {'read': 'false'}) as Map);
      if (mounted) setState(() { _c = c; _error = null; _full = true; });
    } catch (e) {
      if (mounted && (_c == null || _c!['messages'] == null || first)) setState(() => _error = e);
    }
    _loading = false;
  }

  Future<Map<String, dynamic>?> _post(String path, [Map<String, dynamic> body = const {}, String? done]) async {
    setState(() => _busy = true);
    Map<String, dynamic>? res;
    final ok = await run(context, () async {
      final r = await widget.ctx.api.post('$_base/$path', body);
      res = r is Map ? Map<String, dynamic>.from(r) : <String, dynamic>{};
    });
    if (!mounted) return null;
    setState(() => _busy = false);
    if (!ok) return null;
    if (done != null) toast(context, done);
    await _load();
    return res;
  }

  Future<bool> _reply(String text) async {
    try {
      final r = await widget.ctx.api.post('$_base/reply', {'message': text});
      final d = r is Map ? '${r['delivery'] ?? ''}' : '';
      if (mounted) {
        if (d == 'FAILED') {
          toast(context, '${L('الرسالة ما اتبعتتش.', 'Not sent.')} ${r is Map ? (r['deliveryNote'] ?? '') : ''}', isError: true);
        } else {
          toast(context, d == 'SAVED' ? L('اتحفظ · العميل هيشوفه في شات الموقع', 'Saved · the customer sees it in the website chat') : L('اتبعت', 'Sent'));
        }
      }
      await _load();
      return d != 'FAILED';
    } on ApiException catch (e) {
      if (mounted) {
        toast(context, e.code == 'WINDOW_CLOSED' ? L('مش متاح ترد من هنا · نافذة الـ ٢٤ ساعة خلصت أو القناة مش مربوطة.', 'You cannot reply from here: the 24-hour window is closed or the channel is not connected.') : errorText(e), isError: true);
        await _load();
      }
      return false;
    } catch (e) {
      if (mounted) toast(context, errorText(e), isError: true);
      return false;
    }
  }

  Future<bool> _note(String text) async => (await _post('notes', {'body': text}, L('الملاحظة اتحفظت', 'Note saved'))) != null;

  Future<String?> _assist(String action, String draft) async {
    try {
      final r = await widget.ctx.api.post('$_base/assist', {'action': action, if (draft.isNotEmpty) 'draft': draft});
      return r is Map ? '${r['text'] ?? ''}' : null;
    } catch (e) {
      if (mounted) toast(context, errorText(e), isError: true);
      return null;
    }
  }

  List<_Item> _items(Map<String, dynamic> c) {
    final out = <_Item>[
      for (final m in maps(c['messages'])) _Item('msg', DateTime.tryParse('${m['createdAt']}') ?? DateTime(2000), m),
      for (final e in maps(c['events'])) _Item('event', DateTime.tryParse('${e['createdAt']}') ?? DateTime(2000), e),
      for (final n in maps(c['notes'])) _Item('note', DateTime.tryParse('${n['createdAt']}') ?? DateTime(2000), n),
    ];
    out.sort((a, b) => a.at.compareTo(b.at));
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final c = _c;
    if (c == null) {
      return Scaffold(appBar: AppBar(), body: _error != null ? errorState(_error!, () { setState(() => _error = null); _load(first: true); }) : const Center(child: CircularProgressIndicator()));
    }
    final items = _items(c).reversed.toList();
    final closed = c['status'] == 'CLOSED';
    final win = c['window'] is Map ? Map<String, dynamic>.from(c['window'] as Map) : <String, dynamic>{};
    final canSend = win['canSend'] == true;
    final name = whoName(c);
    final loaded = _full;
    return Scaffold(
      appBar: AppBar(
        centerTitle: false,
        titleSpacing: 0,
        title: InkWell(
          onTap: () => showCustomerSheet(context, widget.ctx, c),
          child: Row(children: [
            ChatAvatar(name: name, channel: c['channel'], size: 36),
            const SizedBox(width: 10),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
              Text(dotted([channelText(c['channel']), if ('${c['customerPhone'] ?? ''}'.isNotEmpty) localPhone(c['customerPhone'])]), maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 11.5, fontWeight: FontWeight.w500, color: (t.darkAppBar ? t.onInk : t.text).withValues(alpha: 0.7))),
            ])),
          ]),
        ),
        actions: [
          IconButton(tooltip: L('بيانات العميل', 'Customer'), icon: const Icon(Icons.person_outline_rounded), onPressed: () => showCustomerSheet(context, widget.ctx, c)),
          PopupMenuButton<String>(
            onSelected: (v) async {
              switch (v) {
                case 'summary':
                  final r = await _assist('SUMMARY', '');
                  if (r != null && r.trim().isNotEmpty && mounted) _showSummary(r);
                case 'close': await _post('close', {}, L('المحادثة اتقفلت', 'Chat closed'));
                case 'reopen': await _post('reopen', {}, L('المحادثة اتفتحت تاني', 'Chat reopened'));
                case 'unhappy': await _post('unhappy', {'on': c['unhappy'] != true}, c['unhappy'] == true ? L('اتعلّمت تمام', 'Marked fine') : L('اتعلّمت متضايق', 'Marked unhappy'));
                case 'copyPhone':
                  Clipboard.setData(ClipboardData(text: '${c['customerPhone'] ?? ''}'));
                  toast(context, L('اتنسخ', 'Copied'));
              }
            },
            itemBuilder: (_) => [
              PopupMenuItem(value: 'summary', child: _menu(Icons.summarize_rounded, L('تلخيص المحادثة', 'Summarize chat'))),
              if (_write) PopupMenuItem(value: closed ? 'reopen' : 'close', child: _menu(closed ? Icons.lock_open_rounded : Icons.check_circle_outline_rounded, closed ? L('إعادة فتح', 'Reopen') : L('قفل المحادثة', 'Close chat'))),
              if (_write) PopupMenuItem(value: 'unhappy', child: _menu(Icons.mood_bad_rounded, c['unhappy'] == true ? L('العميل تمام', 'Customer is fine') : L('علّم إن العميل متضايق', 'Mark unhappy'))),
              if ('${c['customerPhone'] ?? ''}'.isNotEmpty) PopupMenuItem(value: 'copyPhone', child: _menu(Icons.copy_rounded, L('نسخ الرقم', 'Copy number'))),
            ],
          ),
        ],
      ),
      body: Column(children: [
        if (c['unhappy'] == true) _Banner(icon: Icons.mood_bad_rounded, color: const Color(0xFFC2185B), text: '${L('العميل ده شكله متضايق', 'This customer looks unhappy')}${'${c['unhappyReason'] ?? ''}'.isEmpty ? '' : ': ${c['unhappyReason']}'}'),
        _modeBanner(c, closed),
        if (loaded && !canSend && !closed) _windowBanner(c, win),
        Expanded(
          child: items.isEmpty
              ? (loaded ? templateEmpty(context, L('لسه مفيش رسائل.', 'No messages yet.'), icon: Icons.chat_bubble_outline_rounded) : const Center(child: CircularProgressIndicator()))
              : ListView.builder(
                  reverse: true,
                  padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
                  itemCount: items.length,
                  itemBuilder: (context, i) {
                    final it = items[i];
                    final older = i + 1 < items.length ? items[i + 1] : null;
                    final dayBreak = older == null || !_sameDay(older.at, it.at);
                    return Column(children: [
                      if (dayBreak) _DayChip(it.at),
                      switch (it.kind) { 'msg' => _Bubble(m: it.data, grouped: older != null && older.kind == 'msg' && older.data['role'] == it.data['role'] && older.data['byOwner'] == it.data['byOwner'] && it.at.difference(older.at).inMinutes < 5), 'event' => _EventPill(e: it.data), _ => _NoteCard(n: it.data) },
                    ]);
                  },
                ),
        ),
        if (_write)
          Composer(ctx: widget.ctx, canSend: canSend && !closed, onSend: _reply, onNote: _note, onAssist: _assist)
        else
          Container(width: double.infinity, padding: const EdgeInsets.all(14), color: t.card, child: SafeArea(top: false, child: Text(L('تقدر تقرا المحادثة بس مش ترد.', 'You can read this chat but not reply.'), textAlign: TextAlign.center, style: TextStyle(color: t.muted)))),
      ]),
    );
  }

  Widget _menu(IconData i, String s) => Row(children: [Icon(i, size: 20), const SizedBox(width: 10), Text(s)]);

  bool _sameDay(DateTime a, DateTime b) { final x = a.toLocal(), y = b.toLocal(); return x.year == y.year && x.month == y.month && x.day == y.day; }

  void _showSummary(String text) => showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        isScrollControlled: true,
        builder: (c) => SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(20, 0, 20, 20), child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(L('ملخص المحادثة', 'Chat summary'), style: AppTemplate.of(context).heading(18)),
          const SizedBox(height: 4),
          Text(L('مكتوب بالذكاء الاصطناعي من المحادثة دي.', 'Written by AI from this chat.'), style: TextStyle(color: AppTemplate.of(context).muted, fontSize: 12.5)),
          const SizedBox(height: 12),
          SelectableText(text, style: const TextStyle(height: 1.55)),
        ]))),
      );

  Widget _modeBanner(Map<String, dynamic> c, bool closed) {
    final t = AppTemplate.of(context);
    if (closed) {
      return _Banner(icon: Icons.lock_outline_rounded, color: const Color(0xFF7A8294), text: L('المحادثة دي مقفولة.', 'This chat is closed.'), action: _write ? _BannerAction(L('إعادة فتح', 'Reopen'), _busy ? null : () => _post('reopen', {}, L('المحادثة اتفتحت تاني', 'Chat reopened'))) : null);
    }
    final you = c['takeover'] == true || c['aiPaused'] == true;
    if (you) {
      return _Banner(icon: Icons.person_rounded, color: const Color(0xFF6D4AE0), text: L('أنت اللي بترد هنا · الذكاء الاصطناعي مش هيرد', 'You are replying here · the AI stays quiet'), action: _write ? _BannerAction(L('رجّع الذكاء', 'Resume AI'), _busy ? null : () => _post('resume', {}, L('الذكاء هيرد من الرسالة الجاية', 'The AI will answer from the next message'))) : null);
    }
    if (c['aiActive'] == true) {
      return _Banner(icon: Icons.auto_awesome_rounded, color: t.isShop ? t.accent : const Color(0xFF1E7E4F), text: L('البائع الذكي هو اللي بيرد في المحادثة دي', 'The AI seller is answering this chat'), action: _write ? _BannerAction(L('استلم المحادثة', 'Take over'), _busy ? null : () => _post('takeover', {}, L('أنت اللي بترد دلوقتي · الذكاء متوقف هنا', 'You are replying now · AI paused here'))) : null);
    }
    return _Banner(icon: Icons.smart_toy_outlined, color: const Color(0xFF7A8294), text: L('الرد الآلي مقفول للقناة دي', 'Auto-reply is off for this channel'), action: _write ? _BannerAction(L('استلم', 'Take over'), _busy ? null : () => _post('takeover', {}, L('أنت اللي بترد دلوقتي', 'You are replying now'))) : null);
  }

  Widget _windowBanner(Map<String, dynamic> c, Map<String, dynamic> win) {
    final notConnected = win['reason'] == 'NOT_CONNECTED';
    final url = '${win['openUrl'] ?? ''}';
    final ch = '${c['channel']}';
    final label = switch ('${win['openKind'] ?? ch}') {
      'WHATSAPP' => L('افتح في واتساب', 'Open in WhatsApp'),
      'INSTAGRAM' || 'INSTAGRAM_INBOX' => L('افتح رسائل إنستجرام', 'Open Instagram inbox'),
      'FACEBOOK' || 'FACEBOOK_INBOX' => L('افتح رسائل فيسبوك', 'Open Facebook inbox'),
      _ => L('افتح', 'Open'),
    };
    final hint = notConnected
        ? L('القناة دي مش مربوطة · اربطها عشان ترد من المنصة.', 'This channel is not connected · connect it to reply from the platform.')
        : switch (ch) {
            'WHATSAPP' => L('واتساب للأعمال بيسمح بالرد الحر خلال ٢٤ ساعة من آخر رسالة للعميل بس. رد من واتساب نفسه أو استنى العميل يكتب تاني.', 'WhatsApp allows free replies only within 24 hours of the customer\'s last message. Reply from WhatsApp itself or wait for the customer to write again.'),
            'FACEBOOK' => L('ماسنجر بيسمح بالرد خلال ٢٤ ساعة من آخر رسالة للعميل. رد من صندوق رسائل الصفحة.', 'Messenger allows replies within 24 hours of the customer\'s last message. Reply from the page inbox.'),
            'INSTAGRAM' => L('إنستجرام بيسمح بالرد خلال ٢٤ ساعة من آخر رسالة للعميل. رد من رسائل إنستجرام.', 'Instagram allows replies within 24 hours of the customer\'s last message. Reply from the Instagram inbox.'),
            _ => L('المحادثة دي مش بتستقبل رسائل دلوقتي.', 'This chat is not receiving messages now.'),
          };
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
      color: const Color(0xFFFFF1D6),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Icon(Icons.info_outline_rounded, color: Color(0xFFB7791F), size: 20),
          const SizedBox(width: 8),
          Expanded(child: Text(notConnected ? L('القناة دي مش مربوطة', 'Channel not connected') : L('مش متاح ترد من هنا · نافذة الـ ٢٤ ساعة خلصت', 'You cannot reply from here · the 24-hour window ended'), style: const TextStyle(fontWeight: FontWeight.w800, color: Color(0xFF7A4B00)))),
        ]),
        const SizedBox(height: 4),
        Text(hint, style: const TextStyle(fontSize: 12.5, color: Color(0xFF7A4B00), height: 1.4)),
        if (url.isNotEmpty) ...[
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: FilledButton.icon(
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(44), backgroundColor: ch == 'WHATSAPP' ? const Color(0xFF25D366) : null),
              onPressed: () => launch(url),
              icon: Icon(channelIcon(ch), size: 18),
              label: Text(label),
            )),
            const SizedBox(width: 8),
            IconButton.outlined(onPressed: () { Clipboard.setData(ClipboardData(text: url)); toast(context, L('اتنسخ الرابط', 'Link copied')); }, icon: const Icon(Icons.link_rounded), tooltip: L('نسخ الرابط', 'Copy link')),
          ]),
        ],
      ]),
    );
  }
}

class _Item {
  _Item(this.kind, this.at, this.data);
  final String kind;
  final DateTime at;
  final Map<String, dynamic> data;
}

class _BannerAction {
  const _BannerAction(this.label, this.onTap);
  final String label;
  final VoidCallback? onTap;
}

class _Banner extends StatelessWidget {
  const _Banner({required this.icon, required this.color, required this.text, this.action});
  final IconData icon;
  final Color color;
  final String text;
  final _BannerAction? action;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsetsDirectional.fromSTEB(14, 6, 6, 6),
        color: color.withValues(alpha: 0.10),
        child: Row(children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 8),
          Expanded(child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w800, fontSize: 12.5, height: 1.3))),
          if (action != null) TextButton(onPressed: action!.onTap, style: TextButton.styleFrom(foregroundColor: color, visualDensity: VisualDensity.compact), child: Text(action!.label, style: const TextStyle(fontWeight: FontWeight.w900))),
        ]),
      );
}

class _DayChip extends StatelessWidget {
  const _DayChip(this.at);
  final DateTime at;
  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final d = at.toLocal();
    final now = DateTime.now();
    final diff = DateTime(now.year, now.month, now.day).difference(DateTime(d.year, d.month, d.day)).inDays;
    final label = diff == 0 ? L('النهارده', 'Today') : diff == 1 ? L('امبارح', 'Yesterday') : '${d.day}/${d.month}/${d.year}';
    return Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: Container(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4), decoration: BoxDecoration(color: t.line.withValues(alpha: 0.7), borderRadius: BorderRadius.circular(12)), child: Text(label, style: TextStyle(fontSize: 11.5, color: t.muted, fontWeight: FontWeight.w700))));
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.m, required this.grouped});
  final Map<String, dynamic> m;
  final bool grouped;
  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final customer = m['role'] == 'user';
    final follow = m['kind'] == 'FOLLOWUP';
    final team = !customer && m['byOwner'] == true;
    final who = customer ? L('العميل', 'Customer') : follow ? L('متابعة تلقائية', 'Automatic follow-up') : team ? L('فريقك', 'Your team') : L('البائع الذكي', 'AI seller');
    final Color bg, fg;
    if (customer) { bg = t.card; fg = t.text; }
    else if (follow) { bg = const Color(0xFFFFF1D6); fg = const Color(0xFF5E3B00); }
    else if (team) { bg = t.isShop ? t.ink : t.ink; fg = Colors.white; }
    else { bg = t.isShop ? t.accent.withValues(alpha: 0.13) : (t.isClinic ? t.accent.withValues(alpha: 0.6) : t.accent.withValues(alpha: 0.2)); fg = t.text; }
    final r = Radius.circular(t.isLawyer ? 8 : 18);
    const sm = Radius.circular(4);
    final radius = customer ? BorderRadiusDirectional.only(topStart: grouped ? r : sm, topEnd: r, bottomStart: r, bottomEnd: r) : BorderRadiusDirectional.only(topStart: r, topEnd: grouped ? r : sm, bottomStart: r, bottomEnd: r);
    final media = '${m['mediaUrl'] ?? ''}';
    return Align(
      alignment: customer ? AlignmentDirectional.centerStart : AlignmentDirectional.centerEnd,
      child: Container(
        constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.8),
        margin: EdgeInsets.only(top: grouped ? 2 : 8),
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 6),
        decoration: BoxDecoration(color: bg, borderRadius: radius, border: customer ? Border.all(color: t.line) : null),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (!grouped) Padding(padding: const EdgeInsets.only(bottom: 2), child: Row(mainAxisSize: MainAxisSize.min, children: [
            if (follow) Padding(padding: const EdgeInsetsDirectional.only(end: 4), child: Icon(Icons.schedule_rounded, size: 12, color: fg.withValues(alpha: 0.7))),
            if (!customer && !follow && !team) Padding(padding: const EdgeInsetsDirectional.only(end: 4), child: Icon(Icons.auto_awesome_rounded, size: 12, color: fg.withValues(alpha: 0.7))),
            Text(who, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: fg.withValues(alpha: 0.7))),
          ])),
          if (media.isNotEmpty) Padding(padding: const EdgeInsets.only(bottom: 6), child: ClipRRect(borderRadius: BorderRadius.circular(10), child: Image.network(media, width: 220, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(width: 220, height: 80, color: t.line, child: const Icon(Icons.broken_image_outlined))))),
          if ('${m['content'] ?? ''}'.isNotEmpty) SelectableText('${m['content']}', style: TextStyle(color: fg, height: 1.4, fontSize: 14.5)),
          Align(alignment: AlignmentDirectional.centerEnd, child: Padding(padding: const EdgeInsets.only(top: 2), child: Text(clock('${m['createdAt']}'), style: TextStyle(fontSize: 10.5, color: fg.withValues(alpha: 0.55))))),
        ]),
      ),
    );
  }
}

String _eventText(String kind, String who, Object? step) => switch (kind) {
      'TAKEOVER' => L('$who استلم المحادثة · الذكاء متوقف هنا', '$who took over · AI paused in this chat'),
      'RESUME' => L('$who رجّع الذكاء الاصطناعي', '$who resumed the AI'),
      'PAUSED' => L('$who وقّف الذكاء مؤقتاً', '$who paused the AI for a while'),
      'CLOSED' => L('$who قفل المحادثة', '$who closed the chat'),
      'REOPENED' => L('$who فتح المحادثة تاني', '$who reopened the chat'),
      'UNHAPPY_ON' => L('اتعلّمت: عميل متضايق', 'Marked unhappy customer'),
      'UNHAPPY_OFF' => L('$who علّم إن العميل تمام', '$who marked the customer fine'),
      'FOLLOWUP_SENT' => L('اتبعتت رسالة المتابعة $step', 'Follow-up $step sent'),
      'FOLLOWUP_FAILED' => L('رسالة المتابعة $step ما اتبعتتش', 'Follow-up $step could not be sent'),
      'FOLLOWUP_CANCELLED' => L('$who لغى رسالة متابعة', '$who cancelled a follow-up'),
      'NO_POINTS' => L('الرصيد خلص · الذكاء متوقف', 'Credit ran out · AI paused'),
      _ => kind.replaceAll('_', ' ').toLowerCase(),
    };

class _EventPill extends StatelessWidget {
  const _EventPill({required this.e});
  final Map<String, dynamic> e;
  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final kind = '${e['kind']}';
    Object? step;
    try { final j = e['meta'] == null ? null : jsonDecode('${e['meta']}'); if (j is Map) step = j['step']; } catch (_) {}
    final who = '${e['actor'] ?? ''}'.isEmpty ? L('النظام', 'The system') : '${e['actor']}';
    final icon = kind == 'TAKEOVER' ? Icons.people_alt_rounded : kind == 'RESUME' ? Icons.auto_awesome_rounded : kind.startsWith('UNHAPPY') ? Icons.warning_amber_rounded : kind.startsWith('FOLLOWUP') ? Icons.schedule_rounded : kind == 'CLOSED' ? Icons.check_rounded : Icons.circle;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Center(child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(color: t.card, borderRadius: BorderRadius.circular(20), border: Border.all(color: t.line)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, size: icon == Icons.circle ? 6 : 14, color: t.muted),
          const SizedBox(width: 6),
          Flexible(child: Text(_eventText(kind, who, step ?? ''), style: TextStyle(fontSize: 12, color: t.muted), overflow: TextOverflow.ellipsis, maxLines: 2)),
          const SizedBox(width: 8),
          Text(clock('${e['createdAt']}'), style: TextStyle(fontSize: 11, color: t.muted.withValues(alpha: 0.8))),
        ]),
      )),
    );
  }
}

class _NoteCard extends StatelessWidget {
  const _NoteCard({required this.n});
  final Map<String, dynamic> n;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
          decoration: BoxDecoration(color: const Color(0xFFFFF7E0), borderRadius: BorderRadius.circular(AppTemplate.of(context).isLawyer ? 8 : 14), border: Border.all(color: const Color(0xFFF2D98B))),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(Icons.lock_outline_rounded, size: 13, color: Color(0xFFA06A00)),
              const SizedBox(width: 5),
              Expanded(child: Text(dotted([L('ملاحظة داخلية', 'Internal note'), n['author'], clock('${n['createdAt']}')]), style: const TextStyle(fontSize: 11.5, fontWeight: FontWeight.w800, color: Color(0xFFA06A00)))),
            ]),
            const SizedBox(height: 4),
            SelectableText('${n['body'] ?? ''}', style: const TextStyle(color: Color(0xFF4A3300), height: 1.4)),
          ]),
        ),
      );
}
