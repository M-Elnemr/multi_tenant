import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' hide TextDirection;
import 'package:platform_core/platform_core.dart';

void main() => runApp(const PatientApp());

class PatientApp extends StatelessWidget {
  const PatientApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Patient',
        theme: themeFor(null),
        localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
        supportedLocales: const [Locale('ar'), Locale('en')],
        home: const PlatformGate(),
      );
}

/// One sign-in for every doctor: the access token is user-wide, and the server decides per clinic host what it may see,
/// so the same session is reused against each clinic's own address and the clinics' data never mix.
class PlatformGate extends StatefulWidget {
  const PlatformGate({super.key});
  @override
  State<PlatformGate> createState() => _PlatformGateState();
}

class _PlatformGateState extends State<PlatformGate> {
  final session = Session('patient-app');
  late final ApiClient platform = ApiClient(baseUrl: platformBase(), session: session);
  bool? loggedIn;

  @override
  void initState() {
    super.initState();
    session.loggedIn.then((v) {
      if (mounted) setState(() => loggedIn = v);
    });
  }

  @override
  Widget build(BuildContext context) {
    if (loggedIn == null) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    if (loggedIn == false) return LoginScreen(api: platform, title: S.of(context).login, onLoggedIn: (_) => setState(() => loggedIn = true));
    return MyClinics(platform: platform, session: session, onLogout: () => setState(() => loggedIn = false));
  }
}

class MyClinics extends StatelessWidget {
  const MyClinics({super.key, required this.platform, required this.session, required this.onLogout});
  final ApiClient platform;
  final Session session;
  final VoidCallback onLogout;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(s.myClinics), actions: [
        IconButton(
          tooltip: s.logout,
          icon: const Icon(Icons.logout),
          onPressed: () async {
            await AuthRepository(platform).logout();
            onLogout();
          },
        ),
      ]),
      body: Async<List<dynamic>>(
        load: () async => await platform.get('me/tenants') as List<dynamic>,
        builder: (context, all, reload) {
          final clinics = all.cast<Map<String, dynamic>>().where((t) => t['type'] == 'CLINIC' && t['host'] != null).toList();
          if (clinics.isEmpty) return Center(child: Text(s.empty));
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(children: [
              for (final c in clinics)
                Card(
                  margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: ListTile(
                    leading: const Icon(Icons.local_hospital_outlined),
                    title: Text(c['name'] as String),
                    subtitle: Text(c['host'] as String, textDirection: TextDirection.ltr),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => ClinicScreen(name: c['name'] as String, api: ApiClient(baseUrl: baseForHost(c['host'] as String), session: session), onLeft: reload),
                    )),
                  ),
                ),
            ]),
          );
        },
      ),
    );
  }
}

/// One clinic. Everything here is requested from this clinic's own address, so it contains only this clinic's data.
class ClinicScreen extends StatefulWidget {
  const ClinicScreen({super.key, required this.name, required this.api, required this.onLeft});
  final String name;
  final ApiClient api;
  final Future<void> Function() onLeft;
  @override
  State<ClinicScreen> createState() => _ClinicScreenState();
}

class _ClinicScreenState extends State<ClinicScreen> {
  int tab = 0;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final pages = [AppointmentsPage(api: widget.api), RecordPage(api: widget.api), AccountPage(api: widget.api, onLeft: () async { await widget.onLeft(); if (context.mounted) Navigator.of(context).pop(); })];
    return Scaffold(
      appBar: AppBar(title: Text(widget.name)),
      body: pages[tab],
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.event_outlined), label: s.appointments),
          NavigationDestination(icon: const Icon(Icons.folder_shared_outlined), label: s.record),
          NavigationDestination(icon: const Icon(Icons.person_outline), label: s.account),
        ],
      ),
    );
  }
}

String _when(String iso) => DateFormat.yMMMd().add_jm().format(DateTime.parse(iso).toLocal());

class AppointmentsPage extends StatelessWidget {
  const AppointmentsPage({super.key, required this.api});
  final ApiClient api;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      load: () async => {
        'appointments': await api.get('portal/appointments', query: {'pageSize': '50'}),
        'queue': await api.get('portal/queue'),
      },
      builder: (context, d, reload) {
        final items = ((d['appointments'] as Map)['data'] as List).cast<Map<String, dynamic>>();
        final queue = (d['queue'] as List).cast<Map<String, dynamic>>();
        return Scaffold(
          floatingActionButton: FloatingActionButton.extended(
            icon: const Icon(Icons.add),
            label: Text(s.ar ? 'حجز موعد' : 'Book'),
            onPressed: () async {
              final booked = await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => BookScreen(api: api)));
              if (booked == true) await reload();
            },
          ),
          body: RefreshIndicator(
          onRefresh: reload,
          child: ListView(children: [
            for (final q in queue)
              Card(
                color: q['called'] == true ? Colors.green.shade50 : null,
                margin: const EdgeInsets.all(16),
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(children: [
                    Text('${q['doctorName']} · ${q['serviceName']}'),
                    const SizedBox(height: 8),
                    if (q['called'] == true)
                      Text(s.queueTurn, style: Theme.of(context).textTheme.headlineSmall)
                    else ...[
                      Text('#${q['queueNumber']}', style: Theme.of(context).textTheme.displaySmall),
                      Text((q['aheadOfYou'] as num) == 0 ? s.queueNext : s.queueAhead((q['aheadOfYou'] as num).toInt())),
                    ],
                  ]),
                ),
              ),
            if (items.isEmpty && queue.isEmpty) Padding(padding: const EdgeInsets.all(48), child: Center(child: Text(s.empty))),
            for (final a in items)
              ListTile(
                title: Text(_when(a['startAt'] as String)),
                subtitle: Text('${a['serviceName']} · ${a['doctorName']}\n${a['status']}'),
                isThreeLine: true,
                trailing: ['REQUESTED', 'PENDING_CONFIRMATION', 'CONFIRMED'].contains(a['status'])
                    ? TextButton(
                        onPressed: () async {
                          try {
                            await api.post('portal/appointments/${a['id']}/cancel', {});
                            await reload();
                          } catch (e) {
                            if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(errorText(e))));
                          }
                        },
                        child: Text(s.ar ? 'إلغاء' : 'Cancel'),
                      )
                    : null,
              ),
          ]),
        ),
        );
      },
    );
  }
}

/// Book with this clinic. Only free slots are listed and the server re-checks on confirm; payment is cash at the clinic.
class BookScreen extends StatefulWidget {
  const BookScreen({super.key, required this.api});
  final ApiClient api;
  @override
  State<BookScreen> createState() => _BookScreenState();
}

class _BookScreenState extends State<BookScreen> {
  Map<String, dynamic>? profile;
  String? patientId, serviceId, doctorId, branchId, slot, error;
  late DateTime day;
  List<String> slots = [];
  List<int> weekdays = const [];   // ISO weekdays the doctor works (1 = Mon ... 7 = Sun); empty until loaded
  bool loadingSlots = false, saving = false, loadedDays = false;
  final note = TextEditingController();

  @override
  void initState() {
    super.initState();
    final n = DateTime.now().add(const Duration(days: 1));
    day = DateTime(n.year, n.month, n.day);
    _init();
  }

  Future<void> _init() async {
    try {
      final p = await widget.api.get('clinic/public/profile') as Map<String, dynamic>;
      final pts = (await widget.api.get('portal/patients') as List).cast<Map<String, dynamic>>();
      String? first(String k) => (p[k] as List).isEmpty ? null : (p[k] as List).first['id'] as String;
      if (!mounted) return;
      setState(() {
        profile = p;
        patientId = pts.isEmpty ? null : pts.first['id'] as String;
        serviceId = first('services');
        doctorId = first('doctors');
        branchId = first('branches');
      });
      await _loadSlots();
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    }
  }

  bool _works(DateTime d) => !loadedDays || weekdays.contains(d.weekday);

  Future<void> _loadDays() async {
    if (doctorId == null || branchId == null) return;
    try {
      final r = await widget.api.get('clinic/public/working-days', query: {'doctorId': doctorId!, 'branchId': branchId!}) as Map<String, dynamic>;
      weekdays = (r['weekdays'] as List).cast<num>().map((e) => e.toInt()).toList();
      loadedDays = true;
      if (!_works(day)) {
        for (var i = 1; i <= 14; i++) {
          final d = DateTime.now().add(Duration(days: i));
          if (_works(d)) { day = DateTime(d.year, d.month, d.day); break; }
        }
      }
    } catch (_) {
      loadedDays = false;   // fall back to showing every day; the server still returns no slots on days off
    }
  }

  Future<void> _loadSlots() async {
    if (serviceId == null || doctorId == null || branchId == null) return;
    await _loadDays();
    if (!mounted) return;
    setState(() { loadingSlots = true; slot = null; });
    try {
      final d = '${day.year.toString().padLeft(4, '0')}-${day.month.toString().padLeft(2, '0')}-${day.day.toString().padLeft(2, '0')}';
      final r = await widget.api.get('clinic/public/slots', query: {'doctorId': doctorId!, 'branchId': branchId!, 'serviceId': serviceId!, 'date': d}) as List;
      if (mounted) setState(() { slots = r.cast<String>(); error = null; });
    } catch (e) {
      if (mounted) setState(() { slots = []; error = errorText(e); });
    } finally {
      if (mounted) setState(() => loadingSlots = false);
    }
  }

  Future<void> _book() async {
    setState(() { saving = true; error = null; });
    try {
      await widget.api.post('portal/appointments', {
        'patientId': patientId, 'doctorId': doctorId, 'branchId': branchId, 'serviceId': serviceId, 'startAt': slot,
        'paymentMethod': 'CASH_AT_CLINIC', if (note.text.trim().isNotEmpty) 'patientNote': note.text.trim(),
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (mounted) setState(() { error = errorText(e); });
      await _loadSlots();   // the slot may have just been taken
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  Widget _drop(String label, String? value, List items, String Function(Map<String, dynamic>) text, void Function(String?) onChanged) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: DropdownButtonFormField<String>(
          initialValue: value,
          decoration: InputDecoration(labelText: label),
          items: [for (final i in items.cast<Map<String, dynamic>>()) DropdownMenuItem(value: i['id'] as String, child: Text(text(i)))],
          onChanged: onChanged,
        ),
      );

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final p = profile;
    final title = s.ar ? 'حجز موعد' : 'Book an appointment';
    if (p == null) return Scaffold(appBar: AppBar(title: Text(title)), body: Center(child: error == null ? const CircularProgressIndicator() : Text(error!)));
    if (p['bookingEnabled'] == false || p['cashEnabled'] == false) return Scaffold(appBar: AppBar(title: Text(title)), body: Center(child: Text(s.ar ? 'الحجز غير متاح حاليًا' : 'Booking is not available right now')));
    final days = [for (var i = 0; i < 14; i++) DateTime.now().add(Duration(days: i + 1))];
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        _drop(s.ar ? 'الخدمة' : 'Service', serviceId, p['services'] as List, (i) => '${i['name']}', (v) { setState(() => serviceId = v); _loadSlots(); }),
        _drop(s.ar ? 'الطبيب' : 'Doctor', doctorId, p['doctors'] as List, (i) => '${i['displayName']}', (v) { setState(() => doctorId = v); _loadSlots(); }),
        if ((p['branches'] as List).length > 1) _drop(s.ar ? 'الفرع' : 'Branch', branchId, p['branches'] as List, (i) => '${i['name']}', (v) { setState(() => branchId = v); _loadSlots(); }),
        SizedBox(
          height: 56,
          child: ListView(scrollDirection: Axis.horizontal, children: [
            for (final d in days)
              Padding(
                padding: const EdgeInsetsDirectional.only(end: 8),
                child: ChoiceChip(
                  label: Text(DateFormat.MMMEd().format(d)),
                  selected: d.year == day.year && d.month == day.month && d.day == day.day,
                  onSelected: _works(d) ? (_) { setState(() => day = DateTime(d.year, d.month, d.day)); _loadSlots(); } : null,
                ),
              ),
          ]),
        ),
        const SizedBox(height: 8),
        if (loadingSlots) const Center(child: CircularProgressIndicator())
        else if (slots.isEmpty) Padding(padding: const EdgeInsets.all(16), child: Center(child: Text(loadedDays && weekdays.isEmpty ? (s.ar ? 'لم يحدد الطبيب مواعيد عمله بعد' : 'This doctor has not set working hours yet') : (s.ar ? 'لا توجد مواعيد متاحة في هذا اليوم' : 'No free times left on this day'))))
        else Wrap(spacing: 8, runSpacing: 8, children: [
          for (final t in slots) ChoiceChip(label: Text(DateFormat.jm().format(DateTime.parse(t).toLocal())), selected: slot == t, onSelected: (_) => setState(() => slot = t)),
        ]),
        const SizedBox(height: 12),
        TextField(controller: note, decoration: InputDecoration(labelText: s.ar ? 'ملاحظة (اختياري)' : 'Note (optional)')),
        const SizedBox(height: 8),
        Text(s.ar ? 'الدفع نقدًا في العيادة' : 'Pay cash at the clinic', style: Theme.of(context).textTheme.bodySmall),
        if (error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
        const SizedBox(height: 12),
        FilledButton(onPressed: slot == null || patientId == null || saving ? null : _book, child: Text(s.ar ? 'تأكيد الحجز' : 'Confirm booking')),
      ]),
    );
  }
}

/// Shows only what this clinic shared with the patient (the server filters; internal notes never reach this app).
class RecordPage extends StatelessWidget {
  const RecordPage({super.key, required this.api});
  final ApiClient api;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      load: () async {
        final patients = (await api.get('portal/patients') as List).cast<Map<String, dynamic>>();
        if (patients.isEmpty) return <String, dynamic>{};
        return await api.get('portal/patients/${patients.first['id']}/timeline') as Map<String, dynamic>;
      },
      builder: (context, t, reload) {
        if (t.isEmpty) return Center(child: Text(s.empty));
        final rx = (t['prescriptions'] as List).cast<Map<String, dynamic>>();
        final labs = (t['labOrders'] as List).cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            Text(s.ar ? 'الروشتات' : 'Prescriptions', style: Theme.of(context).textTheme.titleMedium),
            for (final p in rx)
              Card(
                child: ListTile(
                  title: Text(p['issuedAt'] == null ? '' : _when(p['issuedAt'] as String)),
                  subtitle: Text([for (final i in (p['items'] as List)) '${i['medicationName']} ${i['dosage'] ?? ''} ${i['frequency'] ?? ''}'.trim()].join('\n')),
                ),
              ),
            if (rx.isEmpty) Text(s.empty),
            const SizedBox(height: 16),
            Text(s.ar ? 'التحاليل' : 'Lab tests', style: Theme.of(context).textTheme.titleMedium),
            for (final l in labs)
              Card(
                child: ListTile(
                  title: Text(l['testName'] as String),
                  subtitle: Text([l['status'] as String, for (final r in (l['results'] as List)) (r['resultText'] ?? '') as String].where((x) => x.isNotEmpty).join('\n')),
                ),
              ),
            if (labs.isEmpty) Text(s.empty),
          ]),
        );
      },
    );
  }
}

class AccountPage extends StatefulWidget {
  const AccountPage({super.key, required this.api, required this.onLeft});
  final ApiClient api;
  final Future<void> Function() onLeft;
  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage> {
  final cur = TextEditingController(), next = TextEditingController();
  String? message;
  bool error = false;

  Future<void> _change() async {
    try {
      await AuthRepository(widget.api).changePassword(cur.text, next.text);
      cur.clear();
      next.clear();
      setState(() { message = S.of(context).passwordChanged; error = false; });
    } catch (e) {
      setState(() { message = errorText(e); error = true; });
    }
  }

  Future<void> _leave() async {
    try {
      await widget.api.post('portal/leave', {});
      await widget.onLeft();
    } catch (e) {
      setState(() { message = errorText(e); error = true; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return ListView(padding: const EdgeInsets.all(16), children: [
      Text(s.changePassword, style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 12),
      TextField(controller: cur, obscureText: true, decoration: InputDecoration(labelText: s.currentPassword)),
      const SizedBox(height: 12),
      TextField(controller: next, obscureText: true, decoration: InputDecoration(labelText: s.newPassword)),
      if (message != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(message!, style: TextStyle(color: error ? Theme.of(context).colorScheme.error : Colors.green.shade700))),
      const SizedBox(height: 12),
      FilledButton(onPressed: _change, child: Text(s.changePassword)),
      const SizedBox(height: 32),
      OutlinedButton(onPressed: _leave, child: Text(s.leaveClinic)),
    ]);
  }
}
