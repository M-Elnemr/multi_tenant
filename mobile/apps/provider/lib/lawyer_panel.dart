import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'clinic_panel.dart' show NotificationsScreen;
import 'domains_billing.dart';
import 'forms.dart';
import 'shell.dart' show PanelSection;
import 'admin.dart' show StaffScreen;

// ======================================================================================= labels

bool get _ar => AppLocale.isAr;
String _l(String ar, String en) => _ar ? ar : en;

const kCaseTypes = ['CIVIL', 'CRIMINAL', 'FAMILY', 'COMMERCIAL', 'LABOUR', 'REAL_ESTATE', 'ADMINISTRATIVE', 'ECONOMIC', 'INHERITANCE', 'CONTRACTS', 'IP', 'TAX', 'ARBITRATION', 'CASSATION', 'CONSULTATION', 'OTHER'];
const _typeAr = {'CIVIL': 'مدني', 'CRIMINAL': 'جنائي', 'FAMILY': 'أحوال شخصية', 'COMMERCIAL': 'تجاري', 'LABOUR': 'عمالي', 'REAL_ESTATE': 'عقاري', 'ADMINISTRATIVE': 'إداري', 'ECONOMIC': 'اقتصادي', 'INHERITANCE': 'مواريث', 'CONTRACTS': 'عقود', 'IP': 'ملكية فكرية', 'TAX': 'ضرائب', 'ARBITRATION': 'تحكيم', 'CASSATION': 'نقض', 'CONSULTATION': 'استشارة', 'OTHER': 'أخرى'};
String caseType(Object? t) => _ar ? (_typeAr['$t'] ?? '$t') : '$t'.replaceAll('_', ' ').toLowerCase();
const _statusAr = {'OPEN': 'متداولة', 'UNDER_STUDY': 'تحت الدراسة', 'CLOSED': 'مغلقة', 'WON': 'كسب', 'LOST': 'خسارة', 'SETTLED': 'صلح', 'NONE': 'بدون نتيجة',
  'PENDING': 'في الانتظار', 'APPROVED': 'مؤكد', 'DENIED': 'مرفوض', 'CANCELLED': 'ملغي', 'DONE': 'تم', 'NO_SHOW': 'لم يحضر'};
String legalStatus(Object? s) => _ar ? (_statusAr['$s'] ?? '$s') : '$s'.replaceAll('_', ' ').toLowerCase();
Color legalTone(Object? s) => switch ('$s') {
      'OPEN' => const Color(0xFF2563EB), 'UNDER_STUDY' || 'PENDING' => const Color(0xFFD98200), 'WON' || 'APPROVED' || 'DONE' => const Color(0xFF1E9E5A),
      'LOST' || 'DENIED' || 'NO_SHOW' => const Color(0xFFD14343), 'SETTLED' => const Color(0xFF0EA5E9), _ => const Color(0xFF6B7C8D),
    };
const _stepAr = {'HEARING': 'جلسة', 'DEADLINE': 'موعد نهائي', 'FILING': 'إيداع', 'MEETING': 'اجتماع', 'OTHER': 'موعد', 'BOOKING': 'استشارة', 'NOTE': 'ملاحظة'};
String stepKind(Object? k) => _ar ? (_stepAr['$k'] ?? '$k') : '$k'.toLowerCase();
const _pmAr = {'CASH': 'نقدي', 'TRANSFER': 'تحويل بنكي', 'INSTAPAY': 'إنستاباي', 'WALLET': 'محفظة', 'CHEQUE': 'شيك', 'OTHER': 'أخرى'};

Widget legalPill(Object? s) {
  final c = legalTone(s);
  return Container(padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3), decoration: BoxDecoration(color: c.withValues(alpha: .12), borderRadius: BorderRadius.circular(20)), child: Text(legalStatus(s), style: TextStyle(color: c, fontWeight: FontWeight.w800, fontSize: 11)));
}

Color calColor(Map<String, dynamic> i) {
  if (i['kind'] == 'BOOKING') return i['status'] == 'PENDING' ? const Color(0xFFF59E0B) : const Color(0xFF14B8A6);
  if (i['kind'] == 'NOTE') return const Color(0xFFEAB308);
  if (i['stepKind'] == 'DEADLINE') return const Color(0xFFEF4444);
  if (i['stepKind'] == 'HEARING') return const Color(0xFF6366F1);
  return const Color(0xFF8B5CF6);
}

List<Map<String, dynamic>> _maps(dynamic v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
String _hm(DateTime d) => '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
String _dayLabel(DateTime d) => '${_ar ? const ['الإثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت', 'الأحد'][d.weekday - 1] : const ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][d.weekday - 1]} ${d.day}/${d.month}';

List<PanelSection> lawyerSections() => [
      PanelSection('الرئيسية', 'Home', Icons.dashboard_rounded, group: 'office', bottom: true, build: (c) => LawyerHome(ctx: c)),
      PanelSection('الأجندة', 'Schedule', Icons.calendar_month_rounded, screen: 'schedule', group: 'office', bottom: true, build: (c) => LawyerSchedule(ctx: c)),
      PanelSection('طلبات الحجز', 'Bookings', Icons.event_available_rounded, screen: 'bookings', group: 'office', build: (c) => LawyerBookings(ctx: c)),
      PanelSection('القضايا', 'Cases', Icons.work_rounded, screen: 'cases', group: 'work', bottom: true, build: (c) => LawyerCases(ctx: c)),
      PanelSection('الموكلون', 'Clients', Icons.people_alt_rounded, screen: 'clients', group: 'work', bottom: true, build: (c) => LawyerClients(ctx: c)),
      PanelSection('الأتعاب والمدفوعات', 'Fees & payments', Icons.account_balance_wallet_rounded, screen: 'finance', group: 'work', build: (c) => LawyerFinance(ctx: c)),
      PanelSection('بحث شامل', 'Search', Icons.search_rounded, screen: ['cases', 'clients'], group: 'work', build: (c) => LawyerSearch(ctx: c)),
      PanelSection('الخدمات', 'Services', Icons.checklist_rounded, screen: 'settings', group: 'setup', build: (c) => LawyerServices(ctx: c)),
      PanelSection('مواعيد العمل', 'Working hours', Icons.schedule_rounded, screen: ['schedule', 'settings'], group: 'setup', build: (c) => LawyerHours(ctx: c)),
      PanelSection('الموقع والإعدادات', 'Website & settings', Icons.settings_rounded, screen: 'settings', group: 'setup', build: (c) => LawyerSettings(ctx: c)),
      PanelSection('النطاقات', 'Domains', Icons.language_rounded, screen: 'domains', group: 'setup', build: (c) => DomainsScreen(ctx: c)),
      PanelSection('الموظفون', 'Staff', Icons.badge_rounded, perm: 'staff.manage', group: 'account', build: (c) => StaffScreen(ctx: c)),
      PanelSection('الفواتير والاشتراك', 'Billing', Icons.credit_card_rounded, screen: 'billing', group: 'account', build: (c) => BillingScreen(ctx: c)),
      PanelSection('الإشعارات', 'Notifications', Icons.notifications_rounded, group: 'account', build: (c) => NotificationsScreen(ctx: c)),
    ];

/// Opens what a lawyer push is about (a booking, a case, the schedule).
bool openLegalPush(BuildContext context, Ctx c, Map<String, dynamic> d) {
  if (d['caseId'] != null) { Navigator.of(context).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: c, id: '${d['caseId']}'))); return true; }
  if (d['type'] == 'LEGAL_BOOKING') { Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(_l('طلبات الحجز', 'Bookings'))), body: LawyerBookings(ctx: c)))); return true; }
  if (d['type'] == 'LEGAL_SCHEDULE') { Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(_l('الأجندة', 'Schedule'))), body: LawyerSchedule(ctx: c)))); return true; }
  return false;
}

// ======================================================================================= home

class LawyerHome extends StatelessWidget {
  const LawyerHome({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final today = ymd(DateTime.now());
    return Async<List<dynamic>>(
      load: () async => [await ctx.api.get('lawyer/overview'), await ctx.api.get('lawyer/schedule', query: {'from': today, 'to': today})],
      builder: (context, both, reload) {
        final o = Map<String, dynamic>.from(both[0] as Map);
        final agenda = _maps(both[1]);
        Widget kpi(String label, String value, IconData icon, Color color, {String? hint}) => Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [Container(width: 34, height: 34, decoration: BoxDecoration(color: color.withValues(alpha: .12), borderRadius: BorderRadius.circular(10)), child: Icon(icon, color: color, size: 19)), const Spacer()]),
              const SizedBox(height: 8),
              Text(value, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 22)),
              Text(label, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)),
              if (hint != null) Text(hint, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 11)),
            ])));
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
            Container(
              padding: const EdgeInsets.all(18),
              decoration: BoxDecoration(gradient: const LinearGradient(colors: [Color(0xFF0B1D3A), Color(0xFF1E3A5F)]), borderRadius: BorderRadius.circular(22)),
              child: Row(children: [
                const Text('⚖️', style: TextStyle(fontSize: 34)),
                const SizedBox(width: 12),
                Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(_l('أجندة اليوم', "Today's agenda"), style: const TextStyle(color: Color(0xFFC9A227), fontWeight: FontWeight.w800)),
                  Text(_l('${agenda.length} موعد اليوم', '${agenda.length} items today'), style: const TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.w900)),
                ])),
              ]),
            ),
            const SizedBox(height: 10),
            GridView.count(crossAxisCount: 2, shrinkWrap: true, physics: const NeverScrollableScrollPhysics(), mainAxisSpacing: 6, crossAxisSpacing: 6, childAspectRatio: 1.35, children: [
              kpi(_l('طلبات للمراجعة', 'Requests to review'), '${o['pendingBookings'] ?? 0}', Icons.event_available_rounded, const Color(0xFFD98200)),
              kpi(_l('قضايا متداولة', 'Open cases'), '${o['openCases'] ?? 0}', Icons.work_rounded, const Color(0xFF2563EB), hint: _l('${o['underStudy'] ?? 0} تحت الدراسة', '${o['underStudy'] ?? 0} under study')),
              kpi(_l('نسبة الكسب', 'Win rate'), o['winRate'] == null ? '—' : '${o['winRate']}%', Icons.emoji_events_rounded, const Color(0xFF1E9E5A), hint: _l('${o['won'] ?? 0} كسب · ${o['lost'] ?? 0} خسارة', '${o['won'] ?? 0} won · ${o['lost'] ?? 0} lost')),
              kpi(_l('أتعاب مستحقة', 'Fees due'), s.money((o['dueMinor'] as num?) ?? 0, ctx.currency), Icons.receipt_long_rounded, const Color(0xFFD14343), hint: _l('المحصل هذا الشهر ${s.money((o['collectedThisMonthMinor'] as num?) ?? 0, ctx.currency)}', 'This month ${s.money((o['collectedThisMonthMinor'] as num?) ?? 0, ctx.currency)}')),
            ]),
            const SizedBox(height: 14),
            Text(_l('اليوم', 'Today'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
            if (agenda.isEmpty) Padding(padding: const EdgeInsets.all(20), child: Center(child: Text(_l('☕ لا مواعيد اليوم', '☕ Nothing today'), style: const TextStyle(color: Color(0xFF6B7C8D))))),
            for (final i in agenda) _CalTile(ctx: ctx, item: i, onChanged: reload),
            const SizedBox(height: 14),
            Text(_l('الجلسات والمواعيد القادمة', 'Coming hearings & deadlines'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
            for (final u in _maps(o['upcoming'])) Card(child: ListTile(
              leading: CircleAvatar(backgroundColor: const Color(0x226366F1), child: Text('${DateTime.parse('${u['startsAt']}').toLocal().day}', style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF4338CA)))),
              title: Text('${u['description']}', style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text('#${u['number']} · ${stepKind(u['kind'])} · ${fmtDate('${u['startsAt']}')}'),
              onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: ctx, id: '${u['caseId']}'))),
            )),
          ]),
        );
      },
    );
  }
}

class _CalTile extends StatelessWidget {
  const _CalTile({required this.ctx, required this.item, required this.onChanged});
  final Ctx ctx;
  final Map<String, dynamic> item;
  final Future<void> Function() onChanged;
  @override
  Widget build(BuildContext context) {
    final i = item;
    final c = calColor(i);
    final at = DateTime.parse('${i['startsAt']}').toLocal();
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () {
          if (i['caseId'] != null) { Navigator.of(context).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: ctx, id: '${i['caseId']}'))).then((_) => onChanged()); return; }
          if (i['phone'] != null) dial('${i['phone']}');
        },
        child: IntrinsicHeight(child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Container(width: 5, color: c),
          Expanded(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${i['allDay'] == true ? _l('طوال اليوم', 'All day') : _hm(at)} · ${stepKind(i['kind'] == 'STEP' ? i['stepKind'] : i['kind'])}${i['caseNumber'] != null ? ' · #${i['caseNumber']}' : ''}', style: TextStyle(color: c, fontWeight: FontWeight.w800, fontSize: 12)),
            const SizedBox(height: 2),
            Text('${i['title']}', style: TextStyle(fontWeight: FontWeight.w800, decoration: i['done'] == true ? TextDecoration.lineThrough : null)),
            if ('${i['location'] ?? ''}'.isNotEmpty) Text('📍 ${i['location']}', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
          ]))),
          if (i['kind'] == 'BOOKING' && i['phone'] != null) IconButton(onPressed: () => whatsapp('${i['phone']}'), icon: const Icon(Icons.chat_rounded, color: Color(0xFF1E9E5A))),
          if (i['kind'] == 'NOTE' && ctx.canWrite('schedule')) IconButton(onPressed: () async { if (await confirm(context, _l('حذف الملاحظة؟', 'Delete this note?')) && context.mounted && await run(context, () => ctx.api.delete('lawyer/schedule/notes/${i['id']}'))) await onChanged(); }, icon: const Icon(Icons.delete_outline_rounded)),
        ])),
      ),
    );
  }
}

// ======================================================================================= schedule

class LawyerSchedule extends StatefulWidget {
  const LawyerSchedule({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<LawyerSchedule> createState() => _LawyerScheduleState();
}

class _LawyerScheduleState extends State<LawyerSchedule> {
  late DateTime _day = DateTime(DateTime.now().year, DateTime.now().month, DateTime.now().day);
  late DateTime _weekStart = _startOfWeek(_day);
  List<Map<String, dynamic>>? _items;
  Object? _error;

  static DateTime _startOfWeek(DateTime d) => d.subtract(Duration(days: (d.weekday + 1) % 7));   // Saturday first

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try {
      final r = await widget.ctx.api.get('lawyer/schedule', query: {'from': ymd(_weekStart), 'to': ymd(_weekStart.add(const Duration(days: 6)))});
      if (mounted) setState(() { _items = _maps(r); _error = null; });
    } catch (e) { if (mounted) setState(() => _error = e); }
  }

  void _week(int n) { setState(() { _weekStart = _weekStart.add(Duration(days: 7 * n)); _day = _weekStart; _items = null; }); _load(); }

  Future<void> _add() async {
    final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, showDragHandle: true, builder: (_) => _AddToSchedule(ctx: widget.ctx, day: _day));
    if (ok == true) _load();
  }

  @override
  Widget build(BuildContext context) {
    final days = List.generate(7, (i) => _weekStart.add(Duration(days: i)));
    final todays = (_items ?? []).where((i) { final d = DateTime.parse('${i['startsAt']}').toLocal(); return d.year == _day.year && d.month == _day.month && d.day == _day.day; }).toList();
    int countOn(DateTime d) => (_items ?? []).where((i) { final x = DateTime.parse('${i['startsAt']}').toLocal(); return x.year == d.year && x.month == d.month && x.day == d.day; }).length;
    final now = DateTime.now();
    return Scaffold(
      backgroundColor: Colors.transparent,
      floatingActionButton: widget.ctx.canWrite('schedule') ? FloatingActionButton.extended(onPressed: _add, icon: const Icon(Icons.add_rounded), label: Text(_l('إضافة', 'Add'))) : null,
      body: Column(children: [
        Padding(padding: const EdgeInsets.fromLTRB(8, 6, 8, 0), child: Row(children: [
          IconButton(onPressed: () => _week(-1), icon: Icon(_ar ? Icons.chevron_right_rounded : Icons.chevron_left_rounded)),
          Expanded(child: Text('${_weekStart.day}/${_weekStart.month} – ${days.last.day}/${days.last.month}/${days.last.year}', textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
          TextButton(onPressed: () { setState(() { _day = DateTime(now.year, now.month, now.day); _weekStart = _startOfWeek(_day); _items = null; }); _load(); }, child: Text(_l('اليوم', 'Today'))),
          IconButton(onPressed: () => _week(1), icon: Icon(_ar ? Icons.chevron_left_rounded : Icons.chevron_right_rounded)),
        ])),
        SizedBox(height: 84, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 10), children: [
          for (final d in days) Padding(padding: const EdgeInsets.all(4), child: InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: () => setState(() => _day = d),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 200), width: 54,
              decoration: BoxDecoration(color: d == _day ? const Color(0xFF0B1D3A) : Theme.of(context).cardColor, borderRadius: BorderRadius.circular(16), border: Border.all(color: d.year == now.year && d.month == now.month && d.day == now.day ? const Color(0xFFC9A227) : Colors.transparent, width: 2)),
              child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                Text(_ar ? const ['ن', 'ث', 'ر', 'خ', 'ج', 'س', 'ح'][d.weekday - 1] : const ['M', 'T', 'W', 'T', 'F', 'S', 'S'][d.weekday - 1], style: TextStyle(fontSize: 12, color: d == _day ? Colors.white70 : const Color(0xFF6B7C8D))),
                Text('${d.day}', style: TextStyle(fontWeight: FontWeight.w900, fontSize: 18, color: d == _day ? Colors.white : null)),
                if (countOn(d) > 0) Container(margin: const EdgeInsets.only(top: 2), padding: const EdgeInsets.symmetric(horizontal: 6), decoration: BoxDecoration(color: const Color(0xFFC9A227), borderRadius: BorderRadius.circular(8)), child: Text('${countOn(d)}', style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w900, color: Color(0xFF0B1D3A)))),
              ]),
            ),
          )),
        ])),
        Expanded(child: _error != null ? errorState(_error!, _load) : _items == null ? const Center(child: CircularProgressIndicator()) : RefreshIndicator(
          onRefresh: _load,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 6, 16, 96), children: [
            Text(_dayLabel(_day), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
            const SizedBox(height: 6),
            if (todays.isEmpty) Padding(padding: const EdgeInsets.all(30), child: Center(child: Text(_l('لا شيء في هذا اليوم — اضغط إضافة', 'Nothing on this day — tap Add'), style: const TextStyle(color: Color(0xFF6B7C8D))))),
            for (final i in todays) _CalTile(ctx: widget.ctx, item: i, onChanged: _load),
          ]),
        )),
      ]),
    );
  }
}

/// Add from the schedule: a case step (pick a case or create one here), a note, or a session booked by phone.
class _AddToSchedule extends StatefulWidget {
  const _AddToSchedule({required this.ctx, required this.day});
  final Ctx ctx;
  final DateTime day;
  @override
  State<_AddToSchedule> createState() => _AddToScheduleState();
}

class _AddToScheduleState extends State<_AddToSchedule> {
  final fe = FieldErrors();
  String kind = 'STEP', stepK = 'HEARING';
  late DateTime at = DateTime(widget.day.year, widget.day.month, widget.day.day, 10);
  final title = TextEditingController(), body = TextEditingController(), location = TextEditingController(), phone = TextEditingController(), caseTitle = TextEditingController();
  Map<String, dynamic>? pickedCase;
  bool newCase = false, busy = false;
  String newType = 'CIVIL';

  @override
  void initState() { super.initState(); fe.addListener(() { if (mounted) setState(() {}); }); }

  Future<void> _pickCase() async {
    final c = await showModalBottomSheet<Map<String, dynamic>>(context: context, isScrollControlled: true, showDragHandle: true, builder: (_) => _CasePicker(ctx: widget.ctx));
    if (c != null) setState(() { pickedCase = c; fe.clear('caseId'); });
  }

  Future<void> _save() async {
    final ok = fe.check({
      if (kind == 'STEP') 'step.description': title.text.trim().isEmpty ? _l('اكتب وصف الموعد', 'Describe this step') : null,
      if (kind == 'NOTE') 'title': title.text.trim().isEmpty ? _l('اكتب الملاحظة', 'Write the note') : null,
      if (kind == 'BOOKING') 'name': title.text.trim().isEmpty ? _l('اكتب اسم الموكل', "Write the client's name") : null,
      if (kind == 'STEP' && !newCase && pickedCase == null) 'caseId': _l('اختر قضية أو أنشئ واحدة جديدة', 'Pick a case or create a new one'),
      if (newCase && caseTitle.text.trim().length < 3) 'newCase.title': _l('اكتب عنوانًا واضحًا للقضية', 'Give the case a clear title'),
    });
    if (!ok) return;
    setState(() => busy = true);
    final nc = newCase ? {'title': caseTitle.text.trim(), 'caseType': newType} : null;
    final done = await runForm(context, fe, () async {
      if (kind == 'STEP') {
        await widget.ctx.api.post('lawyer/schedule/steps', {'caseId': newCase ? null : pickedCase?['id'], 'newCase': nc, 'step': {'kind': stepK, 'startsAt': at.toUtc().toIso8601String(), 'description': title.text.trim(), 'location': location.text.trim()}});
      } else if (kind == 'NOTE') {
        await widget.ctx.api.post('lawyer/schedule/notes', {'startsAt': at.toUtc().toIso8601String(), 'title': title.text.trim(), 'body': body.text.trim(), 'caseId': newCase ? null : pickedCase?['id'], 'newCase': nc});
      } else {
        await widget.ctx.api.post('lawyer/bookings', {'name': title.text.trim(), 'phone': phone.text.trim(), 'topic': body.text.trim(), 'startsAt': at.toUtc().toIso8601String(), 'caseId': pickedCase?['id']});
      }
    });
    if (!mounted) return;
    setState(() => busy = false);
    if (done) Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    Widget err(String p) => fe.of(p) == null ? const SizedBox.shrink() : Text(fe.of(p)!, style: const TextStyle(color: Color(0xFFD14343), fontWeight: FontWeight.w700, fontSize: 12));
    return Padding(
      padding: EdgeInsets.fromLTRB(18, 0, 18, MediaQuery.of(context).viewInsets.bottom + 16),
      child: SingleChildScrollView(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
        SegmentedButton<String>(segments: [ButtonSegment(value: 'STEP', label: Text(_l('جلسة/موعد', 'Hearing'))), ButtonSegment(value: 'NOTE', label: Text(_l('ملاحظة', 'Note'))), ButtonSegment(value: 'BOOKING', label: Text(_l('استشارة', 'Session')))], selected: {kind}, onSelectionChanged: (v) => setState(() { kind = v.first; fe.clear(); })),
        const SizedBox(height: 12),
        if (kind == 'STEP') Wrap(spacing: 6, children: [for (final k in const ['HEARING', 'DEADLINE', 'FILING', 'MEETING', 'OTHER']) ChoiceChip(label: Text(stepKind(k)), selected: stepK == k, onSelected: (_) => setState(() => stepK = k))]),
        const SizedBox(height: 8),
        tf(title, kind == 'STEP' ? _l('ما هو؟ (مثال: الجلسة الأولى)', 'What is it?') : kind == 'NOTE' ? _l('الملاحظة', 'Note') : _l('اسم الموكل', 'Client name'), fe: fe, field: kind == 'STEP' ? 'step.description' : kind == 'NOTE' ? 'title' : 'name'),
        if (kind == 'BOOKING') tf(phone, _l('الموبايل', 'Phone'), ltr: true, fe: fe, field: 'phone'),
        if (kind == 'STEP') tf(location, _l('المكان', 'Place')),
        if (kind != 'STEP') tf(body, kind == 'NOTE' ? _l('التفاصيل', 'Details') : _l('الموضوع', 'Topic'), lines: 2),
        ListTile(
          key: fe.key('startsAt'), contentPadding: EdgeInsets.zero, leading: const Icon(Icons.event_rounded),
          title: Text('${_dayLabel(at)}  ${_hm(at)}', style: const TextStyle(fontWeight: FontWeight.w800)),
          subtitle: fe.of('startsAt') != null ? Text(fe.of('startsAt')!, style: const TextStyle(color: Color(0xFFD14343))) : null,
          onTap: () async {
            final d = await showDatePicker(context: context, firstDate: DateTime.now().subtract(const Duration(days: 365)), lastDate: DateTime.now().add(const Duration(days: 730)), initialDate: at);
            if (d == null || !context.mounted) return;
            final t = await showTimePicker(context: context, initialTime: TimeOfDay.fromDateTime(at));
            setState(() => at = DateTime(d.year, d.month, d.day, t?.hour ?? at.hour, t?.minute ?? at.minute));
          },
        ),
        const Divider(),
        Row(children: [
          Expanded(child: Text(_l('القضية', 'Case') + (kind == 'STEP' ? '' : _l(' (اختياري)', ' (optional)')), style: const TextStyle(fontWeight: FontWeight.w800))),
          if (kind != 'BOOKING') ChoiceChip(label: Text(_l('+ قضية جديدة', '+ New case')), selected: newCase, onSelected: (v) => setState(() { newCase = v; if (v) pickedCase = null; })),
        ]),
        const SizedBox(height: 6),
        if (newCase) ...[
          tf(caseTitle, _l('عنوان القضية', 'Case title'), fe: fe, field: 'newCase.title'),
          DropdownButtonFormField<String>(initialValue: newType, decoration: InputDecoration(labelText: _l('نوع القضية', 'Case type'), isDense: true), items: [for (final t in kCaseTypes) DropdownMenuItem(value: t, child: Text(caseType(t)))], onChanged: (v) => setState(() => newType = v ?? newType)),
        ] else OutlinedButton.icon(key: fe.key('caseId'), onPressed: _pickCase, icon: const Icon(Icons.work_outline_rounded), label: Text(pickedCase == null ? _l('اختر من القضايا الحالية', 'Pick from current cases') : '#${pickedCase!['number']} · ${pickedCase!['title']}', overflow: TextOverflow.ellipsis)),
        err('caseId'),
        const SizedBox(height: 14),
        if (kind != 'NOTE') Text('🔔 ${_l('سيصلك تنبيه قبل الموعد بساعة', 'You get a reminder one hour before')}', style: const TextStyle(fontSize: 12, color: Color(0xFF4338CA))),
        const SizedBox(height: 10),
        FilledButton(onPressed: busy ? null : _save, child: busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(_l('حفظ', 'Save'))),
      ])),
    );
  }
}

class _CasePicker extends StatefulWidget {
  const _CasePicker({required this.ctx});
  final Ctx ctx;
  @override
  State<_CasePicker> createState() => _CasePickerState();
}

class _CasePickerState extends State<_CasePicker> {
  final q = TextEditingController();
  List<Map<String, dynamic>> res = [];
  @override
  void initState() { super.initState(); _load(); }
  Future<void> _load() async {
    try {
      final r = await widget.ctx.api.get('lawyer/cases', query: {'pageSize': '30', 'status': 'OPEN,UNDER_STUDY', if (q.text.trim().isNotEmpty) 'q': q.text.trim()}) as Map;
      if (mounted) setState(() => res = _maps(r['data']));
    } catch (_) {}
  }
  @override
  Widget build(BuildContext context) => SizedBox(height: MediaQuery.of(context).size.height * .75, child: Column(children: [
        Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: TextField(controller: q, onChanged: (_) => _load(), decoration: InputDecoration(prefixIcon: const Icon(Icons.search_rounded), hintText: _l('بالرقم أو العنوان أو الموكل', 'Number, title or client')))),
        Expanded(child: ListView(children: [for (final c in res) ListTile(title: Text('#${c['number']} · ${c['title']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text('${c['clientNames'] ?? ''}'), onTap: () => Navigator.pop(context, c))])),
      ]));
}

// ======================================================================================= bookings

class LawyerBookings extends StatefulWidget {
  const LawyerBookings({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<LawyerBookings> createState() => _LawyerBookingsState();
}

class _LawyerBookingsState extends State<LawyerBookings> {
  final int _n = 0;

  Future<void> _deny(Map<String, dynamic> b, Future<void> Function() reload) async {
    final note = TextEditingController();
    final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, showDragHandle: true, builder: (c) => StatefulBuilder(builder: (c, set) => Padding(
          padding: EdgeInsets.fromLTRB(18, 0, 18, MediaQuery.of(c).viewInsets.bottom + 16),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(_l('رفض طلب ${b['guestName']}', "Deny ${b['guestName']}'s request"), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
            const SizedBox(height: 8),
            Wrap(spacing: 6, runSpacing: 6, children: [for (final r in [_l('الموعد لم يعد متاحًا — برجاء اختيار موعد آخر', 'This time is no longer available'), _l('الموضوع خارج نطاق تخصصي', 'Outside my practice'), _l('برجاء الاتصال بالمكتب', 'Please call the office')]) ActionChip(label: Text(r), onPressed: () => set(() => note.text = r))]),
            const SizedBox(height: 8),
            TextField(controller: note, maxLines: 2, decoration: InputDecoration(labelText: _l('السبب (يظهر للعميل)', 'Reason (shown to the client)'))),
            const SizedBox(height: 10),
            FilledButton(style: FilledButton.styleFrom(backgroundColor: const Color(0xFFD14343)), onPressed: () => note.text.trim().isEmpty ? null : Navigator.pop(c, true), child: Text(_l('رفض', 'Deny'))),
          ]),
        )));
    if (ok == true && mounted && await run(context, () => widget.ctx.api.put('lawyer/bookings/${b['id']}', {'status': 'DENIED', 'note': note.text.trim()}))) await reload();
  }

  Future<void> _move(Map<String, dynamic> b, Future<void> Function() reload) async {
    final start = DateTime.parse('${b['startsAt']}').toLocal();
    final d = await showDatePicker(context: context, firstDate: DateTime.now().subtract(const Duration(days: 1)), lastDate: DateTime.now().add(const Duration(days: 365)), initialDate: start.isBefore(DateTime.now()) ? DateTime.now() : start);
    if (d == null || !mounted) return;
    final slots = ((await widget.ctx.api.get('lawyer/slots', query: {'date': ymd(d)})) as List).cast<String>();
    if (!mounted) return;
    if (slots.isEmpty) { toast(context, _l('لا توجد أوقات متاحة في هذا اليوم', 'No free times this day'), isError: true); return; }
    final picked = await showModalBottomSheet<String>(context: context, showDragHandle: true, builder: (c) => Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 16), child: Wrap(spacing: 8, runSpacing: 8, children: [
          for (final sl in slots) ActionChip(label: Text(_hm(DateTime.parse(sl).toLocal())), onPressed: () => Navigator.pop(c, sl)),
        ])));
    if (picked != null && mounted && await run(context, () => widget.ctx.api.put('lawyer/bookings/${b['id']}', {'status': 'APPROVED', 'startsAt': picked}))) await reload();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ctx = widget.ctx;
    return PagedList(
      key: ValueKey('bk$_n'),
      api: ctx.api,
      path: 'lawyer/bookings',
      searchHint: _l('الاسم أو الموبايل أو الكود', 'Name, phone or code'),
      filterParam: 'status',
      filters: [('PENDING', legalStatus('PENDING')), ('APPROVED', legalStatus('APPROVED')), ('', s.all), ('DONE', legalStatus('DONE')), ('DENIED', legalStatus('DENIED')), ('CANCELLED', legalStatus('CANCELLED'))],
      dateRange: true, futureDates: true,
      emptyText: _l('لا توجد حجوزات — شارك رابط موقعك ليحجز العملاء', 'No bookings — share your website link'),
      itemBuilder: (c, b, reload) {
        final at = DateTime.parse('${b['startsAt']}').toLocal();
        final pending = b['status'] == 'PENDING';
        return Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Container(width: 52, height: 56, alignment: Alignment.center, decoration: BoxDecoration(color: pending ? const Color(0x22F59E0B) : const Color(0x2214B8A6), borderRadius: BorderRadius.circular(14)),
                child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [Text('${at.day}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 20)), Text(_hm(at), style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700))])),
            const SizedBox(width: 12),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${b['guestName']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
              Text(dotted([_dayLabel(at), b['serviceName']]), style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12)),
            ])),
            legalPill(b['status']),
          ]),
          if ('${b['topic'] ?? ''}'.isNotEmpty) Container(margin: const EdgeInsets.only(top: 8), padding: const EdgeInsets.all(10), decoration: BoxDecoration(color: const Color(0x0A000000), borderRadius: BorderRadius.circular(10)), child: Text('${b['topic']}')),
          const SizedBox(height: 6),
          Wrap(spacing: 6, runSpacing: 4, children: [
            ActionChip(avatar: const Icon(Icons.phone_rounded, size: 16), label: Text(localPhone(b['guestPhone']), textDirection: TextDirection.ltr), onPressed: () => dial('${b['guestPhone']}')),
            ActionChip(avatar: const Icon(Icons.chat_rounded, size: 16), label: const Text('WhatsApp'), onPressed: () => whatsapp('${b['guestPhone']}', _l('مرحبًا ${b['guestName']}، بخصوص حجزك ${b['code']}:', 'Hello ${b['guestName']}, about your booking ${b['code']}:'))),
            if (ctx.canWrite('bookings') && pending) FilledButton.icon(onPressed: () async { if (await run(c, () => ctx.api.put('lawyer/bookings/${b['id']}', {'status': 'APPROVED'}))) await reload(); }, icon: const Icon(Icons.check_rounded, size: 18), label: Text(_l('موافقة', 'Approve'))),
            if (ctx.canWrite('bookings') && pending) OutlinedButton(onPressed: () => _deny(b, reload), child: Text(_l('رفض', 'Deny'))),
            if (ctx.canWrite('bookings') && (pending || b['status'] == 'APPROVED')) TextButton(onPressed: () => _move(b, reload), child: Text(_l('تغيير الموعد', 'Move'))),
            if (ctx.canWrite('bookings') && b['status'] == 'APPROVED' && at.isBefore(DateTime.now())) TextButton(onPressed: () async { if (await run(c, () => ctx.api.put('lawyer/bookings/${b['id']}', {'status': 'DONE'}))) await reload(); }, child: Text(_l('✓ تم', '✓ Done'))),
          ]),
        ])));
      },
    );
  }
}

// ======================================================================================= cases

class LawyerCases extends StatefulWidget {
  const LawyerCases({super.key, required this.ctx, this.clientId});
  final Ctx ctx;
  final String? clientId;
  @override
  State<LawyerCases> createState() => _LawyerCasesState();
}

class _LawyerCasesState extends State<LawyerCases> {
  final Set<String> _status = {}, _outcome = {}, _types = {};
  int _n = 0;

  Future<void> _types_() async {
    final picked = Set<String>.from(_types);
    final ok = await showModalBottomSheet<bool>(context: context, showDragHandle: true, builder: (c) => StatefulBuilder(builder: (c, set) => Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 16), child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(_l('نوع القضية', 'Case type'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
          const SizedBox(height: 8),
          Wrap(spacing: 6, runSpacing: 6, children: [for (final t in kCaseTypes) FilterChip(label: Text(caseType(t)), selected: picked.contains(t), onSelected: (v) => set(() => v ? picked.add(t) : picked.remove(t)))]),
          const SizedBox(height: 12),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(_l('عرض النتائج', 'Show results'))),
        ]))));
    if (ok == true) setState(() { _types..clear()..addAll(picked); _n++; });
  }

  Widget _chip(String label, Set<String> set, String v, Color color) => Padding(padding: const EdgeInsets.symmetric(horizontal: 3), child: FilterChip(
        label: Text(label), selected: set.contains(v), showCheckmark: false, selectedColor: color.withValues(alpha: .18),
        avatar: CircleAvatar(radius: 5, backgroundColor: color), onSelected: (x) => setState(() { x ? set.add(v) : set.remove(v); _n++; })));

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ctx = widget.ctx;
    return PagedList(
      key: ValueKey('cases$_n'),
      api: ctx.api,
      path: 'lawyer/cases',
      query: {if (_status.isNotEmpty) 'status': _status.join(','), if (_outcome.isNotEmpty) 'outcome': _outcome.join(','), if (_types.isNotEmpty) 'type': _types.join(','), if (widget.clientId != null) 'clientId': widget.clientId},
      searchHint: _l('العنوان أو الرقم أو الخصم أو الموكل', 'Title, number, opponent or client'),
      dateRange: true, dateLabel: _l('تاريخ الفتح', 'Opened'),
      extraFilters: [
        _chip(legalStatus('OPEN'), _status, 'OPEN', legalTone('OPEN')),
        _chip(legalStatus('UNDER_STUDY'), _status, 'UNDER_STUDY', legalTone('UNDER_STUDY')),
        _chip(legalStatus('CLOSED'), _status, 'CLOSED', legalTone('CLOSED')),
        _chip('🏆 ${legalStatus('WON')}', _outcome, 'WON', legalTone('WON')),
        _chip(legalStatus('LOST'), _outcome, 'LOST', legalTone('LOST')),
        Padding(padding: const EdgeInsets.symmetric(horizontal: 3), child: ActionChip(avatar: const Icon(Icons.category_rounded, size: 16), label: Text(_types.isEmpty ? _l('النوع', 'Type') : '${_l('النوع', 'Type')} (${_types.length})'), onPressed: _types_)),
      ],
      fab: ctx.canWrite('cases') ? FloatingActionButton.extended(onPressed: () async {
        final id = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => CaseFormPage(ctx: ctx, clientId: widget.clientId)));
        if (id != null && context.mounted) { setState(() => _n++); Navigator.of(context).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: ctx, id: id))); }
      }, icon: const Icon(Icons.add_rounded), label: Text(_l('قضية جديدة', 'New case'))) : null,
      emptyText: _l('لا توجد قضايا', 'No cases'),
      itemBuilder: (c, k, reload) {
        final due = ((k['billedMinor'] as num?) ?? 0) - ((k['paidMinor'] as num?) ?? 0);
        final next = k['nextStepAt'] == null ? null : DateTime.parse('${k['nextStepAt']}').toLocal();
        return Card(clipBehavior: Clip.antiAlias, child: InkWell(
          onTap: () => Navigator.of(c).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: ctx, id: '${k['id']}'))).then((_) => reload()),
          child: IntrinsicHeight(child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Container(width: 5, color: k['outcome'] != 'NONE' ? legalTone(k['outcome']) : legalTone(k['status'])),
            Expanded(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(child: Text('#${k['number']}${'${k['courtCaseNo'] ?? ''}'.isNotEmpty ? ' · ${k['courtCaseNo']}' : ''}', style: const TextStyle(fontSize: 11, color: Color(0xFF6B7C8D), fontWeight: FontWeight.w700))),
                legalPill(k['status']), if (k['outcome'] != 'NONE') ...[const SizedBox(width: 4), legalPill(k['outcome'])],
              ]),
              const SizedBox(height: 2),
              Text('${k['title']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)),
              Text(dotted([caseType(k['caseType']), k['court'], if ('${k['clientNames'] ?? ''}'.isNotEmpty) '👤 ${k['clientNames']}']), style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
              const SizedBox(height: 4),
              Row(children: [
                if (next != null) Container(padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2), decoration: BoxDecoration(color: const Color(0x226366F1), borderRadius: BorderRadius.circular(8)), child: Text('📅 ${_dayLabel(next)} ${_hm(next)}', style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w800, color: Color(0xFF4338CA)))),
                const Spacer(),
                if (due > 0) Text('${_l('مستحق', 'Due')} ${s.money(due, ctx.currency)}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w900, color: Color(0xFFD14343))),
              ]),
            ]))),
          ])),
        ));
      },
    );
  }
}

/// New / edit case.
class CaseFormPage extends StatefulWidget {
  const CaseFormPage({super.key, required this.ctx, this.initial, this.clientId});
  final Ctx ctx;
  final Map<String, dynamic>? initial;
  final String? clientId;
  @override
  State<CaseFormPage> createState() => _CaseFormPageState();
}

class _CaseFormPageState extends State<CaseFormPage> {
  final fe = FieldErrors();
  late final i = widget.initial ?? const <String, dynamic>{};
  late final title = TextEditingController(text: '${i['title'] ?? ''}'), court = TextEditingController(text: '${i['court'] ?? ''}'), circuit = TextEditingController(text: '${i['circuit'] ?? ''}'),
      courtNo = TextEditingController(text: '${i['courtCaseNo'] ?? ''}'), opponent = TextEditingController(text: '${i['opponent'] ?? ''}'), opponentLawyer = TextEditingController(text: '${i['opponentLawyer'] ?? ''}'),
      fee = TextEditingController(text: (i['agreedFeeMinor'] as num? ?? 0) > 0 ? fmtMoney(i['agreedFeeMinor'] as num) : ''), desc = TextEditingController(text: '${i['description'] ?? ''}');
  late String type = '${i['caseType'] ?? 'CIVIL'}', status = '${i['status'] ?? 'OPEN'}', outcome = '${i['outcome'] ?? 'NONE'}';
  late List<Map<String, dynamic>> clients = _maps(i['clients']);
  bool busy = false;

  @override
  void initState() {
    super.initState();
    fe.addListener(() { if (mounted) setState(() {}); });
    if (widget.clientId != null && clients.isEmpty) {
      widget.ctx.api.get('lawyer/clients/${widget.clientId}').then((c) { if (mounted) setState(() => clients = [Map<String, dynamic>.from(c as Map)]); }).catchError((_) {});
    }
  }

  Future<void> _addClient() async {
    final c = await showModalBottomSheet<Map<String, dynamic>>(context: context, isScrollControlled: true, showDragHandle: true, builder: (_) => _ClientPicker(ctx: widget.ctx));
    if (c != null && !clients.any((x) => x['id'] == c['id'])) setState(() => clients.add(c));
  }

  Future<void> _save() async {
    if (!fe.check({'title': title.text.trim().length < 3 ? _l('اكتب عنوانًا واضحًا للقضية', 'Give the case a clear title') : null})) return;
    setState(() => busy = true);
    String? id;
    final body = {'title': title.text.trim(), 'caseType': type, 'status': status, 'outcome': outcome, 'court': court.text.trim(), 'circuit': circuit.text.trim(), 'courtCaseNo': courtNo.text.trim(), 'opponent': opponent.text.trim(),
      'opponentLawyer': opponentLawyer.text.trim(), 'agreedFeeMinor': fee.text.trim().isEmpty ? 0 : toMinor(fee.text), 'description': desc.text.trim(), 'clientIds': [for (final c in clients) c['id']], 'clientRole': '${i['clientRole'] ?? ''}'};
    final ok = await runForm(context, fe, () async {
      final r = widget.initial == null ? await widget.ctx.api.post('lawyer/cases', body) : await widget.ctx.api.put('lawyer/cases/${widget.initial!['id']}', body);
      id = '${(r as Map)['id']}';
    });
    if (!mounted) return;
    setState(() => busy = false);
    if (ok) Navigator.pop(context, id);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.initial == null ? _l('قضية جديدة', 'New case') : _l('تعديل القضية', 'Edit case'))),
      body: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 100), children: [
        tf(title, _l('عنوان القضية', 'Case title'), fe: fe, field: 'title', hint: _l('مثال: نزاع إيجار — شقة مدينة نصر', 'e.g. Rent dispute')),
        DropdownButtonFormField<String>(initialValue: type, decoration: InputDecoration(labelText: _l('نوع القضية', 'Case type')), items: [for (final t in kCaseTypes) DropdownMenuItem(value: t, child: Text(caseType(t)))], onChanged: (v) => setState(() => type = v ?? type)),
        const SizedBox(height: 10),
        Text(_l('الحالة', 'Status'), style: const TextStyle(fontWeight: FontWeight.w800)),
        Wrap(spacing: 6, children: [for (final st in const ['UNDER_STUDY', 'OPEN', 'CLOSED']) ChoiceChip(label: Text(legalStatus(st)), selected: status == st, onSelected: (_) => setState(() { status = st; if (st == 'UNDER_STUDY') outcome = 'NONE'; }))]),
        if (status != 'UNDER_STUDY') Wrap(key: fe.key('outcome'), spacing: 6, children: [for (final o in const ['NONE', 'WON', 'LOST', 'SETTLED']) ChoiceChip(label: Text(legalStatus(o)), selected: outcome == o, onSelected: (_) => setState(() => outcome = o))]),
        if (fe.of('outcome') != null) Text(fe.of('outcome')!, style: const TextStyle(color: Color(0xFFD14343), fontSize: 12)),
        const SizedBox(height: 10),
        Text(_l('الموكلون', 'Clients'), style: const TextStyle(fontWeight: FontWeight.w800)),
        Wrap(key: fe.key('clientIds'), spacing: 6, runSpacing: 6, children: [
          for (final c in clients) InputChip(label: Text('${c['name']}'), onDeleted: () => setState(() => clients.remove(c))),
          ActionChip(avatar: const Icon(Icons.person_add_alt_rounded, size: 18), label: Text(_l('إضافة موكل', 'Add client')), onPressed: _addClient),
        ]),
        const SizedBox(height: 10),
        tf(court, _l('المحكمة', 'Court')), tf(circuit, _l('الدائرة', 'Circuit')), tf(courtNo, _l('رقم القضية بالمحكمة', 'Case no. at court'), ltr: true),
        tf(opponent, _l('الخصم', 'Opponent')), tf(opponentLawyer, _l('محامي الخصم', "Opponent's lawyer")),
        tf(fee, '${_l('الأتعاب المتفق عليها', 'Agreed fee')} (${widget.ctx.currency})', number: true, ltr: true, fe: fe, field: 'agreedFeeMinor'),
        tf(desc, _l('ملخص القضية', 'Case summary'), lines: 4),
      ]),
      bottomNavigationBar: SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(16, 6, 16, 10), child: FilledButton(onPressed: busy ? null : _save, child: busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(_l('حفظ', 'Save'))))),
    );
  }
}

class _ClientPicker extends StatefulWidget {
  const _ClientPicker({required this.ctx});
  final Ctx ctx;
  @override
  State<_ClientPicker> createState() => _ClientPickerState();
}

class _ClientPickerState extends State<_ClientPicker> {
  final q = TextEditingController();
  List<Map<String, dynamic>> res = [];
  @override
  void initState() { super.initState(); _load(); }
  Future<void> _load() async {
    try {
      final r = await widget.ctx.api.get('lawyer/clients', query: {'pageSize': '30', if (q.text.trim().isNotEmpty) 'q': q.text.trim()}) as Map;
      if (mounted) setState(() => res = _maps(r['data']));
    } catch (_) {}
  }
  @override
  Widget build(BuildContext context) => SizedBox(height: MediaQuery.of(context).size.height * .75, child: Column(children: [
        Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: TextField(controller: q, onChanged: (_) => _load(), decoration: InputDecoration(prefixIcon: const Icon(Icons.search_rounded), hintText: _l('ابحث في الموكلين', 'Search clients')))),
        ListTile(leading: const Icon(Icons.person_add_alt_1_rounded), title: Text(_l('موكل جديد', 'New client'), style: const TextStyle(fontWeight: FontWeight.w800)), onTap: () async {
          final c = await Navigator.of(context).push<Map<String, dynamic>>(MaterialPageRoute(builder: (_) => ClientFormPage(ctx: widget.ctx, name: q.text.trim())));
          if (c != null && context.mounted) Navigator.pop(context, c);
        }),
        const Divider(height: 1),
        Expanded(child: ListView(children: [for (final c in res) ListTile(title: Text('${c['name']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text(localPhone(c['phone']), textDirection: TextDirection.ltr, textAlign: TextAlign.start), onTap: () => Navigator.pop(context, c))])),
      ]));
}

/// One case: overview, hearings timeline, actions with fees, payments, documents.
class CaseDetailPage extends StatefulWidget {
  const CaseDetailPage({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final String id;
  @override
  State<CaseDetailPage> createState() => _CaseDetailPageState();
}

class _CaseDetailPageState extends State<CaseDetailPage> {
  Map<String, dynamic>? k;
  Object? error;
  Ctx get ctx => widget.ctx;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    try { final r = await ctx.api.get('lawyer/cases/${widget.id}'); if (mounted) setState(() { k = Map<String, dynamic>.from(r as Map); error = null; }); } catch (e) { if (mounted) setState(() => error = e); }
  }

  Future<void> _set(Map<String, dynamic> body) async { if (await run(context, () => ctx.api.put('lawyer/cases/${widget.id}/status', body))) await _load(); }

  Future<void> _step([Map<String, dynamic>? st]) async {
    final fe = FieldErrors();
    final desc = TextEditingController(text: '${st?['description'] ?? ''}'), loc = TextEditingController(text: '${st?['location'] ?? ''}'), result = TextEditingController(text: '${st?['result'] ?? ''}');
    String kind = '${st?['kind'] ?? 'HEARING'}';
    DateTime at = st == null ? DateTime.now().add(const Duration(days: 1)).copyWith(hour: 10, minute: 0) : DateTime.parse('${st['startsAt']}').toLocal();
    bool done = st?['done'] == true;
    final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, showDragHandle: true, builder: (c) => StatefulBuilder(builder: (c, set) {
      fe.addListener(() { if (c.mounted) set(() {}); });
      return Padding(padding: EdgeInsets.fromLTRB(18, 0, 18, MediaQuery.of(c).viewInsets.bottom + 16), child: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Wrap(spacing: 6, children: [for (final x in const ['HEARING', 'DEADLINE', 'FILING', 'MEETING', 'OTHER']) ChoiceChip(label: Text(stepKind(x)), selected: kind == x, onSelected: (_) => set(() => kind = x))]),
        const SizedBox(height: 8),
        tf(desc, _l('ما هو؟', 'What is it?'), fe: fe, field: 'description'),
        tf(loc, _l('المكان', 'Place')),
        ListTile(contentPadding: EdgeInsets.zero, leading: const Icon(Icons.event_rounded), title: Text('${_dayLabel(at)}  ${_hm(at)}', style: const TextStyle(fontWeight: FontWeight.w800)), onTap: () async {
          final d = await showDatePicker(context: c, firstDate: DateTime.now().subtract(const Duration(days: 365 * 3)), lastDate: DateTime.now().add(const Duration(days: 730)), initialDate: at);
          if (d == null || !c.mounted) return;
          final t = await showTimePicker(context: c, initialTime: TimeOfDay.fromDateTime(at));
          set(() => at = DateTime(d.year, d.month, d.day, t?.hour ?? at.hour, t?.minute ?? at.minute));
        }),
        if (st != null) ...[tf(result, _l('ما تم', 'What happened'), lines: 2), SwitchListTile(contentPadding: EdgeInsets.zero, value: done, onChanged: (v) => set(() => done = v), title: Text(_l('تم', 'Done')))],
        Text('🔔 ${_l('ستصلك إشعارات قبلها بساعة', 'You get a reminder one hour before')}', style: const TextStyle(fontSize: 12, color: Color(0xFF4338CA))),
        const SizedBox(height: 10),
        FilledButton(onPressed: () async {
          if (!fe.check({'description': desc.text.trim().isEmpty ? _l('اكتب وصف الموعد', 'Describe this step') : null})) return;
          final body = {'kind': kind, 'startsAt': at.toUtc().toIso8601String(), 'description': desc.text.trim(), 'location': loc.text.trim(), 'result': result.text.trim(), 'done': done};
          if (await runForm(c, fe, () => st == null ? ctx.api.post('lawyer/cases/${widget.id}/steps', body) : ctx.api.put('lawyer/cases/${widget.id}/steps/${st['id']}', body)) && c.mounted) Navigator.pop(c, true);
        }, child: Text(_l('حفظ', 'Save'))),
      ])));
    }));
    if (ok == true) await _load();
  }

  Future<void> _action([Map<String, dynamic>? a]) async {
    final fe = FieldErrors();
    final title = TextEditingController(text: '${a?['title'] ?? ''}'), notes = TextEditingController(text: '${a?['notes'] ?? ''}'), fee = TextEditingController(text: (a?['feeMinor'] as num? ?? 0) > 0 ? fmtMoney(a!['feeMinor'] as num) : '');
    final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, showDragHandle: true, builder: (c) => StatefulBuilder(builder: (c, set) {
      fe.addListener(() { if (c.mounted) set(() {}); });
      return Padding(padding: EdgeInsets.fromLTRB(18, 0, 18, MediaQuery.of(c).viewInsets.bottom + 16), child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Wrap(spacing: 6, runSpacing: 6, children: [for (final x in [_l('رفع الدعوى', 'Filed the lawsuit'), _l('حضور الجلسة', 'Attended the hearing'), _l('تقديم مذكرة', 'Submitted a memo'), _l('استشارة', 'Consultation')]) ActionChip(label: Text(x), onPressed: () => set(() => title.text = x))]),
        const SizedBox(height: 8),
        tf(title, _l('الإجراء', 'Action'), fe: fe, field: 'title'),
        tf(fee, '${_l('الأتعاب (اختياري)', 'Fee (optional)')} (${ctx.currency})', number: true, ltr: true, fe: fe, field: 'feeMinor'),
        tf(notes, _l('ملاحظات', 'Notes'), lines: 3),
        FilledButton(onPressed: () async {
          if (!fe.check({'title': title.text.trim().isEmpty ? _l('اكتب ما تم', 'Describe what was done') : null})) return;
          final body = {'title': title.text.trim(), 'notes': notes.text.trim(), 'feeMinor': fee.text.trim().isEmpty ? 0 : toMinor(fee.text)};
          if (await runForm(c, fe, () => a == null ? ctx.api.post('lawyer/cases/${widget.id}/actions', body) : ctx.api.put('lawyer/cases/${widget.id}/actions/${a['id']}', body)) && c.mounted) Navigator.pop(c, true);
        }, child: Text(_l('حفظ', 'Save'))),
      ]));
    }));
    if (ok == true) await _load();
  }

  Future<void> _payment() async {
    final fe = FieldErrors();
    final amount = TextEditingController(text: (k!['balanceMinor'] as num? ?? 0) > 0 ? fmtMoney(k!['balanceMinor'] as num) : ''), note = TextEditingController();
    String method = 'CASH';
    final ok = await showModalBottomSheet<bool>(context: context, isScrollControlled: true, showDragHandle: true, builder: (c) => StatefulBuilder(builder: (c, set) {
      fe.addListener(() { if (c.mounted) set(() {}); });
      return Padding(padding: EdgeInsets.fromLTRB(18, 0, 18, MediaQuery.of(c).viewInsets.bottom + 16), child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        tf(amount, '${_l('المبلغ', 'Amount')} (${ctx.currency})', number: true, ltr: true, fe: fe, field: 'amountMinor'),
        Wrap(spacing: 6, runSpacing: 6, children: [for (final m in _pmAr.keys) ChoiceChip(label: Text(_ar ? _pmAr[m]! : m.toLowerCase()), selected: method == m, onSelected: (_) => set(() => method = m))]),
        const SizedBox(height: 8),
        tf(note, _l('ملاحظة', 'Note')),
        FilledButton(onPressed: () async {
          if (!fe.check({'amountMinor': (num.tryParse(amount.text.trim()) ?? 0) <= 0 ? _l('اكتب المبلغ المستلم', 'Enter the amount received') : null})) return;
          final clients = _maps(k!['clients']);
          if (await runForm(c, fe, () => ctx.api.post('lawyer/cases/${widget.id}/payments', {'amountMinor': toMinor(amount.text), 'method': method, 'note': note.text.trim(), if (clients.isNotEmpty) 'clientId': clients.first['id']})) && c.mounted) Navigator.pop(c, true);
        }, child: Text(_l('تسجيل الدفعة', 'Record payment'))),
      ]));
    }));
    if (ok == true) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    String money(num m) => s.money(m, ctx.currency);
    if (k == null) return Scaffold(appBar: AppBar(), body: error != null ? errorState(error!, _load) : const Center(child: CircularProgressIndicator()));
    final c = k!;
    final write = ctx.canWrite('cases');
    final steps = _maps(c['steps']), actions = _maps(c['actions']), payments = _maps(c['payments']), docs = _maps(c['documents']), clients = _maps(c['clients']);
    final billed = (c['billedMinor'] as num?) ?? 0, paid = (c['paidMinor'] as num?) ?? 0;
    return DefaultTabController(length: 5, child: Scaffold(
      appBar: AppBar(
        title: Text('${c['title']}'),
        actions: [if (write) IconButton(icon: const Icon(Icons.edit_rounded), onPressed: () async { if (await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => CaseFormPage(ctx: ctx, initial: c))) != null) await _load(); })],
        bottom: TabBar(isScrollable: true, tabs: [Tab(text: _l('نظرة عامة', 'Overview')), Tab(text: '${_l('الجلسات', 'Hearings')} (${steps.length})'), Tab(text: '${_l('الإجراءات', 'Actions')} (${actions.length})'), Tab(text: '${_l('المدفوعات', 'Payments')} (${payments.length})'), Tab(text: '${_l('المستندات', 'Documents')} (${docs.length})')]),
      ),
      body: TabBarView(children: [
        RefreshIndicator(onRefresh: _load, child: ListView(padding: const EdgeInsets.all(16), children: [
          Row(children: [legalPill(c['status']), const SizedBox(width: 6), if (c['outcome'] != 'NONE') legalPill(c['outcome']), const Spacer(), Text('#${c['number']}', style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF6B7C8D)))]),
          const SizedBox(height: 10),
          Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(children: [
            Row(children: [
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(_l('الأتعاب', 'Billed'), style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))), Text(money(billed), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))])),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(_l('المحصل', 'Collected'), style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))), Text(money(paid), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16, color: Color(0xFF1E9E5A)))])),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(_l('المتبقي', 'Due'), style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))), Text(money(billed - paid), style: TextStyle(fontWeight: FontWeight.w900, fontSize: 16, color: billed - paid > 0 ? const Color(0xFFD14343) : const Color(0xFF1E9E5A)))])),
            ]),
            const SizedBox(height: 8),
            LinearProgressIndicator(value: billed > 0 ? (paid / billed).clamp(0, 1).toDouble() : 0, minHeight: 6, borderRadius: BorderRadius.circular(6)),
          ]))),
          if (write) ...[
            const SizedBox(height: 6),
            Wrap(spacing: 6, runSpacing: 6, children: [
              if (c['status'] != 'OPEN') ActionChip(label: Text('▶ ${legalStatus('OPEN')}'), onPressed: () => _set({'status': 'OPEN', 'outcome': 'NONE'})),
              if (c['status'] != 'UNDER_STUDY') ActionChip(label: Text(legalStatus('UNDER_STUDY')), onPressed: () => _set({'status': 'UNDER_STUDY', 'outcome': 'NONE'})),
              ActionChip(label: Text('🏆 ${legalStatus('WON')}'), onPressed: () => _set({'status': 'CLOSED', 'outcome': 'WON'})),
              ActionChip(label: Text(legalStatus('LOST')), onPressed: () => _set({'status': 'CLOSED', 'outcome': 'LOST'})),
              ActionChip(label: Text('🤝 ${legalStatus('SETTLED')}'), onPressed: () => _set({'status': 'CLOSED', 'outcome': 'SETTLED'})),
            ]),
          ],
          const SizedBox(height: 10),
          Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(children: [
            kv(_l('النوع', 'Type'), caseType(c['caseType'])), kv(_l('المحكمة', 'Court'), '${c['court'] ?? ''}'), kv(_l('الدائرة', 'Circuit'), '${c['circuit'] ?? ''}'),
            kv(_l('رقم المحكمة', 'Court no.'), '${c['courtCaseNo'] ?? ''}'), kv(_l('الخصم', 'Opponent'), '${c['opponent'] ?? ''}'), kv(_l('محامي الخصم', "Opponent's lawyer"), '${c['opponentLawyer'] ?? ''}'),
            kv(_l('تاريخ الفتح', 'Opened'), '${c['openedOn'] ?? ''}'),
          ]))),
          if ('${c['description'] ?? ''}'.isNotEmpty) Card(child: Padding(padding: const EdgeInsets.all(14), child: Text('${c['description']}'))),
          const SizedBox(height: 6),
          Text(_l('الموكلون', 'Clients'), style: const TextStyle(fontWeight: FontWeight.w900)),
          for (final cl in clients) Card(child: ListTile(
            leading: const CircleAvatar(child: Icon(Icons.person_rounded)),
            title: Text('${cl['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
            subtitle: Text(localPhone(cl['phone']), textDirection: TextDirection.ltr, textAlign: TextAlign.start),
            trailing: Row(mainAxisSize: MainAxisSize.min, children: [IconButton(onPressed: () => dial('${cl['phone']}'), icon: const Icon(Icons.phone_rounded)), IconButton(onPressed: () => whatsapp('${cl['phone']}'), icon: const Icon(Icons.chat_rounded, color: Color(0xFF1E9E5A)))]),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ClientDetailPage(ctx: ctx, id: '${cl['id']}'))),
          )),
          if (write) TextButton(onPressed: () async { if (await confirm(context, _l('حذف القضية وكل ما بها؟', 'Delete this case and everything in it?')) && context.mounted && await run(context, () => ctx.api.delete('lawyer/cases/${widget.id}')) && context.mounted) Navigator.pop(context); }, child: Text(_l('حذف القضية', 'Delete case'), style: const TextStyle(color: Color(0xFFD14343)))),
        ])),
        Scaffold(
          backgroundColor: Colors.transparent,
          floatingActionButton: write ? FloatingActionButton.extended(heroTag: 'st', onPressed: _step, icon: const Icon(Icons.add_rounded), label: Text(_l('جلسة / موعد', 'Hearing / date'))) : null,
          body: steps.isEmpty ? emptyState(_l('لا توجد جلسات بعد', 'No hearings yet'), icon: Icons.event_busy_rounded) : ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 90), children: [
            for (final st in steps) Card(child: ListTile(
              leading: IconButton(onPressed: write ? () async { if (await run(context, () => ctx.api.put('lawyer/cases/${widget.id}/steps/${st['id']}', {...st, 'done': st['done'] != true}))) await _load(); } : null, icon: Icon(st['done'] == true ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded, color: st['done'] == true ? const Color(0xFF1E9E5A) : const Color(0xFF6366F1))),
              title: Text('${st['description']}', style: TextStyle(fontWeight: FontWeight.w800, decoration: st['done'] == true ? TextDecoration.lineThrough : null)),
              subtitle: Text(dotted([stepKind(st['kind']), fmtDate('${st['startsAt']}'), st['location'], if ('${st['result'] ?? ''}'.isNotEmpty) '${_l('ما تم', 'Result')}: ${st['result']}'])),
              onTap: write ? () => _step(st) : null,
              trailing: write ? IconButton(icon: const Icon(Icons.delete_outline_rounded), onPressed: () async { if (await run(context, () => ctx.api.delete('lawyer/cases/${widget.id}/steps/${st['id']}'))) await _load(); }) : null,
            )),
          ]),
        ),
        Scaffold(
          backgroundColor: Colors.transparent,
          floatingActionButton: write ? FloatingActionButton.extended(heroTag: 'ac', onPressed: _action, icon: const Icon(Icons.add_rounded), label: Text(_l('إجراء', 'Action'))) : null,
          body: actions.isEmpty ? emptyState(_l('لم تُسجَّل إجراءات بعد', 'No actions yet'), icon: Icons.note_alt_outlined) : ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 90), children: [
            Text('${_l('أتعاب الإجراءات', 'Action fees')}: ${money((c['actionFeesMinor'] as num?) ?? 0)} · ${_l('المتفق عليه', 'Agreed')}: ${money((c['agreedFeeMinor'] as num?) ?? 0)}', style: const TextStyle(fontWeight: FontWeight.w800)),
            for (final a in actions) Card(child: ListTile(
              title: Text('${a['title']}', style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text(dotted([a['actionDate'], a['notes']])),
              trailing: (a['feeMinor'] as num? ?? 0) > 0 ? Text(money(a['feeMinor'] as num), style: const TextStyle(fontWeight: FontWeight.w900)) : null,
              onTap: write ? () => _action(a) : null,
              onLongPress: write ? () async { if (await confirm(context, _l('حذف الإجراء؟', 'Delete this action?')) && context.mounted && await run(context, () => ctx.api.delete('lawyer/cases/${widget.id}/actions/${a['id']}'))) await _load(); } : null,
            )),
          ]),
        ),
        Scaffold(
          backgroundColor: Colors.transparent,
          floatingActionButton: ctx.can('legal.finance.manage') ? FloatingActionButton.extended(heroTag: 'pa', onPressed: _payment, icon: const Icon(Icons.add_rounded), label: Text(_l('دفعة', 'Payment'))) : null,
          body: payments.isEmpty ? emptyState(_l('لا توجد مدفوعات بعد', 'No payments yet'), icon: Icons.payments_outlined) : ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 90), children: [
            for (final p in payments) Card(child: ListTile(
              leading: const CircleAvatar(backgroundColor: Color(0x221E9E5A), child: Icon(Icons.payments_rounded, color: Color(0xFF1E9E5A))),
              title: Text(money(p['amountMinor'] as num), style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF1E9E5A))),
              subtitle: Text(dotted([p['paidOn'], _ar ? _pmAr['${p['method']}'] : p['method'], p['clientName'], p['note']])),
              trailing: ctx.can('legal.finance.manage') ? IconButton(icon: const Icon(Icons.delete_outline_rounded), onPressed: () async { if (await confirm(context, _l('حذف الدفعة؟', 'Delete this payment?')) && context.mounted && await run(context, () => ctx.api.delete('lawyer/cases/${widget.id}/payments/${p['id']}'))) await _load(); }) : null,
            )),
          ]),
        ),
        Scaffold(
          backgroundColor: Colors.transparent,
          floatingActionButton: write ? FloatingActionButton.extended(heroTag: 'dc', onPressed: () async {
            String? id;
            if (await run(context, () async { id = await pickAndUpload(context, ctx.api, 'LEGAL_DOCUMENT', visibility: 'TENANT_INTERNAL'); if (id != null) await ctx.api.post('lawyer/cases/${widget.id}/documents', {'fileId': id}); }) && id != null) await _load();
          }, icon: const Icon(Icons.upload_file_rounded), label: Text(_l('رفع مستند', 'Upload'))) : null,
          body: docs.isEmpty ? emptyState(_l('لا توجد مستندات بعد', 'No documents yet'), icon: Icons.folder_open_rounded) : ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 90), children: [
            for (final d in docs) Card(child: ListTile(
              leading: Icon('${d['contentType']}'.contains('pdf') ? Icons.picture_as_pdf_rounded : Icons.image_rounded, color: '${d['contentType']}'.contains('pdf') ? const Color(0xFFD14343) : const Color(0xFF0EA5E9)),
              title: Text('${d['name']}', style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text(fmtDate('${d['createdAt']}')),
              onTap: () => launch('https://${ctx.host}${d['url']}'),
              trailing: write ? IconButton(icon: const Icon(Icons.delete_outline_rounded), onPressed: () async { if (await run(context, () => ctx.api.delete('lawyer/cases/${widget.id}/documents/${d['id']}'))) await _load(); }) : null,
            )),
          ]),
        ),
      ]),
    ));
  }
}

// ======================================================================================= clients

class LawyerClients extends StatefulWidget {
  const LawyerClients({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<LawyerClients> createState() => _LawyerClientsState();
}

class _LawyerClientsState extends State<LawyerClients> {
  int _n = 0;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ctx = widget.ctx;
    return PagedList(
      key: ValueKey('cl$_n'),
      api: ctx.api,
      path: 'lawyer/clients',
      searchHint: _l('الاسم أو الموبايل أو الرقم القومي', 'Name, phone or national ID'),
      dateRange: true, dateLabel: _l('تاريخ الإضافة', 'Added'),
      fab: ctx.canWrite('clients') ? FloatingActionButton.extended(onPressed: () async { if (await Navigator.of(context).push(MaterialPageRoute(builder: (_) => ClientFormPage(ctx: ctx))) != null) setState(() => _n++); }, icon: const Icon(Icons.person_add_alt_1_rounded), label: Text(_l('موكل جديد', 'New client'))) : null,
      emptyText: _l('لا يوجد موكلون بعد', 'No clients yet'),
      itemBuilder: (c, x, reload) => Card(child: ListTile(
        leading: CircleAvatar(child: Text('${x['name']}'.characters.first)),
        title: Text('${x['kind'] == 'COMPANY' ? '🏢 ' : ''}${x['name']}', style: const TextStyle(fontWeight: FontWeight.w900)),
        subtitle: Text(dotted([localPhone(x['phone']), _l('${x['caseCount'] ?? 0} قضية', '${x['caseCount'] ?? 0} cases'), if ((x['balanceMinor'] as num? ?? 0) > 0) '${_l('مستحق', 'Due')} ${s.money(x['balanceMinor'] as num, ctx.currency)}'])),
        trailing: const Icon(Icons.chevron_right_rounded),
        onTap: () => Navigator.of(c).push(MaterialPageRoute(builder: (_) => ClientDetailPage(ctx: ctx, id: '${x['id']}'))).then((_) => reload()),
      )),
    );
  }
}

class ClientFormPage extends StatefulWidget {
  const ClientFormPage({super.key, required this.ctx, this.initial, this.name = ''});
  final Ctx ctx;
  final Map<String, dynamic>? initial;
  final String name;
  @override
  State<ClientFormPage> createState() => _ClientFormPageState();
}

class _ClientFormPageState extends State<ClientFormPage> {
  final fe = FieldErrors();
  late final i = widget.initial ?? const <String, dynamic>{};
  late final name = TextEditingController(text: '${i['name'] ?? widget.name}'), phone = TextEditingController(text: localPhone(i['phone'])), phone2 = TextEditingController(text: localPhone(i['phone2'])),
      email = TextEditingController(text: '${i['email'] ?? ''}'), nid = TextEditingController(text: '${i['nationalId'] ?? ''}'), address = TextEditingController(text: '${i['address'] ?? ''}'), notes = TextEditingController(text: '${i['notes'] ?? ''}');
  late String kind = '${i['kind'] ?? 'PERSON'}';
  bool busy = false;

  @override
  void initState() { super.initState(); fe.addListener(() { if (mounted) setState(() {}); }); }

  Future<void> _save() async {
    if (!fe.check({'name': name.text.trim().length < 2 ? _l('اكتب اسم الموكل', "Write the client's name") : null})) return;
    setState(() => busy = true);
    Map<String, dynamic>? res;
    final body = {'name': name.text.trim(), 'phone': phone.text.trim(), 'phone2': phone2.text.trim(), 'email': email.text.trim(), 'nationalId': nid.text.trim(), 'address': address.text.trim(), 'notes': notes.text.trim(), 'kind': kind, 'tags': i['tags'] ?? []};
    final ok = await runForm(context, fe, () async {
      res = Map<String, dynamic>.from((widget.initial == null ? await widget.ctx.api.post('lawyer/clients', body) : await widget.ctx.api.put('lawyer/clients/${widget.initial!['id']}', body)) as Map);
    });
    if (!mounted) return;
    setState(() => busy = false);
    if (ok) Navigator.pop(context, res);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(widget.initial == null ? _l('موكل جديد', 'New client') : _l('تعديل الموكل', 'Edit client'))),
        body: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 100), children: [
          SegmentedButton<String>(segments: [ButtonSegment(value: 'PERSON', label: Text(_l('فرد', 'Person'))), ButtonSegment(value: 'COMPANY', label: Text(_l('شركة', 'Company')))], selected: {kind}, onSelectionChanged: (v) => setState(() => kind = v.first)),
          const SizedBox(height: 12),
          tf(name, _l('الاسم', 'Name'), fe: fe, field: 'name'),
          tf(phone, _l('الموبايل', 'Phone'), ltr: true, fe: fe, field: 'phone'),
          tf(phone2, _l('موبايل آخر', 'Second phone'), ltr: true, fe: fe, field: 'phone2'),
          tf(email, _l('البريد الإلكتروني', 'Email'), ltr: true, fe: fe, field: 'email'),
          tf(nid, kind == 'COMPANY' ? _l('رقم السجل التجاري', 'Commercial register') : _l('الرقم القومي', 'National ID'), ltr: true, fe: fe, field: 'nationalId'),
          tf(address, _l('العنوان', 'Address')),
          tf(notes, _l('ملاحظات', 'Notes'), lines: 3),
        ]),
        bottomNavigationBar: SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(16, 6, 16, 10), child: FilledButton(onPressed: busy ? null : _save, child: busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(_l('حفظ', 'Save'))))),
      );
}

class ClientDetailPage extends StatelessWidget {
  const ClientDetailPage({super.key, required this.ctx, required this.id});
  final Ctx ctx;
  final String id;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(_l('الموكل', 'Client'))),
      body: Async<Map<String, dynamic>>(
        load: () async => Map<String, dynamic>.from(await ctx.api.get('lawyer/clients/$id') as Map),
        builder: (context, c, reload) => RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Row(children: [
            CircleAvatar(radius: 28, child: Text('${c['name']}'.characters.first, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900))),
            const SizedBox(width: 12),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${c['kind'] == 'COMPANY' ? '🏢 ' : ''}${c['name']}', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 19)),
              Text(localPhone(c['phone']), textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF6B7C8D))),
            ])),
            if (ctx.canWrite('clients')) IconButton(icon: const Icon(Icons.edit_rounded), onPressed: () async { if (await Navigator.of(context).push(MaterialPageRoute(builder: (_) => ClientFormPage(ctx: ctx, initial: c))) != null) await reload(); }),
          ]),
          const SizedBox(height: 8),
          Wrap(spacing: 6, children: [
            if ('${c['phone']}'.isNotEmpty) ActionChip(avatar: const Icon(Icons.phone_rounded, size: 16), label: Text(_l('اتصال', 'Call')), onPressed: () => dial('${c['phone']}')),
            if ('${c['phone']}'.isNotEmpty) ActionChip(avatar: const Icon(Icons.chat_rounded, size: 16), label: const Text('WhatsApp'), onPressed: () => whatsapp('${c['phone']}')),
          ]),
          const SizedBox(height: 8),
          Card(child: Padding(padding: const EdgeInsets.all(14), child: Row(children: [
            Expanded(child: Column(children: [Text(_l('الأتعاب', 'Billed'), style: const TextStyle(fontSize: 12)), Text(s.money((c['billedMinor'] as num?) ?? 0, ctx.currency), style: const TextStyle(fontWeight: FontWeight.w900))])),
            Expanded(child: Column(children: [Text(_l('المحصل', 'Paid'), style: const TextStyle(fontSize: 12)), Text(s.money((c['paidMinor'] as num?) ?? 0, ctx.currency), style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF1E9E5A)))])),
            Expanded(child: Column(children: [Text(_l('المتبقي', 'Due'), style: const TextStyle(fontSize: 12)), Text(s.money((c['balanceMinor'] as num?) ?? 0, ctx.currency), style: TextStyle(fontWeight: FontWeight.w900, color: ((c['balanceMinor'] as num?) ?? 0) > 0 ? const Color(0xFFD14343) : const Color(0xFF1E9E5A)))])),
          ]))),
          if ('${c['notes'] ?? ''}'.isNotEmpty) Card(child: Padding(padding: const EdgeInsets.all(14), child: Text('${c['notes']}'))),
          Row(children: [
            Expanded(child: Text(_l('القضايا', 'Cases'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
            if (ctx.canWrite('cases')) TextButton.icon(onPressed: () async {
              final cid = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => CaseFormPage(ctx: ctx, clientId: id)));
              if (cid != null && context.mounted) { await reload(); if (context.mounted) Navigator.of(context).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: ctx, id: cid))); }
            }, icon: const Icon(Icons.add_rounded), label: Text(_l('قضية جديدة', 'New case'))),
          ]),
          for (final k in _maps(c['cases'])) Card(child: ListTile(
            title: Text('${k['title']}', style: const TextStyle(fontWeight: FontWeight.w800)),
            subtitle: Text(dotted(['#${k['number']}', caseType(k['caseType']), k['court']])),
            trailing: legalPill(k['outcome'] != 'NONE' ? k['outcome'] : k['status']),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: ctx, id: '${k['id']}'))).then((_) => reload()),
          )),
          if (_maps(c['cases']).isEmpty) Padding(padding: const EdgeInsets.all(16), child: Text(_l('لا توجد قضايا بعد', 'No cases yet'), style: const TextStyle(color: Color(0xFF6B7C8D)))),
        ])),
      ),
    );
  }
}

// ======================================================================================= finance, search

class LawyerFinance extends StatefulWidget {
  const LawyerFinance({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<LawyerFinance> createState() => _LawyerFinanceState();
}

class _LawyerFinanceState extends State<LawyerFinance> {
  DateTimeRange? _range;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ctx = widget.ctx;
    String money(num m) => s.money(m, ctx.currency);
    return Async<Map<String, dynamic>>(
      key: ValueKey(_range),
      load: () async => Map<String, dynamic>.from(await ctx.api.get('lawyer/finance', query: rangeQuery(_range)) as Map),
      builder: (context, f, reload) {
        final monthly = _maps(f['monthly']);
        final maxV = monthly.fold<num>(1, (m, x) => (x['collectedMinor'] as num) > m ? x['collectedMinor'] as num : m);
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Align(alignment: AlignmentDirectional.centerStart, child: DateRangeChip(value: _range, onChanged: (r) => setState(() => _range = r))),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(child: _tile(_l('الأتعاب', 'Billed'), money((f['billedMinor'] as num?) ?? 0), const Color(0xFF2563EB))),
            const SizedBox(width: 8),
            Expanded(child: _tile(_l('المحصل', 'Collected'), money((f['collectedMinor'] as num?) ?? 0), const Color(0xFF1E9E5A))),
            const SizedBox(width: 8),
            Expanded(child: _tile(_l('المستحق', 'Due'), money((f['dueMinor'] as num?) ?? 0), const Color(0xFFD14343))),
          ]),
          const SizedBox(height: 12),
          Text(_l('الدخل الشهري', 'Monthly income'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          const SizedBox(height: 8),
          SizedBox(height: 150, child: Row(crossAxisAlignment: CrossAxisAlignment.end, children: [
            for (final m in monthly) Expanded(child: Padding(padding: const EdgeInsets.symmetric(horizontal: 2), child: Column(mainAxisAlignment: MainAxisAlignment.end, children: [
              Flexible(child: FractionallySizedBox(heightFactor: ((m['collectedMinor'] as num) / maxV).toDouble().clamp(0.02, 1.0), child: Container(decoration: BoxDecoration(gradient: const LinearGradient(begin: Alignment.bottomCenter, end: Alignment.topCenter, colors: [Color(0xFF0B1D3A), Color(0xFFC9A227)]), borderRadius: BorderRadius.circular(6))))),
              const SizedBox(height: 4),
              Text('${m['month']}'.substring(5), style: const TextStyle(fontSize: 10, color: Color(0xFF6B7C8D))),
            ]))),
          ])),
          const SizedBox(height: 14),
          Text(_l('الأعلى مستحقات', 'Who owes most'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          for (final k in _maps(f['topDue'])) Card(child: ListTile(
            title: Text('${k['title']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text(dotted(['#${k['number']}', k['clientNames']])),
            trailing: Text(money(k['dueMinor'] as num), style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFFD14343))),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: ctx, id: '${k['id']}'))),
          )),
          const SizedBox(height: 10),
          Text(_l('آخر المدفوعات', 'Recent payments'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          for (final p in _maps(f['recentPayments'])) Card(child: ListTile(
            title: Text('${p['clientName'] ?? p['title']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text(dotted([p['paidOn'], _ar ? _pmAr['${p['method']}'] : p['method'], '#${p['number']}'])),
            trailing: Text('+${money(p['amountMinor'] as num)}', style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF1E9E5A))),
          )),
        ]));
      },
    );
  }

  Widget _tile(String label, String value, Color c) => Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: c.withValues(alpha: .08), borderRadius: BorderRadius.circular(16)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: TextStyle(fontSize: 11, color: c, fontWeight: FontWeight.w800)), FittedBox(child: Text(value, style: TextStyle(fontWeight: FontWeight.w900, fontSize: 15, color: c))),
      ]));
}

class LawyerSearch extends StatefulWidget {
  const LawyerSearch({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<LawyerSearch> createState() => _LawyerSearchState();
}

class _LawyerSearchState extends State<LawyerSearch> {
  final q = TextEditingController();
  Map<String, dynamic>? r;
  int _seq = 0;
  Future<void> _go(String v) async {
    final mine = ++_seq;
    if (v.trim().length < 2) { setState(() => r = null); return; }
    await Future<void>.delayed(const Duration(milliseconds: 300));
    if (mine != _seq) return;
    try { final x = await widget.ctx.api.get('lawyer/search', query: {'q': v.trim()}); if (mounted && mine == _seq) setState(() => r = Map<String, dynamic>.from(x as Map)); } catch (_) {}
  }
  @override
  Widget build(BuildContext context) {
    final ctx = widget.ctx;
    Widget group(String title, IconData icon, List<Map<String, dynamic>> items, Widget Function(Map<String, dynamic>) row) => items.isEmpty ? const SizedBox.shrink() : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(padding: const EdgeInsets.fromLTRB(4, 12, 4, 4), child: Row(children: [Icon(icon, size: 18, color: const Color(0xFF6B7C8D)), const SizedBox(width: 6), Text('$title (${items.length})', style: const TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF6B7C8D)))])),
          for (final i in items) Card(child: row(i)),
        ]);
    void openCase(Object? id) => Navigator.of(context).push(MaterialPageRoute(builder: (_) => CaseDetailPage(ctx: ctx, id: '$id')));
    return ListView(padding: const EdgeInsets.all(16), children: [
      TextField(controller: q, autofocus: false, onChanged: _go, decoration: InputDecoration(prefixIcon: const Icon(Icons.search_rounded), hintText: _l('اسم، رقم قضية، محكمة، خصم، موبايل…', 'Name, case no., court, opponent, phone…'))),
      if (r == null) Padding(padding: const EdgeInsets.all(24), child: Text(_l('ابحث في الموكلين والقضايا والجلسات والإجراءات والملاحظات والمستندات. (احمد = أحمد)', 'Search clients, cases, hearings, actions, notes and documents.'), textAlign: TextAlign.center, style: const TextStyle(color: Color(0xFF6B7C8D)))),
      if (r != null) ...[
        group(_l('القضايا', 'Cases'), Icons.work_rounded, _maps(r!['cases']), (k) => ListTile(title: Text('${k['title']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text(dotted(['#${k['number']}', k['courtCaseNo'], k['court']])), trailing: legalPill(k['status']), onTap: () => openCase(k['id']))),
        group(_l('الموكلون', 'Clients'), Icons.people_alt_rounded, _maps(r!['clients']), (c) => ListTile(title: Text('${c['name']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text(localPhone(c['phone'])), onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => ClientDetailPage(ctx: ctx, id: '${c['id']}'))))),
        group(_l('الجلسات والمواعيد', 'Hearings'), Icons.event_rounded, _maps(r!['steps']), (s) => ListTile(title: Text('${s['description']}'), subtitle: Text(dotted([stepKind(s['kind']), fmtDate('${s['startsAt']}'), '#${s['number']}'])), onTap: () => openCase(s['caseId']))),
        group(_l('الإجراءات', 'Actions'), Icons.note_alt_rounded, _maps(r!['actions']), (a) => ListTile(title: Text('${a['title']}'), subtitle: Text(dotted(['#${a['number']}', a['caseTitle'], a['actionDate']])), onTap: () => openCase(a['caseId']))),
        group(_l('الملاحظات', 'Notes'), Icons.sticky_note_2_rounded, _maps(r!['notes']), (n) => ListTile(title: Text('${n['title']}'), subtitle: Text(fmtDate('${n['startsAt']}')), onTap: n['caseId'] == null ? null : () => openCase(n['caseId']))),
        group(_l('المستندات', 'Documents'), Icons.description_rounded, _maps(r!['documents']), (d) => ListTile(title: Text('${d['name']}'), subtitle: Text('#${d['number']}'), onTap: () => launch('https://${ctx.host}${d['url']}'))),
        if (['cases', 'clients', 'steps', 'actions', 'notes', 'documents'].every((k) => _maps(r![k]).isEmpty)) Padding(padding: const EdgeInsets.all(24), child: Center(child: Text(_l('لا نتائج', 'Nothing found')))),
      ],
    ]);
  }
}

// ======================================================================================= services, hours, settings

class LawyerServices extends StatelessWidget {
  const LawyerServices({super.key, required this.ctx});
  final Ctx ctx;

  Future<bool> _edit(BuildContext context, [Map<String, dynamic>? sv]) async {
    final name = TextEditingController(text: '${sv?['name'] ?? ''}'), desc = TextEditingController(text: '${sv?['description'] ?? ''}'), price = TextEditingController(text: sv?['priceMinor'] == null ? '' : fmtMoney(sv!['priceMinor'] as num));
    int dur = (sv?['durationMinutes'] as num?)?.toInt() ?? 30;
    String mode = '${sv?['mode'] ?? 'OFFICE'}';
    bool active = sv?['isActive'] != false;
    return formSheet(context, title: sv == null ? _l('خدمة جديدة', 'New service') : _l('تعديل الخدمة', 'Edit service'), canSave: () => name.text.trim().isNotEmpty,
        body: (x, set) => [
              tf(name, _l('اسم الخدمة', 'Service name'), onChanged: (_) => set(() {})),
              tf(desc, _l('الوصف', 'Description'), lines: 2),
              Wrap(spacing: 6, children: [for (final d in const [15, 30, 45, 60, 90]) ChoiceChip(label: Text(_l('$d دقيقة', '$d min')), selected: dur == d, onSelected: (_) => set(() => dur = d))]),
              const SizedBox(height: 8),
              Wrap(spacing: 6, children: [for (final m in const ['OFFICE', 'ONLINE', 'PHONE']) ChoiceChip(label: Text(_ar ? const {'OFFICE': '🏛 بالمكتب', 'ONLINE': '💻 أونلاين', 'PHONE': '📞 تليفونيًا'}[m]! : m.toLowerCase()), selected: mode == m, onSelected: (_) => set(() => mode = m))]),
              const SizedBox(height: 8),
              tf(price, '${_l('السعر (فارغ = لا يظهر)', 'Price (empty = hidden)')} (${ctx.currency})', number: true, ltr: true),
              SwitchListTile(contentPadding: EdgeInsets.zero, value: active, onChanged: (v) => set(() => active = v), title: Text(_l('إظهار على الموقع', 'Show on the website'))),
            ],
        onSave: () async {
          final body = {'name': name.text.trim(), 'description': desc.text.trim(), 'durationMinutes': dur, 'priceMinor': price.text.trim().isEmpty ? null : toMinor(price.text), 'mode': mode, 'active': active};
          if (sv == null) { await ctx.api.post('lawyer/services', body); } else { await ctx.api.put('lawyer/services/${sv['id']}', body); }
        });
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<List<dynamic>>(
      load: () async => await ctx.api.get('lawyer/services') as List,
      builder: (context, list, reload) => RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
        if (ctx.canWrite('settings')) FilledButton.icon(onPressed: () async { if (await _edit(context)) await reload(); }, icon: const Icon(Icons.add_rounded), label: Text(_l('خدمة جديدة', 'New service'))),
        const SizedBox(height: 8),
        for (final sv in _maps(list)) Card(child: ListTile(
          leading: Text(const {'OFFICE': '🏛', 'ONLINE': '💻', 'PHONE': '📞'}['${sv['mode']}'] ?? '🏛', style: const TextStyle(fontSize: 26)),
          title: Text('${sv['name']}', style: TextStyle(fontWeight: FontWeight.w900, color: sv['isActive'] == false ? const Color(0xFF9AA9BA) : null)),
          subtitle: Text(dotted([_l('${sv['durationMinutes']} دقيقة', '${sv['durationMinutes']} min'), sv['priceMinor'] == null ? null : s.money(sv['priceMinor'] as num, ctx.currency), if (sv['isActive'] == false) _l('مخفية', 'hidden')])),
          onTap: ctx.canWrite('settings') ? () async { if (await _edit(context, sv)) await reload(); } : null,
          trailing: ctx.canWrite('settings') ? IconButton(icon: const Icon(Icons.delete_outline_rounded), onPressed: () async { if (await confirm(context, _l('حذف الخدمة؟', 'Delete this service?')) && context.mounted && await run(context, () => ctx.api.delete('lawyer/services/${sv['id']}'))) await reload(); }) : null,
        )),
      ])),
    );
  }
}

class LawyerHours extends StatefulWidget {
  const LawyerHours({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<LawyerHours> createState() => _LawyerHoursState();
}

class _LawyerHoursState extends State<LawyerHours> {
  List<Map<String, dynamic>>? hours;
  List<Map<String, dynamic>> off = [];
  final fe = FieldErrors();
  static const _order = [6, 7, 1, 2, 3, 4, 5];
  @override
  void initState() { super.initState(); fe.addListener(() { if (mounted) setState(() {}); }); _load(); }
  Future<void> _load() async {
    try { final r = Map<String, dynamic>.from(await widget.ctx.api.get('lawyer/hours') as Map); if (mounted) setState(() { hours = _maps(r['hours']); off = _maps(r['timeOff']); }); } catch (e) { if (mounted) toast(context, errorText(e), isError: true); }
  }
  Future<TimeOfDay?> _t(String v) => showTimePicker(context: context, initialTime: TimeOfDay(hour: int.parse(v.split(':')[0]), minute: int.parse(v.split(':')[1])));
  String _fmt(TimeOfDay t) => '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
  @override
  Widget build(BuildContext context) {
    if (hours == null) return const Center(child: CircularProgressIndicator());
    final write = widget.ctx.canWrite(['schedule', 'settings']);
    String day(int w) => _ar ? const {1: 'الإثنين', 2: 'الثلاثاء', 3: 'الأربعاء', 4: 'الخميس', 5: 'الجمعة', 6: 'السبت', 7: 'الأحد'}[w]! : const {1: 'Mon', 2: 'Tue', 3: 'Wed', 4: 'Thu', 5: 'Fri', 6: 'Sat', 7: 'Sun'}[w]!;
    return ListView(padding: const EdgeInsets.all(16), children: [
      for (final w in _order) Card(child: Padding(padding: const EdgeInsets.all(10), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(day(w), style: const TextStyle(fontWeight: FontWeight.w900))),
          Switch(value: hours!.any((h) => h['weekday'] == w), onChanged: !write ? null : (v) => setState(() => v ? hours!.add({'weekday': w, 'start': '11:00', 'end': '17:00'}) : hours!.removeWhere((h) => h['weekday'] == w))),
        ]),
        for (final h in hours!.where((h) => h['weekday'] == w).toList()) Row(key: fe.key('hours[${hours!.indexOf(h)}].start'), children: [
          TextButton(onPressed: !write ? null : () async { final t = await _t('${h['start']}'); if (t != null) setState(() => h['start'] = _fmt(t)); }, child: Text('${h['start']}')),
          const Text('–'),
          TextButton(onPressed: !write ? null : () async { final t = await _t('${h['end']}'); if (t != null) setState(() => h['end'] = _fmt(t)); }, child: Text('${h['end']}')),
          if (fe.under('hours[${hours!.indexOf(h)}]') != null) Expanded(child: Text(fe.under('hours[${hours!.indexOf(h)}]')!, style: const TextStyle(color: Color(0xFFD14343), fontSize: 12))),
        ]),
      ]))),
      if (write) FilledButton(onPressed: () async { if (await runForm(context, fe, () => widget.ctx.api.put('lawyer/hours', {'hours': hours})) && context.mounted) toast(context, _l('تم الحفظ', 'Saved')); }, child: Text(_l('حفظ مواعيد العمل', 'Save hours'))),
      const SizedBox(height: 16),
      Text(_l('إجازات وغياب', 'Away / holidays'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
      for (final o in off) Card(child: ListTile(title: Text('${o['reason']}'.isEmpty ? _l('غير متاح', 'Away') : '${o['reason']}'), subtitle: Text('${fmtDate('${o['startsAt']}')} → ${fmtDate('${o['endsAt']}')}'),
          trailing: write ? IconButton(icon: const Icon(Icons.delete_outline_rounded), onPressed: () async { if (await run(context, () => widget.ctx.api.delete('lawyer/time-off/${o['id']}'))) await _load(); }) : null)),
      if (write) OutlinedButton.icon(onPressed: () async {
        final r = await showDateRangePicker(context: context, firstDate: DateTime.now(), lastDate: DateTime.now().add(const Duration(days: 365)));
        if (r == null || !context.mounted) return;
        if (await run(context, () => widget.ctx.api.post('lawyer/time-off', {'startsAt': r.start.toUtc().toIso8601String(), 'endsAt': r.end.add(const Duration(days: 1)).toUtc().toIso8601String(), 'reason': ''}))) await _load();
      }, icon: const Icon(Icons.beach_access_rounded), label: Text(_l('إضافة إجازة', 'Add time off'))),
    ]);
  }
}

/// Booking rules, reminders and the website's main texts (the full website design is on the web dashboard).
class LawyerSettings extends StatelessWidget {
  const LawyerSettings({super.key, required this.ctx});
  final Ctx ctx;
  @override
  Widget build(BuildContext context) => ListView(padding: const EdgeInsets.all(16), children: [
        Card(child: ListTile(
          leading: const Icon(Icons.public_rounded, color: Color(0xFFC9A227)),
          title: Text(_l('موقعك', 'Your website'), style: const TextStyle(fontWeight: FontWeight.w900)),
          subtitle: Text(ctx.host, textDirection: TextDirection.ltr, textAlign: TextAlign.start),
          trailing: Wrap(children: [IconButton(onPressed: () => launch('https://${ctx.host}'), icon: const Icon(Icons.open_in_new_rounded)), IconButton(onPressed: () => launch('https://${ctx.host}/dashboard/website'), icon: const Icon(Icons.design_services_rounded))]),
        )),
        FormCard(
          ctx: ctx, titleAr: 'بياناتك على الموقع', titleEn: 'You on the website', loadPath: 'lawyer/profile', savePath: 'lawyer/profile', method: 'PATCH',
          fields: const [
            F(FK.text, 'title', 'اللقب (المستشار / الأستاذ)', 'Title'),
            F(FK.text, 'displayName', 'الاسم', 'Name'),
            F(FK.text, 'headline', 'سطر واحد عنك', 'One line about you'),
            F(FK.area, 'bio', 'نبذة', 'Biography'),
            F(FK.text, 'barLevel', 'القيد بالنقابة', 'Bar admission'),
            F(FK.number, 'yearsExperience', 'سنوات الخبرة', 'Years of experience'),
            F(FK.ltr, 'phone', 'الموبايل', 'Phone'),
            F(FK.ltr, 'whatsapp', 'واتساب', 'WhatsApp'),
            F(FK.text, 'officeAddress', 'عنوان المكتب', 'Office address'),
            F(FK.text, 'officeCity', 'المدينة', 'City'),
          ],
        ),
        const SizedBox(height: 10),
        FormCard(
          ctx: ctx, titleAr: 'قواعد الحجز والتذكيرات', titleEn: 'Booking rules & reminders', loadPath: 'lawyer/profile', savePath: 'lawyer/profile', method: 'PATCH',
          fields: const [
            F(FK.toggle, 'bookingEnabled', 'العملاء يمكنهم الحجز من موقعي', 'Clients can book on my website'),
            F(FK.toggle, 'autoApprove', 'تأكيد الحجوزات تلقائيًا', 'Confirm bookings automatically'),
            F(FK.number, 'slotMinutes', 'مدة الجلسة (دقيقة)', 'Session length (min)'),
            F(FK.number, 'bufferMinutes', 'استراحة بين الجلسات (دقيقة)', 'Break between (min)'),
            F(FK.number, 'minNoticeHours', 'الحجز قبلها بـ (ساعات)', 'Book at least (hours ahead)'),
            F(FK.number, 'maxDaysAhead', 'حتى (أيام مقدمًا)', 'Up to (days ahead)'),
            F(FK.number, 'reminderDaysBefore', 'ذكّرني قبل الجلسات (أيام)', 'Remind me before hearings (days)'),
            F(FK.toggle, 'dailyDigest', 'ملخص مسائي لأجندة الغد', 'Evening digest of tomorrow'),
          ],
        ),
      ]);
}
