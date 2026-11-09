import 'dart:async';

import 'package:flutter/material.dart';

import '../common.dart';
import '../theme/templates.dart';
import 'chat_list.dart';
import 'comments_tab.dart';
import 'followup_settings.dart';
import 'inbox_common.dart';
import 'saved_replies_screen.dart';

/// The shop inbox (replaces the old "customer chats" list): Chats and Comments tabs with live counters, plus the follow-up settings.
class InboxScreen extends StatefulWidget {
  const InboxScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<InboxScreen> createState() => _InboxScreenState();
}

class _InboxScreenState extends State<InboxScreen> {
  int _tab = 0;
  Map<String, dynamic> _counts = const {};
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _loadCounts();
    _timer = Timer.periodic(const Duration(seconds: 25), (_) => _loadCounts());
  }

  @override
  void dispose() { _timer?.cancel(); super.dispose(); }

  Future<void> _loadCounts() async {
    try {
      final r = Map<String, dynamic>.from(await widget.ctx.api.get('store/chat/conversations/counts') as Map);
      if (mounted) setState(() => _counts = r);
    } catch (_) {}
  }

  int _n(String k) => (_counts[k] as num?)?.toInt() ?? 0;

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final tabs = [
      (L('المحادثات', 'Chats'), Icons.forum_rounded, _n('needsReply')),
      (L('التعليقات', 'Comments'), Icons.mode_comment_rounded, _n('unrepliedComments')),
    ];
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
        child: Row(children: [
          Expanded(
            child: Container(
              padding: const EdgeInsets.all(4),
              decoration: BoxDecoration(color: t.line.withValues(alpha: 0.6), borderRadius: BorderRadius.circular(t.isLawyer ? 10 : 22)),
              child: Row(children: [
                for (var i = 0; i < tabs.length; i++)
                  Expanded(
                    child: GestureDetector(
                      onTap: () => setState(() => _tab = i),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 200),
                        padding: const EdgeInsets.symmetric(vertical: 9),
                        decoration: BoxDecoration(
                          color: _tab == i ? t.card : Colors.transparent,
                          borderRadius: BorderRadius.circular(t.isLawyer ? 7 : 18),
                          boxShadow: _tab == i ? [BoxShadow(color: t.ink.withValues(alpha: 0.08), blurRadius: 8, offset: const Offset(0, 2))] : null,
                        ),
                        child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                          Icon(tabs[i].$2, size: 17, color: _tab == i ? accentOf(t) : t.muted),
                          const SizedBox(width: 6),
                          Text(tabs[i].$1, style: TextStyle(fontWeight: FontWeight.w800, color: _tab == i ? t.text : t.muted)),
                          if (tabs[i].$3 > 0) Container(
                            margin: const EdgeInsetsDirectional.only(start: 6),
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                            decoration: BoxDecoration(color: const Color(0xFFD64545), borderRadius: BorderRadius.circular(10)),
                            child: Text(tabs[i].$3 > 99 ? '99+' : '${tabs[i].$3}', style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.w900)),
                          ),
                        ]),
                      ),
                    ),
                  ),
              ]),
            ),
          ),
          const SizedBox(width: 6),
          IconButton(
            tooltip: L('ردود جاهزة', 'Saved replies'),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => SavedRepliesScreen(ctx: widget.ctx))),
            icon: const Icon(Icons.bolt_rounded),
          ),
          IconButton(
            tooltip: L('رسائل المتابعة التلقائية', 'Automatic follow-ups'),
            onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => FollowUpSettingsScreen(ctx: widget.ctx))),
            icon: const Icon(Icons.schedule_send_rounded),
          ),
        ]),
      ),
      Expanded(
        child: IndexedStack(index: _tab, children: [
          ChatListTab(ctx: widget.ctx, counts: _counts, onChanged: _loadCounts),
          CommentsTab(ctx: widget.ctx, onChanged: _loadCounts),
        ]),
      ),
    ]);
  }
}
