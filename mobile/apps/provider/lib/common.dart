import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';
import 'package:url_launcher/url_launcher.dart';

/// What every control-panel screen needs: the business's API, its currency and the user's permissions.
class Ctx {
  const Ctx({required this.api, required this.currency, required this.perms, required this.host, required this.type});
  final ApiClient api;
  final String currency;
  final List<String> perms;
  final String host;
  final String type; // STORE | CLINIC
  bool can(String p) => perms.contains(p);
  bool canAny(List<String> ps) => ps.any(perms.contains);
}

Future<void> launch(String url) async {
  try { await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication); } catch (_) {}
}

Future<void> dial(String? phone) async { if (phone != null && phone.isNotEmpty) await launch('tel:$phone'); }

Future<void> whatsapp(String? phone, [String text = '']) async {
  if (phone == null || phone.isEmpty) return;
  final digits = phone.replaceAll(RegExp(r'[^0-9]'), '');
  await launch('https://wa.me/$digits${text.isEmpty ? '' : '?text=${Uri.encodeComponent(text)}'}');
}

void toast(BuildContext c, String msg) => ScaffoldMessenger.of(c).showSnackBar(SnackBar(content: Text(msg)));

/// Runs an action, shows a readable error if it fails and reports success so the caller can refresh.
Future<bool> run(BuildContext c, Future<void> Function() f) async {
  try { await f(); return true; } catch (e) { if (c.mounted) toast(c, errorText(e)); return false; }
}

Color toneFor(String status) => switch (status) {
      'CONFIRMED' || 'COMPLETED' || 'DELIVERED' || 'ARRIVED' || 'PAID' || 'ACTIVE' || 'APPROVED' || 'PUBLISHED' => const Color(0xFF1E9E5A),
      'PENDING' || 'PENDING_CONFIRMATION' || 'REQUESTED' || 'UNPAID' || 'PREPARING' || 'DRAFT' => const Color(0xFFD98200),
      'CHECKED_IN' || 'IN_PROGRESS' || 'SHIPPED' || 'PROCESSING' => const Color(0xFF1F6A99),
      'CANCELLED' || 'REJECTED' || 'NO_SHOW' || 'RETURNED' || 'REFUNDED' || 'HIDDEN' => const Color(0xFFD14343),
      _ => const Color(0xFF6B7C8D),
    };

/// Small coloured status label, translated for the common order/appointment states.
class StatusPill extends StatelessWidget {
  const StatusPill(this.status, {super.key});
  final String status;
  static const _ar = {
    'PENDING': 'قيد الانتظار', 'PENDING_CONFIRMATION': 'بانتظار التأكيد', 'REQUESTED': 'جديد', 'CONFIRMED': 'مؤكد', 'CHECKED_IN': 'حضر', 'IN_PROGRESS': 'قيد الكشف', 'COMPLETED': 'مكتمل',
    'CANCELLED': 'ملغي', 'NO_SHOW': 'لم يحضر', 'REJECTED': 'مرفوض', 'PREPARING': 'قيد التجهيز', 'SHIPPED': 'تم الشحن', 'ARRIVED': 'تم التسليم', 'RETURNED': 'مرتجع', 'DELIVERED': 'تم التسليم',
    'PAID': 'مدفوع', 'UNPAID': 'غير مدفوع', 'APPROVED': 'مقبول', 'PUBLISHED': 'منشور', 'HIDDEN': 'مخفي', 'ACTIVE': 'نشط', 'DRAFT': 'مسودة', 'REFUNDED': 'مسترد', 'INVITED': 'مدعو',
  };
  @override
  Widget build(BuildContext context) {
    final c = toneFor(status);
    final label = AppLocale.isAr ? (_ar[status] ?? status) : status.replaceAll('_', ' ').toLowerCase();
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: c.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(20)),
      child: Text(label, style: TextStyle(color: c, fontWeight: FontWeight.w800, fontSize: 12)),
    );
  }
}

Widget emptyState(String text, {IconData icon = Icons.inbox_outlined}) => Padding(padding: const EdgeInsets.all(40), child: Center(child: Column(mainAxisSize: MainAxisSize.min, children: [Icon(icon, size: 44, color: const Color(0xFF9AA9BA)), const SizedBox(height: 10), Text(text, textAlign: TextAlign.center, style: const TextStyle(color: Color(0xFF6B7C8D)))])));

Widget errorState(Object e, VoidCallback retry) => Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
      const Icon(Icons.cloud_off_rounded, size: 46, color: Color(0xFF9AA9BA)),
      const SizedBox(height: 10),
      Text(errorText(e), textAlign: TextAlign.center),
      TextButton.icon(onPressed: retry, icon: const Icon(Icons.refresh_rounded), label: Text(S(AppLocale.isAr ? 'ar' : 'en').retry)),
    ])));

String fmtDate(String? iso, {bool time = true}) {
  if (iso == null || iso.isEmpty) return '';
  final d = DateTime.tryParse(iso)?.toLocal();
  if (d == null) return iso;
  String two(int n) => n.toString().padLeft(2, '0');
  final day = '${d.year}-${two(d.month)}-${two(d.day)}';
  return time ? '$day  ${two(d.hour)}:${two(d.minute)}' : day;
}

/// Search + filter chips + endless pagination + pull to refresh. The backend pages are `{data:[...], meta:{hasNext}}`.
class PagedList extends StatefulWidget {
  const PagedList({super.key, required this.api, required this.path, required this.itemBuilder, this.searchHint, this.searchParam = 'q', this.filters, this.filterParam, this.query, this.pageSize = 20, this.emptyText, this.header, this.fab});
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
  int _page = 1;
  bool _loading = true, _more = false, _hasNext = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() { if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 300 && _hasNext && !_more && !_loading) _loadMore(); });
    reload();
  }

  @override
  void dispose() { _t?.cancel(); _scroll.dispose(); _search.dispose(); super.dispose(); }

  Map<String, String?> _q(int page) => {
        ...?widget.query,
        'page': '$page',
        'pageSize': '${widget.pageSize}',
        if (_search.text.trim().isNotEmpty) widget.searchParam: _search.text.trim(),
        if (widget.filterParam != null && _filter.isNotEmpty) widget.filterParam!: _filter,
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
    final scheme = Theme.of(context).colorScheme;
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
        if (widget.filters != null)
          SizedBox(
            height: 46,
            child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6), children: [
              for (final f in widget.filters!)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: ChoiceChip(label: Text(f.$2), selected: _filter == f.$1, selectedColor: scheme.primary, labelStyle: TextStyle(color: _filter == f.$1 ? Colors.white : null, fontWeight: FontWeight.w700), showCheckmark: false, onSelected: (_) { setState(() => _filter = f.$1); reload(); }),
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
Widget kv(String k, String v, {bool ltr = false}) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 118, child: Text(k, style: const TextStyle(color: Color(0xFF6B7C8D)))),
        Expanded(child: Text(v.isEmpty ? '-' : v, textDirection: ltr ? TextDirection.ltr : null, style: const TextStyle(fontWeight: FontWeight.w700))),
      ]),
    );

/// A card used for "manage on the web" entries: heavy configuration stays on the website, one tap away.
class WebLinkCard extends StatelessWidget {
  const WebLinkCard({super.key, required this.title, required this.icon, required this.url, this.subtitle});
  final String title;
  final String? subtitle;
  final IconData icon;
  final String url;
  @override
  Widget build(BuildContext context) => Card(child: ListTile(
        leading: Icon(icon, color: Theme.of(context).colorScheme.primary),
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.w800)),
        subtitle: Text(subtitle ?? (AppLocale.isAr ? 'تُدار من موقع الويب' : 'Managed on the website')),
        trailing: const Icon(Icons.open_in_new_rounded, size: 20),
        onTap: () => launch(url),
      ));
}
