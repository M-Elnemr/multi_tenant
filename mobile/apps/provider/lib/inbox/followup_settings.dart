import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import '../common.dart';
import '../theme/templates.dart';
import 'inbox_common.dart';

class _Step {
  _Step({int delay = 60, String message = '', this.enabled = true}) : delay = TextEditingController(text: '$delay'), message = TextEditingController(text: message);
  final TextEditingController delay, message;
  bool enabled;
  int get minutes => int.tryParse(delay.text.trim()) ?? -1;
  void dispose() { delay.dispose(); message.dispose(); }
}

/// Automatic follow-up sequence: when a customer goes quiet the shop sends up to 5 messages, each after its own delay (30 minutes to 23 hours, each later than the one before).
class FollowUpSettingsScreen extends StatefulWidget {
  const FollowUpSettingsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<FollowUpSettingsScreen> createState() => _FollowUpSettingsScreenState();
}

class _FollowUpSettingsScreenState extends State<FollowUpSettingsScreen> {
  bool _loading = true, _saving = false, _enabled = false;
  Object? _error;
  final List<_Step> _steps = [];
  final Map<int, String> _errors = {};

  bool get _write => widget.ctx.can('ai.manage');

  @override
  void initState() { super.initState(); _load(); }

  @override
  void dispose() { for (final s in _steps) { s.dispose(); } super.dispose(); }

  Future<void> _load() async {
    setState(() { _loading = true; _error = null; });
    try {
      final r = Map<String, dynamic>.from(await widget.ctx.api.get('store/chat/followups/settings') as Map);
      for (final s in _steps) { s.dispose(); }
      _steps.clear();
      for (final m in maps(r['steps'])) { _steps.add(_Step(delay: (m['delayMinutes'] as num?)?.toInt() ?? 60, message: '${m['message'] ?? ''}', enabled: m['enabled'] != false)); }
      if (mounted) setState(() { _enabled = r['enabled'] == true; _loading = false; });
    } catch (e) {
      if (mounted) setState(() { _error = e; _loading = false; });
    }
  }

  void _add() {
    if (_steps.length >= 5) return;
    final last = _steps.isEmpty ? 0 : _steps.last.minutes;
    setState(() => _steps.add(_Step(delay: last < 30 ? 30 : (last + 60).clamp(30, 1380).toInt())));
  }

  bool _validate() {
    _errors.clear();
    var prev = 0;
    for (var i = 0; i < _steps.length; i++) {
      final s = _steps[i];
      final m = s.minutes;
      if (m < 30 || m > 1380) { _errors[i] = L('من ٣٠ دقيقة لحد ١٣٨٠ (٢٣ ساعة)', 'Between 30 and 1380 minutes (23 hours)'); }
      else if (m <= prev) { _errors[i] = L('لازم يكون بعد الرسالة اللي قبله', 'Must be later than the previous step'); }
      else if (s.message.text.trim().isEmpty) { _errors[i] = L('اكتب نص الرسالة', 'Write the message'); }
      if (m > 0) prev = m;
    }
    setState(() {});
    return _errors.isEmpty;
  }

  Future<void> _save() async {
    if (_enabled && _steps.isEmpty) { toast(context, L('ضيف رسالة متابعة واحدة على الأقل', 'Add at least one follow-up message'), isError: true); return; }
    if (_steps.isNotEmpty && !_validate()) { toast(context, L('صحّح الخانات المعلَّمة', 'Fix the marked fields'), isError: true); return; }
    setState(() => _saving = true);
    final ok = await run(context, () => widget.ctx.api.put('store/chat/followups/settings', {
          'enabled': _enabled,
          'steps': [for (final s in _steps) {'delayMinutes': s.minutes, 'message': s.message.text.trim(), 'enabled': s.enabled}],
        }));
    if (!mounted) return;
    setState(() => _saving = false);
    if (ok) toast(context, L('اتحفظ', 'Saved'));
  }

  String _human(int m) => m < 0 ? '' : m < 60 ? L('$m دقيقة', '$m min') : (m % 60 == 0 ? L('${m ~/ 60} ساعة', '${m ~/ 60} h') : L('${m ~/ 60} ساعة و${m % 60} دقيقة', '${m ~/ 60} h ${m % 60} min'));

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(L('رسائل المتابعة التلقائية', 'Automatic follow-ups'))),
      body: _loading
          ? const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(height: 90), SizedBox(height: 10), Skeleton(height: 160)]))
          : _error != null
              ? errorState(_error!, _load)
              : ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
                  Card(child: SwitchListTile(
                    value: _enabled,
                    onChanged: _write ? (v) => setState(() => _enabled = v) : null,
                    title: Text(L('شغّل المتابعة التلقائية', 'Turn automatic follow-ups on'), style: const TextStyle(fontWeight: FontWeight.w800)),
                    subtitle: Text(L('لو العميل سكت بعد ما سأل، المتجر يبعتله رسائل متابعة بعد المدد اللي تحددها. بتتحسب زي رسائل الذكاء.', 'If a customer goes quiet after asking, the shop sends follow-ups after the delays you set. They count like AI messages.')),
                  )),
                  TemplateSectionTitle(L('الرسائل (${_steps.length} من ٥)', 'Messages (${_steps.length} of 5)')),
                  for (var i = 0; i < _steps.length; i++) _stepCard(i, t),
                  if (_steps.isEmpty) templateEmpty(context, L('مفيش رسائل لسه. ضيف أول رسالة متابعة.', 'No messages yet. Add the first follow-up.'), icon: Icons.schedule_send_rounded),
                  if (_write && _steps.length < 5) OutlinedButton.icon(onPressed: _add, icon: const Icon(Icons.add_rounded), label: Text(L('ضيف رسالة', 'Add a message'))),
                  const SizedBox(height: 12),
                  if (_write) FilledButton(onPressed: _saving ? null : _save, child: _saving ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : Text(L('حفظ', 'Save'))),
                  const SizedBox(height: 8),
                  Text(L('تقدر تكتب {name} لاسم العميل و {shop} لاسم متجرك.', 'You can write {name} for the customer name and {shop} for your shop name.'), textAlign: TextAlign.center, style: TextStyle(color: t.muted, fontSize: 12.5)),
                ]),
    );
  }

  Widget _stepCard(int i, AppTemplate t) {
    final s = _steps[i];
    final err = _errors[i];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            CircleAvatar(radius: 14, backgroundColor: accentOf(t).withValues(alpha: 0.14), child: Text('${i + 1}', style: TextStyle(fontWeight: FontWeight.w900, color: accentOf(t)))),
            const SizedBox(width: 10),
            Expanded(child: Text(L('بعد ${_human(s.minutes)}', 'After ${_human(s.minutes)}'), style: const TextStyle(fontWeight: FontWeight.w800))),
            Switch(value: s.enabled, onChanged: _write ? (v) => setState(() => s.enabled = v) : null),
            if (_write) IconButton(tooltip: L('حذف', 'Remove'), onPressed: () => setState(() { _steps.removeAt(i).dispose(); _errors.clear(); }), icon: const Icon(Icons.delete_outline_rounded)),
          ]),
          const SizedBox(height: 8),
          TextField(
            controller: s.delay,
            enabled: _write,
            keyboardType: TextInputType.number,
            onChanged: (_) => setState(() => _errors.remove(i)),
            decoration: InputDecoration(labelText: L('الانتظار بالدقائق (٣٠ – ١٣٨٠)', 'Wait in minutes (30 – 1380)'), errorText: err != null && (s.minutes < 30 || s.minutes > 1380 || err.contains('later') || err.contains('بعد الرسالة')) ? err : null),
          ),
          const SizedBox(height: 10),
          TextField(
            controller: s.message,
            enabled: _write,
            minLines: 2,
            maxLines: 6,
            maxLength: 500,
            onChanged: (_) => setState(() => _errors.remove(i)),
            decoration: InputDecoration(labelText: L('نص الرسالة', 'Message'), errorText: err != null && s.message.text.trim().isEmpty ? err : null),
          ),
        ]),
      ),
    );
  }
}
