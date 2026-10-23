import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart' hide TextDirection;
import 'package:platform_core/platform_core.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

import 'mode.dart';
import 'patient_id.dart';
import 'push.dart';
import 'shop/shop.dart';

const appVersion = '1.3.6';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppLocale.load();
  runApp(const ElmanassaApp());
}

class ElmanassaApp extends StatelessWidget {
  const ElmanassaApp({super.key});
  @override
  Widget build(BuildContext context) => ValueListenableBuilder<Locale>(
        valueListenable: AppLocale.notifier,
        builder: (context, locale, _) => MaterialApp(
          title: 'Tabib',
          debugShowCheckedModeBanner: false,
          scaffoldMessengerKey: messengerKey,
          theme: themeFor(null),
          locale: locale,
          localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
          supportedLocales: const [Locale('ar'), Locale('en')],
          home: const StartGate(),
        ),
      );
}

/// Decides the first screen: the remembered side (clinics / shop) opens straight away; the choice screen only shows when nothing is remembered.
class StartGate extends StatefulWidget {
  const StartGate({super.key});
  @override
  State<StartGate> createState() => _StartGateState();
}

class _StartGateState extends State<StartGate> {
  late final Future<String?> _mode = AppMode.get();
  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    // Tabib is for patients: straight to the clinics, behind the animated splash (the shop side stays in the code, hidden by kShopEnabled)
    if (!kShopEnabled) {
      return SplashGate(
        ready: true,
        splash: AnimatedSplash(title: ar ? 'طبيب' : 'Tabib', tagline: ar ? 'صحتك في جيبك' : 'Your health, in your pocket'),
        child: const PlatformGate(autoOpen: true),
      );
    }
    return FutureBuilder<String?>(
      future: _mode,
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) return const Scaffold(body: Center(child: BrandLogo(size: 110)));
        return switch (snap.data) {
          AppMode.clinics => const PlatformGate(autoOpen: true),
          AppMode.shop => const ShopEntry(),
          _ => const Landing(),
        };
      },
    );
  }
}

/// Entry screen: one app, two separate worlds. Patient and shop sessions are stored apart (different keys), so signing into one never signs into the other.
class Landing extends StatelessWidget {
  const Landing({super.key});
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    Widget card(int i, String mode, IconData icon, String title, String sub, List<Color> colors, Widget Function() page) => FadeSlideIn(
          index: i + 2,
          child: PressScale(
            onTap: () async { await AppMode.set(mode); if (context.mounted) Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => page())); },
            child: Container(
              margin: const EdgeInsets.only(bottom: 16),
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(borderRadius: BorderRadius.circular(26), gradient: LinearGradient(begin: AlignmentDirectional.topStart, end: AlignmentDirectional.bottomEnd, colors: colors), boxShadow: [BoxShadow(color: colors.first.withValues(alpha: 0.3), blurRadius: 22, offset: const Offset(0, 10))]),
              child: Row(children: [
                Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(18)), child: Icon(icon, color: Colors.white, size: 32)),
                const SizedBox(width: 16),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(title, style: const TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.w900)),
                  const SizedBox(height: 4),
                  Text(sub, style: TextStyle(color: Colors.white.withValues(alpha: 0.9), height: 1.35)),
                ])),
                const Icon(Icons.chevron_right_rounded, color: Colors.white, size: 28),
              ]),
            ),
          ),
        );
    return Scaffold(
      body: ListView(padding: EdgeInsets.zero, children: [
        GradientHeader(
          child: SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 30),
              child: Column(children: [
                const Row(mainAxisAlignment: MainAxisAlignment.end, children: [LanguageChip(onDark: true)]),
                const SizedBox(height: 12),
                const FadeSlideIn(child: DecoratedBox(decoration: BoxDecoration(color: Colors.white, shape: BoxShape.circle, boxShadow: [BoxShadow(color: Color(0x33000000), blurRadius: 24, offset: Offset(0, 10))]), child: Padding(padding: EdgeInsets.all(14), child: BrandLogo(size: 84)))),
                const SizedBox(height: 14),
                FadeSlideIn(index: 1, child: Text(ar ? 'أهلاً بك في طبيب' : 'Welcome to Tabib', style: const TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.w900))),
                const SizedBox(height: 4),
                FadeSlideIn(index: 1, child: Text(kShopEnabled ? (ar ? 'صحتك وتسوقك في مكان واحد' : 'Your health and shopping in one place') : (ar ? 'صحتك في جيبك' : 'Your health, in your pocket'), style: TextStyle(color: Colors.white.withValues(alpha: 0.88)))),
              ]),
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(20),
          child: Column(children: [
            card(0, AppMode.clinics, Icons.local_hospital_rounded, ar ? 'طبيب' : 'Tabib', ar ? 'الدور، السجل الطبي، التحاليل والروشتات' : 'Queue, medical record, tests and prescriptions', [const Color(0xFF1F6A99), const Color(0xFF2E8FB8)], () => const PlatformGate()),
            if (kShopEnabled) card(1, AppMode.shop, Icons.storefront_rounded, ar ? 'التسوق' : 'Shop', ar ? 'اختر متجراً واطلب بالدفع عند الاستلام' : 'Pick a shop and order, cash on delivery', [const Color(0xFF3D9A63), const Color(0xFF52B77B)], () => const ShopEntry()),
            const SizedBox(height: 4),
            FadeSlideIn(index: 5, child: Text('${s.version} $appVersion', style: const TextStyle(color: Color(0xFF8A99AA), fontSize: 12))),
          ]),
        ),
      ]),
    );
  }
}

/// Patients cannot register themselves: the clinic gives them their mobile number and a temporary password.
/// One sign-in covers every clinic the patient belongs to; the server decides per clinic address what the token may see,
/// so each clinic's data stays separate.
class PlatformGate extends StatefulWidget {
  const PlatformGate({super.key, this.autoOpen = false});
  /// Reopening the app: go straight into the patient's own clinic (the only one, or the last used) instead of the list.
  final bool autoOpen;
  @override
  State<PlatformGate> createState() => _PlatformGateState();
}

class _PlatformGateState extends State<PlatformGate> {
  final session = Session('patient-app', refreshBase: platformBase());
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
    if (loggedIn == null) return Scaffold(body: Container(decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topRight, end: Alignment.bottomLeft, colors: [kBrandBlue, Color.lerp(kBrandBlue, kBrandGreen, 0.6)!, kBrandGreen]))));
    if (loggedIn == false) {
      return LoginScreen(api: platform, app: 'client', title: S.of(context).ar ? 'طبيب' : 'Tabib', subtitle: S.of(context).ar ? 'سجّل الدخول بالرقم وكلمة المرور من عيادتك' : 'Sign in with the number and password from your clinic', onLoggedIn: (u) => setState(() { mustChange = u['mustChangePassword'] == true; loggedIn = true; }));
    }
    if (mustChange) return ChangeTempPassword(api: platform, onDone: () => setState(() => mustChange = false));
    return MyClinics(platform: platform, session: session, autoOpen: widget.autoOpen, onLogout: () async { await AppMode.clear(); if (context.mounted) Navigator.of(context).pushAndRemoveUntil(MaterialPageRoute(builder: (_) => kShopEnabled ? const Landing() : const PlatformGate()), (r) => false); });
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
  const MyClinics({super.key, required this.platform, required this.session, required this.onLogout, this.autoOpen = false});
  final bool autoOpen;
  final ApiClient platform;
  final Session session;
  final Future<void> Function() onLogout;
  @override
  State<MyClinics> createState() => _MyClinicsState();
}

class _MyClinicsState extends State<MyClinics> {
  ApiClient? pushApi;
  String? pushToken;
  bool autoOpened = false;

  /// The phone's push token belongs to the patient's account; it is registered through one of their clinics and the attempt is repeated
  /// (on every build of this list) until it has succeeded, so a failed first try never leaves the phone without notifications.
  void _startPush(List<Map<String, dynamic>> clinics) {
    if (PushService.registered || clinics.isEmpty) return;
    final api = ApiClient(baseUrl: baseForHost(clinics.first['host'] as String), session: widget.session);
    pushApi = api;
    PushService.ensure((token) async {
      pushToken = token;
      await api.post('portal/devices', {'token': token, 'platform': defaultTargetPlatform == TargetPlatform.iOS ? 'ios' : 'android'});
    });
  }

  /// A clinic whose subscription lapsed is switched off by the platform: patients see it but cannot enter.
  bool _off(Map<String, dynamic> c) => const {'SUSPENDED', 'CANCELLED', 'ARCHIVED'}.contains(c['status']);

  Future<void> _openClinic(Map<String, dynamic> c, Future<void> Function() reload) async {
    try { (await SharedPreferences.getInstance()).setString('clinic.last', c['id'] as String); } catch (_) {}
    if (!mounted) return;
    await Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => ClinicScreen(name: c['name'] as String, api: ApiClient(baseUrl: baseForHost(c['host'] as String), session: widget.session), onLeft: reload),
    ));
  }

  Future<void> _maybeAutoOpen(List<Map<String, dynamic>> clinics, Future<void> Function() reload) async {
    if (!widget.autoOpen || autoOpened || clinics.isEmpty) return;
    autoOpened = true;
    final open = clinics.where((c) => !_off(c)).toList();   // a closed clinic is never opened automatically
    Map<String, dynamic>? pick = clinics.length == 1 && open.isNotEmpty ? open.first : null;
    if (pick == null) {
      try {
        final last = (await SharedPreferences.getInstance()).getString('clinic.last');
        for (final c in open) { if (c['id'] == last) pick = c; }
      } catch (_) {}
    }
    if (pick != null) await _openClinic(pick, reload);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(s.myClinics), actions: [
        if (kShopEnabled) IconButton(tooltip: s.ar ? 'تغيير القسم' : 'Switch section', icon: const Icon(Icons.swap_horiz_rounded), onPressed: () async { await AppMode.clear(); if (context.mounted) Navigator.of(context).pushAndRemoveUntil(MaterialPageRoute(builder: (_) => const Landing()), (r) => false); }),
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
      body: CachedAsync<List<dynamic>>(
        cacheKey: 'patient.clinics',
        load: () async => await widget.platform.get('me/tenants') as List<dynamic>,
        builder: (context, all, reload) {
          final clinics = all.cast<Map<String, dynamic>>().where((t) => t['type'] == 'CLINIC' && t['host'] != null).toList();
          WidgetsBinding.instance.addPostFrameCallback((_) { _startPush(clinics); _maybeAutoOpen(clinics, reload); });
          if (clinics.isEmpty) return Center(child: Padding(padding: const EdgeInsets.all(32), child: Text(s.ar ? 'لا توجد عيادة مسجّل بها هذا الحساب بعد. العيادة هي من تضيفك.' : 'No clinic has added you yet. Your clinic registers you.', textAlign: TextAlign.center)));
          // One clinic: go straight in.
          return RefreshIndicator(
            onRefresh: reload,
            child: ListView(children: [
              PatientIdCard(api: widget.platform),
              for (final c in clinics)
                Card(
                  margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: ListTile(
                    leading: Icon(Icons.local_hospital_outlined, color: _off(c) ? const Color(0xFF9AA9BA) : null),
                    title: Text(c['name'] as String, style: TextStyle(color: _off(c) ? const Color(0xFF6B7C8D) : null)),
                    subtitle: _off(c)
                        ? Text(s.ar ? 'هذه العيادة متوقفة حاليًا ولا يمكن الدخول إليها' : 'This clinic is currently closed', style: const TextStyle(color: Color(0xFFD14343), fontWeight: FontWeight.w700))
                        : Text(c['host'] as String, textDirection: TextDirection.ltr),
                    trailing: Icon(_off(c) ? Icons.lock_outline_rounded : Icons.chevron_right),
                    onTap: _off(c) ? () => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s.ar ? 'هذه العيادة متوقفة حاليًا. تواصل معها مباشرة.' : 'This clinic is currently closed. Please contact it directly.'))) : () => _openClinic(c, reload),
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

String _when(String iso) => DateFormat.yMMMd(AppLocale.isAr ? 'ar' : 'en').add_jm().format(DateTime.parse(iso).toLocal());

Future<void> _dial(String phone) async => launchUrl(Uri(scheme: 'tel', path: phone));

/// A lab/test status as a solid coloured pill (amber = waiting, blue = uploaded, green = done / reviewed).
Widget _statusPill(String code, String label) {
  final (bg, fg) = switch (code) {
    'REVIEWED' || 'DONE' => (const Color(0xFFDCFCE7), const Color(0xFF166534)),
    'PATIENT_UPLOADED' => (const Color(0xFFE0F2FE), const Color(0xFF075985)),
    _ => (const Color(0xFFFEF3C7), const Color(0xFF78350F)),
  };
  return Chip(visualDensity: VisualDensity.compact, side: BorderSide.none, backgroundColor: bg, label: Text(label, style: TextStyle(color: fg, fontWeight: FontWeight.w800, fontSize: 12)));
}

/// Visit type chip (كشف / إعادة).
Widget _visitChip(BuildContext context, String? type) {
  if (type == null) return const SizedBox.shrink();
  final ar = S.of(context).ar;
  final follow = type == 'FOLLOW_UP';
  // solid colours with explicit white/dark text: the old pale fills were almost invisible on the white cards
  final bg = follow ? const Color(0xFFF59E0B) : Theme.of(context).colorScheme.primary;
  final fg = follow ? const Color(0xFF3B2A00) : Colors.white;
  return Chip(
    visualDensity: VisualDensity.compact,
    side: BorderSide.none,
    padding: const EdgeInsets.symmetric(horizontal: 4),
    label: Text(follow ? (ar ? 'إعادة' : 'Follow-up') : (ar ? 'كشف' : 'Consultation'), style: TextStyle(color: fg, fontWeight: FontWeight.w800, fontSize: 12)),
    backgroundColor: bg,
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

  void _ping() { _load(); }

  @override
  void initState() {
    super.initState();
    _load();
    timer = Timer.periodic(const Duration(seconds: 10), (_) => _load());
    queuePing.addListener(_ping);   // a push arrived while the app is open: refresh now
  }

  @override
  void dispose() {
    queuePing.removeListener(_ping);
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
        ValueListenableBuilder<bool?>(
          valueListenable: PushService.allowed,
          builder: (context, ok, _) => ok == false
              ? Container(margin: const EdgeInsets.fromLTRB(16, 12, 16, 0), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFFF4DD), borderRadius: BorderRadius.circular(14)), child: Row(children: [const Icon(Icons.notifications_off_rounded, color: Color(0xFFD98200)), const SizedBox(width: 10), Expanded(child: Text(s.ar ? 'الإشعارات متوقفة. فعّلها من إعدادات الهاتف لتصلك رسالة عند حلول دورك.' : 'Notifications are off. Turn them on in the phone settings to be told when it is your turn.', style: const TextStyle(fontWeight: FontWeight.w600)))]))
              : const SizedBox.shrink(),
        ),
        for (final q in queue)
          Card(
            color: q['called'] == true ? Colors.green.shade50 : null,
            margin: const EdgeInsets.all(16),
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(children: [
                if (q['dependent'] == true) Padding(padding: const EdgeInsets.only(bottom: 6), child: Text('${q['patientName']}', style: Theme.of(context).textTheme.titleMedium)),
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
        final services = ((p['services'] as List?) ?? const []).cast<Map<String, dynamic>>();
        final branches = ((p['branches'] as List?) ?? const []).cast<Map<String, dynamic>>();
        final hours = (p['workingHours'] as Map?)?.cast<String, dynamic>() ?? const <String, dynamic>{};
        final insurance = ((p['insurance'] as List?) ?? const []).cast<String>();
        final faqs = ((p['faqs'] as List?) ?? const []).cast<Map<String, dynamic>>();
        final wa = (p['whatsapp'] as String?) ?? '';
        final maps = (p['mapsUrl'] as String?) ?? '';
        const days = ['sat', 'sun', 'mon', 'tue', 'wed', 'thu', 'fri'];
        const dayAr = {'sat': 'السبت', 'sun': 'الأحد', 'mon': 'الاثنين', 'tue': 'الثلاثاء', 'wed': 'الأربعاء', 'thu': 'الخميس', 'fri': 'الجمعة'};
        const dayEn = {'sat': 'Saturday', 'sun': 'Sunday', 'mon': 'Monday', 'tue': 'Tuesday', 'wed': 'Wednesday', 'thu': 'Thursday', 'fri': 'Friday'};
        Widget title(String x) => Padding(padding: const EdgeInsets.only(top: 18, bottom: 6), child: Text(x, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)));
        String money(num minor, String cur) => '${(minor / 100).toStringAsFixed(minor % 100 == 0 ? 0 : 2)} $cur';
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            if (((p['tagline'] as String?) ?? '').isNotEmpty) Padding(padding: const EdgeInsets.only(bottom: 8), child: Text(p['tagline'] as String, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600))),
            if (((p['announcement'] as String?) ?? '').isNotEmpty) Card(color: Theme.of(context).colorScheme.secondaryContainer, child: Padding(padding: const EdgeInsets.all(12), child: Text(p['announcement'] as String))),
            if (p['isOpen'] == false) Card(color: Theme.of(context).colorScheme.errorContainer, child: Padding(padding: const EdgeInsets.all(12), child: Text(((p['closedMessage'] as String?) ?? '').isNotEmpty ? p['closedMessage'] as String : (s.ar ? 'العيادة مغلقة مؤقتًا' : 'Temporarily closed')))),
            Card(child: Column(children: [
              row(Icons.groups_outlined, s.ar ? 'في الانتظار الآن' : 'Waiting now', '${p['queueCount'] ?? 0}'),
              row(Icons.place_outlined, s.ar ? 'العنوان' : 'Address', p['addressText'] as String?),
              row(Icons.phone_outlined, s.ar ? 'هاتف العيادة' : 'Clinic phone', p['phone'] as String?, phone: p['phone'] as String?),
            ])),
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 8, children: [
              if (wa.isNotEmpty) FilledButton.icon(onPressed: () => launchUrl(Uri.parse('https://wa.me/${wa.replaceAll(RegExp(r'\D'), '')}'), mode: LaunchMode.externalApplication), icon: const Icon(Icons.chat_outlined), label: const Text('WhatsApp')),
              if (maps.isNotEmpty) OutlinedButton.icon(onPressed: () => launchUrl(Uri.parse(maps), mode: LaunchMode.externalApplication), icon: const Icon(Icons.navigation_outlined), label: Text(s.ar ? 'الاتجاهات' : 'Directions')),
            ]),
            if (((p['about'] as String?) ?? '').isNotEmpty) ...[title(s.ar ? 'من نحن' : 'About'), Text(p['about'] as String)],
            if (hours.isNotEmpty) ...[
              title(s.ar ? 'مواعيد العمل' : 'Opening hours'),
              Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(children: [
                for (final d in days)
                  if (hours[d] is Map)
                    Padding(padding: const EdgeInsets.symmetric(vertical: 3), child: Row(children: [
                      Expanded(child: Text(s.ar ? dayAr[d]! : dayEn[d]!)),
                      Text((hours[d] as Map)['closed'] == true ? (s.ar ? 'مغلق' : 'Closed') : '${(hours[d] as Map)['open']} – ${(hours[d] as Map)['close']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w700)),
                    ])),
              ]))),
            ],
            if (services.isNotEmpty) ...[
              title(s.ar ? 'الخدمات والأسعار' : 'Services and prices'),
              for (final x in services)
                Card(child: ListTile(
                  title: Text(x['name'] as String),
                  subtitle: Text('${x['durationMinutes']} ${s.ar ? 'دقيقة' : 'min'}${((x['description'] as String?) ?? '').isNotEmpty ? '\n${x['description']}' : ''}'),
                  trailing: x['priceMinor'] == null ? null : Text(money(x['priceMinor'] as num, (x['currency'] as String?) ?? 'EGP'), style: const TextStyle(fontWeight: FontWeight.w800)),
                )),
            ],
            title(s.ar ? 'الأطباء' : 'Doctors'),
            for (final d in doctors)
              Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const CircleAvatar(child: Icon(Icons.medical_services_outlined)),
                  title: Text(d['displayName'] as String, style: const TextStyle(fontWeight: FontWeight.w800)),
                  subtitle: Text((d['specialties'] as List).map((x) => s.ar ? x['nameAr'] : x['nameEn']).join(' · ')),
                  trailing: d['publicPhone'] == null ? null : IconButton(icon: const Icon(Icons.call), onPressed: () => _dial(d['publicPhone'] as String)),
                ),
                if (d['yearsExperience'] != null) Text(s.ar ? 'خبرة ${d['yearsExperience']} سنة' : '${d['yearsExperience']} years of experience'),
                if (((d['qualifications'] as String?) ?? '').isNotEmpty) Text(d['qualifications'] as String),
                if (((d['bio'] as String?) ?? '').isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text(d['bio'] as String)),
                if (d['defaultAppointmentFeeMinor'] != null) Padding(padding: const EdgeInsets.only(top: 6), child: Text('${s.ar ? 'سعر الكشف' : 'Fee'}: ${money(d['defaultAppointmentFeeMinor'] as num, (d['currency'] as String?) ?? 'EGP')}', style: const TextStyle(fontWeight: FontWeight.w700))),
              ]))),
            if (branches.isNotEmpty) ...[
              title(s.ar ? 'المواقع' : 'Locations'),
              for (final b in branches)
                Card(child: ListTile(
                  leading: const Icon(Icons.place_outlined),
                  title: Text(b['name'] as String),
                  subtitle: Text([b['addressLine1'], b['city'], b['landmark']].where((x) => x != null && '$x'.isNotEmpty).join('، ')),
                  trailing: ((b['mapsUrl'] as String?) ?? '').isEmpty ? null : IconButton(icon: const Icon(Icons.navigation_outlined), onPressed: () => launchUrl(Uri.parse(b['mapsUrl'] as String), mode: LaunchMode.externalApplication)),
                )),
            ],
            if (insurance.isNotEmpty) ...[title(s.ar ? 'التأمين المقبول' : 'Accepted insurance'), Wrap(spacing: 8, runSpacing: 6, children: [for (final i in insurance) Chip(label: Text(i))])],
            if (faqs.isNotEmpty) ...[title(s.ar ? 'الأسئلة الشائعة' : 'FAQ'), for (final f in faqs) Card(child: ExpansionTile(title: Text(f['q'] as String), childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12), expandedAlignment: Alignment.centerLeft, children: [Text(f['a'] as String)]))],
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
    Uint8List? preview;
    String? error;
    var busy = false;
    final sent = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) => StatefulBuilder(builder: (ctx, setM) {
        Future<void> pick(ImageSource src) async {
          try {
            final f = await ImagePicker().pickImage(source: src, maxWidth: 2200, imageQuality: 85);
            if (f == null) return;
            final bytes = await f.readAsBytes();
            setM(() { picked = f; preview = bytes; error = null; });
          } catch (e) {
            // a denied camera/photo permission or a missing camera is shown here, inside the sheet, where the patient can see it
            setM(() => error = s.ar ? 'تعذر فتح ${src == ImageSource.camera ? 'الكاميرا' : 'المعرض'}. اسمح للتطبيق بالوصول من إعدادات الهاتف ثم حاول مرة أخرى.' : 'Could not open the ${src == ImageSource.camera ? 'camera' : 'gallery'}. Allow access in the phone settings and try again.');
          }
        }
        Future<void> send() async {
          setM(() { busy = true; error = null; });
          try {
            String? fileId;
            if (picked != null) {
              final bytes = preview ?? await picked!.readAsBytes();
              final type = picked!.path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';
              final p = await widget.api.post('files/presign', {'filename': picked!.name, 'contentType': type, 'size': bytes.length, 'category': 'LAB_RESULT'}) as Map<String, dynamic>;
              await widget.api.putFile(p['uploadUrl'] as String, bytes, type);
              fileId = p['fileId'] as String;
            }
            await widget.api.post('portal/lab-orders/${lab['id']}/results', {if (text.text.trim().isNotEmpty) 'resultText': text.text.trim(), if (fileId != null) 'fileId': fileId});
            if (ctx.mounted) Navigator.of(ctx).pop(true);
          } catch (e) {
            setM(() { busy = false; error = s.ar ? 'لم يتم إرسال النتيجة: ${errorText(e)}' : 'The result was not sent: ${errorText(e)}'; });
          }
        }
        return SingleChildScrollView(child: Padding(
          padding: EdgeInsets.fromLTRB(20, 4, 20, MediaQuery.of(ctx).viewInsets.bottom + 20),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(s.ar ? 'رفع نتيجة: ${lab['testName']}' : 'Upload result: ${lab['testName']}', style: Theme.of(ctx).textTheme.titleMedium),
            const SizedBox(height: 4),
            Text(s.ar ? 'صوّر ورقة النتيجة أو اخترها من المعرض، وسيصل إشعار للطبيب.' : 'Take a photo of the result or pick it from your gallery. Your doctor is notified.', style: const TextStyle(color: Color(0xFF6B7C8D))),
            const SizedBox(height: 12),
            Row(children: [
              Expanded(child: OutlinedButton.icon(icon: const Icon(Icons.photo_camera_outlined), label: Text(s.ar ? 'كاميرا' : 'Camera'), onPressed: busy ? null : () => pick(ImageSource.camera))),
              const SizedBox(width: 8),
              Expanded(child: OutlinedButton.icon(icon: const Icon(Icons.photo_library_outlined), label: Text(s.ar ? 'المعرض' : 'Gallery'), onPressed: busy ? null : () => pick(ImageSource.gallery))),
            ]),
            if (preview != null) Padding(
              padding: const EdgeInsets.only(top: 12),
              child: ClipRRect(borderRadius: BorderRadius.circular(14), child: Stack(alignment: AlignmentDirectional.topEnd, children: [
                Image.memory(preview!, height: 180, width: double.infinity, fit: BoxFit.cover),
                IconButton.filled(style: IconButton.styleFrom(backgroundColor: Colors.black54), icon: const Icon(Icons.close_rounded, color: Colors.white), onPressed: busy ? null : () => setM(() { picked = null; preview = null; })),
              ])),
            ),
            const SizedBox(height: 12),
            TextField(controller: text, maxLines: 2, onChanged: (_) => setM(() {}), decoration: InputDecoration(labelText: s.ar ? 'ملاحظة أو قيمة (اختياري)' : 'Note or value (optional)')),
            if (error != null) Padding(padding: const EdgeInsets.only(top: 10), child: Text(error!, style: TextStyle(color: Theme.of(ctx).colorScheme.error, fontWeight: FontWeight.w700))),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: busy || (picked == null && text.text.trim().isEmpty) ? null : send,
              child: busy ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white)) : Text(s.ar ? 'إرسال للطبيب' : 'Send to the doctor'),
            ),
          ]),
        ));
      }),
    );
    if (sent == true && mounted) {
      setState(() => version++);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s.ar ? 'تم إرسال النتيجة إلى الطبيب' : 'Result sent to your doctor')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      key: ValueKey(version),
      load: () async {
        final patients = (await widget.api.get('portal/patients') as List).cast<Map<String, dynamic>>();
        if (patients.isEmpty) return <String, dynamic>{};
        final chosen = patients.any((x) => x['id'] == patientId) ? patientId : patients.first['id'] as String;
        final tl = await widget.api.get('portal/patients/$chosen/timeline') as Map<String, dynamic>;
        return {...tl, '_patients': patients, '_chosen': chosen};
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
            // The account holder and the children the clinic added under them: pick whose record to see.
            if ((t['_patients'] as List).length > 1)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Wrap(spacing: 8, children: [
                  for (final p in (t['_patients'] as List).cast<Map<String, dynamic>>())
                    ChoiceChip(
                      // solid colours: the default pale fill made the names hard to read
                      showCheckmark: false,
                      avatar: Icon(p['guardianPatientId'] == null ? Icons.person_rounded : Icons.child_care_rounded, size: 18, color: p['id'] == t['_chosen'] ? Colors.white : Theme.of(context).colorScheme.primary),
                      label: Text('${p['firstName']}', style: TextStyle(fontWeight: FontWeight.w800, color: p['id'] == t['_chosen'] ? Colors.white : const Color(0xFF14232B))),
                      selectedColor: Theme.of(context).colorScheme.primary,
                      backgroundColor: Colors.white,
                      side: BorderSide(color: p['id'] == t['_chosen'] ? Colors.transparent : const Color(0xFFB9C7D6), width: 1.2),
                      selected: p['id'] == t['_chosen'],
                      onSelected: (_) => setState(() { patientId = p['id'] as String; version++; }),
                    ),
                ]),
              ),
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
                      _statusPill(l['status'] as String, status(l['status'] as String)),
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
      PatientIdCard(api: widget.api, margin: const EdgeInsets.only(bottom: 8)),
      Card(child: ListTile(leading: const Icon(Icons.translate_rounded), title: Text(s.language, style: const TextStyle(fontWeight: FontWeight.w700)), trailing: const LanguageChip())),
      const SizedBox(height: 12),
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
