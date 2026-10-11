import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

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
              Text('${a['serviceName']} · ${a['doctorName']}${a['queueNumber'] != null ? ' · #${a['queueNumber']}' : ''}', style: const TextStyle(color: Color(0xFF6B7C8D))),
              if (_buttons(a, s, reload).isNotEmpty) Padding(padding: const EdgeInsets.only(top: 10), child: Wrap(runSpacing: 6, children: _buttons(a, s, reload))),
            ]),
          )),
        ),
      ),
    ]);
  }
}

// ---------------------------------------------------------------- Waiting room

class QueueScreen extends StatelessWidget {
  const QueueScreen({super.key, required this.ctx});
  final Ctx ctx;

  Future<void> _act(BuildContext c, String id, String action, Future<void> Function() reload) async {
    if (await run(c, () => ctx.api.post('clinic/appointments/$id/$action', {}))) await reload();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final blue = Theme.of(context).colorScheme.primary;
    return Async<Map<String, dynamic>>(
      load: () async => await ctx.api.get('clinic/queue') as Map<String, dynamic>,
      builder: (context, q, reload) {
        final waiting = (q['waiting'] as List).cast<Map<String, dynamic>>();
        final inProgress = (q['inProgress'] as List).cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 24), children: [
            if (inProgress.isNotEmpty) Padding(padding: const EdgeInsets.only(bottom: 6), child: Text(s.ar ? 'مع الطبيب الآن' : 'With the doctor now', style: const TextStyle(fontWeight: FontWeight.w900))),
            for (final e in inProgress) Card(color: blue.withValues(alpha: 0.08), child: ListTile(leading: Icon(Icons.medical_services_rounded, color: blue), title: Text('${e['patientName']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text('#${e['queueNumber']} · ${e['serviceName']}'), trailing: TextButton(onPressed: () => _act(context, e['id'] as String, 'complete', reload), child: Text(s.ar ? 'إنهاء' : 'Done')))),
            Padding(padding: const EdgeInsets.only(top: 10, bottom: 6), child: Text(s.waitingRoom, style: const TextStyle(fontWeight: FontWeight.w900))),
            for (var i = 0; i < waiting.length; i++)
              FadeSlideIn(index: i, child: Card(child: ListTile(
                leading: CircleAvatar(backgroundColor: blue.withValues(alpha: 0.12), foregroundColor: blue, child: Text('${waiting[i]['queueNumber']}', style: const TextStyle(fontWeight: FontWeight.w900))),
                title: Text('${waiting[i]['patientName']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text('${waiting[i]['serviceName']} · ${waiting[i]['waitedMinutes']} min'),
                trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                  TextButton(onPressed: () => _act(context, waiting[i]['id'] as String, 'call', reload), child: Text(s.call)),
                  if (ctx.can('medical_note.create')) FilledButton(style: FilledButton.styleFrom(minimumSize: const Size(0, 36), padding: const EdgeInsets.symmetric(horizontal: 12)), onPressed: () => _act(context, waiting[i]['id'] as String, 'start', reload), child: Text(s.startVisit)),
                ]),
              ))),
            if (waiting.isEmpty && inProgress.isEmpty) emptyState(s.empty),
          ]),
        );
      },
    );
  }
}

// ---------------------------------------------------------------- Patients

class PatientsScreen extends StatelessWidget {
  const PatientsScreen({super.key, required this.ctx});
  final Ctx ctx;

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
      api: ctx.api,
      path: 'clinic/patients',
      searchHint: s.ar ? 'ابحث بالاسم أو الهاتف أو الكود' : 'Name, phone or patient code',
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
  const PatientDetail({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final String id;
  @override
  State<PatientDetail> createState() => _PatientDetailState();
}

class _PatientDetailState extends State<PatientDetail> {
  Future<void> _walkIn(Map<String, dynamic> p) async {
    final ctx = widget.ctx;
    final ok = await run(context, () async {
      final docs = (await ctx.api.get('clinic/doctors') as List).cast<Map<String, dynamic>>();
      final branches = (await ctx.api.get('clinic/branches') as List).cast<Map<String, dynamic>>();
      await ctx.api.post('clinic/appointments/walk-in', {'patientId': widget.id, 'doctorId': docs.first['id'], 'branchId': branches.first['id'], 'visitType': 'CONSULTATION'});
    });
    if (ok && mounted) toast(context, S.of(context).ar ? 'تمت إضافته إلى قاعة الانتظار' : 'Added to the waiting room');
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ctx = widget.ctx;
    return Scaffold(
      appBar: AppBar(title: Text(s.ar ? 'بيانات المريض' : 'Patient')),
      body: Async<Map<String, dynamic>>(
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
          final guardian = p['guardianName'] as String?;
          return ListView(padding: const EdgeInsets.all(16), children: [
            Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${p['firstName']} ${p['lastName'] ?? ''}'.trim(), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
              if (guardian != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(s.ar ? 'تابع لـ $guardian' : 'Dependent of $guardian', style: TextStyle(color: Theme.of(context).colorScheme.primary, fontWeight: FontWeight.w700))),
              const SizedBox(height: 10),
              kv(s.ar ? 'الكود' : 'Code', '${p['patientCode']}', ltr: true),
              kv(s.phone, '${p['phone'] ?? ''}', ltr: true),
              kv(s.ar ? 'السن' : 'Age', p['ageYears'] == null ? '' : '${p['ageYears']}'),
              kv(s.ar ? 'النوع' : 'Sex', p['sex'] == 'F' ? (s.ar ? 'أنثى' : 'Female') : p['sex'] == 'M' ? (s.ar ? 'ذكر' : 'Male') : ''),
              kv(s.ar ? 'فصيلة الدم' : 'Blood', '${p['bloodType'] ?? ''}'),
              kv(s.ar ? 'العنوان' : 'Address', '${p['addressText'] ?? ''}'),
              if ((p['notesInternal'] as String?)?.isNotEmpty == true) kv(s.ar ? 'ملاحظات' : 'Notes', '${p['notesInternal']}'),
            ]))),
            Row(children: [
              if ((p['phone'] as String?)?.isNotEmpty == true) ...[
                Expanded(child: OutlinedButton.icon(onPressed: () => dial(p['phone'] as String?), icon: const Icon(Icons.call_rounded), label: Text(s.ar ? 'اتصال' : 'Call'))),
                const SizedBox(width: 8),
                Expanded(child: OutlinedButton.icon(onPressed: () => whatsapp(p['phone'] as String?), icon: const Icon(Icons.chat_rounded), label: const Text('WhatsApp'))),
                const SizedBox(width: 8),
              ],
              if (ctx.can('appointment.manage')) Expanded(child: FilledButton.icon(onPressed: () => _walkIn(p), icon: const Icon(Icons.add_rounded), label: Text(s.ar ? 'أضف للانتظار' : 'Add to queue'))),
            ]),
            if (tl != null) ...[
              Padding(padding: const EdgeInsets.fromLTRB(4, 18, 4, 6), child: Text(s.ar ? 'الزيارات' : 'Visits', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              for (final v in visits) Card(child: ListTile(leading: const Icon(Icons.event_note_rounded), title: Text(fmtDate(v['visitAt'] as String?), textDirection: TextDirection.ltr, textAlign: TextAlign.start), subtitle: Text('${v['doctorName'] ?? ''} ${v['diagnosis'] ?? v['summary'] ?? ''}'.trim()))),
              if (visits.isEmpty) emptyState(s.empty),
              if (rx.isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(4, 14, 4, 6), child: Text(s.ar ? 'الروشتات' : 'Prescriptions', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              for (final r in rx) Card(child: ListTile(leading: const Icon(Icons.medication_rounded), title: Text(fmtDate(r['issuedAt'] as String? ?? r['createdAt'] as String?, time: false), textDirection: TextDirection.ltr, textAlign: TextAlign.start), subtitle: Text('${((r['items'] as List?) ?? const []).length} ${s.ar ? 'أصناف' : 'items'}'))),
              if (labs.isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(4, 14, 4, 6), child: Text(s.ar ? 'التحاليل والأشعة' : 'Tests & radiology', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
              for (final l in labs) Card(child: ListTile(leading: const Icon(Icons.science_rounded), title: Text('${l['testName']}'), trailing: StatusPill('${l['status']}'))),
            ] else
              Padding(padding: const EdgeInsets.all(16), child: Text(s.ar ? 'السجل الطبي متاح للأطباء فقط.' : 'The medical record is for clinical staff only.', style: const TextStyle(color: Color(0xFF6B7C8D)))),
          ]);
        },
      ),
    );
  }
}

// ---------------------------------------------------------------- Services

class ServicesScreen extends StatelessWidget {
  const ServicesScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final canEdit = ctx.can('schedule.manage');
    return Async<List<dynamic>>(
      load: () async => await ctx.api.get('clinic/services') as List<dynamic>,
      builder: (context, list, reload) {
        final items = list.cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            for (var i = 0; i < items.length; i++)
              FadeSlideIn(index: i, child: Card(child: SwitchListTile(
                value: items[i]['isActive'] == true,
                onChanged: canEdit ? (v) async { if (await run(context, () => ctx.api.put('clinic/services/${items[i]['id']}/active', {'active': v}))) await reload(); } : null,
                title: Text('${items[i]['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text('${items[i]['durationMinutes']} ${s.ar ? 'دقيقة' : 'min'}${items[i]['priceMinor'] != null ? ' · ${s.money(items[i]['priceMinor'] as num, (items[i]['currency'] as String?) ?? ctx.currency)}' : ''}'),
              ))),
            if (items.isEmpty) emptyState(s.empty),
            WebLinkCard(title: s.ar ? 'إضافة أو تعديل الخدمات' : 'Add or edit services', icon: Icons.edit_note_rounded, url: 'https://${ctx.host}/dashboard/services'),
          ]),
        );
      },
    );
  }
}

// ---------------------------------------------------------------- Schedule (read-only summary)

class ScheduleScreen extends StatelessWidget {
  const ScheduleScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<List<dynamic>>(
      load: () async => await ctx.api.get('clinic/schedules') as List<dynamic>,
      builder: (context, list, reload) {
        final items = list.cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            for (var i = 0; i < items.length; i++)
              FadeSlideIn(index: i, child: Card(child: ListTile(
                leading: const Icon(Icons.schedule_rounded),
                title: Text('${items[i]['doctorName'] ?? ''}', style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text('${items[i]['weekday'] ?? items[i]['dayOfWeek'] ?? ''}  ${items[i]['startTime'] ?? ''} – ${items[i]['endTime'] ?? ''}', textDirection: TextDirection.ltr, textAlign: TextAlign.start),
              ))),
            if (items.isEmpty) emptyState(s.empty),
            WebLinkCard(title: s.ar ? 'تعديل مواعيد العمل والإجازات' : 'Edit working hours and days off', icon: Icons.edit_calendar_rounded, url: 'https://${ctx.host}/dashboard/schedule'),
          ]),
        );
      },
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
                  onTap: items[i]['readAt'] == null ? () async { if (await run(context, () => ctx.api.post('notifications/${items[i]['id']}/read', {}))) await reload(); } : null,
                ),
              )),
            if (items.isEmpty) emptyState(s.ar ? 'لا توجد إشعارات' : 'No notifications', icon: Icons.notifications_off_outlined),
          ]),
        );
      },
    );
  }
}

class StaffScreen extends StatelessWidget {
  const StaffScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<List<dynamic>>(
      load: () async => await ctx.api.get('tenant/members') as List<dynamic>,
      builder: (context, list, reload) {
        final items = list.cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            for (var i = 0; i < items.length; i++)
              FadeSlideIn(index: i, child: Card(child: ListTile(
                leading: CircleAvatar(child: Text(('${items[i]['firstName']}'.isEmpty ? '?' : '${items[i]['firstName']}'[0]).toUpperCase())),
                title: Text('${items[i]['firstName']} ${items[i]['lastName'] ?? ''}'.trim(), style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text('${items[i]['roles']}\n${items[i]['phone'] ?? ''}', textDirection: TextDirection.ltr, textAlign: TextAlign.start),
                isThreeLine: true,
                trailing: StatusPill('${items[i]['userStatus']}'),
              ))),
            if (items.isEmpty) emptyState(s.empty),
            WebLinkCard(title: s.ar ? 'دعوة موظف أو تغيير الصلاحيات' : 'Invite staff or change roles', icon: Icons.group_add_rounded, url: 'https://${ctx.host}/dashboard/staff'),
          ]),
        );
      },
    );
  }
}
