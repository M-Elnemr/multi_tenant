import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:platform_core/platform_core.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'common.dart';
import 'offline_ops.dart';
import 'shell.dart';
import 'site_editor.dart';

const appVersion = '1.3.2';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppLocale.load();
  runApp(const ProviderApp());
}

class ProviderApp extends StatelessWidget {
  const ProviderApp({super.key});
  @override
  Widget build(BuildContext context) => ValueListenableBuilder<Locale>(
        valueListenable: AppLocale.notifier,
        builder: (context, locale, _) => MaterialApp(
          title: 'Elmanassa Business',
          debugShowCheckedModeBanner: false,
          navigatorKey: navigatorKey,
          theme: themeFor(null),
          locale: locale,
          localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
          supportedLocales: const [Locale('ar'), Locale('en')],
          home: const Root(),
        ),
      );
}

/// One sign-in on the platform address, no domain to type: the businesses of this account (stores and clinics) are listed after login.
class Root extends StatefulWidget {
  const Root({super.key});
  @override
  State<Root> createState() => _RootState();
}

class _RootState extends State<Root> {
  static final _base = platformBase();
  final _session = Session('business', refreshBase: _base);
  late final _api = ApiClient(baseUrl: _base, session: _session);
  bool _checked = false;
  List<Map<String, dynamic>>? _businesses;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    List<Map<String, dynamic>>? list;
    String? error;
    if (await _session.loggedIn) {
      // show the last known list at once; the fresh one replaces it as soon as it arrives
      final cached = await ListCache.read<List<dynamic>>('business.list');
      if (cached != null && mounted) setState(() { _businesses = cached.cast<Map<String, dynamic>>(); _checked = true; });
      try {
        list = ((await _api.get('me/businesses')) as List).cast<Map<String, dynamic>>();
        await ListCache.write('business.list', list);
      } on ApiException catch (e) {
        if (e.status == 401) { list = null; await ListCache.clear(); } else if (cached != null) { list = cached.cast<Map<String, dynamic>>(); } else { error = errorText(e); }
      } catch (e) {
        if (cached != null) { list = cached.cast<Map<String, dynamic>>(); } else { error = errorText(e); }
      }
    }
    if (mounted) setState(() { _businesses = list; _error = error; _checked = true; });
  }

  Future<void> _signOut() async {
    await AuthRepository(_api).logout();
    await OfflineStore.clearAll();
    try { (await SharedPreferences.getInstance()).remove('business.last'); } catch (_) {}
    if (mounted) setState(() => _businesses = null);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    Widget body;
    if (!_checked) {
      body = const Scaffold(key: ValueKey('boot'), body: SizedBox.shrink());
    } else if (_businesses == null && _error == null) {
      body = LoginScreen(
        key: const ValueKey('login'),
        api: _api,
        app: 'business',
        title: 'Elmanassa Business',
        subtitle: s.ar ? 'أدر عيادتك أو متجرك من هاتفك' : 'Run your clinic or shop from your phone',
        onLoggedIn: (u) => _load(),
      );
    } else if (_error != null) {
      body = Scaffold(key: const ValueKey('err'), body: Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Icon(Icons.cloud_off_rounded, size: 52, color: Color(0xFF9AA9BA)),
        const SizedBox(height: 12),
        Text(_error!, textAlign: TextAlign.center),
        const SizedBox(height: 12),
        FilledButton(onPressed: () { setState(() { _checked = false; _error = null; }); _load(); }, child: Text(s.retry)),
        TextButton(onPressed: _signOut, child: Text(s.signOut)),
      ]))));
    } else {
      body = BusinessPicker(key: const ValueKey('picker'), session: _session, businesses: _businesses!, onSignOut: _signOut, onReload: _load);
    }
    return SplashGate(
      ready: _checked,
      splash: AnimatedSplash(title: 'Elmanassa Business', tagline: s.ar ? 'أدر عيادتك أو متجرك من هاتفك' : 'Run your clinic or shop from your phone'),
      child: AnimatedSwitcher(duration: const Duration(milliseconds: 380), switchInCurve: Curves.easeOutCubic, child: body),
    );
  }
}

/// Lists the account's businesses; opens the only one (or the last used one) straight away, and lets the user switch from settings.
class BusinessPicker extends StatefulWidget {
  const BusinessPicker({super.key, required this.session, required this.businesses, required this.onSignOut, required this.onReload});
  final Session session;
  final List<Map<String, dynamic>> businesses;
  final VoidCallback onSignOut;
  final Future<void> Function() onReload;
  @override
  State<BusinessPicker> createState() => _BusinessPickerState();
}

class _BusinessPickerState extends State<BusinessPicker> {
  Map<String, dynamic>? _open;
  bool _auto = true;

  @override
  void initState() {
    super.initState();
    _autoOpen();
  }

  Future<void> _autoOpen() async {
    final list = widget.businesses;
    Map<String, dynamic>? pick = list.length == 1 ? list.first : null;
    if (pick == null && list.length > 1) {
      try {
        final last = (await SharedPreferences.getInstance()).getString('business.last');
        pick = list.cast<Map<String, dynamic>?>().firstWhere((b) => b!['id'] == last, orElse: () => null);
      } catch (_) {}
    }
    if (mounted) setState(() { _open = pick; _auto = false; });
  }

  Future<void> _choose(Map<String, dynamic> b) async {
    try { await (await SharedPreferences.getInstance()).setString('business.last', b['id'] as String); } catch (_) {}
    setState(() => _open = b);
  }

  @override
  Widget build(BuildContext context) {
    if (_auto) return Scaffold(body: Container(decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topRight, end: Alignment.bottomLeft, colors: [kBrandBlue, Color.lerp(kBrandBlue, kBrandGreen, 0.6)!, kBrandGreen]))));
    if (_open != null) {
      final b = _open!;
      return BusinessHome(
        key: ValueKey(b['id']),
        business: b,
        session: widget.session,
        canSwitch: widget.businesses.length > 1,
        onSwitch: () => setState(() => _open = null),
        onSignOut: widget.onSignOut,
      );
    }
    final s = S.of(context);
    final list = widget.businesses;
    return Scaffold(
      body: RefreshIndicator(
        onRefresh: widget.onReload,
        child: ListView(padding: EdgeInsets.zero, children: [
          GradientHeader(
            child: SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [const LanguageChip(onDark: true), IconButton(onPressed: widget.onSignOut, icon: const Icon(Icons.logout_rounded, color: Colors.white), tooltip: s.signOut)]),
                  const SizedBox(height: 22),
                  FadeSlideIn(child: Text(s.chooseBusiness, style: const TextStyle(color: Colors.white, fontSize: 28, fontWeight: FontWeight.w900))),
                  const SizedBox(height: 6),
                  FadeSlideIn(index: 1, child: Text(s.ar ? 'اختر النشاط الذي تريد إدارته' : 'Pick the business you want to manage', style: TextStyle(color: Colors.white.withValues(alpha: 0.85)))),
                ]),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: list.isEmpty
                ? Padding(padding: const EdgeInsets.all(32), child: Center(child: Text(s.noBusinesses, textAlign: TextAlign.center)))
                : Column(children: [for (var i = 0; i < list.length; i++) FadeSlideIn(index: i + 2, child: BusinessCard(business: list[i], onTap: () => _choose(list[i])))]),
          ),
        ]),
      ),
    );
  }
}

class BusinessCard extends StatelessWidget {
  const BusinessCard({super.key, required this.business, required this.onTap});
  final Map<String, dynamic> business;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final color = hexColor(business['primaryColor']) ?? kBrandBlue;
    final store = business['type'] == 'STORE';
    final roles = (business['roles'] as List?)?.cast<String>() ?? const [];
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: PressScale(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(22),
            gradient: LinearGradient(begin: AlignmentDirectional.topStart, end: AlignmentDirectional.bottomEnd, colors: [color.withValues(alpha: 0.95), Color.lerp(color, kBrandGreen, 0.5)!.withValues(alpha: 0.9)]),
            boxShadow: [BoxShadow(color: color.withValues(alpha: 0.28), blurRadius: 18, offset: const Offset(0, 8))],
          ),
          child: Row(children: [
            BusinessLogo(url: business['logoUrl'] as String?, name: business['name'] as String, size: 58),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(business['name'] as String, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w800)),
                const SizedBox(height: 6),
                Wrap(spacing: 6, runSpacing: 4, children: [
                  _Pill(store ? (s.ar ? 'متجر' : 'Shop') : (s.ar ? 'عيادة' : 'Clinic'), store ? Icons.storefront_rounded : Icons.local_hospital_rounded),
                  if (roles.isNotEmpty) _Pill(roles.first.replaceAll('_', ' ').toLowerCase(), Icons.badge_outlined),
                ]),
              ]),
            ),
            const Icon(Icons.chevron_right_rounded, color: Colors.white, size: 28),
          ]),
        ),
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill(this.text, this.icon);
  final String text;
  final IconData icon;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
        decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.2), borderRadius: BorderRadius.circular(14)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(icon, size: 13, color: Colors.white), const SizedBox(width: 4), Text(text, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w700))]),
      );
}

/// A business logo, or its first letter on a brand tile when it has none.
class BusinessLogo extends StatelessWidget {
  const BusinessLogo({super.key, required this.url, required this.name, this.size = 44});
  final String? url;
  final String name;
  final double size;
  @override
  Widget build(BuildContext context) {
    final letter = name.trim().isEmpty ? 'E' : String.fromCharCode(name.trim().runes.first).toUpperCase();
    final fallback = Center(child: Text(letter, style: TextStyle(fontSize: size * 0.45, fontWeight: FontWeight.w900, color: Theme.of(context).colorScheme.primary)));
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(size * 0.3), boxShadow: const [BoxShadow(color: Color(0x22000000), blurRadius: 8, offset: Offset(0, 3))]),
      clipBehavior: Clip.antiAlias,
      child: url == null ? fallback : Image.network(url!, fit: BoxFit.cover, errorBuilder: (_, __, ___) => fallback),
    );
  }
}

/// A business opened on its own address with the account's platform session (staff tokens work on every business they belong to).
class BusinessHome extends StatefulWidget {
  const BusinessHome({super.key, required this.business, required this.session, required this.canSwitch, required this.onSwitch, required this.onSignOut});
  final Map<String, dynamic> business;
  final Session session;
  final bool canSwitch;
  final VoidCallback onSwitch;
  final VoidCallback onSignOut;
  @override
  State<BusinessHome> createState() => _BusinessHomeState();
}

class _BusinessHomeState extends State<BusinessHome> with WidgetsBindingObserver {
  /// Clinics keep a copy of their data on the phone and queue changes made offline; shops use the plain client.
  late final OfflineStore? _off = widget.business['type'] == 'CLINIC' ? OfflineStore(widget.business['host'] as String) : null;
  late final ApiClient _api = ApiClient(baseUrl: widget.business['baseUrl'] as String, session: widget.session, maxAttempts: _off == null ? 5 : 3, attemptTimeout: _off == null ? const Duration(seconds: 20) : const Duration(seconds: 12), offline: _off);
  late Future<Map<String, dynamic>> _me = _loadMe();
  Timer? _timer;
  Ctx? _ctx;

  @override
  void initState() {
    super.initState();
    final off = _off;
    if (off != null) {
      registerClinicOffline(off);
      off.refreshCounts().then((_) => off.flush());
      off.synced.addListener(() { final c = _ctx; if (c != null) unawaited(warmUp(c, force: true)); });
      WidgetsBinding.instance.addObserver(this);
      _timer = Timer.periodic(const Duration(seconds: 45), (_) { _tick(); });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _off?.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) { if (state == AppLifecycleState.resumed) _tick(); }

  Future<void> _tick() async {
    final off = _off, ctx = _ctx;
    if (off == null) return;
    if (off.pending.value > 0) await off.flush();
    if (ctx != null) unawaited(warmUp(ctx));
  }

  /// The permissions come from the phone's copy at once (the fresh answer replaces it for the next open), so the app opens even without internet.
  Future<Map<String, dynamic>> _loadMe() async {
    final key = 'me.${widget.business['id']}';
    final cached = await ListCache.read<Map<String, dynamic>>(key);
    Future<Map<String, dynamic>> fresh() async {
      final r = await AuthRepository(_api).me();
      await ListCache.write(key, r);
      return r;
    }
    if (cached != null) {
      unawaited(fresh().then((_) {}, onError: (_) {}));
      return cached;
    }
    return fresh();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final b = widget.business;
    final color = hexColor(b['primaryColor']);
    return Theme(
      data: themeFor(null, seed: color),
      child: Scaffold(
        appBar: AppBar(
          centerTitle: false,
          titleSpacing: 16,
          title: Row(children: [BusinessLogo(url: b['logoUrl'] as String?, name: b['name'] as String, size: 36), const SizedBox(width: 10), Expanded(child: Text(b['name'] as String, overflow: TextOverflow.ellipsis))]),
          actions: [
            IconButton(
              tooltip: s.settings,
              icon: const Icon(Icons.tune_rounded),
              onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => SettingsPage(ctx: _ctx, businessName: b['name'] as String, canSwitch: widget.canSwitch, onSwitch: () { Navigator.of(context).pop(); widget.onSwitch(); }, onSignOut: () { Navigator.of(context).pop(); widget.onSignOut(); }))),
            ),
          ],
        ),
        body: FutureBuilder<Map<String, dynamic>>(
          future: _me,
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) return const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(height: 130), SizedBox(height: 12), Skeleton(), SizedBox(height: 12), Skeleton()]));
            if (snap.hasError) {
              return Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
                const Icon(Icons.cloud_off_rounded, size: 46, color: Color(0xFF9AA9BA)),
                const SizedBox(height: 10),
                Text(errorText(snap.error!), textAlign: TextAlign.center),
                TextButton.icon(onPressed: () => setState(() => _me = _loadMe()), icon: const Icon(Icons.refresh_rounded), label: Text(s.retry)),
              ])));
            }
            final perms = (snap.data!['permissions'] as List).cast<String>();
            final currency = (b['currency'] as String?) ?? 'EGP';
            final ctx = _ctx ??= Ctx(api: _api, currency: currency, perms: perms, host: b['host'] as String, type: b['type'] as String);
            unawaited(warmUp(ctx));
            return PanelShell(ctx: ctx);
          },
        ),
      ),
    );
  }
}

class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key, this.ctx, required this.businessName, required this.canSwitch, required this.onSwitch, required this.onSignOut});
  final Ctx? ctx;
  final String businessName;
  final bool canSwitch;
  final VoidCallback onSwitch;
  final VoidCallback onSignOut;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    Widget tile(IconData i, String t, {Widget? trailing, VoidCallback? onTap, Color? color}) => Card(child: ListTile(leading: Icon(i, color: color), title: Text(t, style: TextStyle(fontWeight: FontWeight.w700, color: color)), trailing: trailing, onTap: onTap));
    return Scaffold(
      appBar: AppBar(title: Text(s.settings)),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        FadeSlideIn(child: tile(Icons.business_center_rounded, businessName)),
        if (ctx != null && ctx!.can('settings.manage')) FadeSlideIn(index: 1, child: tile(Icons.auto_awesome_rounded, s.ar ? 'خصّص صفحتي' : 'Customize my page', onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => MyPageScreen(ctx: ctx!))), trailing: const Icon(Icons.chevron_right_rounded), color: Theme.of(context).colorScheme.primary)),
        FadeSlideIn(index: 1, child: tile(Icons.translate_rounded, s.language, trailing: const LanguageChip())),
        if (canSwitch) FadeSlideIn(index: 2, child: tile(Icons.swap_horiz_rounded, s.switchBusiness, onTap: onSwitch, trailing: const Icon(Icons.chevron_right_rounded))),
        FadeSlideIn(index: 3, child: tile(Icons.info_outline_rounded, s.version, trailing: const Text(appVersion))),
        FadeSlideIn(index: 4, child: tile(Icons.logout_rounded, s.signOut, onTap: onSignOut, color: Theme.of(context).colorScheme.error)),
      ]),
    );
  }
}
