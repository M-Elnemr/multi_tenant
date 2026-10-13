import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

List<Map<String, dynamic>> _list(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

/// The waiting room. Swipe a waiting patient one way to call them, the other way to take them out of the line (with Undo); drag the handle to
/// change the order. Patients who booked for today but have not arrived are listed below as "Reserved", one tap puts them in the line.
class QueueScreen extends StatefulWidget {
  const QueueScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<QueueScreen> createState() => _QueueScreenState();
}

class _QueueScreenState extends State<QueueScreen> {
  Ctx get ctx => widget.ctx;
  List<Map<String, dynamic>> _waiting = [], _inProgress = [], _reserved = [];
  bool _loaded = false;
  Object? _error;
  Timer? _poll, _orderTimer;
  final Set<String> _busy = {};
  final Map<String, Timer> _removing = {};
  bool _dragging = false;

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    final cached = await ctx.api.peek('clinic/queue');
    if (cached is Map && mounted) _apply(cached);   // the last known line shows at once
    await _load();
    _poll = Timer.periodic(const Duration(seconds: 5), (_) { _load(); });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _orderTimer?.cancel();
    // leaving the screen must not drop a removal that is still waiting for its Undo time
    for (final e in _removing.entries) { e.value.cancel(); unawaited(ctx.api.post('clinic/appointments/${e.key}/remove-from-queue', {}).then((_) {}, onError: (_) {})); }
    super.dispose();
  }

  void _apply(Map q) {
    setState(() {
      _waiting = _list(q['waiting']).where((e) => !_removing.containsKey(e['id'])).toList();
      _inProgress = _list(q['inProgress']);
      _reserved = _list(q['reserved']);
      _loaded = true;
      _error = null;
    });
  }

  Future<void> _load() async {
    if (_dragging || _orderTimer?.isActive == true || _removing.isNotEmpty) return;
    try {
      final r = await ctx.api.get('clinic/queue') as Map;
      if (mounted && !_dragging && _removing.isEmpty && _orderTimer?.isActive != true) _apply(r);
    } catch (e) {
      if (mounted && !_loaded) setState(() => _error = e);
    }
  }

  Future<void> _act(String id, String action, {void Function()? optimistic}) async {
    if (_busy.contains(id)) return;
    setState(() => _busy.add(id));
    optimistic?.call();
    HapticFeedback.selectionClick();
    final ok = await run(context, () => ctx.api.post('clinic/appointments/$id/$action', {}));
    if (mounted) setState(() => _busy.remove(id));
    if (ok || mounted) await _load();
  }

  Future<void> _checkIn(Map<String, dynamic> e) async {
    final id = e['id'] as String;
    if (_busy.contains(id)) return;
    setState(() => _busy.add(id));
    final ok = await run(context, () async {
      if (e['status'] == 'PENDING_CONFIRMATION') await ctx.api.post('clinic/appointments/$id/confirm', {});
      await ctx.api.post('clinic/appointments/$id/check-in', {});
    });
    if (mounted) setState(() => _busy.remove(id));
    if (ok) HapticFeedback.mediumImpact();
    await _load();
  }

  void _remove(Map<String, dynamic> e) {
    final s = S.of(context);
    final id = e['id'] as String;
    setState(() => _waiting = _waiting.where((x) => x['id'] != id).toList());
    HapticFeedback.mediumImpact();
    _removing[id] = Timer(const Duration(seconds: 4), () async {
      _removing.remove(id);
      if (!mounted) return;
      await run(context, () => ctx.api.post('clinic/appointments/$id/remove-from-queue', {}));
      await _load();
    });
    final m = ScaffoldMessenger.of(context);
    m.hideCurrentSnackBar();
    m.showSnackBar(SnackBar(
      duration: const Duration(seconds: 4),
      content: Text(s.ar ? 'تمت إزالة ${e['patientName']} من الطابور' : '${e['patientName']} removed from the queue'),
      action: SnackBarAction(label: s.ar ? 'تراجع' : 'Undo', onPressed: () {
        _removing.remove(id)?.cancel();
        setState(() => _waiting = [..._waiting, e]..sort((a, b) => ((a['queueNumber'] as num?) ?? 0).compareTo((b['queueNumber'] as num?) ?? 0)));
      }),
    ));
  }

  void _reorder(int from, int to) {
    if (from == to) return;
    setState(() { final e = _waiting.removeAt(from); _waiting.insert(to, e); });
    HapticFeedback.selectionClick();
    _orderTimer?.cancel();
    _orderTimer = Timer(const Duration(milliseconds: 600), () async {
      final ids = _waiting.map((e) => e['id'] as String).toList();
      if (!await run(context, () => ctx.api.post('clinic/queue/reorder', {'ids': ids})) && mounted) toast(context, S.of(context).ar ? 'تعذر تغيير الترتيب' : 'Could not change the order');
      await _load();
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final blue = Theme.of(context).colorScheme.primary;
    if (!_loaded) return _error != null ? errorState(_error!, () { setState(() => _error = null); _load(); }) : const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(), SizedBox(height: 12), Skeleton(), SizedBox(height: 12), Skeleton()]));
    Widget head(String t, {int? n}) => Padding(padding: const EdgeInsets.only(top: 14, bottom: 6), child: Row(children: [Text(t, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)), if (n != null && n > 0) ...[const SizedBox(width: 8), _Count(n)]]));
    final empty = _waiting.isEmpty && _inProgress.isEmpty && _reserved.isEmpty;
    return RefreshIndicator(
      onRefresh: _load,
      child: CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
            sliver: SliverList(delegate: SliverChildListDelegate([
              if (_inProgress.isNotEmpty) head(s.ar ? 'مع الطبيب الآن' : 'With the doctor now'),
              for (final e in _inProgress) FadeSlideIn(key: ValueKey('p${e['id']}'), child: QueueCard(
                color: blue.withValues(alpha: 0.08),
                leading: Icon(Icons.medical_services_rounded, color: blue),
                title: '${e['patientName']}',
                subtitle: dotted(['#${e['queueNumber']}', e['serviceName'], visitTypeLabel(e['visitType'], s.ar)]),
                actions: [OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 40)), onPressed: _busy.contains(e['id']) ? null : () => _act(e['id'] as String, 'complete'), child: Text(s.ar ? 'إنهاء' : 'Done'))],
              )),
              head(s.waitingRoom, n: _waiting.length),
              if (_waiting.isNotEmpty) Padding(padding: const EdgeInsets.only(bottom: 4), child: Text(s.ar ? 'اسحب للنداء أو للإخراج، وأمسك المقبض لتغيير الترتيب' : 'Swipe to call or remove, hold the handle to reorder', style: const TextStyle(color: Color(0xFF8A97A5), fontSize: 12))),
            ])),
          ),
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            sliver: SliverReorderableList(
              itemCount: _waiting.length,
              onReorderItem: _reorder,
              onReorderStart: (_) { _dragging = true; HapticFeedback.lightImpact(); },
              onReorderEnd: (_) => _dragging = false,
              proxyDecorator: (child, i, anim) => AnimatedBuilder(animation: anim, builder: (c, _) => Transform.scale(scale: 1 + 0.03 * Curves.easeOut.transform(anim.value), child: Material(color: Colors.transparent, elevation: 10 * anim.value, borderRadius: BorderRadius.circular(20), child: child))),
              itemBuilder: (c, i) => _waitingItem(c, i, blue),
            ),
          ),
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 90),
            sliver: SliverList(delegate: SliverChildListDelegate([
              if (_waiting.isEmpty && _inProgress.isEmpty) emptyState(s.ar ? 'لا أحد في الانتظار' : 'Nobody is waiting', icon: Icons.event_seat_outlined),
              if (_reserved.isNotEmpty) head(s.ar ? 'محجوزين اليوم' : 'Reserved today', n: _reserved.length),
              for (var i = 0; i < _reserved.length; i++) FadeSlideIn(key: ValueKey('r${_reserved[i]['id']}'), index: i, child: _reservedCard(_reserved[i], blue)),
              if (empty) const SizedBox.shrink(),
            ])),
          ),
        ],
      ),
    );
  }

  Widget _waitingItem(BuildContext context, int i, Color blue) {
    final s = S.of(context);
    final e = _waiting[i];
    final id = e['id'] as String;
    final called = e['calledAt'] != null;
    return Dismissible(
      key: ValueKey(id),
      background: _swipeBg(blue, Icons.campaign_rounded, called ? (s.ar ? 'نداء مرة أخرى' : 'Call again') : s.call, Alignment.centerRight),
      secondaryBackground: _swipeBg(const Color(0xFFD14343), Icons.person_remove_rounded, s.ar ? 'إخراج من الطابور' : 'Remove', Alignment.centerLeft),
      confirmDismiss: (dir) async {
        if (dir == DismissDirection.startToEnd) { await _act(id, 'call', optimistic: () => setState(() => _waiting[i] = {...e, 'calledAt': DateTime.now().toIso8601String()})); return false; }
        return true;
      },
      onDismissed: (_) => _remove(e),
      child: QueueCard(
        leading: CircleAvatar(backgroundColor: blue.withValues(alpha: 0.12), foregroundColor: blue, child: Text('${e['queueNumber']}', style: const TextStyle(fontWeight: FontWeight.w900))),
        title: '${e['patientName']}',
        subtitle: dotted([e['serviceName'], visitTypeLabel(e['visitType'], s.ar), '${e['waitedMinutes'] ?? 0} ${s.ar ? 'د' : 'min'}', if (called) (s.ar ? 'تم النداء' : 'called')]),
        trailing: ReorderableDragStartListener(index: i, child: const Padding(padding: EdgeInsets.all(8), child: Icon(Icons.drag_indicator_rounded, color: Color(0xFF8A97A5)))),
        actions: [
          OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 40)), onPressed: _busy.contains(id) ? null : () => _act(id, 'call', optimistic: () => setState(() => _waiting[i] = {...e, 'calledAt': DateTime.now().toIso8601String()})), child: Text(called ? (s.ar ? 'نداء مرة أخرى' : 'Call again') : s.call)),
          if (ctx.can('medical_note.create')) FilledButton(style: FilledButton.styleFrom(minimumSize: const Size(0, 40)), onPressed: _busy.contains(id) ? null : () => _act(id, 'start'), child: Text(s.startVisit)),
        ],
      ),
    );
  }

  Widget _swipeBg(Color color, IconData icon, String label, Alignment align) => Container(
        margin: const EdgeInsets.symmetric(vertical: 6),
        padding: const EdgeInsets.symmetric(horizontal: 22),
        alignment: align,
        decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(20)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(icon, color: Colors.white), const SizedBox(width: 8), Text(label, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w800))]),
      );

  Widget _reservedCard(Map<String, dynamic> e, Color blue) {
    final s = S.of(context);
    final id = e['id'] as String;
    final at = DateTime.tryParse('${e['startAt']}')?.toLocal();
    String two(int n) => n.toString().padLeft(2, '0');
    final time = at == null ? '' : '${two(at.hour)}:${two(at.minute)}';
    final age = e['patientAgeYears'] == null ? null : '${e['patientAgeYears']} ${s.ar ? 'سنة' : 'y'}';
    final sex = e['patientSex'] == 'F' ? (s.ar ? 'أنثى' : 'Female') : e['patientSex'] == 'M' ? (s.ar ? 'ذكر' : 'Male') : null;
    final pending = e['status'] == 'PENDING_CONFIRMATION';
    return QueueCard(
      leading: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        decoration: BoxDecoration(color: const Color(0xFFFFF3DC), borderRadius: BorderRadius.circular(14)),
        child: Column(mainAxisSize: MainAxisSize.min, children: [const Icon(Icons.schedule_rounded, size: 16, color: Color(0xFFB7791F)), Text(time, textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFFB7791F)))]),
      ),
      title: '${e['patientName']}',
      subtitle: dotted([age, sex, if ((e['patientPhone'] as String?)?.isNotEmpty == true) '\u2066${e['patientPhone']}\u2069', e['serviceName'], visitTypeLabel(e['visitType'], s.ar), if (pending) (s.ar ? 'بانتظار التأكيد' : 'awaiting confirmation')]),
      actions: [
        FilledButton.icon(
          style: FilledButton.styleFrom(minimumSize: const Size(0, 42)),
          onPressed: _busy.contains(id) ? null : () => _checkIn(e),
          icon: _busy.contains(id) ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : const Icon(Icons.login_rounded, size: 18),
          label: Text(s.ar ? 'إضافة للطابور' : 'Add to queue'),
        ),
      ],
    );
  }
}

class _Count extends StatelessWidget {
  const _Count(this.n);
  final int n;
  @override
  Widget build(BuildContext context) => AnimatedSwitcher(
        duration: const Duration(milliseconds: 250),
        transitionBuilder: (c, a) => ScaleTransition(scale: a, child: c),
        child: Container(key: ValueKey(n), padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 2), decoration: BoxDecoration(color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(12)), child: Text('$n', style: TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w900))),
      );
}
