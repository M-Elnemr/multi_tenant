import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

// Customer questions: what the AI seller could not answer, each next to its customer and chat. The owner answers once; the assistant sends the answer to the customer in the same chat.

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

String _who(Map<String, dynamic> q, bool ar) => (q['customerName'] ?? q['customerPhone'] ?? (ar ? 'عميل' : 'Customer')).toString();

String _channel(Object? c, bool ar) => switch ('$c') { 'WHATSAPP' => 'WhatsApp', 'INSTAGRAM' => ar ? 'إنستجرام' : 'Instagram', 'FACEBOOK' => ar ? 'ماسنجر' : 'Messenger', _ => ar ? 'الموقع' : 'Website' };

class QuestionsScreen extends StatefulWidget {
  const QuestionsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<QuestionsScreen> createState() => _QuestionsScreenState();
}

class _QuestionsScreenState extends State<QuestionsScreen> {
  String _status = 'OPEN';
  int _gen = 0;

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
        child: SizedBox(
          width: double.infinity,
          child: SegmentedButton<String>(
            showSelectedIcon: false,
            segments: [
              ButtonSegment(value: 'OPEN', label: Text(ar ? 'في انتظار ردك' : 'Waiting')),
              ButtonSegment(value: 'ANSWERED', label: Text(ar ? 'تم الرد' : 'Answered')),
              ButtonSegment(value: 'DISMISSED', label: Text(ar ? 'تم التجاهل' : 'Dismissed')),
            ],
            selected: {_status},
            onSelectionChanged: (v) => setState(() { _status = v.first; _gen++; }),
          ),
        ),
      ),
      Expanded(
        child: Async<List<dynamic>>(
          key: ValueKey('q$_status$_gen'),
          load: () async => _maps(await widget.ctx.api.get('store/ai/questions', query: {'status': _status})),
          builder: (context, items, reload) {
            final list = _maps(items);
            return RefreshIndicator(
              onRefresh: reload,
              child: list.isEmpty
                  ? ListView(children: [Padding(padding: const EdgeInsets.all(40), child: Center(child: Text(ar ? 'مفيش أسئلة هنا.' : 'No questions here.')))])
                  : ListView(padding: const EdgeInsets.all(12), children: [
                      Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(ar ? 'أسئلة المساعد ماقدرش يجاوبها. جاوب مرة واحدة والمساعد هيبعت إجابتك للعميل في نفس المحادثة.' : 'Questions the assistant could not answer. Answer once and the assistant sends it to the customer in the same chat.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))),
                      for (final q in list)
                        Card(
                          child: ListTile(
                            title: Row(children: [Expanded(child: Text(_who(q, ar), style: const TextStyle(fontWeight: FontWeight.w800))), Text(_channel(q['channel'], ar), style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D)))]),
                            subtitle: Padding(padding: const EdgeInsets.only(top: 4), child: Text('${q['question']}', maxLines: 3, overflow: TextOverflow.ellipsis)),
                            trailing: const Icon(Icons.chevron_right_rounded),
                            onTap: () async {
                              await Navigator.of(context).push(MaterialPageRoute(builder: (_) => QuestionDetail(ctx: widget.ctx, id: q['id'] as int)));
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

class QuestionDetail extends StatefulWidget {
  const QuestionDetail({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final int id;
  @override
  State<QuestionDetail> createState() => _QuestionDetailState();
}

class _QuestionDetailState extends State<QuestionDetail> {
  final _answer = TextEditingController();
  Map<String, dynamic>? _q;
  Object? _error;
  bool _busy = false;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _timer?.cancel();
    _answer.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final q = Map<String, dynamic>.from(await widget.ctx.api.get('store/ai/questions/${widget.id}') as Map);
      if (!mounted) return;
      setState(() { _q = q; _error = null; });
      // the assistant words the answer and sends it in the background: follow it until the delivery is known
      _timer?.cancel();
      if (q['status'] == 'ANSWERED' && q['delivery'] == null) _timer = Timer(const Duration(milliseconds: 2500), _load);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _act(Future<void> Function() f) async {
    setState(() => _busy = true);
    final ok = await run(context, f);
    if (mounted) setState(() => _busy = false);
    if (ok) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final write = widget.ctx.can('ai.manage');
    final q = _q;
    return Scaffold(
      appBar: AppBar(title: Text(q == null ? (ar ? 'سؤال عميل' : 'Customer question') : _who(q, ar))),
      body: q == null
          ? Center(child: _error != null ? Text(errorText(_error!)) : const CircularProgressIndicator())
          : ListView(padding: const EdgeInsets.all(16), children: [
              Row(children: [
                Expanded(child: Text(_channel(q['channel'], ar), style: const TextStyle(color: Color(0xFF6B7C8D)))),
                if (q['customerName'] != null && q['customerPhone'] != null) Directionality(textDirection: TextDirection.ltr, child: Text('${q['customerPhone']}', style: const TextStyle(color: Color(0xFF6B7C8D)))),
              ]),
              const SizedBox(height: 10),
              if (_maps(q['messages']).isNotEmpty) ...[
                Text(ar ? 'المحادثة' : 'The conversation', style: const TextStyle(fontWeight: FontWeight.w800)),
                const SizedBox(height: 6),
                Container(
                  constraints: const BoxConstraints(maxHeight: 280),
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(color: const Color(0xFFF3F6F9), borderRadius: BorderRadius.circular(14)),
                  child: ListView(shrinkWrap: true, children: [
                    for (final m in _maps(q['messages']))
                      Align(
                        alignment: m['role'] == 'user' ? AlignmentDirectional.centerStart : AlignmentDirectional.centerEnd,
                        child: Container(
                          margin: const EdgeInsets.symmetric(vertical: 3),
                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          decoration: BoxDecoration(color: m['role'] == 'user' ? Colors.white : const Color(0xFFE0F2F1), borderRadius: BorderRadius.circular(14)),
                          child: Text('${m['content']}'),
                        ),
                      ),
                  ]),
                ),
                const SizedBox(height: 12),
              ],
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: const Color(0xFFFFF7E0), border: Border.all(color: const Color(0xFFF3D98B)), borderRadius: BorderRadius.circular(14)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(ar ? 'السؤال' : 'The question', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 12, color: Color(0xFFB45309))),
                  const SizedBox(height: 4),
                  Text('${q['question']}', style: const TextStyle(fontWeight: FontWeight.w600)),
                ]),
              ),
              const SizedBox(height: 14),
              if (q['status'] == 'OPEN' && write) ...[
                Text(ar ? 'إجابتك' : 'Your answer', style: const TextStyle(fontWeight: FontWeight.w800)),
                const SizedBox(height: 6),
                TextField(controller: _answer, minLines: 3, maxLines: 6, maxLength: 1500, decoration: InputDecoration(hintText: ar ? 'اكتب الإجابة بأسلوبك...' : 'Write the answer in your own words...', border: const OutlineInputBorder())),
                Text(ar ? 'المساعد هيصيغ إجابتك ويبعتها للعميل في نفس المحادثة وبعدين يكمل الكلام معاه.' : 'The assistant words your answer, sends it to the customer in the same chat, and then continues the conversation.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
                const SizedBox(height: 10),
                Row(children: [
                  Expanded(child: FilledButton(onPressed: _busy ? null : () => _act(() async { await widget.ctx.api.post('store/ai/questions/${widget.id}/answer', {'answer': _answer.text}); _answer.clear(); }), child: Text(ar ? 'ابعت الإجابة للعميل' : 'Send the answer to the customer'))),
                  const SizedBox(width: 8),
                  OutlinedButton(onPressed: _busy ? null : () => _act(() async { await widget.ctx.api.post('store/ai/questions/${widget.id}/dismiss', {}); if (context.mounted) Navigator.of(context).pop(); }), child: Text(ar ? 'تجاهل' : 'Dismiss')),
                ]),
              ],
              if (q['status'] == 'ANSWERED') ...[
                Text(ar ? 'إجابتك' : 'Your answer', style: const TextStyle(fontWeight: FontWeight.w800)),
                Padding(padding: const EdgeInsets.only(top: 4, bottom: 10), child: Text('${q['answer']}')),
                if (q['delivery'] == null) Row(children: [const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)), const SizedBox(width: 10), Expanded(child: Text(ar ? 'المساعد بيكتب الرسالة للعميل...' : 'The assistant is writing the message to the customer...'))])
                else
                  Text(
                    switch ('${q['delivery']}') {
                      'SENT' => ar ? 'اتبعتت للعميل.' : 'Sent to the customer.',
                      'SAVED' => ar ? 'اتحفظت في شات الموقع: العميل هيشوفها لما يفتح الشات تاني.' : 'Saved in the website chat: the customer sees it when they open the chat again.',
                      _ => '${ar ? 'الرسالة ماتبعتتش.' : 'The message could not be sent.'} ${q['deliveryNote'] ?? ''}',
                    },
                    style: TextStyle(fontWeight: FontWeight.w700, color: q['delivery'] == 'FAILED' ? const Color(0xFFD14343) : const Color(0xFF1E9E5A)),
                  ),
                if (q['replyText'] != null) ...[
                  const SizedBox(height: 12),
                  Text(ar ? 'اللي وصل للعميل' : 'What the customer received', style: const TextStyle(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 4),
                  Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFE0F2F1), borderRadius: BorderRadius.circular(14)), child: Text('${q['replyText']}')),
                ],
                if (q['delivery'] == 'FAILED') Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Wrap(spacing: 8, children: [
                    OutlinedButton(onPressed: () async { await Clipboard.setData(ClipboardData(text: '${q['replyText'] ?? ''}')); if (context.mounted) toast(context, ar ? 'اتنسخت' : 'Copied'); }, child: Text(ar ? 'انسخ الرسالة' : 'Copy the message')),
                    if (write) FilledButton(onPressed: _busy ? null : () => _act(() async { await widget.ctx.api.post('store/ai/questions/${widget.id}/retry', {}); }), child: Text(ar ? 'جرّب تاني' : 'Try again')),
                  ]),
                ),
              ],
            ]),
    );
  }
}
