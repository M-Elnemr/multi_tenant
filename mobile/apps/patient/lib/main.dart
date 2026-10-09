import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart' hide TextDirection;
import 'package:platform_core/platform_core.dart';
import 'package:url_launcher/url_launcher.dart';

import 'push.dart';

void main() => runApp(const PatientApp());

class PatientApp extends StatelessWidget {
  const PatientApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Elmanassa',
        scaffoldMessengerKey: messengerKey,
        theme: themeFor(null),
        localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
        supportedLocales: const [Locale('ar'), Locale('en')],
        home: const PlatformGate(),
      );
}

/// Patients cannot register themselves: the clinic gives them their mobile number and a temporary password.
/// One sign-in covers every clinic the patient belongs to; the server decides per clinic address what the token may see,
/// so each clinic's data stays separate.
class PlatformGate extends StatefulWidget {
  const PlatformGate({super.key});
  @override
  State<PlatformGate> createState() => _PlatformGateState();
}

class _PlatformGateState extends State<PlatformGate> {
  final session = Session('patient-app');
  late final ApiClient platform = ApiClient(baseUrl: platformBase(), session: session);
  bool? loggedIn;
  bool mustChange = false;

  @override
  void initState() {
    super.initState();
    session.loggedIn.then((v) async {
      if (v) await _checkMe();
      if (mounted) setState(() => loggedIn = v);
    });
  }

  Future<void> _checkMe() async {
    try {
      final me = await AuthRepository(platform).me();
      mustChange = me['mustChangePassword'] == true;
    } catch (_) {
      mustChange = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (loggedIn == null) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    if (loggedIn == false) {
      return LoginScreen(api: platform, title: S.of(context).login, onLoggedIn: (u) => setState(() { mustChange = u['mustChangePassword'] == true; loggedIn = true; }));
    }
    if (mustChange) return ChangeTempPassword(api: platform, onDone: () => setState(() => mustChange = false));
    return MyClinics(platform: platform, session: session, onLogout: () => setState(() => loggedIn = false));
  }
}

/// First sign-in with the temporary password: the patient must choose their own before anything else.
class ChangeTempPassword extends StatefulWidget {
  const ChangeTempPassword({super.key, required this.api, required this.onDone});
  final ApiClient api;
  final VoidCallback onDone;
  @override
  State<ChangeTempPassword> createState() => _ChangeTempPasswordState();
}

class _ChangeTempPasswordState extends State<ChangeTempPassword> {
  final cur = TextEditingController(), next = TextEditingController();
  String? error;
  bool busy = false;

  Future<void> _save() async {
    setState(() { busy = true; error = null; });
    try {
      await AuthRepository(widget.api).changePassword(cur.text, next.text);
      widget.onDone();
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(s.changePassword)),
      body: ListView(padding: const EdgeInsets.all(24), children: [
        const FadeSlideIn(child: Center(child: BrandLogo(size: 90))),
        const SizedBox(height: 16),
        Text(s.ar ? 'لأمانك، اختر كلمة مرور خاصة بك قبل المتابعة.' : 'For your security, choose your own password before you continue.'),
        const SizedBox(height: 16),
        TextField(controller: cur, obscureText: true, decoration: InputDecoration(labelText: s.ar ? 'كلمة المرور المؤقتة' : 'Temporary password')),
        const SizedBox(height: 12),
        TextField(controller: next, obscureText: true, decoration: InputDecoration(labelText: s.newPassword)),
        if (error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
        const SizedBox(height: 16),
        FilledButton(onPressed: busy ? null : _save, child: Text(s.changePassword)),
      ]),
    );
  }
}

class MyClinics extends StatefulWidget {
  const MyClinics({super.key, required this.platform, required this.session, required this.onLogout});
  final ApiClient platform;
  final Session session;
  final VoidCallback onLogout;
  @override
  State<MyClinics> createState() => _MyClinicsState();
}

class _MyClinicsState extends State<MyClinics> {
  bool pushAsked = false;
  ApiClient? pushApi;
  String? pushToken;

  /// The phone's push token is registered through one of the patient's clinics (the account is the same everywhere).
  void _startPush(List<Map<String, dynamic>> clinics) {
    if (pushAsked || clinics.isEmpty) return;
    pushAsked = true;
    final api = ApiClient(baseUrl: baseForHost(clinics.first['host'] as String), session: widget.session);
    pushApi = api;
    PushService.start((token) async {
      pushToken = token;
      try {
        await api.post('portal/devices', {'token': token, 'platform': 'android'});
      } catch (_) {}
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(s.myClinics), actions: [
        IconButton(
          tooltip: s.logout,
          icon: const Icon(Icons.logout),
          onPressed: () async {
            // this phone must stop receiving the previous patient's calls
            if (pushApi != null && pushToken != null) {
              try { await pushApi!.delete('portal/devices', {'token': pushToken}); } catch (_) {}
            }
            await AuthRepository(widget.platform).logout();
            widget.onLogout();
          },
        ),
      ]),
      body: Async<List<dynamic>>(
        load: () async => await widget.platform.get('me/tenants') as List<dynamic>,
        builder: (context, all, reload) {
          final clinics = all.cast<Map<String, dynamic>>().where((t) => t['type'] == 'CLINIC' && t['host'] != null).toList();
          WidgetsBinding.instance.addPostFrameCallback((_) => _startPush(clinics));
          if (clinics.isEmpty) return Center(child: Padding(padding: const EdgeInsets.all(32), child: Text(s.ar ? 'لا توجد عيادة مسجّل بها هذا الحساب بعد. العيادة هي من تضيفك.' : 'No clinic has added you yet. Your clinic registers you.', textAlign: TextAlign.center)));
          // One clinic: go straight in.
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
                      builder: (_) => ClinicScreen(name: c['name'] as String, api: ApiClient(baseUrl: baseForHost(c['host'] as String), session: widget.session), onLeft: reload),
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
    final pages = [
      QueuePage(api: widget.api),
      RecordPage(api: widget.api),
      ClinicInfoPage(api: widget.api),
      AccountPage(api: widget.api, onLeft: () async { await widget.onLeft(); if (context.mounted) Navigator.of(context).pop(); }),
    ];
    return Scaffold(
      appBar: AppBar(title: Text(widget.name)),
      body: pages[tab],
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.groups_outlined), label: s.ar ? 'الدور' : 'Queue'),
          NavigationDestination(icon: const Icon(Icons.folder_shared_outlined), label: s.record),
          NavigationDestination(icon: const Icon(Icons.local_hospital_outlined), label: s.ar ? 'العيادة' : 'Clinic'),
          NavigationDestination(icon: const Icon(Icons.person_outline), label: s.account),
        ],
      ),
    );
  }
}

String _when(String iso) => DateFormat.yMMMd().add_jm().format(DateTime.parse(iso).toLocal());

Future<void> _dial(String phone) async => launchUrl(Uri(scheme: 'tel', path: phone));

/// Visit type chip (كشف / إعادة).
Widget _visitChip(BuildContext context, String? type) {
  if (type == null) return const SizedBox.shrink();
  final ar = S.of(context).ar;
  final follow = type == 'FOLLOW_UP';
  return Chip(
    visualDensity: VisualDensity.compact,
    label: Text(follow ? (ar ? 'إعادة' : 'Follow-up') : (ar ? 'كشف' : 'Consultation')),
    backgroundColor: follow ? Colors.amber.shade50 : Colors.blue.shade50,
  );
}

/// My place in line (refreshes by itself every few seconds without flicker), then my visits here. Online booking is not available yet.
class QueuePage extends StatefulWidget {
  const QueuePage({super.key, required this.api});
  final ApiClient api;
  @override
  State<QueuePage> createState() => _QueuePageState();
}

class _QueuePageState extends State<QueuePage> {
  Timer? timer;
  Map<String, dynamic>? data;
  Object? error;

  @override
  void initState() {
    super.initState();
    _load();
    timer = Timer.periodic(const Duration(seconds: 10), (_) => _load());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final d = {
        'appointments': await widget.api.get('portal/appointments', query: {'pageSize': '30'}),
        'queue': await widget.api.get('portal/queue'),
      };
      if (mounted) setState(() { data = d; error = null; });
    } catch (e) {
      if (mounted) setState(() => error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final d = data;
    if (d == null) {
      if (error != null) return Center(child: Column(mainAxisSize: MainAxisSize.min, children: [Text(errorText(error!)), TextButton(onPressed: _load, child: Text(s.retry))]));
      return const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(), SizedBox(height: 12), Skeleton()]));
    }
    final items = ((d['appointments'] as Map)['data'] as List).cast<Map<String, dynamic>>();
    final queue = (d['queue'] as List).cast<Map<String, dynamic>>();
    return RefreshIndicator(
      onRefresh: _load,
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
        if (queue.isEmpty)
          Padding(padding: const EdgeInsets.all(24), child: Center(child: Text(s.ar ? 'لست في الدور الآن. عند حضورك للعيادة سيضيفك الاستقبال وسترى دورك هنا.' : 'You are not in the queue right now. When you arrive, the reception adds you and your place shows here.', textAlign: TextAlign.center))),
        if (items.isNotEmpty) Padding(padding: const EdgeInsets.fromLTRB(16, 8, 16, 0), child: Text(s.ar ? 'زياراتي' : 'My visits', style: Theme.of(context).textTheme.titleMedium)),
        for (final a in items)
          ListTile(
            title: Text(_when(a['startAt'] as String)),
            subtitle: Text('${a['serviceName']} · ${a['doctorName']}'),
            trailing: _visitChip(context, a['visitType'] as String?),
          ),
      ]),
    );
  }
}

/// Where the clinic is, how to call it and the doctor, and how many people are waiting now.
class ClinicInfoPage extends StatelessWidget {
  const ClinicInfoPage({super.key, required this.api});
  final ApiClient api;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      load: () async => await api.get('clinic/public/profile') as Map<String, dynamic>,
      builder: (context, p, reload) {
        final doctors = (p['doctors'] as List).cast<Map<String, dynamic>>();
        Widget row(IconData icon, String label, String? value, {String? phone}) => ListTile(
              leading: Icon(icon),
              title: Text(label),
              subtitle: Text(value == null || value.isEmpty ? '-' : value, textDirection: phone != null ? TextDirection.ltr : null),
              trailing: phone == null ? null : IconButton(icon: const Icon(Icons.call), onPressed: () => _dial(phone)),
            );
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            Card(child: Column(children: [
              row(Icons.groups_outlined, s.ar ? 'في الانتظار الآن' : 'Waiting now', '${p['queueCount'] ?? 0}'),
              row(Icons.place_outlined, s.ar ? 'العنوان' : 'Address', p['addressText'] as String?),
              row(Icons.phone_outlined, s.ar ? 'هاتف العيادة' : 'Clinic phone', p['phone'] as String?, phone: p['phone'] as String?),
            ])),
            const SizedBox(height: 8),
            Text(s.ar ? 'الأطباء' : 'Doctors', style: Theme.of(context).textTheme.titleMedium),
            for (final d in doctors)
              Card(child: ListTile(
                leading: const CircleAvatar(child: Icon(Icons.medical_services_outlined)),
                title: Text(d['displayName'] as String),
                subtitle: Text([(d['specialties'] as List).map((x) => s.ar ? x['nameAr'] : x['nameEn']).join(' · '), d['publicPhone'] ?? ''].where((x) => '$x'.isNotEmpty).join('\n')),
                isThreeLine: d['publicPhone'] != null,
                trailing: d['publicPhone'] == null ? null : IconButton(icon: const Icon(Icons.call), onPressed: () => _dial(d['publicPhone'] as String)),
              )),
          ]),
        );
      },
    );
  }
}

/// Shows only what this clinic shared with the patient (the server filters; internal notes never reach this app).
class RecordPage extends StatefulWidget {
  const RecordPage({super.key, required this.api});
  final ApiClient api;
  @override
  State<RecordPage> createState() => _RecordPageState();
}

class _RecordPageState extends State<RecordPage> {
  int version = 0;
  String? patientId;

  Future<void> _markDone(Map<String, dynamic> lab) async {
    try {
      await widget.api.post('portal/lab-orders/${lab['id']}/done', {});
      setState(() => version++);
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(errorText(e))));
    }
  }

  Future<void> _addResult(Map<String, dynamic> lab) async {
    final s = S.of(context);
    final text = TextEditingController();
    XFile? picked;
    var busy = false;
    final sent = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setM) {
        return Padding(
          padding: EdgeInsets.fromLTRB(20, 4, 20, MediaQuery.of(ctx).viewInsets.bottom + 20),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(s.ar ? 'رفع نتيجة: ${lab['testName']}' : 'Upload result: ${lab['testName']}', style: Theme.of(ctx).textTheme.titleMedium),
            const SizedBox(height: 12),
            TextField(controller: text, maxLines: 2, onChanged: (_) => setM(() {}), decoration: InputDecoration(labelText: s.ar ? 'ملاحظة أو قيمة (اختياري)' : 'Note or value (optional)')),
            const SizedBox(height: 12),
            Row(children: [
              Expanded(child: OutlinedButton.icon(
                icon: const Icon(Icons.photo_camera_outlined),
                label: Text(s.ar ? 'كاميرا' : 'Camera'),
                onPressed: () async { final f = await ImagePicker().pickImage(source: ImageSource.camera, maxWidth: 2200, imageQuality: 85); if (f != null) setM(() => picked = f); },
              )),
              const SizedBox(width: 8),
              Expanded(child: OutlinedButton.icon(
                icon: const Icon(Icons.photo_library_outlined),
                label: Text(s.ar ? 'المعرض' : 'Gallery'),
                onPressed: () async { final f = await ImagePicker().pickImage(source: ImageSource.gallery, maxWidth: 2200, imageQuality: 85); if (f != null) setM(() => picked = f); },
              )),
            ]),
            if (picked != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text('📎 ${picked!.name}')),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: busy || (picked == null && text.text.trim().isEmpty) ? null : () async {
                setM(() => busy = true);
                try {
                  String? fileId;
                  if (picked != null) {
                    final bytes = await picked!.readAsBytes();
                    final type = picked!.path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';
                    final p = await widget.api.post('files/presign', {'filename': picked!.name, 'contentType': type, 'size': bytes.length, 'category': 'LAB_RESULT'}) as Map<String, dynamic>;
                    await widget.api.putFile(p['uploadUrl'] as String, bytes, type);
                    fileId = p['fileId'] as String;
                  }
                  await widget.api.post('portal/lab-orders/${lab['id']}/results', {if (text.text.trim().isNotEmpty) 'resultText': text.text.trim(), if (fileId != null) 'fileId': fileId});
                  if (ctx.mounted) Navigator.of(ctx).pop(true);
                } catch (e) {
                  if (ctx.mounted) ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(content: Text(errorText(e))));
                  setM(() => busy = false);
                }
              },
              child: Text(s.ar ? 'إرسال' : 'Send'),
            ),
          ]),
        );
      }),
    );
    if (sent == true) setState(() => version++);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      key: ValueKey(version),
      load: () async {
        final patients = (await widget.api.get('portal/patients') as List).cast<Map<String, dynamic>>();
        if (patients.isEmpty) return <String, dynamic>{};
        return await widget.api.get('portal/patients/${patients.first['id']}/timeline') as Map<String, dynamic>;
      },
      builder: (context, t, reload) {
        if (t.isEmpty) return Center(child: Text(s.empty));
        final rx = (t['prescriptions'] as List).cast<Map<String, dynamic>>();
        final labs = (t['labOrders'] as List).cast<Map<String, dynamic>>();
        final visits = (t['visits'] as List).cast<Map<String, dynamic>>();
        String status(String x) => switch (x) {
              'ORDERED' => s.ar ? 'مطلوب' : 'Requested',
              'DONE' => s.ar ? 'تم' : 'Done',
              'PATIENT_UPLOADED' => s.ar ? 'تم رفع النتيجة' : 'Result uploaded',
              'UNDER_REVIEW' => s.ar ? 'قيد مراجعة الطبيب' : 'With the doctor',
              'REVIEWED' => s.ar ? 'راجعها الطبيب' : 'Reviewed',
              _ => x,
            };
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            Text(s.ar ? 'التحاليل والأشعة المطلوبة' : 'Requested tests & radiology', style: Theme.of(context).textTheme.titleMedium),
            for (final l in labs)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      Text(l['kind'] == 'RADIOLOGY' ? '🩻' : '🧪', style: const TextStyle(fontSize: 20)),
                      const SizedBox(width: 8),
                      Expanded(child: Text(l['testName'] as String, style: const TextStyle(fontWeight: FontWeight.w700))),
                      Chip(visualDensity: VisualDensity.compact, label: Text(status(l['status'] as String))),
                    ]),
                    if ((l['instructions'] ?? '').toString().isNotEmpty) Text(l['instructions'] as String),
                    for (final r in (l['results'] as List)) if ((r['resultText'] ?? '').toString().isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text('• ${r['resultText']}')),
                    const SizedBox(height: 8),
                    Wrap(spacing: 8, children: [
                      if (['ORDERED', 'PATIENT_UPLOADED'].contains(l['status'])) FilledButton.tonalIcon(style: FilledButton.styleFrom(minimumSize: const Size(0, 40)), icon: const Icon(Icons.check), label: Text(s.ar ? 'تم' : 'Mark done'), onPressed: () => _markDone(l)),
                      if (['ORDERED', 'PATIENT_UPLOADED', 'DONE', 'UNDER_REVIEW'].contains(l['status'])) OutlinedButton.icon(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 40)), icon: const Icon(Icons.upload_file), label: Text(s.ar ? 'رفع النتيجة' : 'Upload result'), onPressed: () => _addResult(l)),
                    ]),
                  ]),
                ),
              ),
            if (labs.isEmpty) Padding(padding: const EdgeInsets.all(8), child: Text(s.empty)),
            const SizedBox(height: 16),
            Text(s.ar ? 'الروشتات' : 'Prescriptions', style: Theme.of(context).textTheme.titleMedium),
            for (final p in rx)
              Card(
                child: ListTile(
                  title: Text(p['issuedAt'] == null ? '' : _when(p['issuedAt'] as String)),
                  subtitle: Text([for (final i in (p['items'] as List)) '${i['medicationName']} ${i['dosage'] ?? ''} ${i['frequency'] ?? ''}'.trim()].join('\n')),
                ),
              ),
            if (rx.isEmpty) Padding(padding: const EdgeInsets.all(8), child: Text(s.empty)),
            const SizedBox(height: 16),
            Text(s.ar ? 'سجل الزيارات' : 'Visit history', style: Theme.of(context).textTheme.titleMedium),
            for (final v in visits)
              Card(
                child: ListTile(
                  title: Text(_when(v['visitAt'] as String)),
                  subtitle: Text('${v['doctorName'] ?? ''}'),
                  trailing: _visitChip(context, v['visitType'] as String?),
                ),
              ),
            if (visits.isEmpty) Padding(padding: const EdgeInsets.all(8), child: Text(s.empty)),
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
