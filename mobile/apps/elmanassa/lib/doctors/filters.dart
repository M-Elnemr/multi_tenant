import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'directory.dart' show money;

class Specialty {
  const Specialty(this.code, this.nameAr, this.nameEn, this.n);
  final String code, nameAr, nameEn;
  final int n;
}

/// What the directory can be filtered by: only values a listed doctor really has (from tenant/doctors/filters).
class DoctorFacets {
  const DoctorFacets({required this.specialties, required this.cities, this.feeMin, this.feeMax, this.onlineBooking = false});
  final List<Specialty> specialties;
  final List<String> cities;
  final int? feeMin, feeMax;
  final bool onlineBooking;
  factory DoctorFacets.fromJson(Map<String, dynamic> j) => DoctorFacets(
        specialties: (j['specialties'] as List? ?? const []).cast<Map>().map((s) => Specialty(s['code'] as String, s['nameAr'] as String, s['nameEn'] as String, (s['n'] as num?)?.toInt() ?? 0)).toList(),
        cities: (j['cities'] as List? ?? const []).cast<String>(),
        feeMin: (j['feeMinMinor'] as num?)?.toInt(),
        feeMax: (j['feeMaxMinor'] as num?)?.toInt(),
        onlineBooking: j['onlineBooking'] == true,
      );
}

/// The chosen filters (immutable). `query` is what the API takes.
class DoctorFilter {
  const DoctorFilter({this.q = '', this.specialty, this.city, this.minFee, this.maxFee, this.visitType, this.bookingOnly = false, this.sort = 'name'});
  final String q;
  final String? specialty, city, visitType;
  final int? minFee, maxFee;
  final bool bookingOnly;
  final String sort;

  int get activeCount => [specialty != null, city != null, minFee != null || maxFee != null, visitType != null, bookingOnly, sort != 'name'].where((x) => x).length;

  Map<String, String?> get query => {
        'q': q.isEmpty ? null : q, 'specialty': specialty, 'city': city, 'minFee': minFee?.toString(), 'maxFee': maxFee?.toString(),
        'visitType': visitType, 'bookingOnly': bookingOnly ? 'true' : null, 'sort': sort,
      };

  DoctorFilter copyWith({String? q, String? specialty, bool clearSpecialty = false, String? city, bool clearCity = false, int? minFee, int? maxFee, bool clearFee = false,
          String? visitType, bool clearVisit = false, bool? bookingOnly, String? sort}) =>
      DoctorFilter(
        q: q ?? this.q,
        specialty: clearSpecialty ? null : specialty ?? this.specialty,
        city: clearCity ? null : city ?? this.city,
        minFee: clearFee ? null : minFee ?? this.minFee,
        maxFee: clearFee ? null : maxFee ?? this.maxFee,
        visitType: clearVisit ? null : visitType ?? this.visitType,
        bookingOnly: bookingOnly ?? this.bookingOnly,
        sort: sort ?? this.sort,
      );
}

/// Full-height, draggable filter sheet: sort, specialty, city, fee range slider, visit type and (when the platform offers it) online booking.
/// Selections animate; "Clear" resets, "Show doctors" applies and closes.
class DoctorFilterSheet extends StatefulWidget {
  const DoctorFilterSheet({super.key, required this.initial, required this.facets, required this.ar});
  final DoctorFilter initial;
  final DoctorFacets? facets;
  final bool ar;
  @override
  State<DoctorFilterSheet> createState() => _DoctorFilterSheetState();
}

class _DoctorFilterSheetState extends State<DoctorFilterSheet> {
  late DoctorFilter f = widget.initial;

  @override
  Widget build(BuildContext context) {
    final ar = widget.ar;
    final fc = widget.facets;
    final cs = Theme.of(context).colorScheme;
    final lo = fc?.feeMin, hi = fc?.feeMax;
    final hasFee = lo != null && hi != null && hi > lo;
    return DraggableScrollableSheet(
      initialChildSize: .86, minChildSize: .5, maxChildSize: .95, expand: false,
      builder: (context, scroll) => Container(
        decoration: const BoxDecoration(color: Colors.white, borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
        child: Column(children: [
          const SizedBox(height: 10),
          Container(width: 44, height: 5, decoration: BoxDecoration(color: const Color(0xFFDDE3E0), borderRadius: BorderRadius.circular(99))),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 12, 4),
            child: Row(children: [
              Text(ar ? 'تصفية الأطباء' : 'Filter doctors', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
              const Spacer(),
              IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.close_rounded)),
            ]),
          ),
          Expanded(
            child: ListView(controller: scroll, padding: const EdgeInsets.fromLTRB(20, 4, 20, 20), children: [
              _Title(ar ? 'الترتيب' : 'Sort by'),
              _Choices(
                values: const ['name', 'fee_asc', 'fee_desc', 'experience'],
                labels: ar ? const ['الاسم', 'الأقل سعرًا', 'الأعلى سعرًا', 'الأكثر خبرة'] : const ['Name', 'Lowest fee', 'Highest fee', 'Most experienced'],
                selected: f.sort, onPick: (v) => setState(() => f = f.copyWith(sort: v)),
              ),
              if ((fc?.specialties ?? const []).isNotEmpty) ...[
                _Title(ar ? 'التخصص' : 'Specialty'),
                _Choices(
                  values: fc!.specialties.map((s) => s.code).toList(), labels: fc.specialties.map((s) => '${ar ? s.nameAr : s.nameEn}  ${s.n}').toList(),
                  selected: f.specialty, onPick: (v) => setState(() => f = v == f.specialty ? f.copyWith(clearSpecialty: true) : f.copyWith(specialty: v)),
                ),
              ],
              if ((fc?.cities ?? const []).isNotEmpty) ...[
                _Title(ar ? 'المدينة' : 'City'),
                _Choices(values: fc!.cities, labels: fc.cities, selected: f.city, onPick: (v) => setState(() => f = v == f.city ? f.copyWith(clearCity: true) : f.copyWith(city: v))),
              ],
              if (hasFee) ...[
                _Title(ar ? 'سعر الكشف' : 'Consultation fee'),
                _FeeRange(lo: lo, hi: hi, value: RangeValues((f.minFee ?? lo).toDouble(), (f.maxFee ?? hi).toDouble()), ar: ar,
                    onChanged: (r) => setState(() => f = DoctorFilter(q: f.q, specialty: f.specialty, city: f.city, visitType: f.visitType, bookingOnly: f.bookingOnly, sort: f.sort,
                        minFee: r.start.round() <= lo ? null : r.start.round(), maxFee: r.end.round() >= hi ? null : r.end.round()))),
              ],
              _Title(ar ? 'نوع الزيارة' : 'Visit type'),
              _Choices(
                values: const ['CONSULTATION', 'FOLLOW_UP'], labels: ar ? const ['كشف', 'إعادة / متابعة'] : const ['Consultation', 'Follow-up'],
                selected: f.visitType, onPick: (v) => setState(() => f = v == f.visitType ? f.copyWith(clearVisit: true) : f.copyWith(visitType: v)),
              ),
              if (fc?.onlineBooking == true) ...[
                const SizedBox(height: 12),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero, value: f.bookingOnly, onChanged: (v) => setState(() => f = f.copyWith(bookingOnly: v)),
                  title: Text(ar ? 'يقبل الحجز أونلاين فقط' : 'Books online only', style: const TextStyle(fontWeight: FontWeight.w700)),
                ),
              ],
            ]),
          ),
          SafeArea(
            top: false,
            child: Container(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
              decoration: const BoxDecoration(border: Border(top: BorderSide(color: Color(0xFFEDF0EF)))),
              child: Row(children: [
                TextButton(onPressed: f.activeCount == 0 ? null : () => setState(() => f = DoctorFilter(q: f.q)), child: Text(ar ? 'مسح الكل' : 'Clear all')),
                const SizedBox(width: 10),
                Expanded(
                  child: FilledButton(
                    style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)), backgroundColor: cs.onSurface),
                    onPressed: () => Navigator.pop(context, f),
                    child: Text(ar ? 'عرض الأطباء' : 'Show doctors', style: const TextStyle(fontWeight: FontWeight.w800)),
                  ),
                ),
              ]),
            ),
          ),
        ]),
      ),
    );
  }
}

class _Title extends StatelessWidget {
  const _Title(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Padding(padding: const EdgeInsets.only(top: 18, bottom: 10), child: Text(text, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 15)));
}

/// Wrap of selectable chips; the chosen one fills with an animated colour change and a check.
class _Choices extends StatelessWidget {
  const _Choices({required this.values, required this.labels, required this.selected, required this.onPick});
  final List<String> values, labels;
  final String? selected;
  final ValueChanged<String> onPick;
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Wrap(spacing: 8, runSpacing: 8, children: [
      for (var i = 0; i < values.length; i++)
        PressScale(
          onTap: () => onPick(values[i]),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            curve: Curves.easeOutCubic,
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              color: values[i] == selected ? cs.primary.withValues(alpha: .1) : const Color(0xFFF5F7F6),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: values[i] == selected ? cs.primary : Colors.transparent, width: 1.5),
            ),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              AnimatedSize(duration: const Duration(milliseconds: 200), child: values[i] == selected ? Padding(padding: const EdgeInsetsDirectional.only(end: 6), child: Icon(Icons.check_rounded, size: 16, color: cs.primary)) : const SizedBox.shrink()),
              Text(labels[i], style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13, color: values[i] == selected ? cs.primary : cs.onSurface)),
            ]),
          ),
        ),
    ]);
  }
}

class _FeeRange extends StatelessWidget {
  const _FeeRange({required this.lo, required this.hi, required this.value, required this.onChanged, required this.ar});
  final int lo, hi;
  final RangeValues value;
  final ValueChanged<RangeValues> onChanged;
  final bool ar;
  @override
  Widget build(BuildContext context) {
    final step = ((hi - lo) / 40).clamp(500, 1e9).toDouble();
    final divisions = ((hi - lo) / step).round().clamp(1, 200);
    return Column(children: [
      Row(children: [
        _Bubble(money(value.start, 'EGP', ar)),
        const Expanded(child: Divider(indent: 10, endIndent: 10)),
        _Bubble(money(value.end, 'EGP', ar)),
      ]),
      RangeSlider(min: lo.toDouble(), max: hi.toDouble(), divisions: divisions, values: value, onChanged: onChanged),
    ]);
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => AnimatedSwitcher(
        duration: const Duration(milliseconds: 150),
        child: Container(
          key: ValueKey(text),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          decoration: BoxDecoration(color: const Color(0xFFF5F7F6), borderRadius: BorderRadius.circular(12)),
          child: Text(text, style: const TextStyle(fontWeight: FontWeight.w800), textDirection: TextDirection.ltr),
        ),
      );
}
