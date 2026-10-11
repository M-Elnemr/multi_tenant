import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';

List<Map<String, dynamic>> _maps(dynamic v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

/// Create a patient (or a child under [guardian]) or edit [patient]. Returns the created/updated patient map on success.
Future<Map<String, dynamic>?> patientSheet(BuildContext c, Ctx ctx, {Map<String, dynamic>? patient, Map<String, dynamic>? guardian}) async {
  final isAr = S.of(c).ar;
  final edit = patient != null;
  final child = guardian != null || (edit && patient['guardianName'] != null);
  final name = TextEditingController(text: edit ? '${patient['firstName'] ?? ''}'.trim() : '');
  final phone = TextEditingController(text: edit ? '${patient['phone'] ?? ''}' : '');
  final years = TextEditingController(text: edit && patient['ageYears'] != null ? '${patient['ageYears']}' : '');
  final months = TextEditingController(text: edit && patient['ageMonths'] != null ? '${patient['ageMonths']}' : '0');
  final address = TextEditingController(text: edit ? '${patient['addressText'] ?? ''}' : '');
  final blood = TextEditingController(text: edit ? '${patient['bloodType'] ?? ''}' : '');
  final notes = TextEditingController(text: edit ? '${patient['notesInternal'] ?? ''}' : '');
  final password = TextEditingController();
  var sex = edit ? '${patient['sex'] ?? ''}' : '';
  Map<String, dynamic>? result;
  final ok = await formSheet(c,
      title: edit ? (isAr ? 'تعديل بيانات المريض' : 'Edit patient') : child ? (isAr ? 'إضافة طفل' : 'Add a child') : (isAr ? 'مريض جديد' : 'New patient'),
      canSave: () => name.text.trim().isNotEmpty && (int.tryParse(years.text.trim()) != null) && (child || edit || phone.text.trim().length >= 10),
      body: (x, set) => [
            tf(name, isAr ? 'الاسم' : 'Name', onChanged: (_) => set(() {})),
            if (!child) tf(phone, isAr ? 'رقم الموبايل' : 'Mobile number', ltr: true, number: true, onChanged: (_) => set(() {})),
            Row(children: [
              Expanded(child: tf(years, isAr ? 'السن (سنوات)' : 'Age (years)', number: true, ltr: true, onChanged: (_) => set(() {}))),
              const SizedBox(width: 10),
              Expanded(child: tf(months, isAr ? 'شهور' : 'Months', number: true, ltr: true)),
            ]),
            SegmentedButton<String>(
              emptySelectionAllowed: true,
              segments: [ButtonSegment(value: 'M', label: Text(isAr ? 'ذكر' : 'Male')), ButtonSegment(value: 'F', label: Text(isAr ? 'أنثى' : 'Female'))],
              selected: sex.isEmpty ? {} : {sex},
              onSelectionChanged: (s) => set(() => sex = s.isEmpty ? '' : s.first),
            ),
            const SizedBox(height: 10),
            if (edit) ...[tf(address, isAr ? 'العنوان' : 'Address'), tf(blood, isAr ? 'فصيلة الدم' : 'Blood type', ltr: true), tf(notes, isAr ? 'ملاحظات داخلية' : 'Internal notes', lines: 2)],
            if (!edit && !child) tf(password, isAr ? 'كلمة مرور للمريض (اختياري)' : 'Patient password (optional)', ltr: true, hint: isAr ? '8 أحرف على الأقل' : 'At least 8 characters'),
          ],
      onSave: () async {
        if (edit) {
          await ctx.api.patch('clinic/patients/${patient['id']}', {'firstName': name.text.trim(), 'lastName': '', if (!child) 'phone': phone.text.trim(), 'ageYears': int.parse(years.text.trim()), 'ageMonths': int.tryParse(months.text.trim()) ?? 0, if (sex.isNotEmpty) 'sex': sex, 'addressText': address.text.trim(), 'bloodType': blood.text.trim(), 'notesInternal': notes.text.trim()});
        } else {
          result = Map<String, dynamic>.from(await ctx.api.post('clinic/patients', {
            'firstName': name.text.trim(),
            if (!child) 'phone': phone.text.trim(),
            if (guardian != null) 'guardianPatientId': guardian['id'],
            if (guardian != null) 'relationship': 'PARENT',
            'ageYears': int.parse(years.text.trim()), 'ageMonths': int.tryParse(months.text.trim()) ?? 0,
            if (sex.isNotEmpty) 'sex': sex,
            if (password.text.isNotEmpty) 'initialPassword': password.text,
          }) as Map);
        }
      });
  if (!ok) return null;
  return result ?? {'updated': true};
}

Future<bool> setPatientPasswordSheet(BuildContext c, Ctx ctx, String id) {
  final isAr = S.of(c).ar;
  final pw = TextEditingController();
  return formSheet(c, title: isAr ? 'تعيين كلمة مرور للمريض' : 'Set the patient password', canSave: () => pw.text.length >= 8,
      body: (x, set) => [Text(isAr ? 'سيدخل بها المريض إلى تطبيق العيادة.' : 'The patient will use it to sign in to the app.'), const SizedBox(height: 10), tf(pw, isAr ? 'كلمة المرور (8 أحرف على الأقل)' : 'Password (min 8 characters)', ltr: true, onChanged: (_) => set(() {}))],
      onSave: () async { await ctx.api.post('clinic/patients/$id/set-password', {'password': pw.text}); });
}

/// Adds the patient to today's waiting room: pick the doctor and the type of visit.
Future<bool> walkInSheet(BuildContext c, Ctx ctx, String patientId) async {
  final isAr = S.of(c).ar;
  List<Map<String, dynamic>> docs = [], branches = [];
  try { docs = _maps(await ctx.api.get('clinic/doctors')); branches = _maps(await ctx.api.get('clinic/branches')); } catch (e) { if (c.mounted) toast(c, errorText(e)); return false; }
  if (docs.isEmpty || branches.isEmpty || !c.mounted) return false;
  var doctor = '${docs.first['id']}', visit = 'CONSULTATION';
  return formSheet(c, title: isAr ? 'إضافة لقاعة الانتظار' : 'Add to the waiting room',
      body: (x, set) => [
            if (docs.length > 1) DropdownButtonFormField<String>(initialValue: doctor, decoration: InputDecoration(labelText: isAr ? 'الطبيب' : 'Doctor'), items: [for (final d in docs) DropdownMenuItem(value: '${d['id']}', child: Text('${d['displayName']}'))], onChanged: (v) => set(() => doctor = v ?? doctor)),
            const SizedBox(height: 10),
            SegmentedButton<String>(segments: [ButtonSegment(value: 'CONSULTATION', label: Text(isAr ? 'كشف' : 'Consultation')), ButtonSegment(value: 'FOLLOW_UP', label: Text(isAr ? 'إعادة' : 'Follow-up'))], selected: {visit}, onSelectionChanged: (s) => set(() => visit = s.first)),
          ],
      onSave: () async { await ctx.api.post('clinic/appointments/walk-in', {'patientId': patientId, 'doctorId': doctor, 'branchId': branches.first['id'], 'visitType': visit}); });
}

// ---------------------------------------------------------------- Booking by reception

class BookingPage extends StatefulWidget {
  const BookingPage({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<BookingPage> createState() => _BookingPageState();
}

class _BookingPageState extends State<BookingPage> {
  Ctx get ctx => widget.ctx;
  final _q = TextEditingController();
  Timer? _t;
  List<Map<String, dynamic>> _patients = [], _doctors = [], _branches = [], _services = [];
  Map<String, dynamic>? _patient;
  String? _doctor, _branch, _service, _slot;
  DateTime _date = DateTime.now();
  List<String>? _slots;
  bool _busy = false;
  Object? _error;

  @override
  void initState() { super.initState(); _init(); }

  @override
  void dispose() { _t?.cancel(); _q.dispose(); super.dispose(); }

  Future<void> _init() async {
    try {
      final r = await Future.wait([ctx.api.get('clinic/doctors'), ctx.api.get('clinic/branches'), ctx.api.get('clinic/services')]);
      _doctors = _maps(r[0]); _branches = _maps(r[1]); _services = _maps(r[2]).where((s) => s['isActive'] == true).toList();
      _doctor = _doctors.isEmpty ? null : '${_doctors.first['id']}';
      _branch = _branches.isEmpty ? null : '${_branches.first['id']}';
      _service = _services.isEmpty ? null : '${_services.first['id']}';
      if (mounted) setState(() {});
      await _loadSlots();
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  String get _day => '${_date.year}-${_date.month.toString().padLeft(2, '0')}-${_date.day.toString().padLeft(2, '0')}';

  Future<void> _loadSlots() async {
    if (_doctor == null || _branch == null || _service == null) return;
    setState(() { _slots = null; _slot = null; });
    try {
      final r = await ctx.api.get('clinic/appointments/slots', query: {'doctorId': _doctor, 'branchId': _branch, 'serviceId': _service, 'date': _day});
      if (mounted) setState(() => _slots = (r as List).cast<String>());
    } catch (_) { if (mounted) setState(() => _slots = []); }
  }

  void _search(String v) {
    _t?.cancel();
    _t = Timer(const Duration(milliseconds: 300), () async {
      if (v.trim().length < 2) { if (mounted) setState(() => _patients = []); return; }
      try {
        final r = await ctx.api.get('clinic/patients', query: {'q': v.trim(), 'pageSize': '8'}) as Map;
        if (mounted) setState(() => _patients = _maps(r['data']));
      } catch (_) {}
    });
  }

  Future<void> _newPatient() async {
    final r = await patientSheet(context, ctx);
    if (r != null && r['id'] != null && mounted) setState(() { _patient = r; _patients = []; });
  }

  Future<void> _book() async {
    setState(() => _busy = true);
    final service = _services.firstWhere((s) => '${s['id']}' == _service);
    final ok = await run(context, () => ctx.api.post('clinic/appointments', {'patientId': _patient!['id'], 'doctorId': _doctor, 'branchId': _branch, 'serviceId': _service, if (service['visitType'] != null) 'visitType': service['visitType'], 'startAt': _slot, 'source': 'RECEPTION'}));
    if (mounted) setState(() => _busy = false);
    if (ok && mounted) { toast(context, S.of(context).ar ? 'تم الحجز' : 'Booked'); Navigator.of(context).pop(true); }
  }

  String _slotLabel(String iso) => fmtDate(iso).split('  ').last;

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    return Scaffold(
      appBar: AppBar(title: Text(isAr ? 'حجز جديد' : 'New booking')),
      body: _error != null ? Center(child: Text(errorText(_error!))) : ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 100), children: [
        sectionTitle(isAr ? 'المريض' : 'Patient'),
        if (_patient != null) Card(child: ListTile(leading: const Icon(Icons.person_rounded), title: Text('${_patient!['firstName']} ${_patient!['lastName'] ?? ''}'.trim(), style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text('${_patient!['patientCode'] ?? ''}'), trailing: IconButton(icon: const Icon(Icons.close_rounded), onPressed: () => setState(() => _patient = null))))
        else ...[
          TextField(controller: _q, onChanged: _search, decoration: InputDecoration(prefixIcon: const Icon(Icons.search_rounded), hintText: isAr ? 'ابحث بالاسم أو الهاتف أو الكود' : 'Name, phone or code')),
          for (final p in _patients) ListTile(
            title: Text('${p['firstName']} ${p['lastName'] ?? ''}'.trim()),
            subtitle: Text('${p['patientCode'] ?? ''} ${p['phone'] ?? ''}'.trim()),
            onTap: () => setState(() { _patient = p; _patients = []; }),
          ),
          if (ctx.can('patient.create')) TextButton.icon(onPressed: _newPatient, icon: const Icon(Icons.person_add_alt_1_rounded), label: Text(isAr ? 'مريض جديد' : 'New patient')),
        ],
        sectionTitle(isAr ? 'الموعد' : 'Appointment'),
        if (_doctors.length > 1) DropdownButtonFormField<String>(initialValue: _doctor, decoration: InputDecoration(labelText: isAr ? 'الطبيب' : 'Doctor'), items: [for (final d in _doctors) DropdownMenuItem(value: '${d['id']}', child: Text('${d['displayName']}'))], onChanged: (v) { setState(() => _doctor = v); _loadSlots(); }),
        const SizedBox(height: 10),
        if (_branches.length > 1) ...[DropdownButtonFormField<String>(initialValue: _branch, decoration: InputDecoration(labelText: isAr ? 'الفرع' : 'Branch'), items: [for (final b in _branches) DropdownMenuItem(value: '${b['id']}', child: Text('${b['name']}'))], onChanged: (v) { setState(() => _branch = v); _loadSlots(); }), const SizedBox(height: 10)],
        if (_services.isNotEmpty) DropdownButtonFormField<String>(initialValue: _service, decoration: InputDecoration(labelText: isAr ? 'الخدمة' : 'Service'), items: [for (final s in _services) DropdownMenuItem(value: '${s['id']}', child: Text('${s['name']}'))], onChanged: (v) { setState(() => _service = v); _loadSlots(); }),
        const SizedBox(height: 10),
        OutlinedButton.icon(
          icon: const Icon(Icons.event_rounded),
          label: Text(_day),
          onPressed: () async { final d = await showDatePicker(context: context, initialDate: _date, firstDate: DateTime.now(), lastDate: DateTime.now().add(const Duration(days: 365))); if (d != null) { setState(() => _date = d); _loadSlots(); } },
        ),
        sectionTitle(isAr ? 'المواعيد المتاحة' : 'Free slots'),
        if (_slots == null) const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator()))
        else if (_slots!.isEmpty) Text(isAr ? 'لا توجد مواعيد متاحة في هذا اليوم.' : 'No free slots on this day.', style: const TextStyle(color: Color(0xFF6B7C8D)))
        else Wrap(spacing: 8, runSpacing: 8, children: [
          for (final s in _slots!) ChoiceChip(
            label: Text(_slotLabel(s)),
            selected: _slot == s,
            selectedColor: Theme.of(context).colorScheme.primary,
            backgroundColor: Colors.white,
            side: BorderSide(color: _slot == s ? Theme.of(context).colorScheme.primary : const Color(0xFFB6C2CF)),
            labelStyle: TextStyle(fontWeight: FontWeight.w800, color: _slot == s ? Colors.white : const Color(0xFF1F2A37)),
            showCheckmark: false,
            onSelected: (_) => setState(() => _slot = s),
          ),
        ]),
      ]),
      bottomNavigationBar: SafeArea(child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 16, 10),
        child: FilledButton(onPressed: _busy || _patient == null || _slot == null ? null : _book, child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(isAr ? 'تأكيد الحجز' : 'Book')),
      )),
    );
  }
}
