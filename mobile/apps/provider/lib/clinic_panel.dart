import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'lab_sheet.dart';
import 'patients.dart';

// ---------------------------------------------------------------- Appointments

/// All appointments of one day (previous/next day buttons) with every action the reception needs.
class AppointmentsScreen extends StatefulWidget {
  const AppointmentsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<AppointmentsScreen> createState() => _AppointmentsScreenState();
}

class _AppointmentsScreenState extends State<AppointmentsScreen> {
  DateTime _day = DateTime.now();
  int _n = 0;
  String get _key => fmtDate(_day.toIso8601String(), time: false);

  Future<void> _act(Map<String, dynamic> a, String action, VoidCallback reload) async {
    if (await run(context, () => widget.ctx.api.post('clinic/appointments/${a['id']}/$action', {}))) reload();
  }

  List<Widget> _buttons(Map<String, dynamic> a, S s, VoidCallback reload) {
    Widget b(String label, String action, {bool primary = false}) => Padding(
          padding: const EdgeInsetsDirectional.only(end: 6),
          child: primary ? FilledButton(style: FilledButton.styleFrom(minimumSize: const Size(0, 36), padding: const EdgeInsets.symmetric(horizontal: 14)), onPressed: () => _act(a, action, reload), child: Text(label)) : OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 36), padding: const EdgeInsets.symmetric(horizontal: 12)), onPressed: () => _act(a, action, reload), child: Text(label)),
        );
    final st = a['status'] as String;
    final ar = s.ar;
    return [
      if (st == 'PENDING_CONFIRMATION') ...[b(ar ? 'تأكيد' : 'Confirm', 'confirm', primary: true), b(ar ? 'رفض' : 'Reject', 'reject')],
      if (st == 'CONFIRMED') ...[b(ar ? 'حضر' : 'Check in', 'check-in', primary: true), b(ar ? 'لم يحضر' : 'No-show', 'no-show'), b(ar ? 'إلغاء' : 'Cancel', 'cancel')],
      if (st == 'CHECKED_IN' && widget.ctx.can('medical_note.create')) b(s.startVisit, 'start', primary: true),
      if (st == 'IN_PROGRESS') b(ar ? 'إنهاء' : 'Complete', 'complete', primary: true),
      if (a['paymentMethod'] == 'CASH_AT_CLINIC' && a['paymentStatus'] != 'PAID' && ['CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'COMPLETED'].contains(st)) b(ar ? 'تم الدفع' : 'Mark paid', 'mark-paid'),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final scheme = Theme.of(context).colorScheme;
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
        child: Row(children: [
          IconButton.filledTonal(onPressed: () => setState(() { _day = _day.subtract(const Duration(days: 1)); _n++; }), icon: const Icon(Icons.chevron_left_rounded)),
          Expanded(child: InkWell(
            borderRadius: BorderRadius.circular(14),
            onTap: () async {
              final d = await showDatePicker(context: context, initialDate: _day, firstDate: DateTime(2024), lastDate: DateTime(2035));
              if (d != null) setState(() { _day = d; _n++; });
            },
            child: Padding(padding: const EdgeInsets.symmetric(vertical: 10), child: Center(child: Text(_key, style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900, color: scheme.primary)))),
          )),
          IconButton.filledTonal(onPressed: () => setState(() { _day = _day.add(const Duration(days: 1)); _n++; }), icon: const Icon(Icons.chevron_right_rounded)),
        ]),
      ),
      Expanded(
        child: PagedList(
          key: ValueKey('$_n'),
          api: widget.ctx.api,
          path: 'clinic/appointments',
          pageSize: 100,
          query: {'from': _key, 'to': _key},
          emptyText: s.ar ? 'لا توجد مواعيد في هذا اليوم' : 'No appointments on this day',
          fab: widget.ctx.can('appointment.manage') ? FloatingActionButton.extended(onPressed: () async { if (await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => BookingPage(ctx: widget.ctx))) == true) setState(() => _n++); }, icon: const Icon(Icons.add_rounded), label: Text(s.ar ? 'حجز جديد' : 'New booking')) : null,
          itemBuilder: (c, a, reload) => Card(child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Text(fmtDate(a['startAt'] as String?).split('  ').last, style: TextStyle(fontWeight: FontWeight.w900, color: scheme.primary, fontSize: 16)),
                const SizedBox(width: 10),
                Expanded(child: Text('${a['patientName']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16))),
                StatusPill(a['status'] as String),
              ]),
              const SizedBox(height: 4),
              Text(dotted([a['serviceName'], visitTypeLabel(a['visitType'], s.ar), a['doctorName'], a['queueNumber'] != null ? '#${a['queueNumber']}' : null]), style: const TextStyle(color: Color(0xFF6B7C8D))),
              if (_buttons(a, s, reload).isNotEmpty) Padding(padding: const EdgeInsets.only(top: 10), child: Wrap(runSpacing: 6, children: _buttons(a, s, reload))),
            ]),
          )),
        ),
      ),
    ]);
  }
}

// ---------------------------------------------------------------- Waiting room

// ---------------------------------------------------------------- Patients

class PatientsScreen extends StatefulWidget {
  const PatientsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<PatientsScreen> createState() => _PatientsScreenState();
}

class _PatientsScreenState extends State<PatientsScreen> {
  int _n = 0;
  Ctx get ctx => widget.ctx;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    Widget row(BuildContext c, Map<String, dynamic> p, {bool child = false, String? parent}) {
      final name = '${p['firstName']} ${p['lastName'] ?? ''}'.trim();
      final age = p['ageYears'] != null ? (s.ar ? '${p['ageYears']} سنة' : '${p['ageYears']} y') : '';
      return Card(
        margin: EdgeInsetsDirectional.only(start: child ? 22 : 0, top: 4, bottom: 4),
        child: ListTile(
          leading: CircleAvatar(backgroundColor: Theme.of(c).colorScheme.primary.withValues(alpha: child ? 0.08 : 0.14), child: Icon(child ? Icons.child_care_rounded : Icons.person_rounded, color: Theme.of(c).colorScheme.primary)),
          title: Text(name, style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text([p['patientCode'], age, if (child) (s.ar ? 'تابع لـ $parent' : 'child of $parent'), if (!child && (p['phone'] as String?)?.isNotEmpty == true) '\u2066${p['phone']}\u2069'].where((e) => e != null && '$e'.isNotEmpty).join(' · ')),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: () => Navigator.of(c).push(MaterialPageRoute(builder: (_) => PatientDetail(ctx: ctx, id: p['id'] as String))),
        ),
      );
    }

    return PagedList(
      key: ValueKey('patients$_n'),
      api: ctx.api,
      path: 'clinic/patients',
      fab: ctx.can('patient.create') ? FloatingActionButton.extended(onPressed: () async { if (await addPatientChooser(context, ctx) != null && mounted) setState(() => _n++); }, icon: const Icon(Icons.person_add_alt_1_rounded), label: Text(s.ar ? 'مريض جديد' : 'New patient')) : null,
      searchHint: s.ar ? 'ابحث بالاسم أو الهاتف أو الكود أو رقم المريض' : 'Name, phone, code or patient ID',
      emptyText: s.ar ? 'لا يوجد مرضى' : 'No patients',
      itemBuilder: (c, p, reload) {
        final kids = ((p['dependents'] as List?) ?? const []).cast<Map<String, dynamic>>();
        final parentName = '${p['firstName']}';
        return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [row(c, p), for (final k in kids) row(c, k, child: true, parent: parentName)]);
      },
    );
  }
}

class PatientDetail extends StatefulWidget {
  const PatientDetail({super.key, required this.ctx, required this.id, this.openLabId});
  final Ctx ctx;
  final String id;
  /// Opens this lab's sheet as soon as the patient has loaded (from a notification about an uploaded result).
  final String? openLabId;
  @override
  State<PatientDetail> createState() => _PatientDetailState();
}

class _PatientDetailState extends State<PatientDetail> {
  bool _openedLab = false;
  Future<void> _walkIn() async {
    if (await walkInSheet(context, widget.ctx, widget.id) && mounted) toast(context, S.of(context).ar ? 'تمت إضافته إلى قاعة الانتظار' : 'Added to the waiting room');
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ctx = widget.ctx;
    return Scaffold(
      appBar: AppBar(title: Text(s.ar ? 'بيانات المريض' : 'Patient')),
      body: Async<Map<String, dynamic>>(
        peek: () async {
          final p = await ctx.api.peek('clinic/patients/${widget.id}');
          if (p is! Map) return null;
          final tl = await ctx.api.peek('clinic/patients/${widget.id}/timeline');
          return {'p': Map<String, dynamic>.from(p), 'tl': tl is Map ? Map<String, dynamic>.from(tl) : null};
        },
        load: () async {
          final p = await ctx.api.get('clinic/patients/${widget.id}') as Map<String, dynamic>;
          Map<String, dynamic>? tl;
          if (ctx.canAny(['medical_note.create', 'prescription.create', 'lab_order.create'])) {
            try { tl = await ctx.api.get('clinic/patients/${widget.id}/timeline') as Map<String, dynamic>; } catch (_) {}
          }
          return {'p': p, 'tl': tl};
        },
        builder: (context, d, reload) {
          final p = d['p'] as Map<String, dynamic>;
          final tl = d['tl'] as Map<String, dynamic>?;
          final visits = ((tl?['visits'] as List?) ?? const []).cast<Map<String, dynamic>>();
          final rx = ((tl?['prescriptions'] as List?) ?? const []).cast<Map<String, dynamic>>();
          final labs = ((tl?['labOrders'] as List?) ?? const []).cast<Map<String, dynamic>>();
          if (!_openedLab && widget.openLabId != null) {
            final target = labs.where((l) => l['id'] == widget.openLabId).toList();
            if (target.isNotEmpty) {
              _openedLab = true;
              WidgetsBinding.instance.addPostFrameCallback((_) async { if (mounted && await showLabSheet(context, ctx, target.first)) await reload(); });
            }
          }
          final guardian = p['guardianName'] as String?;
          return ListView(padding: const EdgeInsets.all(16), children: [
            Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${p['firstName']} ${p['lastName'] ?? ''}'.trim(), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
              if (guardian != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(s.ar ? 'تابع لـ $guardian' : 'Dependent of $guardian', style: TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w700))),
              const SizedBox(height: 10),
              kv(s.ar ? 'رقم المريض' : 'Patient ID', '${p['patientCode']}', ltr: true),
              kv(s.phone, '${p['phone'] ?? ''}', ltr: true),
              kv(s.ar ? 'السن' : 'Age', p['ageYears'] == null ? '' : '${p['ageYears']}'),
              kv(s.ar ? 'النوع' : 'Sex', p['sex'] == 'F' ? (s.ar ? 'أنثى' : 'Female') : p['sex'] == 'M' ? (s.ar ? 'ذكر' : 'Male') : ''),
              kv(s.ar ? 'فصيلة الدم' : 'Blood', '${p['bloodType'] ?? ''}'),
              kv(s.ar ? 'العنوان' : 'Address', '${p['addressText'] ?? ''}'),
              if ((p['notesInternal'] as String?)?.isNotEmpty == true) kv(s.ar ? 'ملاحظات' : 'Notes', '${p['notesInternal']}'),
            ]))),
            Row(children: [
              if ((p['phone'] as String?)?.isNotEmpty == true) ...[
                IconButton.filledTonal(tooltip: s.ar ? 'اتصال' : 'Call', iconSize: 26, style: IconButton.styleFrom(minimumSize: const Size(54, 54)), onPressed: () => dial(p['phone'] as String?), icon: const Icon(Icons.call_rounded)),
                const SizedBox(width: 8),
                IconButton.filledTonal(tooltip: 'WhatsApp', iconSize: 26, style: IconButton.styleFrom(minimumSize: const Size(54, 54), backgroundColor: const Color(0xFFDDF5E6), foregroundColor: const Color(0xFF1E9E5A)), onPressed: () => whatsapp(p['phone'] as String?), icon: const Icon(Icons.chat_rounded)),
                const SizedBox(width: 10),
              ],
              if (ctx.can('appointment.manage')) Expanded(child: FilledButton.icon(onPressed: _walkIn, icon: const Icon(Icons.add_rounded), label: Text(s.ar ? 'أضف للانتظار' : 'Add to queue'))),
            ]),
            Padding(padding: const EdgeInsets.only(top: 8), child: Wrap(spacing: 8, runSpacing: 8, children: [
              if (ctx.can('patient.update') || ctx.can('patient.create')) OutlinedButton.icon(onPressed: () async { if (await patientSheet(context, ctx, patient: p) != null) await reload(); }, icon: const Icon(Icons.edit_outlined), label: Text(s.ar ? 'تعديل' : 'Edit')),
              if (guardian == null && ctx.can('patient.create')) OutlinedButton.icon(onPressed: () async { if (await patientSheet(context, ctx, guardian: p) != null && context.mounted) toast(context, s.ar ? 'تمت إضافة الطفل' : 'Child added'); }, icon: const Icon(Icons.child_care_rounded), label: Text(s.ar ? 'إضافة طفل' : 'Add a child')),
              if (p['hasPortal'] == true) OutlinedButton.icon(onPressed: () async { if (await setPatientPasswordSheet(context, ctx, widget.id) && context.mounted) toast(context, s.ar ? 'تم تعيين كلمة المرور' : 'Password set'); }, icon: const Icon(Icons.key_rounded), label: Text(s.ar ? 'كلمة مرور المريض' : 'Patient password')),
            ])),
            if (tl != null) ...[
              Padding(padding: const EdgeInsets.fromLTRB(4, 18, 4, 6), child: Text(s.ar ? 'الزيارات' : 'Visits', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              for (final v in visits) Card(child: ListTile(leading: const Icon(Icons.event_note_rounded), title: Text(fmtDate(v['visitAt'] as String?), textDirection: TextDirection.ltr, textAlign: TextAlign.start), subtitle: Text(dotted([visitTypeLabel(v['visitType'], s.ar), v['doctorName'], v['diagnosis'] ?? v['clinicalSummary'] ?? v['summary'] ?? v['chiefComplaint']])), trailing: v['visitType'] == null ? null : _VisitChip(v['visitType'] as String, s.ar))),
              if (visits.isEmpty) emptyState(s.empty),
              if (rx.isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(4, 14, 4, 6), child: Text(s.ar ? 'الروشتات' : 'Prescriptions', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              for (final r in rx) Card(child: ListTile(leading: const Icon(Icons.medication_rounded), title: Text(fmtDate(r['issuedAt'] as String? ?? r['createdAt'] as String?, time: false), textDirection: TextDirection.ltr, textAlign: TextAlign.start), subtitle: Text('${((r['items'] as List?) ?? const []).length} ${s.ar ? 'أصناف' : 'items'}'))),
              if (labs.isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(4, 14, 4, 6), child: Text(s.ar ? 'التحاليل والأشعة' : 'Tests & radiology', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              for (final l in labs) Card(child: ListTile(leading: const Icon(Icons.science_rounded), title: Text('${l['testName']}'), trailing: StatusPill('${l['status']}'), onTap: () async { if (await showLabSheet(context, ctx, l)) await reload(); })),
            ] else
              Padding(padding: const EdgeInsets.all(16), child: Text(s.ar ? 'السجل الطبي متاح للأطباء فقط.' : 'The medical record is for clinical staff only.', style: const TextStyle(color: Color(0xFF6B7C8D)))),
          ]);
        },
      ),
    );
  }
}

// ---------------------------------------------------------------- Notifications / Staff (shared with the shop)

class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      load: () async => await ctx.api.get('notifications', query: {'pageSize': '50'}) as Map<String, dynamic>,
      builder: (context, d, reload) {
        final items = (d['data'] as List).cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            if (items.any((n) => n['readAt'] == null)) Align(alignment: AlignmentDirectional.centerEnd, child: TextButton.icon(onPressed: () async { if (await run(context, () => ctx.api.post('notifications/read-all', {}))) await reload(); }, icon: const Icon(Icons.done_all_rounded), label: Text(s.ar ? 'تحديد الكل كمقروء' : 'Mark all read'))),
            for (var i = 0; i < items.length; i++)
              FadeSlideIn(index: i, child: Card(
                color: items[i]['readAt'] == null ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.07) : null,
                child: ListTile(
                  leading: Icon(items[i]['readAt'] == null ? Icons.notifications_active_rounded : Icons.notifications_none_rounded, color: Theme.of(context).colorScheme.primary),
                  title: Text('${items[i]['title']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                  subtitle: Text('${items[i]['body']}\n${fmtDate(items[i]['createdAt'] as String?)}'),
                  isThreeLine: true,
                  trailing: (items[i]['payload'] is Map && (items[i]['payload'] as Map)['patientId'] != null) ? const Icon(Icons.chevron_right_rounded) : null,
                  onTap: () async {
                    final n = items[i];
                    if (n['readAt'] == null) await run(context, () => ctx.api.post('notifications/${n['id']}/read', {}));
                    final payload = n['payload'];
                    if (context.mounted && payload is Map && payload['patientId'] != null) {
                      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => PatientDetail(ctx: ctx, id: '${payload['patientId']}', openLabId: payload['labOrderId'] == null ? null : '${payload['labOrderId']}')));
                    }
                    await reload();
                  },
                ),
              )),
            if (items.isEmpty) emptyState(s.ar ? 'لا توجد إشعارات' : 'No notifications', icon: Icons.notifications_off_outlined),
          ]),
        );
      },
    );
  }
}

class _VisitChip extends StatelessWidget {
  const _VisitChip(this.type, this.ar);
  final String type;
  final bool ar;
  @override
  Widget build(BuildContext context) {
    final follow = type == 'FOLLOW_UP';
    final c = follow ? const Color(0xFFB7791F) : Theme.of(context).colorScheme.primary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(14)),
      child: Text(visitTypeLabel(type, ar), style: TextStyle(color: c, fontWeight: FontWeight.w800, fontSize: 12)),
    );
  }
}
