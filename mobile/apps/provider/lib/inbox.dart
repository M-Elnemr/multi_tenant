import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

// Customer chats: every chat the assistant had, those that need the owner first (an upset customer, someone asking for a person, the assistant failing).
// The owner replies in their own words and the assistant stays quiet in that chat until it is handed back (or two hours pass).

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

String _who(Map<String, dynamic> c, bool ar) => (c['customerName'] ?? c['customerPhone'] ?? (ar ? 'عميل' : 'Customer')).toString();

String _channel(Object? c, bool ar) => switch ('$c') { 'WHATSAPP' => 'WhatsApp', 'INSTAGRAM' => ar ? 'إنستجرام' : 'Instagram', 'FACEBOOK' => ar ? 'ماسنجر' : 'Messenger', _ => ar ? 'الموقع' : 'Website' };

class InboxScreen extends StatefulWidget {
  const InboxScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<InboxScreen> createState() => _InboxScreenState();
}

class _InboxScreenState extends State<InboxScreen> {
  bool _needs = true;
  int _gen = 0;

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
        child: SizedBox(
          width: double.infinity,
          child: SegmentedButton<bool>(
            showSelectedIcon: false,
            segments: [ButtonSegment(value: true, label: Text(ar ? 'محتاجاك' : 'Needs you')), ButtonSegment(value: false, label: Text(ar ? 'كل المحادثات' : 'All chats'))],
            selected: {_needs},
            onSelectionChanged: (v) => setState(() { _needs = v.first; _gen++; }),
          ),
        ),
      ),
      Expanded(
        child: Async<List<dynamic>>(
          key: ValueKey('i$_needs$_gen'),
          load: () async => _maps(await widget.ctx.api.get('store/chat/conversations', query: {'needsYou': '$_needs'})),
          builder: (context, items, reload) {
            final list = _maps(items);
            return RefreshIndicator(
              onRefresh: reload,
              child: list.isEmpty
                  ? ListView(children: [Padding(padding: const EdgeInsets.all(40), child: Center(child: Text(_needs ? (ar ? 'مفيش محادثة محتاجاك دلوقتي.' : 'No chat needs you right now.') : (ar ? 'مفيش محادثات لسه.' : 'No chats yet.'))))])
                  : ListView(padding: const EdgeInsets.all(12), children: [
                      for (final c in list)
                        Card(
                          child: ListTile(
                            title: Row(children: [
                              Expanded(child: Text(_who(c, ar), style: const TextStyle(fontWeight: FontWeight.w800))),
                              if (c['needsHuman'] == true) _Tag(ar ? 'محتاجاك' : 'Needs you', const Color(0xFFD14343)),
                              if (c['aiPaused'] == true) _Tag(ar ? 'إنت بترد' : 'You reply', const Color(0xFFB45309)),
                              Text(_channel(c['channel'], ar), style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
                            ]),
                            subtitle: Padding(
                              padding: const EdgeInsets.only(top: 4),
                              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                                if (c['needsHuman'] == true && c['needsHumanReason'] != null) Text('${c['needsHumanReason']}', style: const TextStyle(color: Color(0xFFD14343), fontWeight: FontWeight.w600, fontSize: 12.5)),
                                Text('${c['lastMessage'] ?? ''}', maxLines: 2, overflow: TextOverflow.ellipsis),
                              ]),
                            ),
                            trailing: const Icon(Icons.chevron_right_rounded),
                            onTap: () async {
                              await Navigator.of(context).push(MaterialPageRoute(builder: (_) => ChatDetail(ctx: widget.ctx, id: '${c['id']}')));
                              if (mounted) setState(() => _gen++);
                            },
                          ),
                        ),
                    ]),
            );
          },
        ),
      ),
    ]);
  }
}

class _Tag extends StatelessWidget {
  const _Tag(this.text, this.color);
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsetsDirectional.only(end: 6),
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(20)),
        child: Text(text, style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.w700)),
      );
}

class ChatDetail extends StatefulWidget {
  const ChatDetail({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final String id;
  @override
  State<ChatDetail> createState() => _ChatDetailState();
}

class _ChatDetailState extends State<ChatDetail> {
  final _text = TextEditingController();
  Map<String, dynamic>? _c;
  Object? _error;
  bool _busy = false;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(const Duration(seconds: 10), (_) => _load());   // new customer messages while the screen is open
  }

  @override
  void dispose() {
    _timer?.cancel();
    _text.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final c = Map<String, dynamic>.from(await widget.ctx.api.get('store/chat/conversations/${widget.id}') as Map);
      if (mounted) setState(() { _c = c; _error = null; });
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _act(String path, [Map<String, dynamic> body = const {}]) async {
    setState(() => _busy = true);
    Map<String, dynamic>? res;
    final ok = await run(context, () async { res = Map<String, dynamic>.from(await widget.ctx.api.post('store/chat/conversations/${widget.id}/$path', body) as Map); });
    if (!mounted) return;
    setState(() => _busy = false);
    if (!ok) return;
    if (path == 'reply') {
      _text.clear();
      final ar = S.of(context).ar;
      final d = '${res?['delivery'] ?? ''}';
      toast(context, d == 'FAILED' ? '${ar ? 'الرسالة ماتبعتتش.' : 'Not sent.'} ${res?['deliveryNote'] ?? ''}' : d == 'SAVED' ? (ar ? 'اتحفظت في شات الموقع.' : 'Saved in the website chat.') : (ar ? 'اتبعتت للعميل.' : 'Sent to the customer.'));
    }
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final write = widget.ctx.can('ai.manage');
    final c = _c;
    if (c == null) return Scaffold(appBar: AppBar(), body: Center(child: _error != null ? Text(errorText(_error!)) : const CircularProgressIndicator()));
    final msgs = _maps(c['messages']);
    return Scaffold(
      appBar: AppBar(title: Text(_who(c, ar)), actions: [Padding(padding: const EdgeInsetsDirectional.only(end: 14), child: Center(child: Text(_channel(c['channel'], ar))))]),
      body: Column(children: [
        if (c['needsHuman'] == true) _Banner('${ar ? 'المحادثة دي محتاجاك:' : 'This chat needs you:'} ${c['needsHumanReason'] ?? ''}', const Color(0xFFD14343)),
        _Banner(c['aiPaused'] == true ? (ar ? 'المساعد ساكت في المحادثة دي وإنت بترد.' : 'The assistant is quiet here while you reply.') : (ar ? 'المساعد بيرد في المحادثة دي.' : 'The assistant is answering this chat.'),
            c['aiPaused'] == true ? const Color(0xFFB45309) : const Color(0xFF1E9E5A)),
        Expanded(
          child: ListView(reverse: true, padding: const EdgeInsets.all(12), children: [
            for (final m in msgs.reversed)
              Align(
                alignment: m['role'] == 'user' ? AlignmentDirectional.centerStart : AlignmentDirectional.centerEnd,
                child: Container(
                  constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.8),
                  margin: const EdgeInsets.symmetric(vertical: 3),
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(color: m['role'] == 'user' ? const Color(0xFFF3F6F9) : m['byOwner'] == true ? const Color(0xFFFFF1CC) : const Color(0xFFE0F2F1), borderRadius: BorderRadius.circular(14)),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(m['role'] == 'user' ? (ar ? 'العميل' : 'Customer') : m['byOwner'] == true ? (ar ? 'إنت' : 'You') : (ar ? 'المساعد' : 'Assistant'), style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: Color(0xFF6B7C8D))),
                    Text('${m['content']}'),
                  ]),
                ),
              ),
          ]),
        ),
        if (write)
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
              child: Column(children: [
                Row(children: [
                  Expanded(child: TextField(controller: _text, minLines: 1, maxLines: 4, maxLength: 2000, decoration: InputDecoration(hintText: ar ? 'اكتب للعميل بنفسك...' : 'Write to the customer yourself...', counterText: '', border: const OutlineInputBorder()))),
                  const SizedBox(width: 8),
                  IconButton.filled(onPressed: _busy ? null : () { if (_text.text.trim().isNotEmpty) _act('reply', {'message': _text.text}); }, icon: const Icon(Icons.send_rounded)),
                ]),
                const SizedBox(height: 6),
                Wrap(spacing: 8, children: [
                  if (c['aiPaused'] == true)
                    OutlinedButton(onPressed: _busy ? null : () => _act('resume'), child: Text(ar ? 'رجّعها للمساعد' : 'Hand back to the assistant'))
                  else
                    OutlinedButton(onPressed: _busy ? null : () => _act('pause', {'hours': 2}), child: Text(ar ? 'استلم المحادثة' : 'Take over')),
                  if (c['needsHuman'] == true) TextButton(onPressed: _busy ? null : () => _act('done'), child: Text(ar ? 'مفيش حاجة مطلوبة' : 'Nothing to do')),
                ]),
              ]),
            ),
          ),
      ]),
    );
  }
}

class _Banner extends StatelessWidget {
  const _Banner(this.text, this.color);
  final String text;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        color: color.withValues(alpha: 0.1),
        child: Text(text, style: TextStyle(color: color, fontWeight: FontWeight.w700, fontSize: 13)),
      );
}
