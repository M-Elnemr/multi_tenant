import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

// Training room (platform admin): chat as a customer of any shop with its assistant, on GLM, Alibaba Qwen or Claude, and turn a reply into a lesson for every shop or for that shop only.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

class TrainRoomScreen extends StatefulWidget {
  const TrainRoomScreen({super.key, required this.api});
  final ApiClient api;
  @override
  State<TrainRoomScreen> createState() => _TrainRoomScreenState();
}

class _TrainRoomScreenState extends State<TrainRoomScreen> {
  List<Map<String, dynamic>> _shops = [];
  List<Map<String, dynamic>> _msgs = [];
  String? _shop;
  String _model = 'default';
  bool _busy = false;
  final _text = TextEditingController();
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    widget.api.get('platform/chat/train/shops').then((v) { if (mounted) setState(() => _shops = _maps(v)); }).catchError((_) {});
  }

  @override
  void dispose() { _text.dispose(); _scroll.dispose(); super.dispose(); }

  String get _base => 'platform/chat/train/$_shop';

  Future<void> _pick(String? id) async {
    setState(() { _shop = id; _msgs = []; });
    if (id == null) return;
    await run(context, () async {
      final r = Map<String, dynamic>.from(await widget.api.get(_base) as Map);
      if (!mounted) return;
      setState(() { _msgs = _maps(r['messages']); _model = '${r['model'] ?? 'default'}'; });
    });
  }

  void _down() => WidgetsBinding.instance.addPostFrameCallback((_) { if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent); });

  Future<void> _send([String? override]) async {
    final message = (override ?? _text.text).trim();
    if (message.isEmpty || _shop == null || _busy) return;
    setState(() { _msgs = [..._msgs, {'role': 'user', 'content': message}]; _busy = true; });
    _text.clear();
    _down();
    await run(context, () async {
      final r = Map<String, dynamic>.from(await widget.api.post(_base, {'message': message, 'model': _model}) as Map);
      if (mounted) setState(() => _msgs = [..._msgs, {'role': 'assistant', 'content': '${r['reply']}'}]);
    });
    if (mounted) setState(() => _busy = false);
    _down();
  }

  Future<void> _reset() async {
    if (_shop == null) return;
    await run(context, () async { await widget.api.delete(_base); });
    if (mounted) setState(() => _msgs = []);
  }

  Future<void> _teach(int at) async {
    final ar = AppLocale.isAr;
    final reply = '${_msgs[at]['content']}';
    var customer = '';
    for (var i = at - 1; i >= 0; i--) { if (_msgs[i]['role'] == 'user') { customer = '${_msgs[i]['content']}'; break; } }
    final remark = TextEditingController(), ins = TextEditingController(), q = TextEditingController(), a = TextEditingController();
    var drafted = false, shopOnly = false, writing = false;
    final saved = await showDialog<bool>(
      context: context,
      builder: (d) => StatefulBuilder(builder: (d, set) => AlertDialog(
        title: Text(ar ? 'علّمه من الرد ده' : 'Teach from this reply'),
        content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(padding: const EdgeInsets.all(8), decoration: BoxDecoration(color: const Color(0xFFF1F5F9), borderRadius: BorderRadius.circular(10)), child: Text(reply, style: const TextStyle(fontSize: 13))),
          const SizedBox(height: 10),
          TextField(controller: remark, minLines: 2, maxLines: 4, decoration: InputDecoration(labelText: ar ? 'إيه الغلط أو المفروض يقول إيه؟' : 'What was wrong, or what should it say?')),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: writing ? null : () async {
              if (remark.text.trim().isEmpty) return;
              set(() => writing = true);
              try {
                final r = Map<String, dynamic>.from(await widget.api.post('platform/chat/train/draft-lesson', {'customerMessage': customer, 'agentReply': reply, 'remark': remark.text.trim()}) as Map);
                ins.text = '${r['instruction'] ?? ''}'; q.text = '${r['exampleQuestion'] ?? ''}'; a.text = '${r['exampleAnswer'] ?? ''}';
                set(() { drafted = true; writing = false; });
              } catch (e) {
                set(() => writing = false);
                if (mounted) toast(context, errorText(e), isError: true);
              }
            },
            child: Text(writing ? '…' : (ar ? 'اكتب الدرس' : 'Write the lesson')),
          ),
          if (drafted) ...[
            const SizedBox(height: 8),
            TextField(controller: ins, minLines: 2, maxLines: 4, decoration: InputDecoration(labelText: ar ? 'القاعدة' : 'Rule')),
            const SizedBox(height: 8),
            TextField(controller: q, decoration: InputDecoration(labelText: ar ? 'لما العميل يقول' : 'When the customer says')),
            const SizedBox(height: 8),
            TextField(controller: a, minLines: 2, maxLines: 4, decoration: InputDecoration(labelText: ar ? 'الرد المثالي' : 'Ideal reply')),
            const SizedBox(height: 8),
            SegmentedButton<bool>(
              showSelectedIcon: false,
              segments: [ButtonSegment(value: false, label: Text(ar ? 'كل المتاجر' : 'All shops')), ButtonSegment(value: true, label: Text(ar ? 'المتجر ده فقط' : 'This shop only'))],
              selected: {shopOnly},
              onSelectionChanged: (v) => set(() => shopOnly = v.first),
            ),
          ],
        ])),
        actions: [
          TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'إلغاء' : 'Cancel')),
          FilledButton(onPressed: drafted && ins.text.trim().isNotEmpty ? () => Navigator.pop(d, true) : null, child: Text(ar ? 'حفظ الدرس' : 'Save lesson')),
        ],
      )),
    );
    final body = {'instruction': ins.text.trim(), 'exampleQuestion': q.text.trim(), 'exampleAnswer': a.text.trim()};
    remark.dispose(); ins.dispose(); q.dispose(); a.dispose();
    if (saved != true || !mounted) return;
    final ok = await run(context, () async { await widget.api.post(shopOnly ? '$_base/lessons' : 'platform/chat/lessons', body); });
    if (!ok || !mounted) return;
    toast(context, ar ? 'اتحفظ. هيلتزم بيه من الرسالة الجاية.' : 'Saved. It follows it from the next message.');
    if (customer.isNotEmpty) {   // try the same question again on a fresh chat to see the lesson at work
      await _reset();
      await _send(customer);
    }
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
        child: Column(children: [
          DropdownButtonFormField<String>(
            initialValue: _shop,
            isExpanded: true,
            decoration: InputDecoration(labelText: ar ? 'المتجر' : 'Shop', border: const OutlineInputBorder()),
            items: [for (final s in _shops) DropdownMenuItem(value: '${s['id']}', child: Text('${s['name']} (${s['slug']})', overflow: TextOverflow.ellipsis))],
            onChanged: _pick,
          ),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(
              child: SegmentedButton<String>(
                showSelectedIcon: false,
                segments: const [ButtonSegment(value: 'default', label: Text('GLM')), ButtonSegment(value: 'qwen', label: Text('Alibaba')), ButtonSegment(value: 'anthropic', label: Text('Claude'))],
                selected: {_model},
                onSelectionChanged: (v) => setState(() => _model = v.first),
              ),
            ),
            IconButton(tooltip: ar ? 'محادثة جديدة' : 'New chat', icon: const Icon(Icons.refresh_rounded), onPressed: _shop == null ? null : _reset),
          ]),
        ]),
      ),
      Expanded(
        child: _shop == null
            ? emptyState(ar ? 'اختار متجر وابدأ كلّم المساعد كأنك عميل' : 'Pick a shop and talk to its assistant as a customer', icon: Icons.chat_bubble_outline_rounded)
            : ListView.builder(
                controller: _scroll,
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
                itemCount: _msgs.length,
                itemBuilder: (c, i) {
                  final m = _msgs[i];
                  final mine = m['role'] == 'user';
                  return Align(
                    alignment: mine ? AlignmentDirectional.centerEnd : AlignmentDirectional.centerStart,
                    child: Container(
                      margin: const EdgeInsets.only(bottom: 8),
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
                      constraints: BoxConstraints(maxWidth: MediaQuery.of(c).size.width * 0.82),
                      decoration: BoxDecoration(color: mine ? Theme.of(c).colorScheme.primary : const Color(0xFFEEF2F6), borderRadius: BorderRadius.circular(16)),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        SelectableText('${m['content']}', style: TextStyle(color: mine ? Colors.white : const Color(0xFF1E2A36), fontSize: 14.5)),
                        if (!mine) InkWell(onTap: () => _teach(i), child: Padding(padding: const EdgeInsets.only(top: 6), child: Text(ar ? 'علّمه' : 'Teach', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 12.5, color: Theme.of(c).colorScheme.primary, decoration: TextDecoration.underline)))),
                      ]),
                    ),
                  );
                },
              ),
      ),
      SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
          child: Row(children: [
            Expanded(child: TextField(controller: _text, enabled: _shop != null, onSubmitted: (_) => _send(), decoration: InputDecoration(hintText: ar ? 'اكتب كأنك العميل…' : 'Write as the customer…', border: const OutlineInputBorder()))),
            const SizedBox(width: 8),
            IconButton.filled(onPressed: _shop == null || _busy ? null : _send, icon: _busy ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : const Icon(Icons.send_rounded)),
          ]),
        ),
      ),
    ]);
  }
}
