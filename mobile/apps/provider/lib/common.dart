import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';
import 'package:url_launcher/url_launcher.dart';

import 'theme/templates.dart';

/// What every control-panel screen needs: the business's API, its currency and the user's permissions.
class Ctx {
  const Ctx({required this.api, required this.currency, required this.perms, required this.host, required this.type, this.screens = const {}});
  final ApiClient api;
  final String currency;
  final List<String> perms;
  final String host;
  final String type; // STORE | CLINIC
  bool can(String p) => perms.contains(p);
  bool canAny(List<String> ps) => ps.any(perms.contains);

  /// Screens the owner allowed for this person: `screen -> READ | WRITE` (hidden screens are absent).
  final Map<String, String> screens;
  bool canSee(Object screen) => (screen is List ? screen.cast<String>() : [screen as String]).any(screens.containsKey);

  /// False for read only access: the screen shows its data but offers no way to change it.
  bool canWrite(Object screen) => (screen is List ? screen.cast<String>() : [screen as String]).any((k) => screens[k] == 'WRITE');
}

/// Lets screens deep in the panel open full pages without passing a context around.
final navigatorKey = GlobalKey<NavigatorState>();

Future<void> launch(String url) async {
  try { await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication); } catch (_) {}
}

/// Egyptian numbers are stored as +201xxxxxxxxx; people read and type 01xxxxxxxxx, so lists and details show that (calls and WhatsApp still use the full number).
String localPhone(Object? p) {
  final s = '${p ?? ''}'.trim();
  final m = RegExp(r'^\+20(\d{10})$').firstMatch(s);
  return m == null ? s : '0${m.group(1)}';
}

Future<void> dial(String? phone) async { if (phone != null && phone.isNotEmpty) await launch('tel:$phone'); }

Future<void> whatsapp(String? phone, [String text = '']) async {
  if (phone == null || phone.isEmpty) return;
  final digits = phone.replaceAll(RegExp(r'[^0-9]'), '');
  await launch('https://wa.me/$digits${text.isEmpty ? '' : '?text=${Uri.encodeComponent(text)}'}');
}

OverlayEntry? _toastEntry;
Timer? _toastTimer;

/// A message at the TOP of the screen that hides itself after 3 seconds (tap or swipe up to close it sooner). An optional action (e.g. Undo) closes it too.
void toast(BuildContext c, String msg, {bool isError = false, String? actionLabel, VoidCallback? onAction}) {
  final overlay = navigatorKey.currentState?.overlay ?? Overlay.maybeOf(c, rootOverlay: true);
  if (overlay == null) return;
  _toastTimer?.cancel();
  _toastEntry?.remove();
  late final OverlayEntry entry;
  void close() { _toastTimer?.cancel(); if (_toastEntry == entry) { entry.remove(); _toastEntry = null; } }
  entry = OverlayEntry(builder: (context) => _TopToast(message: msg, isError: isError, actionLabel: actionLabel, onAction: onAction == null ? null : () { close(); onAction(); }, onClose: close));
  _toastEntry = entry;
  overlay.insert(entry);
  _toastTimer = Timer(const Duration(seconds: 3), close);
}

class _TopToast extends StatefulWidget {
  const _TopToast({required this.message, required this.isError, required this.onClose, this.actionLabel, this.onAction});
  final String message;
  final bool isError;
  final String? actionLabel;
  final VoidCallback? onAction;
  final VoidCallback onClose;
  @override
  State<_TopToast> createState() => _TopToastState();
}

class _TopToastState extends State<_TopToast> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 280))..forward();
  @override
  void dispose() { _c.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) {
    final bg = widget.isError ? const Color(0xFFB3261E) : const Color(0xFF1B2733);
    return Positioned(
      top: MediaQuery.of(context).padding.top + 10,
      left: 14,
      right: 14,
      child: SlideTransition(
        position: Tween(begin: const Offset(0, -1.4), end: Offset.zero).animate(CurvedAnimation(parent: _c, curve: Curves.easeOutBack)),
        child: FadeTransition(
          opacity: _c,
          child: Dismissible(
            key: UniqueKey(),
            direction: DismissDirection.up,
            onDismissed: (_) => widget.onClose(),
            child: Material(
              color: Colors.transparent,
              child: GestureDetector(
                onTap: widget.onClose,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                  decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(18), boxShadow: const [BoxShadow(color: Color(0x44000000), blurRadius: 18, offset: Offset(0, 6))]),
                  child: Row(children: [
                    Icon(widget.isError ? Icons.error_outline_rounded : Icons.check_circle_outline_rounded, color: Colors.white, size: 22),
                    const SizedBox(width: 10),
                    Expanded(child: Text(widget.message, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 14, height: 1.3))),
                    if (widget.actionLabel != null) TextButton(onPressed: widget.onAction, style: TextButton.styleFrom(foregroundColor: const Color(0xFF8FD3FF), padding: const EdgeInsets.symmetric(horizontal: 10), minimumSize: const Size(0, 36)), child: Text(widget.actionLabel!, style: const TextStyle(fontWeight: FontWeight.w900))),
                  ]),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Like [run] for a form: field errors go under their fields (and the screen scrolls there); anything else is a toast.
Future<bool> runForm(BuildContext c, FieldErrors fe, Future<void> Function() f) async {
  fe.clear();
  try { await f(); return true; } catch (e) {
    if (!c.mounted) return false;
    if (fe.take(e)) { toast(c, AppLocale.isAr ? 'من فضلك صحّح الحقول المعلَّمة باللون الأحمر' : 'Please fix the fields marked in red', isError: true); } else { toast(c, errorText(e), isError: true); }
    return false;
  }
}

/// Runs an action, shows a readable error if it fails and reports success so the caller can refresh.
Future<bool> run(BuildContext c, Future<void> Function() f) async {
  try { await f(); return true; } catch (e) { if (c.mounted) toast(c, errorText(e), isError: true); return false; }
}

Color toneFor(String status) => switch (status) {
      'CONFIRMED' || 'COMPLETED' || 'DELIVERED' || 'ARRIVED' || 'PAID' || 'ACTIVE' || 'APPROVED' || 'PUBLISHED' || 'REVIEWED' || 'DONE' => const Color(0xFF1E9E5A),
      'PENDING' || 'PENDING_CONFIRMATION' || 'REQUESTED' || 'NEW' || 'UNPAID' || 'PREPARING' || 'DRAFT' || 'PATIENT_UPLOADED' || 'UNDER_REVIEW' => const Color(0xFFD98200),
      'CHECKED_IN' || 'IN_PROGRESS' || 'SHIPPED' || 'PROCESSING' || 'TRIALING' || 'CONTACTED' => const Color(0xFF1F6A99),
      'CANCELLED' || 'REJECTED' || 'NO_SHOW' || 'RETURNED' || 'REFUNDED' || 'HIDDEN' || 'SUSPENDED' || 'PAST_DUE' || 'EXPIRED' => const Color(0xFFD14343),
      _ => const Color(0xFF6B7C8D),
    };

/// Small coloured status label, translated for the common order/appointment states.
class StatusPill extends StatelessWidget {
  const StatusPill(this.status, {super.key});
  final String status;
  static const _ar = {
    'PENDING': 'قيد الانتظار', 'PENDING_CONFIRMATION': 'بانتظار التأكيد', 'REQUESTED': 'جديد', 'CONFIRMED': 'مؤكد', 'CHECKED_IN': 'حضر', 'IN_PROGRESS': 'قيد الكشف', 'COMPLETED': 'مكتمل',
    'CANCELLED': 'ملغي', 'NO_SHOW': 'لم يحضر', 'REJECTED': 'مرفوض', 'PREPARING': 'قيد التجهيز', 'SHIPPED': 'تم الشحن', 'ARRIVED': 'تم التسليم', 'RETURNED': 'مرتجع', 'DELIVERED': 'تم التسليم',
    'ORDERED': 'مطلوب', 'PATIENT_UPLOADED': 'رفع المريض نتيجة', 'UNDER_REVIEW': 'قيد المراجعة', 'DONE': 'تم', 'REVIEWED': 'تمت المراجعة', 'PAID': 'مدفوع', 'UNPAID': 'غير مدفوع', 'APPROVED': 'مقبول', 'PUBLISHED': 'منشور', 'HIDDEN': 'مخفي', 'ACTIVE': 'نشط', 'DRAFT': 'مسودة', 'REFUNDED': 'مسترد', 'INVITED': 'مدعو', 'TRIAL': 'تجربة', 'TRIALING': 'تجربة مجانية', 'PAST_DUE': 'بانتظار التجديد', 'SUSPENDED': 'متوقف', 'EXPIRED': 'منتهي', 'NEW': 'جديد', 'CONTACTED': 'تم التواصل', 'CREATED': 'تم إنشاء الشحنة', 'PICKED_UP': 'تم الاستلام من المتجر', 'IN_TRANSIT': 'في الطريق', 'OUT_FOR_DELIVERY': 'خرج للتسليم', 'EXCEPTION': 'مشكلة في التسليم', 'PROCESSING': 'قيد التجهيز',
  };
  @override
  Widget build(BuildContext context) {
    final c = toneFor(status);
    final label = statusLabel(status);
    final t = AppTemplate.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: c.withValues(alpha: t.isClinic ? 0.14 : 0.12), borderRadius: BorderRadius.circular(t.isLawyer ? 6 : 20), border: t.isLawyer ? Border.all(color: c.withValues(alpha: 0.5)) : null),
      child: Text(label, style: TextStyle(color: c, fontWeight: FontWeight.w800, fontSize: 12)),
    );
  }
}

/// The words for an order / appointment / request status in the app's language (Arabic names for the known ones).
String statusLabel(String status) => AppLocale.isAr ? (StatusPill._ar[status] ?? status) : status.replaceAll('_', ' ').toLowerCase();

/// "اللون=أزرق|المقاس=M" (how a variant is stored) as "أزرق / M".
String comboLabel(Object? key) {
  final k = '${key ?? ''}';
  if (k.isEmpty) return '';
  return k.split('|').map((p) => p.contains('=') ? p.substring(p.indexOf('=') + 1) : p).join(' / ');
}

Widget emptyState(String text, {IconData icon = Icons.inbox_outlined}) => Builder(builder: (context) => templateEmpty(context, text, icon: icon));

Widget errorState(Object e, VoidCallback retry) => Builder(builder: (context) {
      final t = AppTemplate.of(context);
      return Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.cloud_off_rounded, size: 46, color: t.muted),
        const SizedBox(height: 10),
        Text(errorText(e), textAlign: TextAlign.center),
        TextButton.icon(onPressed: retry, icon: const Icon(Icons.refresh_rounded), label: Text(S(AppLocale.isAr ? 'ar' : 'en').retry)),
      ])));
    });

String fmtDate(String? iso, {bool time = true}) {
  if (iso == null || iso.isEmpty) return '';
  final d = DateTime.tryParse(iso)?.toLocal();
  if (d == null) return iso;
  String two(int n) => n.toString().padLeft(2, '0');
  final day = '${d.year}-${two(d.month)}-${two(d.day)}';
  return time ? '$day  ${two(d.hour)}:${two(d.minute)}' : day;
}

String ymd(DateTime d) => '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// from/to query values of a date range (both days included); empty when there is none.
Map<String, String> rangeQuery(DateTimeRange? r) => r == null ? const {} : {'from': ymd(r.start), 'to': ymd(r.end)};

/// The date filter of every list: a chip that opens quick choices (today, last 7 days, this month...) or the calendar for any range.
class DateRangeChip extends StatelessWidget {
  const DateRangeChip({super.key, required this.value, required this.onChanged, this.future = false, this.label});
  final DateTimeRange? value;
  final ValueChanged<DateTimeRange?> onChanged;
  final bool future;
  final String? label;

  static String text(DateTimeRange? r, bool ar, {String? empty}) {
    if (r == null) return empty ?? (ar ? 'أي تاريخ' : 'Any date');
    String d(DateTime x) => '${x.day}/${x.month}${x.year == DateTime.now().year ? '' : '/${x.year % 100}'}';
    return r.start == r.end ? d(r.start) : '${d(r.start)} – ${d(r.end)}';
  }

  Future<void> _open(BuildContext context) async {
    final ar = AppLocale.isAr;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    DateTimeRange r(DateTime a, DateTime b) => DateTimeRange(start: a, end: b);
    final presets = future
        ? <(String, DateTimeRange)>[
            (ar ? 'اليوم' : 'Today', r(today, today)), (ar ? 'غدًا' : 'Tomorrow', r(today.add(const Duration(days: 1)), today.add(const Duration(days: 1)))),
            (ar ? 'الأيام السبعة القادمة' : 'Next 7 days', r(today, today.add(const Duration(days: 6)))), (ar ? 'الثلاثون يومًا القادمة' : 'Next 30 days', r(today, today.add(const Duration(days: 29)))),
          ]
        : <(String, DateTimeRange)>[
            (ar ? 'اليوم' : 'Today', r(today, today)), (ar ? 'أمس' : 'Yesterday', r(today.subtract(const Duration(days: 1)), today.subtract(const Duration(days: 1)))),
            (ar ? 'آخر 7 أيام' : 'Last 7 days', r(today.subtract(const Duration(days: 6)), today)), (ar ? 'آخر 30 يومًا' : 'Last 30 days', r(today.subtract(const Duration(days: 29)), today)),
            (ar ? 'هذا الشهر' : 'This month', r(DateTime(now.year, now.month, 1), today)),
            (ar ? 'الشهر الماضي' : 'Last month', r(DateTime(now.year, now.month - 1, 1), DateTime(now.year, now.month, 0))),
          ];
    final picked = await showModalBottomSheet<Object>(
      context: context,
      showDragHandle: true,
      builder: (c) => SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 16), child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(ar ? 'اختر الفترة' : 'Choose dates', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
        const SizedBox(height: 10),
        Wrap(spacing: 8, runSpacing: 8, children: [for (final p in presets) ChoiceChip(label: Text(p.$1), selected: value != null && value!.start == p.$2.start && value!.end == p.$2.end, onSelected: (_) => Navigator.pop(c, p.$2))]),
        const SizedBox(height: 12),
        OutlinedButton.icon(onPressed: () => Navigator.pop(c, 'custom'), icon: const Icon(Icons.date_range_rounded), label: Text(ar ? 'اختر من التقويم' : 'Pick on the calendar')),
        if (value != null) TextButton(onPressed: () => Navigator.pop(c, 'clear'), child: Text(ar ? 'مسح التاريخ' : 'Clear dates')),
      ]))),
    );
    if (!context.mounted || picked == null) return;
    if (picked == 'clear') { onChanged(null); return; }
    if (picked is DateTimeRange) { onChanged(picked); return; }
    final custom = await showDateRangePicker(context: context, firstDate: DateTime(2020), lastDate: DateTime(now.year + 2, 12, 31), initialDateRange: value, helpText: ar ? 'من — إلى' : 'From — to');
    if (custom != null) onChanged(DateTimeRange(start: DateTime(custom.start.year, custom.start.month, custom.start.day), end: DateTime(custom.end.year, custom.end.month, custom.end.day)));
  }

  @override
  Widget build(BuildContext context) {
    final ar = AppLocale.isAr;
    final on = value != null;
    final tt = AppTemplate.of(context);
    final color = tt.isShop ? tt.accent : tt.ink;
    return InputChip(
      avatar: Icon(Icons.calendar_month_rounded, size: 18, color: on ? Colors.white : color),
      label: Text(text(value, ar, empty: label), style: TextStyle(fontWeight: FontWeight.w800, color: on ? Colors.white : null)),
      selected: on,
      showCheckmark: false,
      selectedColor: color,
      onPressed: () => _open(context),
      onDeleted: on ? () => onChanged(null) : null,
      deleteIconColor: Colors.white,
    );
  }
}

/// Search + filter chips + endless pagination + pull to refresh. The backend pages are `{data:[...], meta:{hasNext}}`.
class PagedList extends StatefulWidget {
  const PagedList({super.key, required this.api, required this.path, required this.itemBuilder, this.searchHint, this.searchParam = 'q', this.filters, this.filterParam, this.query, this.pageSize = 20, this.emptyText, this.header, this.fab, this.dateRange = false, this.dateLabel, this.futureDates = false, this.extraFilters});
  /// Shows a date chip next to the filters and sends from/to.
  final bool dateRange;
  final String? dateLabel;
  final bool futureDates;
  /// More chips/buttons in the filter row (e.g. a "more filters" button).
  final List<Widget>? extraFilters;
  final ApiClient api;
  final String path;
  final String? searchHint;
  final String searchParam;
  /// (value, label) chips; the selected value is sent as [filterParam]. Value '' means "all".
  final List<(String, String)>? filters;
  final String? filterParam;
  final Map<String, String?>? query;
  final int pageSize;
  final String? emptyText;
  final Widget? header;
  final Widget? fab;
  final Widget Function(BuildContext, Map<String, dynamic>, Future<void> Function() reload) itemBuilder;
  @override
  State<PagedList> createState() => PagedListState();
}

class PagedListState extends State<PagedList> {
  final _scroll = ScrollController();
  final _search = TextEditingController();
  Timer? _t;
  List<Map<String, dynamic>> _items = [];
  String _filter = '';
  DateTimeRange? _range;
  int _page = 1;
  bool _loading = true, _more = false, _hasNext = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() { if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 300 && _hasNext && !_more && !_loading) _loadMore(); });
    _first();
  }

  /// The phone's copy of the first page shows at once; the fresh answer replaces it.
  Future<void> _first() async {
    try {
      final c = await widget.api.peek(widget.path, query: _q(1));
      final list = c is Map ? c['data'] : c;
      if (list is List && mounted && _items.isEmpty) {
        setState(() { _items = list.map((e) => Map<String, dynamic>.from(e as Map)).toList(); _hasNext = c is Map && (c['meta'] as Map?)?['hasNext'] == true; _loading = false; });
      }
    } catch (_) {}
    await reload();
  }

  @override
  void dispose() { _t?.cancel(); _scroll.dispose(); _search.dispose(); super.dispose(); }

  Map<String, String?> _q(int page) => {
        ...?widget.query,
        'page': '$page',
        'pageSize': '${widget.pageSize}',
        if (_search.text.trim().isNotEmpty) widget.searchParam: _search.text.trim(),
        if (widget.filterParam != null && _filter.isNotEmpty) widget.filterParam!: _filter,
        ...rangeQuery(_range),
      };

  Future<void> reload() async {
    setState(() { _loading = _items.isEmpty; _error = null; _page = 1; });
    try {
      final r = await widget.api.get(widget.path, query: _q(1));
      final list = r is Map ? (r['data'] as List) : (r as List);
      if (!mounted) return;
      setState(() { _items = list.cast<Map<String, dynamic>>(); _hasNext = r is Map && (r['meta'] as Map?)?['hasNext'] == true; _loading = false; });
    } catch (e) {
      if (mounted) setState(() { if (_items.isEmpty) _error = e; _loading = false; });
    }
  }

  Future<void> _loadMore() async {
    setState(() => _more = true);
    try {
      final r = await widget.api.get(widget.path, query: _q(_page + 1)) as Map;
      if (!mounted) return;
      setState(() { _page++; _items = [..._items, ...(r['data'] as List).cast<Map<String, dynamic>>()]; _hasNext = (r['meta'] as Map?)?['hasNext'] == true; });
    } catch (_) {}
    if (mounted) setState(() => _more = false);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      backgroundColor: Colors.transparent,
      floatingActionButton: widget.fab,
      body: Column(children: [
        if (widget.searchHint != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
            child: TextField(
              controller: _search,
              onChanged: (_) { _t?.cancel(); _t = Timer(const Duration(milliseconds: 350), reload); },
              decoration: InputDecoration(hintText: widget.searchHint, prefixIcon: const Icon(Icons.search_rounded), suffixIcon: _search.text.isEmpty ? null : IconButton(icon: const Icon(Icons.close_rounded), onPressed: () { _search.clear(); reload(); })),
            ),
          ),
        if (widget.filters != null || widget.dateRange || widget.extraFilters != null)
          SizedBox(
            height: 50,
            child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6), children: [
              if (widget.dateRange) Padding(padding: const EdgeInsets.symmetric(horizontal: 4), child: DateRangeChip(value: _range, future: widget.futureDates, label: widget.dateLabel, onChanged: (r) { setState(() => _range = r); reload(); })),
              ...?widget.extraFilters,
              for (final f in widget.filters ?? const <(String, String)>[])
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: ChoiceChip(label: Text(f.$2), selected: _filter == f.$1, showCheckmark: false, onSelected: (_) { setState(() => _filter = f.$1); reload(); }),
                ),
            ]),
          ),
        if (widget.header != null) widget.header!,
        Expanded(
          child: _loading
              ? ListView(padding: const EdgeInsets.all(16), children: const [Skeleton(height: 84), SizedBox(height: 10), Skeleton(height: 84), SizedBox(height: 10), Skeleton(height: 84)])
              : _error != null
                  ? errorState(_error!, reload)
                  : RefreshIndicator(
                      onRefresh: reload,
                      child: _items.isEmpty
                          ? ListView(children: [emptyState(widget.emptyText ?? s.empty)])
                          : ListView.builder(
                              controller: _scroll,
                              padding: const EdgeInsets.fromLTRB(16, 6, 16, 90),
                              itemCount: _items.length + (_more ? 1 : 0),
                              itemBuilder: (c, i) => i >= _items.length ? const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator())) : FadeSlideIn(index: i < 8 ? i : 0, dy: 12, child: widget.itemBuilder(c, _items[i], reload)),
                            ),
                    ),
        ),
      ]),
    );
  }
}

/// A "key: value" row for detail screens.
Widget kv(String k, String v, {bool ltr = false}) => Builder(builder: (context) => Padding(
      padding: EdgeInsets.symmetric(vertical: AppTemplate.of(context).isClinic ? 7 : 5),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 118, child: Text(k, style: TextStyle(color: AppTemplate.of(context).muted))),
        Expanded(child: Text(v.isEmpty ? '-' : (ltr ? localPhone(v) : v), textDirection: ltr ? TextDirection.ltr : null, style: const TextStyle(fontWeight: FontWeight.w700))),
      ]),
    ));

/// A queue entry: avatar + full-width name/details on top, the action buttons below (side by side, sharing the width) so nothing squeezes the name.
class QueueCard extends StatelessWidget {
  const QueueCard({super.key, required this.leading, required this.title, required this.subtitle, this.actions = const [], this.color, this.trailing, this.onTap});
  final Widget leading;
  final String title, subtitle;
  final List<Widget> actions;
  final Color? color;
  final Widget? trailing;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Card(
        color: color,
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              leading,
              const SizedBox(width: 12),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(title, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
                const SizedBox(height: 2),
                Text(subtitle, style: TextStyle(color: AppTemplate.of(context).muted)),
              ])),
              if (trailing != null) trailing!,
            ]),
            if (actions.isNotEmpty) ...[
              const SizedBox(height: 10),
              Row(children: [for (var i = 0; i < actions.length; i++) ...[if (i > 0) const SizedBox(width: 8), Expanded(child: actions[i])]]),
            ],
          ]),
        ),
        ),
      );
}

/// "كشف" / "إعادة" for an appointment's visit type, or '' when unknown.
String visitTypeLabel(Object? type, bool ar) => type == 'FOLLOW_UP' ? (ar ? 'إعادة' : 'Follow-up') : type == 'CONSULTATION' ? (ar ? 'كشف' : 'Consultation') : '';

/// Joins the non-empty parts with " · ".
String dotted(Iterable<Object?> parts) => parts.where((e) => e != null && '$e'.trim().isNotEmpty).join(' · ');
