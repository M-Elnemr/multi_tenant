import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show Clipboard, ClipboardData;

import '../common.dart';
import '../theme/templates.dart';
import 'inbox_common.dart';

/// Reply box: a Reply / Internal-note switch, saved replies, AI Assist and send. AI Assist only writes text into the box, it never sends.
class Composer extends StatefulWidget {
  const Composer({super.key, required this.ctx, required this.canSend, required this.onSend, required this.onNote, required this.onAssist});
  final Ctx ctx;
  /// False when the channel window is closed or the channel is not connected (notes still work).
  final bool canSend;
  final Future<bool> Function(String text) onSend;
  final Future<bool> Function(String text) onNote;
  final Future<String?> Function(String action, String draft) onAssist;
  @override
  State<Composer> createState() => _ComposerState();
}

class _ComposerState extends State<Composer> {
  final _text = TextEditingController();
  bool _note = false, _busy = false;

  @override
  void dispose() { _text.dispose(); super.dispose(); }

  Future<void> _send() async {
    final v = _text.text.trim();
    if (v.isEmpty || _busy) return;
    setState(() => _busy = true);
    final ok = await (_note ? widget.onNote(v) : widget.onSend(v));
    if (!mounted) return;
    setState(() => _busy = false);
    if (ok) _text.clear();
  }

  Future<void> _saved() async {
    final picked = await showModalBottomSheet<String>(context: context, isScrollControlled: true, showDragHandle: true, builder: (c) => _SavedReplies(ctx: widget.ctx));
    if (picked != null && mounted) { _text.text = picked; _text.selection = TextSelection.collapsed(offset: picked.length); setState(() {}); }
  }

  Future<void> _assist() async {
    final r = await showAssistSheet(context, draft: _text.text.trim(), run: widget.onAssist);
    if (r != null && mounted) { _text.text = r; _text.selection = TextSelection.collapsed(offset: r.length); setState(() {}); }
  }

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final can = _note || widget.canSend;
    final tint = _note ? const Color(0xFFB7791F) : accentOf(t);
    return Container(
      decoration: BoxDecoration(color: t.card, border: Border(top: BorderSide(color: t.line))),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Row(children: [
              _seg(L('رد', 'Reply'), !_note, () => setState(() => _note = false), t),
              const SizedBox(width: 6),
              _seg(L('ملاحظة داخلية', 'Internal note'), _note, () => setState(() => _note = true), t),
              const Spacer(),
              if (!_note) ...[
                TextButton.icon(onPressed: _saved, icon: const Icon(Icons.bolt_rounded, size: 18), label: Text(L('ردود جاهزة', 'Saved'))),
                TextButton.icon(onPressed: _assist, icon: const Icon(Icons.auto_awesome_rounded, size: 18), label: Text(L('مساعد الذكاء', 'AI Assist'))),
              ],
            ]),
            Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Expanded(
                child: TextField(
                  controller: _text,
                  enabled: can,
                  minLines: 1,
                  maxLines: 5,
                  maxLength: 2000,
                  textInputAction: TextInputAction.newline,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    hintText: _note ? L('اكتب ملاحظة لفريقك…', 'Write a note for your team…') : (can ? L('اكتب ردك…', 'Write your reply…') : L('الرد من هنا مش متاح دلوقتي', 'Replying from here is not available now')),
                    counterText: '',
                    filled: true,
                    fillColor: _note ? const Color(0xFFFFF7E0) : t.paper,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filled(
                style: IconButton.styleFrom(backgroundColor: tint, foregroundColor: Colors.white, minimumSize: const Size(50, 50)),
                onPressed: !can || _busy || _text.text.trim().isEmpty ? null : _send,
                icon: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : Icon(_note ? Icons.sticky_note_2_rounded : Icons.send_rounded),
              ),
            ]),
          ]),
        ),
      ),
    );
  }

  Widget _seg(String label, bool on, VoidCallback tap, AppTemplate t) => GestureDetector(
        onTap: tap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(color: on ? accentOf(t).withValues(alpha: 0.12) : Colors.transparent, borderRadius: BorderRadius.circular(t.isLawyer ? 6 : 16)),
          child: Text(label, style: TextStyle(fontWeight: FontWeight.w800, fontSize: 13, color: on ? accentOf(t) : t.muted)),
        ),
      );
}

class _SavedReplies extends StatelessWidget {
  const _SavedReplies({required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.7),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(padding: const EdgeInsets.fromLTRB(20, 0, 20, 4), child: Text(L('ردود جاهزة', 'Saved replies'), style: t.heading(18))),
          Padding(padding: const EdgeInsets.fromLTRB(20, 0, 20, 8), child: Text(L('{name} و {shop} بيتبدلوا باسم العميل واسم متجرك عند الإرسال.', '{name} and {shop} are replaced with the customer and shop name when sent.'), style: TextStyle(color: t.muted, fontSize: 12.5))),
          Flexible(
            child: FutureBuilder<dynamic>(
              future: ctx.api.get('store/chat/saved-replies'),
              builder: (c, snap) {
                if (snap.connectionState != ConnectionState.done) return const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()));
                if (snap.hasError) return errorState(snap.error!, () {});
                final list = maps(snap.data is Map ? (snap.data as Map)['data'] : snap.data);
                if (list.isEmpty) return templateEmpty(c, L('لسه مفيش ردود جاهزة. ضيفها من الموقع.', 'No saved replies yet. Add them on the website.'), icon: Icons.bolt_outlined);
                return ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 12), children: [
                  for (final r in list)
                    Card(child: ListTile(
                      title: Text('${r['title']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                      subtitle: Text('${r['body']}', maxLines: 3, overflow: TextOverflow.ellipsis),
                      onTap: () => Navigator.pop(context, '${r['body']}'),
                    )),
                ]);
              },
            ),
          ),
        ]),
      ),
    );
  }
}

const _assistActions = ['SUGGEST', 'REWRITE', 'FRIENDLIER', 'SHORTER', 'SELL', 'SUMMARY'];
String _assistLabel(String a) => switch (a) {
      'SUGGEST' => L('اقترح رد', 'Suggest a reply'),
      'REWRITE' => L('إعادة صياغة', 'Rewrite my draft'),
      'FRIENDLIER' => L('خليه ألطف', 'Make it friendlier'),
      'SHORTER' => L('خليه أقصر', 'Make it shorter'),
      'SELL' => L('حسّن أسلوب البيع', 'Improve the sales approach'),
      _ => L('لخّص المحادثة', 'Summarize the chat'),
    };
IconData _assistIcon(String a) => switch (a) { 'SUGGEST' => Icons.lightbulb_outline_rounded, 'REWRITE' => Icons.edit_note_rounded, 'FRIENDLIER' => Icons.sentiment_satisfied_alt_rounded, 'SHORTER' => Icons.compress_rounded, 'SELL' => Icons.trending_up_rounded, _ => Icons.summarize_rounded };

/// AI Assist: pick what to do, read the result, then use it as the reply text (nothing is sent). Returns the text to put in the box, or null.
Future<String?> showAssistSheet(BuildContext context, {required String draft, required Future<String?> Function(String action, String draft) run}) =>
    showModalBottomSheet<String>(context: context, isScrollControlled: true, showDragHandle: true, builder: (c) => _AssistSheet(draft: draft, run: run));

class _AssistSheet extends StatefulWidget {
  const _AssistSheet({required this.draft, required this.run});
  final String draft;
  final Future<String?> Function(String action, String draft) run;
  @override
  State<_AssistSheet> createState() => _AssistSheetState();
}

class _AssistSheetState extends State<_AssistSheet> {
  String? _action, _result, _error;
  bool _busy = false;

  Future<void> _go(String a) async {
    if (a != 'SUGGEST' && a != 'SUMMARY' && widget.draft.isEmpty) { setState(() { _action = a; _result = null; _error = L('اكتب مسودة الأول.', 'Write a draft first.'); }); return; }
    setState(() { _action = a; _busy = true; _result = null; _error = null; });
    final r = await widget.run(a, widget.draft);
    if (!mounted) return;
    setState(() { _busy = false; _result = r; if (r == null || r.trim().isEmpty) _error = L('المساعد ما ردش، جرّب تاني.', 'The assistant did not answer, try again.'); });
  }

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.85),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
            Row(children: [
              Icon(Icons.auto_awesome_rounded, color: accentOf(t)),
              const SizedBox(width: 8),
              Expanded(child: Text(L('مساعد الذكاء · بتراجع قبل الإرسال', 'AI Assist · you review before sending'), style: t.heading(17))),
            ]),
            const SizedBox(height: 4),
            Text(L('مساعد الذكاء عمره ما بيبعت حاجة لوحده.', 'AI Assist never sends anything by itself.'), style: TextStyle(color: t.muted, fontSize: 12.5)),
            const SizedBox(height: 14),
            Wrap(spacing: 8, runSpacing: 8, children: [
              for (final a in _assistActions)
                ChoiceChip(avatar: Icon(_assistIcon(a), size: 16), label: Text(_assistLabel(a)), selected: _action == a, showCheckmark: false, onSelected: _busy ? null : (_) => _go(a)),
            ]),
            const SizedBox(height: 14),
            if (_busy) const Padding(padding: EdgeInsets.all(20), child: Center(child: CircularProgressIndicator())),
            if (_error != null) Text(_error!, style: const TextStyle(color: Color(0xFFD64545), fontWeight: FontWeight.w700)),
            if (_result != null && _result!.trim().isNotEmpty) ...[
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(color: accentOf(t).withValues(alpha: 0.07), borderRadius: BorderRadius.circular(t.cardRadius * 0.7), border: Border.all(color: accentOf(t).withValues(alpha: 0.3))),
                child: SelectableText(_result!, style: const TextStyle(height: 1.5)),
              ),
              const SizedBox(height: 10),
              Row(children: [
                if (_action != 'SUMMARY') Expanded(child: FilledButton.icon(onPressed: () => Navigator.pop(context, _result), icon: const Icon(Icons.check_rounded), label: Text(L('استخدمه كرد', 'Use as reply')))),
                if (_action != 'SUMMARY') const SizedBox(width: 8),
                Expanded(child: OutlinedButton.icon(onPressed: () { Clipboard.setData(ClipboardData(text: _result!)); toast(context, L('اتنسخ', 'Copied')); }, icon: const Icon(Icons.copy_rounded), label: Text(L('نسخ', 'Copy')))),
              ]),
            ],
          ]),
        ),
      ),
    );
  }
}
