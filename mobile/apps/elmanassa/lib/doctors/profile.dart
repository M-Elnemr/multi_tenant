import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';
import 'package:url_launcher/url_launcher.dart';

import 'directory.dart' show DoctorPhoto, money, specialtyNames;

/// A doctor's profile: the photo flies in from the list, then specialties, experience, qualifications, languages, the clinic and how to reach it.
/// Booking opens the clinic's own booking page when the clinic takes online bookings; otherwise call / WhatsApp / directions.
class DoctorProfile extends StatelessWidget {
  const DoctorProfile({super.key, required this.d});
  final Map<String, dynamic> d;

  Future<void> _open(BuildContext context, Uri uri) async {
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication) && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(S.of(context).ar ? 'تعذّر الفتح' : 'Could not open')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final cs = Theme.of(context).colorScheme;
    final clinic = (d['clinic'] as Map).cast<String, dynamic>();
    final specs = specialtyNames(d, ar);
    final years = d['yearsExperience'] as int?;
    final fee = d['feeMinor'] as num?;
    final langs = (d['languages'] as List? ?? const []).cast<String>();
    final phone = (clinic['phone'] as String?)?.trim() ?? '';
    final wa = (clinic['whatsapp'] as String?)?.replaceAll(RegExp(r'[^0-9]'), '') ?? '';
    final maps = clinic['mapsUrl'] as String?;
    final base = clinic['baseUrl'] as String;
    final booking = clinic['bookingEnabled'] == true;
    final cities = (clinic['cities'] as List? ?? const []).cast<String>();
    final waIntl = wa.startsWith('0') ? '2$wa' : wa;

    return Scaffold(
      backgroundColor: const Color(0xFFF5F7F6),
      body: CustomScrollView(slivers: [
        SliverAppBar(
          expandedHeight: 340, pinned: true, stretch: true, backgroundColor: cs.primary, foregroundColor: Colors.white,
          flexibleSpace: FlexibleSpaceBar(
            stretchModes: const [StretchMode.zoomBackground],
            background: Stack(fit: StackFit.expand, children: [
              Hero(tag: 'doc-${d['id']}', child: DoctorPhoto(url: d['photoUrl'] as String?, name: d['name'] as String, size: 600, radius: 0)),
              const DecoratedBox(decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0x33000000), Color(0x00000000), Color(0xCC000000)], stops: [0, .45, 1]))),
              PositionedDirectional(
                start: 20, end: 20, bottom: 20,
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(d['name'] as String, style: const TextStyle(color: Colors.white, fontSize: 28, fontWeight: FontWeight.w800, height: 1.1)),
                  if (specs.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text(specs.join(' · '), style: const TextStyle(color: Colors.white70, fontWeight: FontWeight.w600))),
                ]),
              ),
            ]),
          ),
        ),
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 120),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              FadeSlideIn(
                index: 0,
                child: Row(children: [
                  if (years != null && years > 0) Expanded(child: _Fact(value: '$years+', label: ar ? 'سنة خبرة' : 'years')),
                  if (years != null && years > 0 && fee != null) const SizedBox(width: 10),
                  if (fee != null) Expanded(child: _Fact(value: money(fee, d['currency'] as String? ?? 'EGP', ar), label: ar ? 'سعر الكشف' : 'consultation')),
                ]),
              ),
              if ((d['bio'] as String?)?.isNotEmpty == true) FadeSlideIn(index: 1, child: _Card(title: ar ? 'نبذة' : 'About', child: Text(d['bio'] as String, style: const TextStyle(height: 1.6)))),
              if ((d['qualifications'] as String?)?.isNotEmpty == true || langs.isNotEmpty)
                FadeSlideIn(
                  index: 2,
                  child: _Card(title: ar ? 'المؤهلات' : 'Credentials', child: Column(children: [
                    if ((d['qualifications'] as String?)?.isNotEmpty == true) _Row(icon: Icons.workspace_premium_outlined, text: d['qualifications'] as String),
                    if (langs.isNotEmpty) _Row(icon: Icons.translate_rounded, text: langs.join(' · ')),
                  ])),
                ),
              FadeSlideIn(
                index: 3,
                child: _Card(
                  title: ar ? 'العيادة' : 'Clinic',
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      if (clinic['logoUrl'] != null) ClipRRect(borderRadius: BorderRadius.circular(12), child: Image.network(clinic['logoUrl'] as String, width: 44, height: 44, fit: BoxFit.cover, errorBuilder: (_, __, ___) => const SizedBox(width: 44, height: 44))),
                      if (clinic['logoUrl'] != null) const SizedBox(width: 12),
                      Expanded(child: Text(clinic['name'] as String, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16))),
                    ]),
                    const SizedBox(height: 10),
                    if ((clinic['address'] as String?)?.isNotEmpty == true) _Row(icon: Icons.place_outlined, text: clinic['address'] as String),
                    if (cities.isNotEmpty) _Row(icon: Icons.location_city_outlined, text: cities.join(' · ')),
                    if (phone.isNotEmpty) _Row(icon: Icons.call_outlined, text: phone, ltr: true),
                    const SizedBox(height: 6),
                    Wrap(spacing: 8, runSpacing: 8, children: [
                      if (maps != null && maps.isNotEmpty) _Action(icon: Icons.directions_outlined, label: ar ? 'الاتجاهات' : 'Directions', onTap: () => _open(context, Uri.parse(maps))),
                      _Action(icon: Icons.language_rounded, label: ar ? 'موقع العيادة' : 'Clinic website', onTap: () => _open(context, Uri.parse(base))),
                    ]),
                  ]),
                ),
              ),
            ]),
          ),
        ),
      ]),
      bottomNavigationBar: SafeArea(
        child: Container(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
          decoration: const BoxDecoration(color: Colors.white, border: Border(top: BorderSide(color: Color(0xFFEDF0EF)))),
          child: Row(children: [
            if (wa.isNotEmpty) ...[
              _Square(icon: Icons.chat_rounded, color: const Color(0xFF16A34A), onTap: () => _open(context, Uri.parse('https://wa.me/$waIntl'))),
              const SizedBox(width: 10),
            ],
            if (phone.isNotEmpty && booking) ...[
              _Square(icon: Icons.call_rounded, color: cs.primary, onTap: () => _open(context, Uri.parse('tel:$phone'))),
              const SizedBox(width: 10),
            ],
            Expanded(
              child: FilledButton.icon(
                style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(54), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
                onPressed: booking
                    ? () => _open(context, Uri.parse('$base/book?doctorId=${d['id']}'))
                    : phone.isNotEmpty ? () => _open(context, Uri.parse('tel:$phone')) : () => _open(context, Uri.parse(base)),
                icon: Icon(booking ? Icons.event_available_rounded : Icons.call_rounded),
                label: Text(booking ? (ar ? 'احجز موعدًا' : 'Book a visit') : (ar ? 'اتصل للحجز' : 'Call to book'), style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
              ),
            ),
          ]),
        ),
      ),
    );
  }
}

class _Fact extends StatelessWidget {
  const _Fact({required this.value, required this.label});
  final String value, label;
  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(20)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(value, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Theme.of(context).colorScheme.primary)),
          Text(label, style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontWeight: FontWeight.w600)),
        ]),
      );
}

class _Card extends StatelessWidget {
  const _Card({required this.title, required this.child});
  final String title;
  final Widget child;
  @override
  Widget build(BuildContext context) => Container(
        margin: const EdgeInsets.only(top: 12),
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(22)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: TextStyle(fontWeight: FontWeight.w800, color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 13)),
          const SizedBox(height: 10),
          child,
        ]),
      );
}

class _Row extends StatelessWidget {
  const _Row({required this.icon, required this.text, this.ltr = false});
  final IconData icon;
  final String text;
  final bool ltr;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(icon, size: 18, color: Theme.of(context).colorScheme.primary),
          const SizedBox(width: 10),
          Expanded(child: Text(text, textDirection: ltr ? TextDirection.ltr : null, textAlign: TextAlign.start, style: const TextStyle(height: 1.4))),
        ]),
      );
}

class _Action extends StatelessWidget {
  const _Action({required this.icon, required this.label, required this.onTap});
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => OutlinedButton.icon(
        onPressed: onTap, icon: Icon(icon, size: 18), label: Text(label),
        style: OutlinedButton.styleFrom(shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)), side: const BorderSide(color: Color(0xFFE3E8E6))),
      );
}

class _Square extends StatelessWidget {
  const _Square({required this.icon, required this.color, required this.onTap});
  final IconData icon;
  final Color color;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => PressScale(
        onTap: onTap,
        child: Container(width: 54, height: 54, decoration: BoxDecoration(color: color.withValues(alpha: .1), borderRadius: BorderRadius.circular(16)), child: Icon(icon, color: color)),
      );
}
