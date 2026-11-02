import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'lawyer_panel.dart' show caseType, legalPill, stepKind;
import 'push.dart';
import 'single_business.dart';

// The office app for the lawyer's CLIENTS (only in a single-office app such as com.elhusseiny.lawyer). A client signs in with the mobile
// number the lawyer saved and the temporary password the lawyer sent, chooses their own password, then follows their cases (status,
// hearings, fees, payments), books sessions and gets reminders. Everything goes to the office's own address.

bool get _ar => AppLocale.isAr;
String _l(String ar, String en) => _ar ? ar : en;
List<Map<String, dynamic>> _maps(dynamic v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
const _navy = Color(0xFF0B1D3A);
const _gold = Color(0xFFC9A227);

/// The office's own address and the client's sign-in there (kept apart from the staff sign-in).
class ClientAuth {
  static final base = SingleBusiness.officeBase;
  static final session = Session('lawclient', refreshBase: base);
  static final api = ApiClient(baseUrl: base, session: session);

  static Future<Map<String, dynamic>> login(String phone, String password) async {
    final r = await api.post('lawyer/portal/login', {'phone': phone, 'password': password}) as Map<String, dynamic>;
    await session.save(r);
    return Map<String, dynamic>.from(r['user'] as Map);
  }

  static Future<void> changePassword(String current, String next) async {
    final r = await api.post('lawyer/portal/change-password', {'currentPassword': current, 'newPassword': next}) as Map<String, dynamic>;
    await session.save(r);
  }

  static Future<void> signOut() async {
    final t = await PushService.token();
    if (t != null) { try { await api.delete('lawyer/portal/devices', {'token': t}); } catch (_) {} }
    await AuthRepository(api).logout();
  }
}

// ============================================================================================ sign-in

class ClientLoginScreen extends StatefulWidget {
  const ClientLoginScreen({super.key, required this.onSignedIn, required this.onStaff});
  final VoidCallback onSignedIn;
  final VoidCallback onStaff;
  @override
  State<ClientLoginScreen> createState() => _ClientLoginScreenState();
}

class _ClientLoginScreenState extends State<ClientLoginScreen> {
  final _phone = TextEditingController(), _pw = TextEditingController();
  final _fe = FieldErrors();
  bool _busy = false, _hide = true;

  Future<void> _go() async {
    if (!_fe.check({
      'phone': _phone.text.trim().isEmpty ? _l('اكتب رقم موبايلك المسجل لدى المكتب', 'Write the mobile number the office has') : null,
      'password': _pw.text.isEmpty ? _l('اكتب كلمة المرور', 'Write your password') : null,
    })) { setState(() {}); return; }
    setState(() => _busy = true);
    Map<String, dynamic>? user;
    final ok = await runForm(context, _fe, () async { user = await ClientAuth.login(_phone.text.trim(), _pw.text); });
    if (!mounted) return;
    setState(() => _busy = false);
    if (!ok) return;
    if (user?['mustChangePassword'] == true) {
      final changed = await Navigator.of(context).push<bool>(MaterialPageRoute(builder: (_) => ClientPasswordScreen(forced: true, current: _pw.text)));
      if (changed != true) { await ClientAuth.signOut(); return; }
    }
    widget.onSignedIn();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        body: ListenableBuilder(
          listenable: _fe,
          builder: (context, _) => ListView(padding: EdgeInsets.zero, children: [
            GradientHeader(
              child: SafeArea(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 34),
                  child: Column(children: [
                    const Align(alignment: AlignmentDirectional.centerStart, child: LanguageChip(onDark: true)),
                    const SizedBox(height: 8),
                    const DecoratedBox(decoration: BoxDecoration(color: Colors.white, shape: BoxShape.circle), child: Padding(padding: EdgeInsets.all(5), child: BrandLogo(size: 92))),
                    const SizedBox(height: 14),
                    Text(_ar ? SingleBusiness.nameAr : SingleBusiness.nameEn, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.w900)),
                    const SizedBox(height: 6),
                    Text(_l('تابع قضاياك وجلساتك واحجز موعدك', 'Follow your cases and hearings, book your session'), textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withValues(alpha: .85))),
                  ]),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(20),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(18),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    Text(_l('دخول الموكلين', 'Client sign-in'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
                    const SizedBox(height: 4),
                    Text(_l('بالرقم المسجل لدى المكتب وكلمة المرور التي أرسلها لك المكتب.', 'With the number the office has and the password the office sent you.'), style: const TextStyle(color: Color(0xFF6B7A8C), fontSize: 13)),
                    const SizedBox(height: 16),
                    Padding(key: _fe.key('phone'), padding: const EdgeInsets.only(bottom: 12), child: TextField(controller: _phone, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, onChanged: (_) => _fe.clear('phone'),
                        decoration: InputDecoration(labelText: _l('رقم الموبايل', 'Mobile number'), hintText: '01xxxxxxxxx', prefixIcon: const Icon(Icons.phone_iphone_rounded), errorText: _fe.of('phone')))),
                    Padding(key: _fe.key('password'), padding: const EdgeInsets.only(bottom: 16), child: TextField(controller: _pw, obscureText: _hide, onChanged: (_) => _fe.clear('password'), onSubmitted: (_) => _go(),
                        decoration: InputDecoration(labelText: _l('كلمة المرور', 'Password'), prefixIcon: const Icon(Icons.lock_outline_rounded), errorText: _fe.of('password'),
                            suffixIcon: IconButton(onPressed: () => setState(() => _hide = !_hide), icon: Icon(_hide ? Icons.visibility_outlined : Icons.visibility_off_outlined))))),
                    FilledButton(style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52), backgroundColor: _navy), onPressed: _busy ? null : _go,
                        child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : Text(_l('دخول', 'Sign in'), style: const TextStyle(fontWeight: FontWeight.w900))),
                    const SizedBox(height: 10),
                    Text(_l('ليس لديك بيانات دخول؟ اطلبها من المكتب.', 'No sign-in details? Ask the office.'), textAlign: TextAlign.center, style: const TextStyle(color: Color(0xFF6B7A8C), fontSize: 12.5)),
                  ]),
                ),
              ),
            ),
            Center(child: TextButton.icon(onPressed: widget.onStaff, icon: const Icon(Icons.badge_outlined), label: Text(_l('دخول فريق المكتب', 'Office team sign-in')))),
            const SizedBox(height: 24),
          ]),
        ),
      );
}

/// First sign-in (forced) or later from the account tab.
class ClientPasswordScreen extends StatefulWidget {
  const ClientPasswordScreen({super.key, this.forced = false, this.current});
  final bool forced;
  final String? current;
  @override
  State<ClientPasswordScreen> createState() => _ClientPasswordScreenState();
}

class _ClientPasswordScreenState extends State<ClientPasswordScreen> {
  late final _cur = TextEditingController(text: widget.current ?? '');
  final _next = TextEditingController(), _again = TextEditingController();
  final _fe = FieldErrors();
  bool _busy = false;

  Future<void> _save() async {
    if (!_fe.check({
      'currentPassword': _cur.text.isEmpty ? _l('اكتب كلمة المرور الحالية', 'Write your current password') : null,
      'newPassword': _next.text.length < 8 ? _l('8 أحرف أو أرقام على الأقل', 'At least 8 characters') : null,
      'again': _again.text != _next.text ? _l('غير مطابقة', 'Does not match') : null,
    })) { setState(() {}); return; }
    setState(() => _busy = true);
    final ok = await runForm(context, _fe, () => ClientAuth.changePassword(_cur.text, _next.text));
    if (!mounted) return;
    setState(() => _busy = false);
    if (ok) { toast(context, _l('تم حفظ كلمة المرور ✓', 'Password saved ✓')); Navigator.of(context).pop(true); }
  }

  Widget _f(TextEditingController c, String key, String label) => Padding(key: _fe.key(key), padding: const EdgeInsets.only(bottom: 12),
      child: TextField(controller: c, obscureText: true, onChanged: (_) => _fe.clear(key), decoration: InputDecoration(labelText: label, errorText: _fe.of(key), prefixIcon: const Icon(Icons.lock_outline_rounded))));

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(_l('كلمة مرور جديدة', 'New password')), automaticallyImplyLeading: !widget.forced),
        body: ListenableBuilder(
          listenable: _fe,
          builder: (context, _) => ListView(padding: const EdgeInsets.all(20), children: [
            if (widget.forced) Container(margin: const EdgeInsets.only(bottom: 16), padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: const Color(0xFFEFF4FF), borderRadius: BorderRadius.circular(14)),
                child: Text(_l('أهلًا بك! اختر كلمة مرور خاصة بك بدل كلمة المرور المؤقتة.', 'Welcome! Choose your own password instead of the temporary one.'), style: const TextStyle(fontWeight: FontWeight.w700))),
            if (!widget.forced || (widget.current ?? '').isEmpty) _f(_cur, 'currentPassword', _l('كلمة المرور الحالية', 'Current password')),
            _f(_next, 'newPassword', _l('كلمة المرور الجديدة', 'New password')),
            _f(_again, 'again', _l('أعد كتابتها', 'Type it again')),
            const SizedBox(height: 8),
            FilledButton(style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52), backgroundColor: _navy), onPressed: _busy ? null : _save,
                child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : Text(_l('حفظ', 'Save'), style: const TextStyle(fontWeight: FontWeight.w900))),
          ]),
        ),
      );
}

// ============================================================================================ the client's home (4 tabs)

class ClientHome extends StatefulWidget {
  const ClientHome({super.key, required this.onSignedOut});
  final VoidCallback onSignedOut;
  @override
  State<ClientHome> createState() => _ClientHomeState();
}

class _ClientHomeState extends State<ClientHome> {
  int _tab = 0;
  Map<String, dynamic>? _home, _office;
  final _bookingsKey = GlobalKey<_ClientBookingsState>();

  @override
  void initState() {
    super.initState();
    _load();
    PushService.ensure((token) async { await ClientAuth.api.post('lawyer/portal/devices', {'token': token, 'platform': defaultTargetPlatform == TargetPlatform.iOS ? 'ios' : 'android'}); });
    pushOpened.addListener(_fromPush);
    WidgetsBinding.instance.addPostFrameCallback((_) => _fromPush());
  }

  @override
  void dispose() {
    pushOpened.removeListener(_fromPush);
    super.dispose();
  }

  void _fromPush() {
    final d = pushOpened.value;
    if (d == null || !mounted) return;
    pushOpened.value = null;
    if (d['type'] == 'LEGAL_CLIENT_CASE' && d['caseId'] != null) { Navigator.of(context).push(MaterialPageRoute(builder: (_) => ClientCaseScreen(id: '${d['caseId']}'))); return; }
    if (d['type'] == 'LEGAL_CLIENT_BOOKING') { setState(() => _tab = 2); _bookingsKey.currentState?._load(); }
  }

  Future<void> _load() async {
    try {
      final r = await Future.wait([ClientAuth.api.get('lawyer/portal/home'), ClientAuth.api.get('lawyer/public/profile')]);
      if (mounted) setState(() { _home = Map<String, dynamic>.from(r[0] as Map); _office = Map<String, dynamic>.from(r[1] as Map); });
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.status == 401 || e.status == 403) { await ClientAuth.session.clear(); widget.onSignedOut(); return; }
      toast(context, errorText(e), isError: true);
    } catch (e) {
      if (mounted) toast(context, errorText(e), isError: true);
    }
  }

  Future<void> _signOut() async {
    await ClientAuth.signOut();
    widget.onSignedOut();
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      _HomeTab(home: _home, office: _office, onRefresh: _load, onBook: () => setState(() => _tab = 1)),
      _BookTab(office: _office, cases: _maps(_home?['cases']), onBooked: () { setState(() => _tab = 2); _bookingsKey.currentState?._load(); _load(); }),
      ClientBookings(key: _bookingsKey, onChanged: _load),
      _AccountTab(home: _home, office: _office, onSignOut: _signOut),
    ];
    return Scaffold(
      body: IndexedStack(index: _tab, children: pages),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.home_outlined), selectedIcon: const Icon(Icons.home_rounded), label: _l('الرئيسية', 'Home')),
          NavigationDestination(icon: const Icon(Icons.event_available_outlined), selectedIcon: const Icon(Icons.event_available_rounded), label: _l('احجز موعد', 'Book')),
          NavigationDestination(icon: const Icon(Icons.calendar_month_outlined), selectedIcon: const Icon(Icons.calendar_month_rounded), label: _l('مواعيدي', 'My sessions')),
          NavigationDestination(icon: const Icon(Icons.person_outline_rounded), selectedIcon: const Icon(Icons.person_rounded), label: _l('حسابي', 'Account')),
        ],
      ),
    );
  }
}

String _when(String? iso) {
  if (iso == null) return '';
  final d = DateTime.parse(iso).toLocal();
  final today = DateTime.now();
  final day = DateTime(d.year, d.month, d.day).difference(DateTime(today.year, today.month, today.day)).inDays;
  final dayTxt = day == 0 ? _l('اليوم', 'Today') : day == 1 ? _l('غدًا', 'Tomorrow')
      : '${_ar ? const ['الإثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت', 'الأحد'][d.weekday - 1] : const ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][d.weekday - 1]} ${d.day}/${d.month}';
  final h = d.hour % 12 == 0 ? 12 : d.hour % 12;
  return '$dayTxt ${'$h:${d.minute.toString().padLeft(2, '0')}'} ${d.hour < 12 ? _l('ص', 'AM') : _l('م', 'PM')}';
}

String _no(Object? n) => '\u2066#$n\u2069';

class _HomeTab extends StatelessWidget {
  const _HomeTab({required this.home, required this.office, required this.onRefresh, required this.onBook});
  final Map<String, dynamic>? home, office;
  final Future<void> Function() onRefresh;
  final VoidCallback onBook;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (home == null) return const Center(child: CircularProgressIndicator());
    final cases = _maps(home!['cases']), upcoming = _maps(home!['upcoming']);
    final first = '${home!['name'] ?? ''}';
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView(padding: EdgeInsets.zero, children: [
        GradientHeader(
          child: SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 14, 20, 26),
              child: Row(children: [
                const DecoratedBox(decoration: BoxDecoration(color: Colors.white, shape: BoxShape.circle), child: Padding(padding: EdgeInsets.all(3), child: BrandLogo(size: 54))),
                const SizedBox(width: 14),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(_l('أهلًا $first', 'Hello $first'), style: const TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900)),
                  Text(_ar ? SingleBusiness.nameAr : SingleBusiness.nameEn, style: TextStyle(color: Colors.white.withValues(alpha: .85))),
                ])),
              ]),
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 24),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            if (upcoming.isNotEmpty) _NextCard(item: upcoming.first) else Card(child: ListTile(
              leading: const Icon(Icons.event_available_rounded, color: _gold),
              title: Text(_l('لا مواعيد قادمة', 'Nothing coming up'), style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text(_l('احجز استشارة مع المكتب في أي وقت', 'Book a session with the office any time')),
              trailing: FilledButton(onPressed: onBook, style: FilledButton.styleFrom(backgroundColor: _navy), child: Text(_l('احجز', 'Book'))),
            )),
            if (upcoming.length > 1) ...[
              const SizedBox(height: 6),
              for (final u in upcoming.skip(1).take(3)) Card(child: ListTile(
                dense: true,
                leading: Icon(u['kind'] == 'BOOKING' ? Icons.chat_rounded : Icons.gavel_rounded, color: _navy),
                title: Text('${u['title']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
                subtitle: Text(_when('${u['startsAt']}')),
              )),
            ],
            const SizedBox(height: 18),
            Text(_l('قضاياي', 'My cases'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
            const SizedBox(height: 8),
            if (cases.isEmpty) Padding(padding: const EdgeInsets.all(24), child: Text(_l('لا توجد قضايا مسجلة باسمك بعد.', 'No cases under your name yet.'), textAlign: TextAlign.center, style: const TextStyle(color: Color(0xFF6B7A8C))))
            else for (final k in cases) _CaseCard(k: k, money: (m) => s.money(m, 'EGP')),
          ]),
        ),
      ]),
    );
  }
}

class _NextCard extends StatelessWidget {
  const _NextCard({required this.item});
  final Map<String, dynamic> item;
  @override
  Widget build(BuildContext context) {
    final booking = item['kind'] == 'BOOKING';
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(gradient: const LinearGradient(colors: [_navy, Color(0xFF1E3A5F)]), borderRadius: BorderRadius.circular(20), boxShadow: const [BoxShadow(color: Color(0x330B1D3A), blurRadius: 16, offset: Offset(0, 6))]),
      child: Row(children: [
        Container(width: 48, height: 48, decoration: BoxDecoration(color: _gold.withValues(alpha: .18), borderRadius: BorderRadius.circular(14)), child: Icon(booking ? Icons.chat_rounded : Icons.gavel_rounded, color: _gold)),
        const SizedBox(width: 12),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(booking ? _l('موعدك القادم في المكتب', 'Your next session') : '${_l('القادم', 'Next')}: ${stepKind(item['stepKind'])}', style: const TextStyle(color: _gold, fontWeight: FontWeight.w800, fontSize: 12.5)),
          const SizedBox(height: 2),
          Text(_when('${item['startsAt']}'), style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w900)),
          Text([item['title'], if ('${item['place'] ?? ''}'.isNotEmpty) item['place'], if (item['caseNumber'] != null) _no(item['caseNumber'])].join(' • '),
              maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(color: Colors.white.withValues(alpha: .85))),
          if (booking && item['status'] == 'PENDING') Padding(padding: const EdgeInsets.only(top: 6), child: Text(_l('بانتظار تأكيد المكتب', 'Waiting for the office to confirm'), style: const TextStyle(color: Color(0xFFFCD34D), fontSize: 12, fontWeight: FontWeight.w700))),
        ])),
      ]),
    );
  }
}

class _CaseCard extends StatelessWidget {
  const _CaseCard({required this.k, required this.money});
  final Map<String, dynamic> k;
  final String Function(num) money;
  @override
  Widget build(BuildContext context) {
    final due = ((k['billedMinor'] as num?) ?? 0) - ((k['paidMinor'] as num?) ?? 0);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ClientCaseScreen(id: '${k['id']}'))),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [legalPill(k['status']), const SizedBox(width: 6), if (k['outcome'] != 'NONE') legalPill(k['outcome']), const Spacer(),
              Text(_no(k['number']), style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF6B7C8D), fontSize: 12))]),
            const SizedBox(height: 8),
            Text('${k['title']}', style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w900)),
            const SizedBox(height: 2),
            Text(dotted([caseType(k['caseType']), k['court']]), style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
            if (k['nextStepAt'] != null || due > 0) const SizedBox(height: 8),
            Row(children: [
              if (k['nextStepAt'] != null) ...[const Icon(Icons.event_rounded, size: 15, color: Color(0xFF6366F1)), const SizedBox(width: 4), Text(_when('${k['nextStepAt']}'), style: const TextStyle(color: Color(0xFF6366F1), fontWeight: FontWeight.w800, fontSize: 12.5))],
              const Spacer(),
              if (due > 0) Text('${_l('متبقي', 'Due')} ${money(due)}', style: const TextStyle(color: Color(0xFFD14343), fontWeight: FontWeight.w800, fontSize: 12.5)),
            ]),
          ]),
        ),
      ),
    );
  }
}

// ============================================================================================ one case

class ClientCaseScreen extends StatefulWidget {
  const ClientCaseScreen({super.key, required this.id});
  final String id;
  @override
  State<ClientCaseScreen> createState() => _ClientCaseScreenState();
}

class _ClientCaseScreenState extends State<ClientCaseScreen> {
  Map<String, dynamic>? _k;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try { final r = await ClientAuth.api.get('lawyer/portal/cases/${widget.id}'); if (mounted) setState(() => _k = Map<String, dynamic>.from(r as Map)); }
    catch (e) { if (mounted) toast(context, errorText(e), isError: true); }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final k = _k;
    return Scaffold(
      appBar: AppBar(title: Text(k == null ? '' : _no(k['number']))),
      body: k == null ? const Center(child: CircularProgressIndicator()) : RefreshIndicator(
        onRefresh: _load,
        child: ListView(padding: const EdgeInsets.all(16), children: _body(k, (m) => s.money(m, 'EGP'))),
      ),
    );
  }

  List<Widget> _body(Map<String, dynamic> k, String Function(num) money) {
    final steps = _maps(k['steps']), payments = _maps(k['payments']);
    final now = DateTime.now();
    final coming = steps.where((x) => x['done'] != true && DateTime.parse('${x['startsAt']}').isAfter(now)).toList().reversed.toList();
    final past = steps.where((x) => !coming.contains(x)).toList();
    final billed = (k['billedMinor'] as num?) ?? 0, paid = (k['paidMinor'] as num?) ?? 0;
    Widget row(String a, Object? b) => '${b ?? ''}'.trim().isEmpty ? const SizedBox.shrink() : Padding(padding: const EdgeInsets.symmetric(vertical: 4), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(width: 110, child: Text(a, style: const TextStyle(color: Color(0xFF6B7C8D)))), Expanded(child: Text('$b', style: const TextStyle(fontWeight: FontWeight.w700)))]));
    return [
      Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [legalPill(k['status']), const SizedBox(width: 6), if (k['outcome'] != 'NONE') legalPill(k['outcome'])]),
        const SizedBox(height: 10),
        Text('${k['title']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900)),
        const SizedBox(height: 10),
        row(_l('النوع', 'Type'), caseType(k['caseType'])),
        row(_l('المحكمة', 'Court'), dotted([k['court'], k['circuit']])),
        row(_l('رقم الدعوى', 'Court no.'), k['courtCaseNo']),
        row(_l('الخصم', 'Opponent'), k['opponent']),
        row(_l('تاريخ الفتح', 'Opened'), fmtDate('${k['openedOn']}', time: false)),
      ]))),
      const SizedBox(height: 8),
      Text(_l('الجلسات والمواعيد', 'Hearings and dates'), style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w900)),
      const SizedBox(height: 6),
      if (steps.isEmpty) Padding(padding: const EdgeInsets.all(16), child: Text(_l('لم تُحدد مواعيد بعد.', 'No dates set yet.'), style: const TextStyle(color: Color(0xFF6B7C8D)))),
      for (final x in coming) _StepTile(x: x, coming: true),
      for (final x in past) _StepTile(x: x, coming: false),
      const SizedBox(height: 14),
      Text(_l('الأتعاب', 'Fees'), style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w900)),
      const SizedBox(height: 6),
      Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(children: [
        Row(children: [Expanded(child: _money(_l('الإجمالي', 'Total'), money(billed), const Color(0xFF0B1D3A))), Expanded(child: _money(_l('المدفوع', 'Paid'), money(paid), const Color(0xFF1E9E5A))),
          Expanded(child: _money(_l('المتبقي', 'Due'), money(((k['balanceMinor'] as num?) ?? 0)), const Color(0xFFD14343)))]),
        if (billed > 0) ...[const SizedBox(height: 12), ClipRRect(borderRadius: BorderRadius.circular(8), child: LinearProgressIndicator(value: (paid / billed).clamp(0, 1).toDouble(), minHeight: 8, backgroundColor: const Color(0xFFE8EEF4), color: const Color(0xFF1E9E5A)))],
        for (final p in payments) ListTile(dense: true, contentPadding: EdgeInsets.zero, leading: const Icon(Icons.check_circle_rounded, color: Color(0xFF1E9E5A)),
            title: Text(money((p['amountMinor'] as num?) ?? 0), style: const TextStyle(fontWeight: FontWeight.w800)), trailing: Text(fmtDate('${p['paidOn']}', time: false))),
      ]))),
    ];
  }

  Widget _money(String label, String v, Color c) => Column(children: [Text(label, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)), const SizedBox(height: 4), FittedBox(child: Text(v, style: TextStyle(color: c, fontWeight: FontWeight.w900, fontSize: 15)))]);
}

class _StepTile extends StatelessWidget {
  const _StepTile({required this.x, required this.coming});
  final Map<String, dynamic> x;
  final bool coming;
  @override
  Widget build(BuildContext context) => Card(
        color: coming ? const Color(0xFFEEF2FF) : null,
        child: ListTile(
          leading: Icon(x['done'] == true ? Icons.check_circle_rounded : coming ? Icons.event_rounded : Icons.history_rounded, color: x['done'] == true ? const Color(0xFF1E9E5A) : coming ? const Color(0xFF6366F1) : const Color(0xFF9AA9BA)),
          title: Text('${stepKind(x['kind'])} — ${x['description']}', style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: Text([_when('${x['startsAt']}'), if ('${x['location'] ?? ''}'.isNotEmpty) '📍 ${x['location']}', if ('${x['result'] ?? ''}'.isNotEmpty) '${_l('النتيجة', 'Result')}: ${x['result']}'].join('\n')),
          isThreeLine: '${x['result'] ?? ''}'.isNotEmpty || '${x['location'] ?? ''}'.isNotEmpty,
        ),
      );
}

// ============================================================================================ booking

class _BookTab extends StatefulWidget {
  const _BookTab({required this.office, required this.cases, required this.onBooked});
  final Map<String, dynamic>? office;
  final List<Map<String, dynamic>> cases;
  final VoidCallback onBooked;
  @override
  State<_BookTab> createState() => _BookTabState();
}

class _BookTabState extends State<_BookTab> {
  String? _service, _caseId;
  String? _day, _slot;
  List<Map<String, dynamic>> _days = [];
  List<String>? _slots;
  final _topic = TextEditingController();
  bool _busy = false;

  List<Map<String, dynamic>> get _services => _maps(widget.office?['services']);

  Future<void> _loadDays() async {
    setState(() { _days = []; _day = null; _slots = null; _slot = null; });
    try {
      final r = await ClientAuth.api.get('lawyer/public/availability', query: {'serviceId': _service, 'days': '21'});
      if (mounted) setState(() => _days = _maps(r));
    } catch (e) { if (mounted) toast(context, errorText(e), isError: true); }
  }

  Future<void> _loadSlots(String day) async {
    setState(() { _day = day; _slots = null; _slot = null; });
    try {
      final r = await ClientAuth.api.get('lawyer/public/slots', query: {'date': day, 'serviceId': _service});
      if (mounted) setState(() => _slots = ((r as List?) ?? const []).map((e) => '$e').toList());
    } catch (e) { if (mounted) toast(context, errorText(e), isError: true); }
  }

  Future<void> _book() async {
    if (_slot == null) return;
    setState(() => _busy = true);
    Map<String, dynamic>? made;
    final ok = await run(context, () async {
      made = Map<String, dynamic>.from(await ClientAuth.api.post('lawyer/portal/bookings', {'serviceId': _service, 'startsAt': _slot, 'topic': _topic.text.trim(), 'caseId': _caseId}) as Map);
    });
    if (!mounted) return;
    setState(() => _busy = false);
    if (!ok) return;
    await showDialog<void>(context: context, builder: (c) => AlertDialog(
      icon: const Icon(Icons.check_circle_rounded, color: Color(0xFF1E9E5A), size: 48),
      title: Text(made?['status'] == 'APPROVED' ? _l('تم تأكيد موعدك', 'Your session is confirmed') : _l('تم إرسال طلبك', 'Request sent')),
      content: Text('${_when(_slot)}\n${made?['status'] == 'APPROVED' ? '' : _l('سيصلك إشعار عند تأكيد المكتب.', 'You will be notified when the office confirms.')}', textAlign: TextAlign.center),
      actions: [FilledButton(onPressed: () => Navigator.pop(c), child: Text(_l('تمام', 'OK')))],
    ));
    setState(() { _slot = null; _slots = null; _day = null; _topic.clear(); });
    widget.onBooked();
  }

  @override
  void initState() { super.initState(); if (widget.office != null) WidgetsBinding.instance.addPostFrameCallback((_) => _loadDays()); }

  @override
  void didUpdateWidget(covariant _BookTab old) {
    super.didUpdateWidget(old);
    if (old.office == null && widget.office != null) _loadDays();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (widget.office == null) return const Center(child: CircularProgressIndicator());
    if (widget.office!['bookingEnabled'] == false) return Center(child: Padding(padding: const EdgeInsets.all(28), child: Text(_l('الحجز من التطبيق متوقف الآن. تواصل مع المكتب.', 'Booking is closed right now. Please contact the office.'), textAlign: TextAlign.center)));
    return SafeArea(
      child: ListView(padding: const EdgeInsets.all(16), children: [
        Text(_l('احجز موعدًا مع المكتب', 'Book a session'), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
        const SizedBox(height: 14),
        Text(_l('1. نوع الموعد', '1. Kind of session'), style: const TextStyle(fontWeight: FontWeight.w900)),
        const SizedBox(height: 6),
        for (final sv in _services) Card(
          color: _service == sv['id'] ? const Color(0xFFEFF4FF) : null,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: _service == sv['id'] ? _navy : Colors.transparent, width: 1.5)),
          child: ListTile(
            onTap: () { setState(() => _service = '${sv['id']}'); _loadDays(); },
            leading: Icon(_service == sv['id'] ? Icons.radio_button_checked_rounded : Icons.radio_button_off_rounded, color: _navy),
            title: Text('${sv['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
            subtitle: Text(dotted(['${sv['durationMinutes']} ${_l('دقيقة', 'min')}', if (((sv['priceMinor'] as num?) ?? 0) > 0) s.money(sv['priceMinor'] as num, 'EGP')])),
          ),
        ),
        const SizedBox(height: 14),
        Text(_l('2. اليوم', '2. Day'), style: const TextStyle(fontWeight: FontWeight.w900)),
        const SizedBox(height: 6),
        SizedBox(
          height: 78,
          child: _days.isEmpty ? const Center(child: CircularProgressIndicator()) : ListView(scrollDirection: Axis.horizontal, children: [
            for (final d in _days)
              Builder(builder: (_) {
                final date = DateTime.parse('${d['date']}'), free = ((d['free'] as num?) ?? 0) > 0, on = _day == d['date'];
                return Padding(padding: const EdgeInsetsDirectional.only(end: 8), child: InkWell(
                  borderRadius: BorderRadius.circular(16),
                  onTap: free ? () => _loadSlots('${d['date']}') : null,
                  child: Container(width: 62, decoration: BoxDecoration(color: on ? _navy : free ? Colors.white : const Color(0xFFF1F3F6), borderRadius: BorderRadius.circular(16), border: Border.all(color: on ? _navy : const Color(0xFFE2E8F0))),
                    child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Text(_ar ? const ['إثن', 'ثلا', 'أرب', 'خمي', 'جمع', 'سبت', 'أحد'][date.weekday - 1] : const ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][date.weekday - 1], style: TextStyle(fontSize: 11, color: on ? Colors.white70 : const Color(0xFF6B7C8D))),
                      Text('${date.day}', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900, color: on ? Colors.white : free ? _navy : const Color(0xFFB0BBC7))),
                      Text('${date.month}/${date.year % 100}', style: TextStyle(fontSize: 10, color: on ? Colors.white70 : const Color(0xFF9AA9BA))),
                    ])),
                ));
              }),
          ]),
        ),
        if (_day != null) ...[
          const SizedBox(height: 14),
          Text(_l('3. الوقت', '3. Time'), style: const TextStyle(fontWeight: FontWeight.w900)),
          const SizedBox(height: 6),
          if (_slots == null) const Center(child: CircularProgressIndicator())
          else if (_slots!.isEmpty) Text(_l('لا أوقات متاحة في هذا اليوم', 'No free times this day'))
          else Wrap(spacing: 8, runSpacing: 8, children: [
            for (final sl in _slots!) ChoiceChip(label: Text(_when(sl).split(' ').skip(1).join(' '), textDirection: TextDirection.ltr), selected: _slot == sl, selectedColor: _gold.withValues(alpha: .3), onSelected: (_) => setState(() => _slot = sl)),
          ]),
        ],
        if (_slot != null) ...[
          const SizedBox(height: 14),
          if (widget.cases.isNotEmpty) DropdownButtonFormField<String?>(
            initialValue: _caseId,
            decoration: InputDecoration(labelText: _l('عن قضية؟ (اختياري)', 'About a case? (optional)')),
            items: [DropdownMenuItem(value: null, child: Text(_l('موضوع جديد', 'A new matter'))), for (final k in widget.cases) DropdownMenuItem(value: '${k['id']}', child: Text('${k['title']}', overflow: TextOverflow.ellipsis))],
            onChanged: (v) => setState(() => _caseId = v),
          ),
          const SizedBox(height: 10),
          TextField(controller: _topic, maxLines: 3, decoration: InputDecoration(labelText: _l('ما الموضوع؟ (اختياري)', 'What is it about? (optional)'))),
          const SizedBox(height: 14),
          FilledButton.icon(style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(54), backgroundColor: _navy), onPressed: _busy ? null : _book,
              icon: _busy ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white)) : const Icon(Icons.check_rounded),
              label: Text('${_l('تأكيد الحجز', 'Confirm')} — ${_when(_slot)}', style: const TextStyle(fontWeight: FontWeight.w900))),
        ],
      ]),
    );
  }
}

// ============================================================================================ my bookings

class ClientBookings extends StatefulWidget {
  const ClientBookings({super.key, required this.onChanged});
  final VoidCallback onChanged;
  @override
  State<ClientBookings> createState() => _ClientBookingsState();
}

class _ClientBookingsState extends State<ClientBookings> {
  List<Map<String, dynamic>>? _list;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try { final r = await ClientAuth.api.get('lawyer/portal/bookings'); if (mounted) setState(() => _list = _maps(r)); }
    catch (e) { if (mounted) toast(context, errorText(e), isError: true); }
  }

  Future<void> _cancel(Map<String, dynamic> b) async {
    final yes = await showDialog<bool>(context: context, builder: (c) => AlertDialog(title: Text(_l('إلغاء الموعد؟', 'Cancel this session?')), content: Text(_when('${b['startsAt']}')),
        actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: Text(_l('رجوع', 'Back'))), FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(_l('إلغاء الموعد', 'Cancel it')))]));
    if (yes != true || !mounted) return;
    if (await run(context, () async { await ClientAuth.api.post('lawyer/portal/bookings/${b['id']}/cancel', {}); })) { await _load(); widget.onChanged(); }
  }

  @override
  Widget build(BuildContext context) {
    final list = _list;
    return SafeArea(
      child: RefreshIndicator(
        onRefresh: _load,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          Text(_l('مواعيدي', 'My sessions'), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
          const SizedBox(height: 12),
          if (list == null) const Padding(padding: EdgeInsets.all(40), child: Center(child: CircularProgressIndicator()))
          else if (list.isEmpty) Padding(padding: const EdgeInsets.all(40), child: Text(_l('لا مواعيد بعد. احجز من «احجز موعد».', 'No sessions yet. Book one from «Book».'), textAlign: TextAlign.center, style: const TextStyle(color: Color(0xFF6B7A8C))))
          else for (final b in list) Builder(builder: (_) {
            final d = DateTime.parse('${b['startsAt']}').toLocal();
            final open = (b['status'] == 'PENDING' || b['status'] == 'APPROVED') && d.isAfter(DateTime.now());
            return Card(child: Padding(padding: const EdgeInsets.all(12), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Container(width: 58, padding: const EdgeInsets.symmetric(vertical: 8), decoration: BoxDecoration(color: open ? const Color(0xFFEFF4FF) : const Color(0xFFF1F3F6), borderRadius: BorderRadius.circular(14)),
                  child: Column(children: [Text('${d.day}', style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900, color: _navy)), Text('${d.month}/${d.year % 100}', style: const TextStyle(fontSize: 11, color: Color(0xFF6B7C8D)))])),
              const SizedBox(width: 12),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(children: [Expanded(child: Text('${b['serviceName'] ?? _l('استشارة', 'Session')}', style: const TextStyle(fontWeight: FontWeight.w900))), legalPill(b['status'])]),
                Text(_when('${b['startsAt']}'), style: const TextStyle(color: Color(0xFF6B7C8D))),
                if ('${b['decisionNote'] ?? ''}'.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 4), child: Text('${b['decisionNote']}', style: const TextStyle(fontSize: 12.5))),
                if (open) Align(alignment: AlignmentDirectional.centerEnd, child: TextButton(onPressed: () => _cancel(b), child: Text(_l('إلغاء', 'Cancel'), style: const TextStyle(color: Color(0xFFD14343))))),
              ])),
            ])));
          }),
        ]),
      ),
    );
  }
}

// ============================================================================================ account

class _AccountTab extends StatelessWidget {
  const _AccountTab({required this.home, required this.office, required this.onSignOut});
  final Map<String, dynamic>? home, office;
  final VoidCallback onSignOut;
  @override
  Widget build(BuildContext context) {
    final phone = '${office?['phone'] ?? ''}', wa = '${office?['whatsapp'] ?? ''}';
    Widget tile(IconData i, String t, {String? sub, Widget? trailing, VoidCallback? onTap, Color? color}) =>
        Card(child: ListTile(leading: Icon(i, color: color ?? _navy), title: Text(t, style: TextStyle(fontWeight: FontWeight.w800, color: color)), subtitle: sub == null ? null : Text(sub, textDirection: TextDirection.ltr, textAlign: TextAlign.start), trailing: trailing, onTap: onTap));
    return SafeArea(
      child: ListView(padding: const EdgeInsets.all(16), children: [
        Text(_l('حسابي', 'My account'), style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
        const SizedBox(height: 12),
        tile(Icons.person_rounded, '${home?['name'] ?? ''}', sub: '${home?['phone'] ?? ''}'),
        tile(Icons.lock_reset_rounded, _l('تغيير كلمة المرور', 'Change password'), trailing: const Icon(Icons.chevron_right_rounded),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const ClientPasswordScreen()))),
        tile(Icons.translate_rounded, _l('اللغة', 'Language'), trailing: const LanguageChip()),
        const SizedBox(height: 10),
        Text(_l('تواصل مع المكتب', 'Contact the office'), style: const TextStyle(fontWeight: FontWeight.w900)),
        const SizedBox(height: 6),
        if (phone.isNotEmpty) tile(Icons.call_rounded, _l('اتصال', 'Call'), sub: phone, onTap: () => launch('tel:$phone')),
        if (wa.isNotEmpty) tile(Icons.chat_rounded, 'WhatsApp', sub: wa, color: const Color(0xFF1E9E5A), onTap: () => launch('https://wa.me/${wa.replaceAll(RegExp(r'[^0-9]'), '')}')),
        tile(Icons.public_rounded, _l('موقع المكتب', 'Office website'), sub: Uri.parse(ClientAuth.base).host, onTap: () => launch(ClientAuth.base)),
        const SizedBox(height: 10),
        tile(Icons.logout_rounded, _l('تسجيل الخروج', 'Sign out'), color: const Color(0xFFD14343), onTap: onSignOut),
      ]),
    );
  }
}
