import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'clinic_panel.dart';
import 'common.dart';
import 'forms.dart';
import 'lab_sheet.dart';

List<Map<String, dynamic>> _maps(dynamic v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

String _age(Map<String, dynamic> e, bool isAr) {
  final y = e['patientAgeYears'] as num?, m = e['patientAgeMonths'] as num?;
  if (y == null) return '-';
  if (y < 2 && m != null) return isAr ? '$m شهر' : '$m mo';
  return isAr ? '$y سنة' : '$y y';
}

/// "الكشف الحالي": the patient the secretary sent in. One tap opens the exam; the list refreshes itself every 5 seconds.
class CurrentExam extends StatefulWidget {
  const CurrentExam({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<CurrentExam> createState() => _CurrentExamState();
}

class _CurrentExamState extends State<CurrentExam> {
  Map<String, dynamic>? _q;
  Object? _error;
  String _doctorId = '';
  bool _resolved = false, _opening = false;
  Timer? _timer;
  Ctx get ctx => widget.ctx;

  @override
  void initState() {
    super.initState();
    _init();
    _timer = Timer.periodic(const Duration(seconds: 5), (_) { if (_resolved) _load(); });
  }

  @override
  void dispose() { _timer?.cancel(); super.dispose(); }

  Future<void> _init() async {
    try {
      final me = await ctx.api.get('clinic/doctors/me');
      _doctorId = (me as Map)['id'] as String? ?? '';
    } catch (_) { /* not a doctor: the screen then follows every doctor */ }
    _resolved = true;
    await _load();
  }

  Future<void> _load() async {
    try {
      final q = await ctx.api.get('clinic/queue', query: {'doctorId': _doctorId.isEmpty ? null : _doctorId}) as Map<String, dynamic>;
      if (mounted) setState(() { _q = q; _error = null; });
    } catch (e) {
      if (mounted && _q == null) setState(() => _error = e);
    }
  }

  Future<void> _open(Map<String, dynamic> e) async {
    setState(() => _opening = true);
    String? id;
    final ok = await run(context, () async {
      final enc = await ctx.api.post('clinic/encounters', {'patientId': e['patientId'], 'appointmentId': e['id']}) as Map<String, dynamic>;
      id = enc['id'] as String;
    });
    if (mounted) setState(() => _opening = false);
    if (ok && id != null && mounted) {
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => EncounterPage(ctx: ctx, id: id!)));
      await _load();
    }
  }

  Future<void> _visitType(Map<String, dynamic> e) async {
    final isAr = S.of(context).ar;
    final v = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
        ListTile(title: Text(isAr ? 'كشف' : 'Consultation'), selected: e['visitType'] == 'CONSULTATION', onTap: () => Navigator.pop(c, 'CONSULTATION')),
        ListTile(title: Text(isAr ? 'إعادة' : 'Follow-up'), selected: e['visitType'] == 'FOLLOW_UP', onTap: () => Navigator.pop(c, 'FOLLOW_UP')),
      ])),
    );
    if (v == null || !mounted) return;
    if (await run(context, () => ctx.api.patch('clinic/appointments/${e['id']}/visit-type', {'visitType': v}))) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    final blue = Theme.of(context).colorScheme.primary;
    final clinical = ctx.can('medical_note.create');
    if (_q == null && _error == null) return const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(), SizedBox(height: 12), Skeleton()]));
    if (_q == null) return Center(child: Column(mainAxisSize: MainAxisSize.min, children: [Text(errorText(_error!)), TextButton(onPressed: () { setState(() => _error = null); _load(); }, child: Text(S.of(context).retry))]));
    final inProgress = _maps(_q!['inProgress']);
    final waiting = _maps(_q!['waiting']);
    final current = inProgress.isEmpty ? null : inProgress.first;
    final others = inProgress.skip(1).toList();
    final next = waiting.isEmpty ? null : waiting.first;
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
        Text(isAr ? 'المريض الذي أدخلته السكرتيرة. افتح الكشف لإضافة العلامات الحيوية والملاحظات والروشتة والتحاليل.' : 'The patient the secretary sent in. Open the exam to add vitals, notes, prescription and tests.', style: const TextStyle(color: Color(0xFF6B7C8D))),
        const SizedBox(height: 10),
        if (current != null) Card(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: blue.withValues(alpha: 0.5), width: 2)),
          child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text('${current['patientName']}', style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w900)),
            Text('${current['patientCode']} · #${current['queueNumber']}', style: const TextStyle(color: Color(0xFF6B7C8D), fontFamily: 'monospace')),
            const SizedBox(height: 8),
            kv(isAr ? 'السن' : 'Age', _age(current, isAr)),
            kv(isAr ? 'الموبايل' : 'Mobile', '${current['patientPhone'] ?? ''}', ltr: true),
            kv(isAr ? 'الخدمة' : 'Service', '${current['serviceName'] ?? ''}'),
            Row(children: [
              Expanded(child: kv(isAr ? 'نوع الزيارة' : 'Visit type', current['visitType'] == 'FOLLOW_UP' ? (isAr ? 'إعادة' : 'Follow-up') : (isAr ? 'كشف' : 'Consultation'))),
              TextButton(onPressed: () => _visitType(current), child: Text(isAr ? 'تغيير' : 'Change')),
            ]),
            Text(isAr ? 'داخل الغرفة منذ ${current['statusMinutes'] ?? 0} دقيقة' : 'In the room for ${current['statusMinutes'] ?? 0} min', style: TextStyle(color: blue, fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            if (clinical) FilledButton.icon(onPressed: _opening ? null : () => _open(current), icon: const Icon(Icons.medical_services_rounded), label: Text(isAr ? 'فتح الكشف' : 'Open exam')),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: OutlinedButton.icon(onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => PatientDetail(ctx: ctx, id: '${current['patientId']}'))), icon: const Icon(Icons.folder_open_rounded), label: Text(isAr ? 'ملف المريض' : 'Record'))),
              if ((current['patientPhone'] as String?)?.isNotEmpty == true) ...[
                const SizedBox(width: 8),
                IconButton.outlined(onPressed: () => whatsapp(current['patientPhone'] as String?), icon: const Icon(Icons.chat_rounded)),
                IconButton.outlined(onPressed: () => dial(current['patientPhone'] as String?), icon: const Icon(Icons.call_rounded)),
              ],
            ]),
          ])),
        ) else Card(child: Padding(padding: const EdgeInsets.symmetric(vertical: 28), child: Center(child: Text(isAr ? 'لا يوجد مريض في الغرفة الآن.' : 'No patient in the room right now.', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 16))))),
        if (others.isNotEmpty) ...[
          sectionTitle(isAr ? 'مرضى آخرون داخل الغرفة' : 'Also in the room'),
          for (final e in others) QueueCard(
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => PatientDetail(ctx: ctx, id: '${e['patientId']}'))),
            leading: const Icon(Icons.meeting_room_rounded),
            title: '${e['patientName']}',
            subtitle: dotted(['#${e['queueNumber']}', visitTypeLabel(e['visitType'], isAr)]),
            actions: [if (clinical) OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 40)), onPressed: () => _open(e), child: Text(isAr ? 'فتح الكشف' : 'Open exam'))],
          ),
        ],
        sectionTitle('${isAr ? 'التالي في الدور' : 'Next in line'} (${waiting.length})'),
        if (next == null) Text(isAr ? 'لا أحد في الانتظار.' : 'Nobody is waiting.', style: const TextStyle(color: Color(0xFF8A97A5))) else QueueCard(
          onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => PatientDetail(ctx: ctx, id: '${next['patientId']}'))),
          color: blue.withValues(alpha: 0.06),
          leading: CircleAvatar(backgroundColor: blue.withValues(alpha: 0.12), foregroundColor: blue, child: Text('${next['queueNumber']}', style: const TextStyle(fontWeight: FontWeight.w900))),
          title: '${next['patientName']}',
          subtitle: dotted([visitTypeLabel(next['visitType'], isAr), '${isAr ? 'انتظر' : 'waited'} ${next['waitedMinutes'] ?? 0} ${isAr ? 'د' : 'min'}']),
          actions: [
            if (ctx.can('appointment.manage')) FilledButton.tonal(style: FilledButton.styleFrom(minimumSize: const Size(0, 40)), onPressed: () async { if (await run(context, () => ctx.api.post('clinic/appointments/${next['id']}/call', {}))) await _load(); }, child: Text(next['calledAt'] != null ? (isAr ? 'نداء مرة أخرى' : 'Call again') : (isAr ? 'نداء' : 'Call'))),
          ],
        ),
      ]),
    );
  }
}

// ---------------------------------------------------------------- The exam itself

class EncounterPage extends StatefulWidget {
  const EncounterPage({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final String id;
  @override
  State<EncounterPage> createState() => _EncounterPageState();
}

class _EncounterPageState extends State<EncounterPage> {
  Map<String, dynamic>? e;
  Map<String, dynamic>? patient;
  Object? error;
  bool _busy = false;
  final _complaint = TextEditingController(), _summary = TextEditingController(), _follow = TextEditingController();
  Ctx get ctx => widget.ctx;

  @override
  void initState() { super.initState(); _load(first: true); }

  @override
  void dispose() { _complaint.dispose(); _summary.dispose(); _follow.dispose(); super.dispose(); }

  Future<void> _load({bool first = false}) async {
    try {
      final r = Map<String, dynamic>.from(await ctx.api.get('clinic/encounters/${widget.id}') as Map);
      if (first) {
        _complaint.text = '${r['chiefComplaint'] ?? ''}';
        _summary.text = '${r['clinicalSummary'] ?? ''}';
        _follow.text = '${r['followUpDate'] ?? ''}';
        try { patient = Map<String, dynamic>.from(await ctx.api.get('clinic/patients/${r['patientId']}') as Map); } catch (_) {}
      }
      if (mounted) setState(() { e = r; error = null; });
    } catch (x) {
      if (mounted && e == null) setState(() => error = x);
    }
  }

  Future<bool> _saveVisit() => run(context, () => ctx.api.patch('clinic/encounters/${widget.id}', {'chiefComplaint': _complaint.text, 'clinicalSummary': _summary.text, if (_follow.text.isNotEmpty) 'followUpDate': _follow.text}));

  Future<void> _complete() async {
    final isAr = S.of(context).ar;
    if (!await confirm(context, isAr ? 'إنهاء الكشف وإغلاق الحجز؟' : 'Finish the exam and close the appointment?') || !mounted) return;
    setState(() => _busy = true);
    final ok = await run(context, () async {
      await ctx.api.patch('clinic/encounters/${widget.id}', {'chiefComplaint': _complaint.text, 'clinicalSummary': _summary.text, if (_follow.text.isNotEmpty) 'followUpDate': _follow.text});
      final appt = e!['appointmentId'];
      if (appt != null) await ctx.api.post('clinic/appointments/$appt/complete', {});
    });
    if (mounted) setState(() => _busy = false);
    if (ok && mounted) Navigator.of(context).pop();
  }

  Future<void> _vitals() async {
    final isAr = S.of(context).ar;
    final keys = [('temperatureC', '°C'), ('heartRateBpm', 'bpm'), ('oxygenSaturation', 'SpO₂ %'), ('systolicBp', 'SYS'), ('diastolicBp', 'DIA'), ('weightKg', 'kg')];
    final c = {for (final k in keys) k.$1: TextEditingController()};
    if (await formSheet(context, title: isAr ? 'تسجيل العلامات الحيوية' : 'Record vitals',
        body: (x, set) => [for (final k in keys) tf(c[k.$1]!, k.$2, number: true, ltr: true)],
        onSave: () async { await ctx.api.post('clinic/encounters/${widget.id}/vitals', {for (final k in keys) if (c[k.$1]!.text.trim().isNotEmpty) k.$1: num.tryParse(c[k.$1]!.text.trim().replaceAll(',', '.'))}); })) {
      await _load();
    }
  }

  Future<void> _addCondition() async {
    final isAr = S.of(context).ar;
    final t = TextEditingController();
    if (await formSheet(context, title: isAr ? 'إضافة تشخيص' : 'Add diagnosis', canSave: () => t.text.trim().isNotEmpty,
        body: (x, set) => [tf(t, isAr ? 'التشخيص' : 'Diagnosis', onChanged: (_) => set(() {}))],
        onSave: () async { await ctx.api.post('clinic/encounters/${widget.id}/conditions', {'name': t.text.trim()}); })) {
      await _load();
    }
  }

  Future<void> _addNote() async {
    final isAr = S.of(context).ar;
    final t = TextEditingController();
    var shared = false;
    if (await formSheet(context, title: isAr ? 'إضافة ملاحظة' : 'Add note', canSave: () => t.text.trim().isNotEmpty,
        body: (x, set) => [
              tf(t, isAr ? 'الملاحظة' : 'Note', lines: 4, onChanged: (_) => set(() {})),
              SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(isAr ? 'مشاركتها مع المريض' : 'Share with the patient'), value: shared, onChanged: (v) => set(() => shared = v)),
            ],
        onSave: () async { await ctx.api.post('clinic/encounters/${widget.id}/notes', {'content': t.text.trim(), 'patientVisible': shared}); })) {
      await _load();
    }
  }

  Future<void> _prescribe() async {
    final isAr = S.of(context).ar;
    final rows = <Map<String, TextEditingController>>[];
    Map<String, TextEditingController> row() => {for (final k in ['medicationName', 'strength', 'dosage', 'frequency', 'duration']) k: TextEditingController()};
    rows.add(row());
    String? photo;
    var issue = true;
    final ok = await formSheet(context, title: isAr ? 'روشتة جديدة' : 'New prescription', saveLabel: isAr ? 'حفظ' : 'Save',
        canSave: () => photo != null || rows.any((r) => r['medicationName']!.text.trim().isNotEmpty),
        body: (x, set) => [
              for (var i = 0; i < rows.length; i++) Card(margin: const EdgeInsets.only(bottom: 10), child: Padding(padding: const EdgeInsets.all(10), child: Column(children: [
                tf(rows[i]['medicationName']!, isAr ? 'اسم الدواء' : 'Medication', onChanged: (_) => set(() {})),
                Row(children: [
                  Expanded(child: tf(rows[i]['strength']!, isAr ? 'التركيز' : 'Strength')),
                  const SizedBox(width: 8),
                  Expanded(child: tf(rows[i]['dosage']!, isAr ? 'الجرعة' : 'Dosage')),
                ]),
                Row(children: [
                  Expanded(child: tf(rows[i]['frequency']!, isAr ? 'التكرار' : 'Frequency')),
                  const SizedBox(width: 8),
                  Expanded(child: tf(rows[i]['duration']!, isAr ? 'المدة' : 'Duration')),
                ]),
              ]))),
              TextButton.icon(onPressed: () => set(() => rows.add(row())), icon: const Icon(Icons.add), label: Text(isAr ? 'إضافة دواء' : 'Add medication')),
              OutlinedButton.icon(
                icon: const Icon(Icons.photo_camera_outlined),
                label: Text(photo == null ? (isAr ? 'إرفاق صورة روشتة' : 'Attach prescription photo') : (isAr ? 'تم إرفاق الصورة ✓' : 'Photo attached ✓')),
                onPressed: () async { String? id; if (await run(x, () async { id = await pickAndUpload(x, ctx.api, 'PRESCRIPTION', visibility: 'PRIVATE'); }) && id != null) set(() => photo = id); },
              ),
              SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(isAr ? 'إصدارها للمريض الآن' : 'Issue to the patient now'), value: issue, onChanged: (v) => set(() => issue = v)),
            ],
        onSave: () async {
          await ctx.api.post('clinic/encounters/${widget.id}/prescriptions', {
            'items': [for (final r in rows) if (r['medicationName']!.text.trim().isNotEmpty) {for (final e in r.entries) e.key: e.value.text.trim()}],
            if (photo != null) 'imageFileId': photo,
            'issue': issue,
          });
        });
    if (ok) await _load();
  }

  Future<void> _orderLab() async {
    final isAr = S.of(context).ar;
    final name = TextEditingController();
    var kind = 'LAB', priority = 'ROUTINE';
    if (await formSheet(context, title: isAr ? 'طلب تحليل أو أشعة' : 'Order a test', canSave: () => name.text.trim().isNotEmpty,
        body: (x, set) => [
              tf(name, isAr ? 'اسم التحليل أو الأشعة' : 'Test name', onChanged: (_) => set(() {})),
              SegmentedButton<String>(segments: [ButtonSegment(value: 'LAB', label: Text(isAr ? 'تحليل' : 'Lab')), ButtonSegment(value: 'RADIOLOGY', label: Text(isAr ? 'أشعة' : 'Radiology'))], selected: {kind}, onSelectionChanged: (s) => set(() => kind = s.first)),
              const SizedBox(height: 10),
              SegmentedButton<String>(segments: [ButtonSegment(value: 'ROUTINE', label: Text(isAr ? 'عادي' : 'Routine')), ButtonSegment(value: 'URGENT', label: Text(isAr ? 'عاجل' : 'Urgent'))], selected: {priority}, onSelectionChanged: (s) => set(() => priority = s.first)),
            ],
        onSave: () async { await ctx.api.post('clinic/encounters/${widget.id}/lab-orders', {'testName': name.text.trim(), 'kind': kind, 'priority': priority}); })) {
      await _load();
    }
  }

  Widget _card(String title, List<Widget> children, {VoidCallback? onAdd, String? addLabel}) => Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
          if (onAdd != null) FilledButton.tonalIcon(style: FilledButton.styleFrom(minimumSize: const Size(0, 36)), onPressed: onAdd, icon: const Icon(Icons.add_rounded, size: 18), label: Text(addLabel ?? (S.of(context).ar ? 'إضافة' : 'Add'))),
        ]),
        const SizedBox(height: 6),
        ...children,
      ])));

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    final name = patient == null ? '…' : '${patient!['firstName']} ${patient!['lastName'] ?? ''}'.trim();
    return Scaffold(
      appBar: AppBar(
        title: Text(name),
        actions: [
          if (e != null) IconButton(tooltip: isAr ? 'ملف المريض' : 'Patient record', icon: const Icon(Icons.history_rounded), onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => PatientDetail(ctx: ctx, id: '${e!['patientId']}')))),
        ],
      ),
      body: e == null
          ? (error != null ? Center(child: Column(mainAxisSize: MainAxisSize.min, children: [Text(errorText(error!)), TextButton(onPressed: () { setState(() => error = null); _load(first: true); }, child: Text(S.of(context).retry))])) : const Center(child: CircularProgressIndicator()))
          : _body(context, isAr),
      bottomNavigationBar: e == null ? null : SafeArea(child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 16, 10),
        child: Row(children: [
          Expanded(child: OutlinedButton(onPressed: _busy ? null : () async { if (await _saveVisit() && context.mounted) { toast(context, isAr ? 'تم الحفظ' : 'Saved'); await _load(); } }, child: Text(isAr ? 'حفظ مسودة' : 'Save draft'))),
          if (e!['appointmentId'] != null) ...[
            const SizedBox(width: 10),
            Expanded(child: FilledButton(onPressed: _busy ? null : _complete, child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(isAr ? 'إنهاء الكشف' : 'Complete'))),
          ],
        ]),
      )),
    );
  }

  Widget _body(BuildContext context, bool isAr) {
    final conditions = _maps(e!['conditions']), notes = _maps(e!['notes']), rx = _maps(e!['prescriptions']), labs = _maps(e!['labOrders']);
    final vitals = (e!['vitals'] as List?) ?? const [];
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 24), children: [
        _card(isAr ? 'الكشف' : 'Visit', [
          TextField(controller: _complaint, maxLines: 2, decoration: InputDecoration(labelText: isAr ? 'الشكوى الرئيسية' : 'Chief complaint')),
          const SizedBox(height: 10),
          TextField(controller: _summary, maxLines: 5, decoration: InputDecoration(labelText: isAr ? 'الملخص الطبي (داخلي)' : 'Clinical summary (internal)')),
          const SizedBox(height: 10),
          TextField(
            controller: _follow,
            readOnly: true,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(labelText: isAr ? 'موعد المتابعة' : 'Follow-up date', suffixIcon: _follow.text.isEmpty ? const Icon(Icons.event) : IconButton(icon: const Icon(Icons.close), onPressed: () => setState(() => _follow.clear()))),
            onTap: () async {
              final d = await showDatePicker(context: context, initialDate: DateTime.now().add(const Duration(days: 7)), firstDate: DateTime.now(), lastDate: DateTime.now().add(const Duration(days: 730)));
              if (d != null) setState(() => _follow.text = '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}');
            },
          ),
        ]),
        const SizedBox(height: 12),
        _card(isAr ? 'العلامات الحيوية' : 'Vitals', [Text(isAr ? 'سُجّل ${vitals.length} مرة' : '${vitals.length} recorded', style: const TextStyle(color: Color(0xFF6B7C8D)))], onAdd: _vitals, addLabel: isAr ? 'تسجيل' : 'Record'),
        const SizedBox(height: 12),
        _card(isAr ? 'التشخيص' : 'Diagnosis', [
          if (conditions.isEmpty) Text(isAr ? 'لا يوجد' : 'None', style: const TextStyle(color: Color(0xFF8A97A5))),
          Wrap(spacing: 6, runSpacing: 6, children: [for (final c in conditions) Chip(label: Text('${c['name']}', style: const TextStyle(fontWeight: FontWeight.w700)))]),
        ], onAdd: _addCondition),
        const SizedBox(height: 12),
        _card(isAr ? 'الملاحظات' : 'Notes', [
          if (notes.isEmpty) Text(isAr ? 'لا يوجد' : 'None', style: const TextStyle(color: Color(0xFF8A97A5))),
          for (final n in notes) Container(
            margin: const EdgeInsets.only(bottom: 6), padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(color: const Color(0xFFF4F7FA), borderRadius: BorderRadius.circular(10)),
            child: Text('${n['content']}\n${n['isPatientVisible'] == true ? (isAr ? '· ظاهرة للمريض' : '· visible to the patient') : (isAr ? '· داخلية' : '· internal')}'),
          ),
        ], onAdd: _addNote),
        if (ctx.can('prescription.create')) ...[
          const SizedBox(height: 12),
          _card(isAr ? 'الروشتات' : 'Prescriptions', [
            if (rx.isEmpty) Text(isAr ? 'لا يوجد' : 'None', style: const TextStyle(color: Color(0xFF8A97A5))),
            for (final p in rx) Container(
              margin: const EdgeInsets.only(bottom: 8), padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(border: Border.all(color: Theme.of(context).dividerColor), borderRadius: BorderRadius.circular(12)),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [
                  StatusPill('${p['status']}'),
                  const Spacer(),
                  if (p['status'] == 'DRAFT') TextButton(onPressed: () async { if (await run(context, () => ctx.api.post('clinic/prescriptions/${p['id']}/issue', {}))) await _load(); }, child: Text(isAr ? 'إصدار' : 'Issue')),
                  if (p['status'] != 'CANCELLED' && ctx.can('prescription.delete')) TextButton(onPressed: () async { if (await confirm(context, isAr ? 'إلغاء الروشتة؟' : 'Cancel this prescription?') && context.mounted && await run(context, () => ctx.api.post('clinic/prescriptions/${p['id']}/cancel', {}))) await _load(); }, child: Text(isAr ? 'إلغاء' : 'Cancel')),
                ]),
                for (final i in _maps(p['items'])) Text('• ${i['medicationName']} ${i['dosage'] ?? ''} ${i['frequency'] ?? ''}'.trim()),
                if (p['imageFileId'] != null) Padding(padding: const EdgeInsets.only(top: 6), child: Text(isAr ? '📎 صورة مرفقة' : '📎 Photo attached')),
              ]),
            ),
          ], onAdd: _prescribe, addLabel: isAr ? 'روشتة' : 'New'),
        ],
        if (ctx.can('lab_order.create')) ...[
          const SizedBox(height: 12),
          _card(isAr ? 'التحاليل والأشعة' : 'Tests and radiology', [
            if (labs.isEmpty) Text(isAr ? 'لا يوجد' : 'None', style: const TextStyle(color: Color(0xFF8A97A5))),
            for (final l in labs) ListTile(
              contentPadding: EdgeInsets.zero, dense: true,
              onTap: () async { if (await showLabSheet(context, ctx, l)) await _load(); },
              leading: Text(l['kind'] == 'RADIOLOGY' ? '🩻' : '🧪', style: const TextStyle(fontSize: 20)),
              title: Text('${l['testName']}${l['priority'] == 'URGENT' ? (isAr ? ' · عاجل' : ' · urgent') : ''}'),
              trailing: StatusPill(l['status'] == 'ORDERED' ? 'REQUESTED' : '${l['status']}'),
            ),
          ], onAdd: _orderLab, addLabel: isAr ? 'طلب' : 'Order'),
        ],
      ]),
    );
  }
}
