import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'filters.dart';
import 'profile.dart';

/// Find a doctor across every clinic on the platform: search, a specialty rail, and a full filter sheet (city, fee range, visit type,
/// online booking, sort). Filters stay as chosen for the rest of the session. Data: GET tenant/doctors and tenant/doctors/filters (public).
class DoctorDirectory extends StatefulWidget {
  const DoctorDirectory({super.key, required this.api});
  final ApiClient api;
  @override
  State<DoctorDirectory> createState() => _DoctorDirectoryState();
}

class _DoctorDirectoryState extends State<DoctorDirectory> {
  static DoctorFilter _kept = const DoctorFilter();   // remembered while the app runs
  DoctorFilter _f = _kept;
  final _search = TextEditingController(text: _kept.q);
  final _scroll = ScrollController();
  Timer? _debounce;
  DoctorFacets? _facets;
  List<Map<String, dynamic>> _rows = [];
  int _page = 1, _total = 0;
  bool _loading = true, _more = false, _hasNext = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() { if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 400 && _hasNext && !_more && !_loading) _loadMore(); });
    _init();
  }

  @override
  void dispose() { _debounce?.cancel(); _search.dispose(); _scroll.dispose(); super.dispose(); }

  Future<void> _init() async {
    try { _facets = DoctorFacets.fromJson(await widget.api.get('tenant/doctors/filters') as Map<String, dynamic>); } catch (_) {/* filters are optional */}
    await _fetch();
  }

  Future<void> _fetch() async {
    setState(() { _loading = true; _error = null; _page = 1; });
    try {
      final r = await widget.api.get('tenant/doctors', query: {..._f.query, 'page': '1', 'pageSize': '20'}) as Map<String, dynamic>;
      if (!mounted) return;
      setState(() { _rows = (r['data'] as List).cast<Map<String, dynamic>>(); _total = (r['meta'] as Map)['total'] as int? ?? _rows.length; _hasNext = (r['meta'] as Map)['hasNext'] == true; _loading = false; });
    } catch (e) {
      if (mounted) setState(() { _error = e; _loading = false; });
    }
  }

  Future<void> _loadMore() async {
    setState(() => _more = true);
    try {
      final r = await widget.api.get('tenant/doctors', query: {..._f.query, 'page': '${_page + 1}', 'pageSize': '20'}) as Map<String, dynamic>;
      if (!mounted) return;
      setState(() { _page++; _rows = [..._rows, ...(r['data'] as List).cast<Map<String, dynamic>>()]; _hasNext = (r['meta'] as Map)['hasNext'] == true; });
    } catch (_) {} finally { if (mounted) setState(() => _more = false); }
  }

  void _apply(DoctorFilter f) { setState(() { _f = f; _kept = f; }); _fetch(); }

  Future<void> _openFilters() async {
    final s = S.of(context);
    final f = await showModalBottomSheet<DoctorFilter>(
      context: context, isScrollControlled: true, useSafeArea: true, backgroundColor: Colors.transparent,
      builder: (_) => DoctorFilterSheet(initial: _f, facets: _facets, ar: s.ar),
    );
    if (f != null) _apply(f);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final cs = Theme.of(context).colorScheme;
    final specs = _facets?.specialties ?? const [];
    return Scaffold(
      backgroundColor: const Color(0xFFF5F7F6),
      body: RefreshIndicator(
        onRefresh: _fetch,
        child: CustomScrollView(
          controller: _scroll,
          slivers: [
            SliverAppBar.large(
              backgroundColor: const Color(0xFFF5F7F6),
              surfaceTintColor: Colors.transparent,
              title: Text(s.ar ? 'ابحث عن طبيب' : 'Find a doctor', style: const TextStyle(fontWeight: FontWeight.w800)),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
                child: Row(children: [
                  Expanded(
                    child: TextField(
                      controller: _search,
                      textInputAction: TextInputAction.search,
                      onChanged: (v) { _debounce?.cancel(); _debounce = Timer(const Duration(milliseconds: 350), () => _apply(_f.copyWith(q: v.trim()))); },
                      decoration: InputDecoration(
                        hintText: s.ar ? 'اسم الطبيب أو العيادة' : 'Doctor or clinic name',
                        prefixIcon: const Icon(Icons.search_rounded),
                        filled: true, fillColor: Colors.white,
                        contentPadding: const EdgeInsets.symmetric(vertical: 14),
                        border: OutlineInputBorder(borderRadius: BorderRadius.circular(16), borderSide: BorderSide.none),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  _FilterButton(count: _f.activeCount, onTap: _openFilters, label: s.ar ? 'تصفية' : 'Filters'),
                ]),
              ),
            ),
            if (specs.isNotEmpty)
              SliverToBoxAdapter(
                child: SizedBox(
                  height: 46,
                  child: ListView(
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    children: [
                      _RailChip(label: s.ar ? 'الكل' : 'All', selected: _f.specialty == null, onTap: () => _apply(_f.copyWith(specialty: null, clearSpecialty: true))),
                      for (final sp in specs) _RailChip(label: s.ar ? sp.nameAr : sp.nameEn, selected: _f.specialty == sp.code, onTap: () => _apply(_f.copyWith(specialty: sp.code))),
                    ],
                  ),
                ),
              ),
            SliverToBoxAdapter(
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 220),
                child: Padding(
                  key: ValueKey('$_total-$_loading'),
                  padding: const EdgeInsets.fromLTRB(20, 14, 20, 6),
                  child: Text(_loading ? ' ' : (s.ar ? '$_total طبيب' : '$_total doctors'), style: TextStyle(color: cs.onSurfaceVariant, fontWeight: FontWeight.w600)),
                ),
              ),
            ),
            if (_loading)
              SliverList.builder(itemCount: 5, itemBuilder: (_, i) => const Padding(padding: EdgeInsets.fromLTRB(16, 6, 16, 6), child: Skeleton(height: 112)))
            else if (_error != null)
              SliverFillRemaining(hasScrollBody: false, child: _Empty(icon: Icons.wifi_off_rounded, title: errorText(_error!), action: FilledButton(onPressed: _fetch, child: Text(s.ar ? 'حاول مرة أخرى' : 'Try again'))))
            else if (_rows.isEmpty)
              SliverFillRemaining(hasScrollBody: false, child: _Empty(icon: Icons.person_search_rounded, title: s.ar ? 'لا يوجد طبيب بهذه الاختيارات' : 'No doctor matches these choices',
                  action: _f.activeCount > 0 || _f.q.isNotEmpty ? OutlinedButton(onPressed: () { _search.clear(); _apply(const DoctorFilter()); }, child: Text(s.ar ? 'مسح التصفية' : 'Clear filters')) : null))
            else
              SliverList.builder(
                itemCount: _rows.length + (_more ? 1 : 0),
                itemBuilder: (_, i) => i >= _rows.length
                    ? const Padding(padding: EdgeInsets.all(20), child: Center(child: CircularProgressIndicator()))
                    : FadeSlideIn(index: i % 8, child: DoctorCard(d: _rows[i], ar: s.ar, onTap: () => Navigator.of(context).push(_route(DoctorProfile(d: _rows[i]))))),
              ),
            const SliverToBoxAdapter(child: SizedBox(height: 32)),
          ],
        ),
      ),
    );
  }
}

/// A fade-through push that keeps the photo's Hero flight smooth.
Route<T> _route<T>(Widget page) => PageRouteBuilder<T>(
      transitionDuration: const Duration(milliseconds: 380),
      reverseTransitionDuration: const Duration(milliseconds: 300),
      pageBuilder: (_, __, ___) => page,
      transitionsBuilder: (_, a, __, child) => FadeTransition(opacity: CurvedAnimation(parent: a, curve: Curves.easeOutCubic), child: child),
    );

class _FilterButton extends StatelessWidget {
  const _FilterButton({required this.count, required this.onTap, required this.label});
  final int count;
  final VoidCallback onTap;
  final String label;
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return PressScale(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 220),
        height: 50,
        padding: const EdgeInsets.symmetric(horizontal: 14),
        decoration: BoxDecoration(color: count > 0 ? cs.primary : Colors.white, borderRadius: BorderRadius.circular(16)),
        child: Row(children: [
          Icon(Icons.tune_rounded, color: count > 0 ? Colors.white : cs.onSurface),
          if (count > 0) ...[
            const SizedBox(width: 6),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
              decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(99)),
              child: Text('$count', style: TextStyle(color: cs.primary, fontWeight: FontWeight.w800, fontSize: 12)),
            ),
          ],
          Semantics(label: label, child: const SizedBox.shrink()),
        ]),
      ),
    );
  }
}

class _RailChip extends StatelessWidget {
  const _RailChip({required this.label, required this.selected, required this.onTap});
  final String label;
  final bool selected;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsetsDirectional.only(end: 8),
      child: PressScale(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: selected ? cs.onSurface : Colors.white,
            borderRadius: BorderRadius.circular(99),
            border: Border.all(color: selected ? cs.onSurface : const Color(0xFFE3E8E6)),
          ),
          child: Text(label, style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13, color: selected ? Colors.white : cs.onSurface)),
        ),
      ),
    );
  }
}

/// One doctor in the list: photo (flies into the profile), name, specialties, clinic and city, experience and fee.
class DoctorCard extends StatelessWidget {
  const DoctorCard({super.key, required this.d, required this.ar, required this.onTap});
  final Map<String, dynamic> d;
  final bool ar;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final clinic = (d['clinic'] as Map).cast<String, dynamic>();
    final specs = specialtyNames(d, ar);
    final cities = (clinic['cities'] as List? ?? const []).cast<String>();
    final years = d['yearsExperience'] as int?;
    final fee = d['feeMinor'] as num?;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
      child: PressScale(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22), boxShadow: const [BoxShadow(color: Color(0x0F0B2A22), blurRadius: 18, offset: Offset(0, 6))]),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Hero(tag: 'doc-${d['id']}', child: DoctorPhoto(url: d['photoUrl'] as String?, name: d['name'] as String, size: 84, radius: 18)),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(d['name'] as String, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                if (specs.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 2), child: Text(specs.join(' · '), maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: cs.primary, fontWeight: FontWeight.w700, fontSize: 13))),
                const SizedBox(height: 6),
                Row(children: [
                  Icon(Icons.local_hospital_outlined, size: 15, color: cs.onSurfaceVariant),
                  const SizedBox(width: 4),
                  Expanded(child: Text([clinic['name'], if (cities.isNotEmpty) cities.first].join(' · '), maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12.5))),
                ]),
                const SizedBox(height: 8),
                Wrap(spacing: 6, runSpacing: 6, children: [
                  if (years != null && years > 0) _Pill(icon: Icons.workspace_premium_outlined, text: ar ? '$years سنة خبرة' : '$years yrs'),
                  if (fee != null) _Pill(icon: Icons.payments_outlined, text: money(fee, d['currency'] as String? ?? 'EGP', ar), strong: true),
                  if (clinic['bookingEnabled'] == true) _Pill(icon: Icons.event_available_rounded, text: ar ? 'حجز أونلاين' : 'Books online', green: true),
                ]),
              ]),
            ),
          ]),
        ),
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.icon, required this.text, this.strong = false, this.green = false});
  final IconData icon;
  final String text;
  final bool strong, green;
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final fg = green ? const Color(0xFF13795B) : strong ? cs.onSurface : cs.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(color: green ? const Color(0xFFE6F6EF) : const Color(0xFFF2F4F3), borderRadius: BorderRadius.circular(99)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(icon, size: 13, color: fg), const SizedBox(width: 4), Text(text, style: TextStyle(fontSize: 11.5, fontWeight: strong ? FontWeight.w800 : FontWeight.w600, color: fg))]),
    );
  }
}

class _Empty extends StatelessWidget {
  const _Empty({required this.icon, required this.title, this.action});
  final IconData icon;
  final String title;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Container(width: 72, height: 72, decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(24)), child: Icon(icon, size: 34, color: Theme.of(context).colorScheme.primary)),
          const SizedBox(height: 16),
          Text(title, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
          if (action != null) ...[const SizedBox(height: 16), action!],
        ]),
      );
}

/// The doctor's photo, or their initial on the brand colour when there is none (or it fails to load).
class DoctorPhoto extends StatelessWidget {
  const DoctorPhoto({super.key, required this.url, required this.name, this.size = 80, this.radius = 18});
  final String? url;
  final String name;
  final double size, radius;
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final fallback = Container(
      width: size, height: size, alignment: Alignment.center,
      decoration: BoxDecoration(gradient: LinearGradient(colors: [cs.primary, cs.primary.withValues(alpha: .7)]), borderRadius: BorderRadius.circular(radius)),
      child: Text(name.trim().isEmpty ? '?' : name.trim().characters.first, style: TextStyle(color: Colors.white, fontSize: size * .38, fontWeight: FontWeight.w800)),
    );
    if (url == null) return fallback;
    return ClipRRect(borderRadius: BorderRadius.circular(radius), child: Image.network(url!, width: size, height: size, fit: BoxFit.cover, errorBuilder: (_, __, ___) => fallback));
  }
}

List<String> specialtyNames(Map<String, dynamic> d, bool ar) {
  final list = (d['specialties'] as List? ?? const []).cast<Map>();
  return list.map((s) => s['code'] == 'other' && (d['otherSpecialty'] as String?)?.isNotEmpty == true ? d['otherSpecialty'] as String : (ar ? s['nameAr'] : s['nameEn']) as String).toList();
}

String money(num minor, String currency, bool ar) {
  final v = minor / 100;
  final n = v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(2);
  return ar ? '$n ${currency == 'EGP' ? 'ج.م' : currency}' : '$currency $n';
}
