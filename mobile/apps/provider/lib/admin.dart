import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';

List<Map<String, dynamic>> _maps(dynamic v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

const roleLabels = {
  'STORE_OWNER': 'صاحب المتجر|Store owner', 'STORE_ADMIN': 'مدير المتجر|Store admin', 'STORE_MANAGER': 'مشرف المتجر|Store manager', 'INVENTORY_MANAGER': 'مسؤول المخزون|Inventory manager',
  'ORDER_MANAGER': 'مسؤول الطلبات|Order manager', 'CUSTOMER_SUPPORT': 'خدمة العملاء|Customer support', 'REPORT_VIEWER': 'مطّلع على التقارير|Report viewer', 'CLINIC_OWNER': 'صاحب العيادة|Clinic owner',
  'DOCTOR': 'طبيب|Doctor', 'CLINIC_ADMIN': 'مدير العيادة|Clinic admin', 'RECEPTIONIST': 'موظف استقبال|Receptionist', 'NURSE': 'ممرض/ة|Nurse', 'LAB_COORDINATOR': 'منسق معمل|Lab coordinator', 'ACCOUNTANT': 'محاسب|Accountant',
};
String roleName(String r, bool isAr) => (roleLabels[r] ?? '$r|$r').split('|')[isAr ? 0 : 1];

// ---------------------------------------------------------------- Services

class ServicesScreen extends StatelessWidget {
  const ServicesScreen({super.key, required this.ctx});
  final Ctx ctx;

  Future<void> _add(BuildContext c, Future<void> Function() reload) async {
    final isAr = S.of(c).ar;
    final name = TextEditingController(), dur = TextEditingController(text: '30'), price = TextEditingController();
    var visit = '';
    if (await formSheet(c, title: isAr ? 'خدمة جديدة' : 'New service', canSave: () => name.text.trim().isNotEmpty && (int.tryParse(dur.text.trim()) ?? 0) >= 5,
        body: (x, set) => [
              tf(name, isAr ? 'الاسم' : 'Name', onChanged: (_) => set(() {})),
              tf(dur, isAr ? 'المدة (دقائق)' : 'Duration (minutes)', number: true, ltr: true, onChanged: (_) => set(() {})),
              tf(price, isAr ? 'السعر (اختياري)' : 'Price (optional)', number: true, ltr: true),
              DropdownButtonFormField<String>(
                initialValue: visit,
                decoration: InputDecoration(labelText: isAr ? 'نوع الخدمة' : 'Service type'),
                items: [DropdownMenuItem(value: '', child: Text(isAr ? 'بدون' : 'None')), DropdownMenuItem(value: 'CONSULTATION', child: Text(isAr ? 'كشف' : 'Consultation')), DropdownMenuItem(value: 'FOLLOW_UP', child: Text(isAr ? 'إعادة' : 'Follow-up'))],
                onChanged: (v) => set(() => visit = v ?? ''),
              ),
            ],
        onSave: () async { await ctx.api.post('clinic/services', {'name': name.text.trim(), 'durationMinutes': int.parse(dur.text.trim()), if (price.text.trim().isNotEmpty) 'priceMinor': toMinor(price.text), if (visit.isNotEmpty) 'visitType': visit}); })) {
      await reload();
    }
  }

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
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
            if (canEdit) FilledButton.icon(onPressed: () => _add(context, reload), icon: const Icon(Icons.add_rounded), label: Text(s.ar ? 'خدمة جديدة' : 'New service')),
            const SizedBox(height: 8),
            for (var i = 0; i < items.length; i++)
              FadeSlideIn(index: i, child: Card(child: SwitchListTile(
                value: items[i]['isActive'] == true,
                onChanged: canEdit ? (v) async { if (await run(context, () => ctx.api.put('clinic/services/${items[i]['id']}/active', {'active': v}))) await reload(); } : null,
                title: Text('${items[i]['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text('${items[i]['durationMinutes']} ${s.ar ? 'دقيقة' : 'min'}${items[i]['priceMinor'] != null ? ' · ${s.money(items[i]['priceMinor'] as num, (items[i]['currency'] as String?) ?? ctx.currency)}' : ''}${items[i]['visitType'] == 'FOLLOW_UP' ? (s.ar ? ' · إعادة' : ' · follow-up') : items[i]['visitType'] == 'CONSULTATION' ? (s.ar ? ' · كشف' : ' · consultation') : ''}'),
              ))),
            if (items.isEmpty) emptyState(s.empty),
          ]),
        );
      },
    );
  }
}

// ---------------------------------------------------------------- Schedule

const _isoDays = [6, 7, 1, 2, 3, 4, 5];
const _dayAr = {1: 'الاثنين', 2: 'الثلاثاء', 3: 'الأربعاء', 4: 'الخميس', 5: 'الجمعة', 6: 'السبت', 7: 'الأحد'};
const _dayEn = {1: 'Monday', 2: 'Tuesday', 3: 'Wednesday', 4: 'Thursday', 5: 'Friday', 6: 'Saturday', 7: 'Sunday'};

class ScheduleScreen extends StatefulWidget {
  const ScheduleScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<ScheduleScreen> createState() => _ScheduleScreenState();
}

class _ScheduleScreenState extends State<ScheduleScreen> {
  int _n = 0;
  Ctx get ctx => widget.ctx;

  Future<String?> _time(BuildContext c, String initial) async {
    final p = initial.split(':');
    final t = await showTimePicker(context: c, initialTime: TimeOfDay(hour: int.tryParse(p[0]) ?? 10, minute: int.tryParse(p.length > 1 ? p[1] : '0') ?? 0));
    return t == null ? null : '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
  }

  Future<void> _addHours(Map<String, dynamic> doctor, List<Map<String, dynamic>> branches) async {
    final isAr = S.of(context).ar;
    var weekday = 7, start = '10:00', end = '16:00';
    var branch = '${branches.first['id']}';
    final slot = TextEditingController(text: '30'), buffer = TextEditingController(text: '0');
    if (await formSheet(context, title: '${isAr ? 'إضافة مواعيد' : 'Add hours'} · ${doctor['displayName']}',
        body: (x, set) => [
              if (branches.length > 1) DropdownButtonFormField<String>(initialValue: branch, decoration: InputDecoration(labelText: isAr ? 'الفرع' : 'Branch'), items: [for (final b in branches) DropdownMenuItem(value: '${b['id']}', child: Text('${b['name']}'))], onChanged: (v) => set(() => branch = v ?? branch)),
              const SizedBox(height: 10),
              DropdownButtonFormField<int>(initialValue: weekday, decoration: InputDecoration(labelText: isAr ? 'اليوم' : 'Day'), items: [for (final d in _isoDays) DropdownMenuItem(value: d, child: Text(isAr ? _dayAr[d]! : _dayEn[d]!))], onChanged: (v) => set(() => weekday = v ?? weekday)),
              const SizedBox(height: 10),
              Row(children: [
                Expanded(child: OutlinedButton(onPressed: () async { final t = await _time(x, start); if (t != null) set(() => start = t); }, child: Text('${isAr ? 'من' : 'From'} $start'))),
                const SizedBox(width: 10),
                Expanded(child: OutlinedButton(onPressed: () async { final t = await _time(x, end); if (t != null) set(() => end = t); }, child: Text('${isAr ? 'إلى' : 'To'} $end'))),
              ]),
              const SizedBox(height: 10),
              Row(children: [Expanded(child: tf(slot, isAr ? 'مدة الموعد (د)' : 'Slot (min)', number: true, ltr: true)), const SizedBox(width: 10), Expanded(child: tf(buffer, isAr ? 'فاصل (د)' : 'Buffer (min)', number: true, ltr: true))]),
            ],
        onSave: () async { await ctx.api.post('clinic/schedules', {'doctorId': doctor['id'], 'branchId': branch, 'weekday': weekday, 'startTime': start, 'endTime': end, 'slotDurationMinutes': int.tryParse(slot.text.trim()) ?? 30, 'bufferMinutes': int.tryParse(buffer.text.trim()) ?? 0}); })) {
      setState(() => _n++);
    }
  }

  Future<void> _addException(List<Map<String, dynamic>> doctors, List<Map<String, dynamic>> branches) async {
    final isAr = S.of(context).ar;
    var doctor = '${doctors.first['id']}', type = 'DAY_OFF', start = '10:00', end = '14:00';
    DateTime? date;
    final reason = TextEditingController();
    const types = {'DAY_OFF': 'يوم إجازة|Day off', 'HOLIDAY': 'عطلة رسمية|Holiday', 'CUSTOM_HOURS': 'مواعيد خاصة|Special hours', 'FULL_BOOKED': 'مكتمل الحجز|Fully booked'};
    if (await formSheet(context, title: isAr ? 'إجازة أو استثناء' : 'Day off or exception', canSave: () => date != null,
        body: (x, set) => [
              if (doctors.length > 1) DropdownButtonFormField<String>(initialValue: doctor, decoration: InputDecoration(labelText: isAr ? 'الطبيب' : 'Doctor'), items: [for (final d in doctors) DropdownMenuItem(value: '${d['id']}', child: Text('${d['displayName']}'))], onChanged: (v) => set(() => doctor = v ?? doctor)),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                icon: const Icon(Icons.event_rounded),
                label: Text(date == null ? (isAr ? 'اختر التاريخ' : 'Pick the date') : '${date!.year}-${date!.month.toString().padLeft(2, '0')}-${date!.day.toString().padLeft(2, '0')}'),
                onPressed: () async { final d = await showDatePicker(context: x, initialDate: DateTime.now(), firstDate: DateTime.now(), lastDate: DateTime.now().add(const Duration(days: 730))); if (d != null) set(() => date = d); },
              ),
              const SizedBox(height: 10),
              DropdownButtonFormField<String>(initialValue: type, decoration: InputDecoration(labelText: isAr ? 'النوع' : 'Type'), items: [for (final t in types.entries) DropdownMenuItem(value: t.key, child: Text(t.value.split('|')[isAr ? 0 : 1]))], onChanged: (v) => set(() => type = v ?? type)),
              const SizedBox(height: 10),
              if (type == 'CUSTOM_HOURS') Row(children: [
                Expanded(child: OutlinedButton(onPressed: () async { final t = await _time(x, start); if (t != null) set(() => start = t); }, child: Text('${isAr ? 'من' : 'From'} $start'))),
                const SizedBox(width: 10),
                Expanded(child: OutlinedButton(onPressed: () async { final t = await _time(x, end); if (t != null) set(() => end = t); }, child: Text('${isAr ? 'إلى' : 'To'} $end'))),
              ]),
              tf(reason, isAr ? 'السبب (اختياري)' : 'Reason (optional)'),
            ],
        onSave: () async {
          final d = date!;
          await ctx.api.post('clinic/schedule-exceptions', {'doctorId': doctor, 'date': '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}', 'type': type, if (type == 'CUSTOM_HOURS') 'startTime': start, if (type == 'CUSTOM_HOURS') 'endTime': end, if (reason.text.trim().isNotEmpty) 'reason': reason.text.trim()});
        })) {
      if (mounted) toast(context, isAr ? 'تمت الإضافة' : 'Added');
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final isAr = s.ar;
    final canEdit = ctx.can('schedule.manage');
    return Async<List<dynamic>>(
      key: ValueKey(_n),
      load: () async => [await ctx.api.get('clinic/schedules'), await ctx.api.get('clinic/doctors'), await ctx.api.get('clinic/branches')],
      builder: (context, r, reload) {
        final schedules = _maps(r[0]), doctors = _maps(r[1]), branches = _maps(r[2]);
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
            Text(isAr ? 'المرضى يرون المواعيد الفارغة داخل هذه الساعات فقط.' : 'Patients only see free slots inside these hours.', style: const TextStyle(color: Color(0xFF6B7C8D))),
            const SizedBox(height: 8),
            for (final d in doctors) Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Row(children: [
                Expanded(child: Text('${d['displayName']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
                if (canEdit && branches.isNotEmpty) FilledButton.tonalIcon(style: FilledButton.styleFrom(minimumSize: const Size(0, 36)), onPressed: () => _addHours(d, branches), icon: const Icon(Icons.add_rounded, size: 18), label: Text(isAr ? 'مواعيد' : 'Hours')),
              ]),
              const SizedBox(height: 6),
              for (final day in _isoDays) Builder(builder: (_) {
                final rows = schedules.where((x) => x['doctorId'] == d['id'] && x['weekday'] == day).toList();
                return Padding(padding: const EdgeInsets.symmetric(vertical: 3), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  SizedBox(width: 80, child: Text(isAr ? _dayAr[day]! : _dayEn[day]!, style: const TextStyle(fontWeight: FontWeight.w700))),
                  Expanded(child: rows.isEmpty
                      ? Text(isAr ? 'إجازة' : 'Off', style: const TextStyle(color: Color(0xFFA0AAB6)))
                      : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          for (final x in rows) Row(children: [
                            Text('${x['startLocalTime']}–${x['endLocalTime']}  (${x['slotDurationMinutes']}m)', textDirection: TextDirection.ltr),
                            if (canEdit) IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.close_rounded, size: 18, color: Color(0xFFD14343)), onPressed: () async { if (await run(context, () => ctx.api.delete('clinic/schedules/${x['id']}'))) await reload(); }),
                          ]),
                        ])),
                ]));
              }),
            ]))),
            if (doctors.isEmpty) emptyState(s.empty),
            if (canEdit && doctors.isNotEmpty) OutlinedButton.icon(onPressed: () => _addException(doctors, branches), icon: const Icon(Icons.event_busy_rounded), label: Text(isAr ? 'إضافة إجازة أو مواعيد خاصة' : 'Add a day off or special hours')),
          ]),
        );
      },
    );
  }
}

// ---------------------------------------------------------------- Staff

class StaffScreen extends StatelessWidget {
  const StaffScreen({super.key, required this.ctx});
  final Ctx ctx;

  List<String> get _roles => ctx.type == 'STORE'
      ? const ['STORE_ADMIN', 'STORE_MANAGER', 'INVENTORY_MANAGER', 'ORDER_MANAGER', 'CUSTOMER_SUPPORT', 'REPORT_VIEWER']
      : const ['DOCTOR', 'CLINIC_ADMIN', 'RECEPTIONIST', 'NURSE', 'LAB_COORDINATOR', 'ACCOUNTANT', 'REPORT_VIEWER'];

  Future<void> _showPin(BuildContext c, String name, String pin) {
    final isAr = S.of(c).ar;
    return showDialog<void>(
      context: c,
      builder: (x) => AlertDialog(
        title: Text(isAr ? 'رمز التفعيل لـ $name' : 'Activation PIN for $name'),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          CopyRow('PIN', pin),
          const SizedBox(height: 8),
          Text(isAr ? 'يظهر مرة واحدة فقط. أعطِه للموظف ليُنشئ كلمة المرور من شاشة الدخول.' : 'Shown once. Give it to the staff member to create their password on the login screen.'),
        ]),
        actions: [TextButton(onPressed: () => Navigator.pop(x), child: Text(isAr ? 'تم' : 'Done'))],
      ),
    );
  }

  Future<void> _invite(BuildContext c, Future<void> Function() reload) async {
    final isAr = S.of(c).ar;
    final first = TextEditingController(), phone = TextEditingController(), email = TextEditingController();
    var role = _roles.first;
    String? pin;
    final ok = await formSheet(c, title: isAr ? 'إضافة موظف' : 'Add staff', canSave: () => first.text.trim().isNotEmpty && phone.text.trim().length >= 10,
        body: (x, set) => [
              tf(first, isAr ? 'الاسم' : 'Name', onChanged: (_) => set(() {})),
              tf(phone, isAr ? 'رقم الموبايل' : 'Mobile number', ltr: true, number: true, onChanged: (_) => set(() {})),
              tf(email, isAr ? 'البريد (اختياري)' : 'Email (optional)', ltr: true),
              DropdownButtonFormField<String>(initialValue: role, decoration: InputDecoration(labelText: isAr ? 'الدور' : 'Role'), items: [for (final r in _roles) DropdownMenuItem(value: r, child: Text(roleName(r, isAr)))], onChanged: (v) => set(() => role = v ?? role)),
            ],
        onSave: () async {
          final r = await ctx.api.post('tenant/members', {'firstName': first.text.trim(), 'lastName': '', 'phone': phone.text.trim(), if (email.text.trim().isNotEmpty) 'email': email.text.trim(), 'role': role}) as Map<String, dynamic>;
          pin = r['activationPin'] as String?;
        });
    if (ok) {
      await reload();
      if (pin != null && c.mounted) await _showPin(c, first.text.trim(), pin!);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final canManage = ctx.can('staff.manage');
    return Async<List<dynamic>>(
      load: () async => await ctx.api.get('tenant/members') as List<dynamic>,
      builder: (context, list, reload) {
        final items = list.cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
            if (canManage) FilledButton.icon(onPressed: () => _invite(context, reload), icon: const Icon(Icons.group_add_rounded), label: Text(s.ar ? 'إضافة موظف' : 'Add staff')),
            const SizedBox(height: 8),
            for (var i = 0; i < items.length; i++)
              FadeSlideIn(index: i, child: Card(child: ListTile(
                leading: CircleAvatar(child: Text(('${items[i]['firstName']}'.isEmpty ? '?' : '${items[i]['firstName']}'[0]).toUpperCase())),
                title: Text('${items[i]['firstName']} ${items[i]['lastName'] ?? ''}'.trim(), style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text('${'${items[i]['roles']}'.split(',').map((r) => roleName(r, s.ar)).join('، ')}\n${items[i]['phone'] ?? ''}', textAlign: TextAlign.start),
                isThreeLine: true,
                trailing: canManage && !'${items[i]['roles']}'.contains('_OWNER')
                    ? PopupMenuButton<String>(
                        onSelected: (v) async {
                          final m = items[i];
                          if (v == 'pin') {
                            String? pin;
                            if (await run(context, () async { final r = await ctx.api.post('tenant/members/${m['userId']}/activation-pin', {}) as Map<String, dynamic>; pin = r['activationPin'] as String?; }) && pin != null && context.mounted) {
                              await _showPin(context, '${m['firstName']}', pin!);
                            }
                          }
                          if (v == 'delete' && context.mounted && await confirm(context, s.ar ? 'إزالة الموظف؟' : 'Remove this staff member?') && context.mounted && await run(context, () => ctx.api.delete('tenant/members/${m['userId']}'))) await reload();
                        },
                        itemBuilder: (_) => [PopupMenuItem(value: 'pin', child: Text(s.ar ? 'رمز تفعيل جديد' : 'New activation PIN')), PopupMenuItem(value: 'delete', child: Text(s.ar ? 'إزالة' : 'Remove'))],
                      )
                    : StatusPill('${items[i]['userStatus']}'),
              ))),
            if (items.isEmpty) emptyState(s.empty),
          ]),
        );
      },
    );
  }
}
