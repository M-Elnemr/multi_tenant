import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import '../common.dart';
import '../theme/templates.dart';
import 'inbox_common.dart';

/// Saved replies of the shop: create, edit and delete the ready answers the team inserts in a chat (title + body; {name} and {shop} are filled in when sent).
class SavedRepliesScreen extends StatefulWidget {
  const SavedRepliesScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<SavedRepliesScreen> createState() => _SavedRepliesScreenState();
}

class _SavedRepliesScreenState extends State<SavedRepliesScreen> {
  List<Map<String, dynamic>> _items = [];
  bool _loading = true;
  Object? _error;

  bool get _write => widget.ctx.can('ai.manage');

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    setState(() { _loading = _items.isEmpty; _error = null; });
    try {
      final r = await widget.ctx.api.get('store/chat/saved-replies');
      if (mounted) setState(() { _items = maps(r is Map ? r['data'] : r); _loading = false; });
    } catch (e) {
      if (mounted) setState(() { if (_items.isEmpty) _error = e; _loading = false; });
    }
  }

  Future<void> _edit([Map<String, dynamic>? r]) async {
    final title = TextEditingController(text: '${r?['title'] ?? ''}'), body = TextEditingController(text: '${r?['body'] ?? ''}');
    final fe = FieldErrors();
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheet) => StatefulBuilder(builder: (sheet, set) => SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(20, 0, 20, 16 + MediaQuery.of(sheet).viewInsets.bottom),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(r == null ? L('رد جاهز جديد', 'New saved reply') : L('تعديل الرد الجاهز', 'Edit saved reply'), style: AppTemplate.of(sheet).heading(18)),
          const SizedBox(height: 12),
          TextField(controller: title, maxLength: 80, decoration: InputDecoration(labelText: L('العنوان', 'Title'), errorText: fe.of('title'))),
          TextField(controller: body, minLines: 3, maxLines: 8, maxLength: 1000, decoration: InputDecoration(labelText: L('نص الرد', 'Reply text'), helperText: L('{name} و {shop} بيتبدلوا عند الإرسال', '{name} and {shop} are replaced when sent'), errorText: fe.of('body'))),
          const SizedBox(height: 8),
          FilledButton(onPressed: () async {
            if (title.text.trim().isEmpty || body.text.trim().isEmpty) { toast(sheet, L('اكتب العنوان والنص', 'Write a title and a text'), isError: true); return; }
            final payload = {'title': title.text.trim(), 'body': body.text.trim()};
            final ok = await runForm(sheet, fe, () async { if (r == null) { await widget.ctx.api.post('store/chat/saved-replies', payload); } else { await widget.ctx.api.put('store/chat/saved-replies/${r['id']}', payload); } });
            if (ok && sheet.mounted) { Navigator.pop(sheet, true); } else if (sheet.mounted) { set(() {}); }
          }, child: Text(L('حفظ', 'Save'))),
        ]),
      )),
    );
    title.dispose(); body.dispose(); fe.dispose();
    if (saved == true && mounted) { toast(context, L('اتحفظ', 'Saved')); await _load(); }
  }

  Future<void> _delete(Map<String, dynamic> r) async {
    final sure = await showDialog<bool>(context: context, builder: (d) => AlertDialog(
      content: Text(L('تمسح "${r['title']}"؟', 'Delete "${r['title']}"?')),
      actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(L('لا', 'No'))), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(L('امسح', 'Delete')))]));
    if (sure != true || !mounted) return;
    if (await run(context, () async { await widget.ctx.api.delete('store/chat/saved-replies/${r['id']}'); }) && mounted) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(L('ردود جاهزة', 'Saved replies'))),
      floatingActionButton: _write ? FloatingActionButton.extended(onPressed: _edit, icon: const Icon(Icons.add_rounded), label: Text(L('رد جديد', 'New reply'))) : null,
      body: _loading
          ? ListView(padding: const EdgeInsets.all(16), children: const [Skeleton(height: 84), SizedBox(height: 10), Skeleton(height: 84)])
          : _error != null
              ? errorState(_error!, _load)
              : RefreshIndicator(
                  onRefresh: _load,
                  child: _items.isEmpty
                      ? ListView(children: [templateEmpty(context, L('لسه مفيش ردود جاهزة. اضغط "رد جديد" وضيف أول واحد.', 'No saved replies yet. Tap "New reply" to add the first.'), icon: Icons.bolt_outlined)])
                      : ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 90), children: [
                          for (final r in _items) Card(child: ListTile(
                            title: Text('${r['title']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                            subtitle: Text('${r['body']}', maxLines: 3, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.muted)),
                            onTap: _write ? () => _edit(r) : null,
                            trailing: _write ? IconButton(tooltip: L('مسح', 'Delete'), icon: const Icon(Icons.delete_outline_rounded), onPressed: () => _delete(r)) : null,
                          )),
                        ]),
                ),
    );
  }
}
