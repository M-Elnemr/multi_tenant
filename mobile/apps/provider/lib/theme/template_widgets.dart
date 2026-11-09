import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'app_template.dart';

/// One small figure under the hero's headline number.
class HeroFigure {
  const HeroFigure(this.label, this.value, {this.text});
  final String label;
  final num value;
  final String? text;
}

/// The big card at the top of each home dashboard, drawn differently per template:
/// shop = ink card with a violet/cyan glow and glass tiles; clinic = soft sage paper card; lawyer = navy card framed by a double brass rule.
class TemplateHero extends StatelessWidget {
  const TemplateHero({super.key, required this.overline, required this.value, this.format, this.valueText, this.caption, this.figures = const [], this.icon});
  final String overline;
  final num value;
  final String Function(num)? format;
  final String? valueText;
  final String? caption;
  final List<HeroFigure> figures;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    return switch (t.kind) {
      TemplateKind.shop => _shop(t),
      TemplateKind.clinic => _clinic(t),
      TemplateKind.lawyer => _lawyer(t),
    };
  }

  Widget _number(TextStyle style) => valueText != null ? Text(valueText!, style: style) : CountUp(value: value, format: format, style: style);

  Widget _shop(AppTemplate t) => Container(
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(24), gradient: LinearGradient(begin: AlignmentDirectional.topStart, end: AlignmentDirectional.bottomEnd, colors: t.heroGradient)),
        clipBehavior: Clip.antiAlias,
        child: Stack(children: [
          PositionedDirectional(top: -70, end: -50, child: _glow(220, t.accent, 0.55)),
          PositionedDirectional(bottom: -90, start: -40, child: _glow(220, t.accent2, 0.35)),
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                  decoration: BoxDecoration(borderRadius: BorderRadius.circular(20), gradient: LinearGradient(colors: t.accentGradient)),
                  child: Row(mainAxisSize: MainAxisSize.min, children: [Icon(icon ?? t.heroIcon, size: 14, color: Colors.white), const SizedBox(width: 5), Text(overline, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w800))]),
                ),
              ]),
              const SizedBox(height: 14),
              ShaderMask(
                shaderCallback: (r) => LinearGradient(colors: [Colors.white, Color.lerp(Colors.white, t.accent2, 0.55)!]).createShader(r),
                child: _number(const TextStyle(color: Colors.white, fontSize: 38, fontWeight: FontWeight.w900, letterSpacing: -0.8, height: 1.1)),
              ),
              if (caption != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(caption!, style: TextStyle(color: t.onInk.withValues(alpha: 0.7), fontSize: 13))),
              if (figures.isNotEmpty) ...[
                const SizedBox(height: 16),
                Row(children: [
                  for (var i = 0; i < figures.length; i++) ...[
                    if (i > 0) const SizedBox(width: 10),
                    Expanded(child: Container(
                      padding: const EdgeInsets.symmetric(vertical: 11, horizontal: 12),
                      decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.07), borderRadius: BorderRadius.circular(16), border: Border.all(color: Colors.white.withValues(alpha: 0.12))),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        figures[i].text != null ? Text(figures[i].text!, style: TextStyle(color: t.accent2, fontSize: 18, fontWeight: FontWeight.w900)) : CountUp(value: figures[i].value, style: TextStyle(color: t.accent2, fontSize: 22, fontWeight: FontWeight.w900)),
                        Text(figures[i].label, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.onInk.withValues(alpha: 0.72), fontSize: 12)),
                      ]),
                    )),
                  ],
                ]),
              ],
            ]),
          ),
        ]),
      );

  Widget _glow(double s, Color c, double a) => Container(width: s, height: s, decoration: BoxDecoration(shape: BoxShape.circle, gradient: RadialGradient(colors: [c.withValues(alpha: a), c.withValues(alpha: 0)])));

  Widget _clinic(AppTemplate t) => Container(
        padding: const EdgeInsets.all(22),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(30),
          gradient: LinearGradient(begin: AlignmentDirectional.topStart, end: AlignmentDirectional.bottomEnd, colors: t.heroGradient),
          boxShadow: [BoxShadow(color: t.ink.withValues(alpha: 0.08), blurRadius: 26, offset: const Offset(0, 10))],
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Container(width: 46, height: 46, decoration: BoxDecoration(color: t.card.withValues(alpha: 0.75), shape: BoxShape.circle), child: Icon(icon ?? t.heroIcon, color: t.ink)),
            const SizedBox(width: 12),
            Expanded(child: Text(overline, style: TextStyle(color: t.ink.withValues(alpha: 0.8), fontWeight: FontWeight.w700, fontSize: 15))),
          ]),
          const SizedBox(height: 16),
          _number(TextStyle(color: t.ink, fontSize: 40, fontWeight: FontWeight.w800, height: 1.1)),
          if (caption != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(caption!, style: TextStyle(color: t.muted, fontSize: 13.5))),
          if (figures.isNotEmpty) ...[
            const SizedBox(height: 18),
            Wrap(spacing: 10, runSpacing: 10, children: [
              for (final f in figures)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  decoration: BoxDecoration(color: t.card.withValues(alpha: 0.85), borderRadius: BorderRadius.circular(22)),
                  child: Row(mainAxisSize: MainAxisSize.min, children: [
                    f.text != null ? Text(f.text!, style: TextStyle(color: t.ink, fontWeight: FontWeight.w800, fontSize: 16)) : CountUp(value: f.value, style: TextStyle(color: t.ink, fontWeight: FontWeight.w800, fontSize: 18)),
                    const SizedBox(width: 8),
                    Text(f.label, style: TextStyle(color: t.muted, fontSize: 13)),
                  ]),
                ),
            ]),
          ],
        ]),
      );

  Widget _lawyer(AppTemplate t) => Container(
        decoration: BoxDecoration(borderRadius: BorderRadius.circular(12), gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: t.heroGradient)),
        padding: const EdgeInsets.all(6),
        child: Container(
          padding: const EdgeInsets.fromLTRB(18, 16, 18, 16),
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(8), border: Border.all(color: t.accent.withValues(alpha: 0.7))),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Icon(icon ?? t.heroIcon, color: t.accent2, size: 20),
              const SizedBox(width: 8),
              Expanded(child: Text(overline, style: TextStyle(color: t.accent2, fontWeight: FontWeight.w800, fontSize: 13, letterSpacing: 1.2))),
            ]),
            Container(margin: const EdgeInsets.symmetric(vertical: 10), height: 1, color: t.accent.withValues(alpha: 0.45)),
            _number(TextStyle(color: t.onInk, fontSize: 34, fontWeight: FontWeight.w800, letterSpacing: 0.4, height: 1.15)),
            if (caption != null) Padding(padding: const EdgeInsets.only(top: 4), child: Text(caption!, style: TextStyle(color: t.onInk.withValues(alpha: 0.7), fontSize: 13))),
            if (figures.isNotEmpty) ...[
              const SizedBox(height: 14),
              IntrinsicHeight(child: Row(children: [
                for (var i = 0; i < figures.length; i++) ...[
                  if (i > 0) VerticalDivider(color: t.accent.withValues(alpha: 0.5), width: 22, thickness: 1),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    figures[i].text != null ? Text(figures[i].text!, style: TextStyle(color: t.accent2, fontSize: 17, fontWeight: FontWeight.w800)) : CountUp(value: figures[i].value, style: TextStyle(color: t.accent2, fontSize: 20, fontWeight: FontWeight.w800)),
                    Text(figures[i].label, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.onInk.withValues(alpha: 0.72), fontSize: 12, letterSpacing: 0.3)),
                  ])),
                ],
              ])),
            ],
          ]),
        ),
      );
}

/// A small figure card for the dashboard grids.
class TemplateStatTile extends StatelessWidget {
  const TemplateStatTile({super.key, required this.label, required this.icon, this.value = 0, this.text, this.hint, this.color, this.onTap});
  final String label;
  final IconData icon;
  final num value;
  final String? text;
  final String? hint;
  final Color? color;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final c = color ?? (t.isShop ? t.accent : t.ink);
    final numStyle = TextStyle(fontSize: t.isClinic ? 22 : 21, fontWeight: FontWeight.w900, color: t.text, letterSpacing: t.isShop ? -0.4 : t.headingSpacing);
    final badge = switch (t.kind) {
      TemplateKind.shop => Container(width: 36, height: 36, decoration: BoxDecoration(borderRadius: BorderRadius.circular(11), gradient: LinearGradient(colors: [c, Color.lerp(c, t.accent2, 0.6)!])), child: Icon(icon, color: Colors.white, size: 19)),
      TemplateKind.clinic => Container(width: 40, height: 40, decoration: BoxDecoration(shape: BoxShape.circle, color: t.accent.withValues(alpha: 0.55)), child: Icon(icon, color: t.ink, size: 20)),
      TemplateKind.lawyer => Container(width: 34, height: 34, decoration: BoxDecoration(borderRadius: BorderRadius.circular(6), border: Border.all(color: t.accent), color: t.paper), child: Icon(icon, color: color ?? t.ink, size: 18)),
    };
    return Container(
      decoration: t.cardDecoration(),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(t.cardRadius),
          onTap: onTap,
          child: Padding(
            padding: EdgeInsets.all(t.isClinic ? 16 : 14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              badge,
              Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                FittedBox(fit: BoxFit.scaleDown, alignment: AlignmentDirectional.centerStart, child: text != null ? Text(text!, style: numStyle) : CountUp(value: value, style: numStyle)),
                Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.muted, fontSize: 12.5, fontWeight: FontWeight.w600)),
                if (hint != null) Text(hint!, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.muted.withValues(alpha: 0.85), fontSize: 11)),
              ]),
            ]),
          ),
        ),
      ),
    );
  }
}

/// Two-column grid of [TemplateStatTile]s that fades in.
Widget templateGrid(BuildContext context, List<Widget> tiles, {double aspect = 1.4}) {
  final t = AppTemplate.of(context);
  return GridView.count(
    crossAxisCount: 2,
    shrinkWrap: true,
    physics: const NeverScrollableScrollPhysics(),
    mainAxisSpacing: t.gap * 0.8,
    crossAxisSpacing: t.gap * 0.8,
    childAspectRatio: aspect,
    children: [for (var i = 0; i < tiles.length; i++) FadeSlideIn(index: i + 1, child: tiles[i])],
  );
}

/// Section heading of a dashboard: gradient tick (shop), soft title (clinic), brass rule (lawyer).
class TemplateSectionTitle extends StatelessWidget {
  const TemplateSectionTitle(this.title, {super.key, this.trailing});
  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final label = Text(title, style: t.heading(t.isClinic ? 17 : 16));
    final row = switch (t.kind) {
      TemplateKind.shop => Row(children: [
          Container(width: 4, height: 18, decoration: BoxDecoration(borderRadius: BorderRadius.circular(4), gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: t.accentGradient))),
          const SizedBox(width: 8),
          Expanded(child: label),
          if (trailing != null) trailing!,
        ]),
      TemplateKind.clinic => Row(children: [Expanded(child: label), if (trailing != null) trailing!]),
      TemplateKind.lawyer => Row(children: [
          label,
          const SizedBox(width: 10),
          Expanded(child: Container(height: 1, color: t.accent.withValues(alpha: 0.6))),
          if (trailing != null) ...[const SizedBox(width: 8), trailing!],
        ]),
    };
    return Padding(padding: EdgeInsets.fromLTRB(4, t.gap + 8, 4, t.gap * 0.5), child: row);
  }
}

/// The bottom navigation of the panel in the template's style: a floating ink capsule (shop), a white rounded sheet (clinic),
/// a flat navy bar ruled in brass (lawyer).
class TemplateNavBar extends StatelessWidget {
  const TemplateNavBar({super.key, required this.selectedIndex, required this.onSelected, required this.destinations});
  final int selectedIndex;
  final ValueChanged<int> onSelected;
  final List<NavigationDestination> destinations;

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final bar = NavigationBar(selectedIndex: selectedIndex, onDestinationSelected: onSelected, labelBehavior: NavigationDestinationLabelBehavior.alwaysShow, destinations: destinations);
    switch (t.kind) {
      case TemplateKind.shop:
        return Container(
          color: t.paper,
          child: SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 4, 12, 10),
              child: DecoratedBox(
                decoration: BoxDecoration(borderRadius: BorderRadius.circular(26), boxShadow: [BoxShadow(color: t.accent.withValues(alpha: 0.25), blurRadius: 24, offset: const Offset(0, 8))]),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(26),
                  child: Stack(children: [
                    MediaQuery.removePadding(context: context, removeBottom: true, child: bar),
                    Positioned(top: 0, left: 24, right: 24, child: Container(height: 2, decoration: BoxDecoration(gradient: LinearGradient(colors: [t.accent.withValues(alpha: 0), t.accent, t.accent2, t.accent2.withValues(alpha: 0)])))),
                  ]),
                ),
              ),
            ),
          ),
        );
      case TemplateKind.clinic:
        return Container(
          decoration: BoxDecoration(color: t.card, borderRadius: const BorderRadius.vertical(top: Radius.circular(28)), boxShadow: [BoxShadow(color: t.ink.withValues(alpha: 0.08), blurRadius: 24, offset: const Offset(0, -6))]),
          clipBehavior: Clip.antiAlias,
          padding: const EdgeInsets.only(top: 6),
          child: bar,
        );
      case TemplateKind.lawyer:
        return Container(
          decoration: BoxDecoration(color: t.ink, border: Border(top: BorderSide(color: t.accent, width: 2))),
          child: bar,
        );
    }
  }
}

/// A calm placeholder for empty lists in the template's colours.
Widget templateEmpty(BuildContext context, String text, {IconData icon = Icons.inbox_outlined}) {
  final t = AppTemplate.of(context);
  return Padding(
    padding: const EdgeInsets.all(28),
    child: Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
      Container(
        width: 64, height: 64,
        decoration: BoxDecoration(shape: t.isLawyer ? BoxShape.rectangle : BoxShape.circle, borderRadius: t.isLawyer ? BorderRadius.circular(10) : null, color: (t.isShop ? t.accent : t.isClinic ? t.accent : t.accent).withValues(alpha: t.isClinic ? 0.45 : 0.14)),
        child: Icon(icon, size: 30, color: t.isShop ? t.accent : t.ink),
      ),
      const SizedBox(height: 10),
      Text(text, textAlign: TextAlign.center, style: TextStyle(color: t.muted)),
    ])),
  );
}

/// One row of a list screen (orders, products, patients, clients...) in the template's style: a card with a status-coloured edge
/// (shop: rounded with glow, clinic: soft and roomy, lawyer: square card ruled in brass), the title with a trailing pill, a subtitle and an optional footer.
class TemplateListCard extends StatelessWidget {
  const TemplateListCard({super.key, required this.title, this.subtitle, this.leading, this.trailing, this.footer, this.edge, this.onTap, this.titleLtr = false});
  final String title;
  final String? subtitle;
  final Widget? leading;
  final Widget? trailing;
  final Widget? footer;
  /// Colour of the edge strip (usually the status tone). Null = no strip.
  final Color? edge;
  final VoidCallback? onTap;
  final bool titleLtr;

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final r = BorderRadius.circular(t.cardRadius);
    final body = Padding(
      padding: EdgeInsetsDirectional.fromSTEB(t.isClinic ? 16 : 14, t.isClinic ? 14 : 12, 12, t.isClinic ? 14 : 12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
          if (leading != null) ...[leading!, const SizedBox(width: 12)],
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, maxLines: 1, overflow: TextOverflow.ellipsis, textDirection: titleLtr ? TextDirection.ltr : null, textAlign: TextAlign.start, style: t.heading(15.5)),
            if (subtitle != null && subtitle!.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 3), child: Text(subtitle!, maxLines: 3, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.muted, fontSize: 12.8, height: 1.35))),
          ])),
          if (trailing != null) ...[const SizedBox(width: 8), trailing!],
        ]),
        if (footer != null) Padding(padding: const EdgeInsets.only(top: 8), child: footer!),
      ]),
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: DecoratedBox(
        decoration: t.cardDecoration(),
        child: ClipRRect(
          borderRadius: r,
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: onTap,
              child: IntrinsicHeight(
                child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  if (edge != null) Container(width: t.isLawyer ? 3 : 4, color: edge),
                  Expanded(child: body),
                ]),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A bold value (an amount, a count) with an optional pill above and caption below: the trailing part of a [TemplateListCard].
class TemplateListFigure extends StatelessWidget {
  const TemplateListFigure(this.value, {super.key, this.caption, this.pill});
  final String value;
  final String? caption;
  final Widget? pill;
  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    return Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.end, children: [
      if (pill != null) pill!,
      if (pill != null) const SizedBox(height: 4),
      Text(value, style: t.heading(14.5, color: t.isShop ? t.accent : t.ink)),
      if (caption != null) Text(caption!, style: TextStyle(color: t.muted, fontSize: 11)),
    ]);
  }
}
