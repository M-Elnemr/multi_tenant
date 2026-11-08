import 'package:flutter/cupertino.dart' show CupertinoPageTransitionsBuilder;
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'shop_template.dart';

/// The three looks of the owner app, one per business type. Every screen reads the tokens of the signed-in business with
/// `AppTemplate.of(context)`; the [ThemeData] built from them styles the Material widgets (cards, buttons, chips, app bar, bottom bar).
enum TemplateKind { shop, clinic, lawyer }

/// Colour, shape and type tokens of one template. Carried inside the theme as a [ThemeExtension] so any widget can reach it.
@immutable
class AppTemplate extends ThemeExtension<AppTemplate> {
  const AppTemplate({
    required this.kind,
    required this.ink,
    required this.inkSoft,
    required this.accent,
    required this.accent2,
    required this.paper,
    required this.card,
    required this.line,
    required this.muted,
    required this.text,
    required this.onInk,
    required this.heroGradient,
    required this.accentGradient,
    required this.cardRadius,
    required this.controlRadius,
    required this.cardShadow,
    required this.cardBorder,
    required this.darkAppBar,
    required this.headingWeight,
    required this.headingSpacing,
    required this.bodyHeight,
    required this.gap,
    required this.heroIcon,
  });

  final TemplateKind kind;

  /// The main dark colour (app bar, hero card, primary buttons).
  final Color ink;
  final Color inkSoft;

  /// Accent colours: AI violet/cyan for shops, sage for clinics, brass for lawyers.
  final Color accent;
  final Color accent2;

  /// Page background, card fill, hairlines, secondary text, primary text and text on [ink].
  final Color paper;
  final Color card;
  final Color line;
  final Color muted;
  final Color text;
  final Color onInk;

  final List<Color> heroGradient;
  final List<Color> accentGradient;
  final double cardRadius;
  final double controlRadius;
  final bool cardShadow;
  final bool cardBorder;
  final bool darkAppBar;
  final FontWeight headingWeight;
  final double headingSpacing;
  final double bodyHeight;

  /// Base spacing: clinics are roomier than shops.
  final double gap;
  final IconData heroIcon;

  bool get isShop => kind == TemplateKind.shop;
  bool get isClinic => kind == TemplateKind.clinic;
  bool get isLawyer => kind == TemplateKind.lawyer;

  /// The tokens of the current theme (the shop look when a screen is shown outside a business, e.g. in a test).
  static AppTemplate of(BuildContext context) => Theme.of(context).extension<AppTemplate>() ?? shopTemplate;

  TextStyle heading(double size, {Color? color}) => TextStyle(fontFamily: kFont, fontSize: size, fontWeight: headingWeight, letterSpacing: headingSpacing, color: color ?? text, height: 1.25);

  BoxDecoration cardDecoration({Color? color, double? radius}) => BoxDecoration(
        color: color ?? card,
        borderRadius: BorderRadius.circular(radius ?? cardRadius),
        border: cardBorder ? Border.all(color: line) : null,
        boxShadow: cardShadow ? [BoxShadow(color: ink.withValues(alpha: 0.07), blurRadius: 22, offset: const Offset(0, 8))] : null,
      );

  @override
  AppTemplate copyWith() => this;

  @override
  AppTemplate lerp(covariant ThemeExtension<AppTemplate>? other, double t) => other is AppTemplate && t >= 0.5 ? other : this;

  /// The full Material theme of this template.
  ThemeData buildTheme() {
    final scheme = ColorScheme(
      brightness: Brightness.light,
      primary: isClinic ? ink : (isShop ? accent : ink),
      onPrimary: Colors.white,
      primaryContainer: accent.withValues(alpha: 0.18),
      onPrimaryContainer: ink,
      secondary: isShop ? accent2 : accent,
      onSecondary: isClinic ? ink : Colors.white,
      secondaryContainer: (isShop ? accent2 : accent).withValues(alpha: 0.22),
      onSecondaryContainer: ink,
      tertiary: isShop ? accent2 : accent2,
      onTertiary: ink,
      error: const Color(0xFFC0392B),
      onError: Colors.white,
      surface: card,
      onSurface: text,
      onSurfaceVariant: muted,
      outline: line,
      outlineVariant: line,
      surfaceContainerLowest: card,
      surfaceContainerLow: paper,
      surfaceContainer: paper,
      surfaceContainerHigh: Color.lerp(paper, line, 0.5)!,
      surfaceContainerHighest: line,
      inverseSurface: ink,
      onInverseSurface: onInk,
      shadow: ink,
      surfaceTint: Colors.transparent,
    );
    final r = BorderRadius.circular(controlRadius);
    final barFg = darkAppBar ? onInk : text;
    final base = ThemeData(useMaterial3: true, colorScheme: scheme, fontFamily: kFont);
    final tt = base.textTheme.apply(fontFamily: kFont, bodyColor: text, displayColor: text);
    return base.copyWith(
      extensions: [this],
      scaffoldBackgroundColor: paper,
      canvasColor: paper,
      dividerColor: line,
      dividerTheme: DividerThemeData(color: line, thickness: 1, space: 1),
      visualDensity: isClinic ? VisualDensity.standard : VisualDensity.compact,
      textTheme: tt.copyWith(
        headlineSmall: tt.headlineSmall?.copyWith(fontWeight: headingWeight, letterSpacing: headingSpacing),
        titleLarge: tt.titleLarge?.copyWith(fontWeight: headingWeight, letterSpacing: headingSpacing),
        titleMedium: tt.titleMedium?.copyWith(fontWeight: headingWeight, letterSpacing: headingSpacing),
        bodyMedium: tt.bodyMedium?.copyWith(height: bodyHeight),
        bodySmall: tt.bodySmall?.copyWith(color: muted, height: bodyHeight),
      ),
      pageTransitionsTheme: const PageTransitionsTheme(builders: {
        TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
        TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
      }),
      appBarTheme: AppBarTheme(
        backgroundColor: darkAppBar ? ink : paper,
        foregroundColor: barFg,
        iconTheme: IconThemeData(color: barFg),
        actionsIconTheme: IconThemeData(color: barFg),
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        centerTitle: !isShop,
        shape: isLawyer ? Border(bottom: BorderSide(color: accent, width: 2)) : null,
        titleTextStyle: heading(isClinic ? 19 : 18, color: barFg),
      ),
      cardTheme: CardThemeData(
        color: card,
        elevation: cardShadow ? 3 : 0,
        shadowColor: ink.withValues(alpha: 0.10),
        surfaceTintColor: Colors.transparent,
        margin: EdgeInsets.symmetric(vertical: gap * 0.45),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(cardRadius), side: cardBorder ? BorderSide(color: line) : BorderSide.none),
      ),
      listTileTheme: ListTileThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(cardRadius)),
        iconColor: isShop ? accent : ink,
        contentPadding: EdgeInsets.symmetric(horizontal: gap + 4, vertical: isClinic ? 4 : 0),
        titleTextStyle: TextStyle(fontFamily: kFont, color: text, fontSize: 15, fontWeight: FontWeight.w700),
        subtitleTextStyle: TextStyle(fontFamily: kFont, color: muted, fontSize: 13, height: bodyHeight),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: card,
        contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: isClinic ? 18 : 15),
        border: OutlineInputBorder(borderRadius: r, borderSide: BorderSide(color: line)),
        enabledBorder: OutlineInputBorder(borderRadius: r, borderSide: BorderSide(color: line)),
        focusedBorder: OutlineInputBorder(borderRadius: r, borderSide: BorderSide(color: isShop ? accent : ink, width: 2)),
        hintStyle: TextStyle(fontFamily: kFont, color: muted),
      ),
      filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(
        backgroundColor: isShop ? accent : ink,
        foregroundColor: Colors.white,
        minimumSize: const Size.fromHeight(52),
        disabledBackgroundColor: line,
        disabledForegroundColor: muted,
        shape: RoundedRectangleBorder(borderRadius: r),
        textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w800, fontSize: 15.5, letterSpacing: headingSpacing),
      )),
      outlinedButtonTheme: OutlinedButtonThemeData(style: OutlinedButton.styleFrom(
        foregroundColor: isShop ? ink : ink,
        minimumSize: const Size.fromHeight(52),
        side: BorderSide(color: isLawyer ? accent : line, width: isLawyer ? 1.4 : 1),
        shape: RoundedRectangleBorder(borderRadius: r),
        textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700),
      )),
      textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: isShop ? accent : (isLawyer ? const Color(0xFF7A5E31) : ink), textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700))),
      iconButtonTheme: IconButtonThemeData(style: IconButton.styleFrom(shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(controlRadius)))),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: isShop ? accent : ink,
        foregroundColor: Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(isLawyer ? 10 : 18)),
        extendedTextStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w800),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: isClinic ? card : ink,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        height: isClinic ? 76 : 68,
        indicatorColor: isShop ? accent.withValues(alpha: 0.28) : isClinic ? accent : Colors.transparent,
        indicatorShape: isLawyer ? const UnderlineInputBorder(borderSide: BorderSide.none) : const StadiumBorder(),
        iconTheme: WidgetStateProperty.resolveWith((s) {
          final on = s.contains(WidgetState.selected);
          if (isClinic) return IconThemeData(color: on ? ink : muted, size: 24);
          if (isLawyer) return IconThemeData(color: on ? accent2 : onInk.withValues(alpha: 0.62), size: 24);
          return IconThemeData(color: on ? accent2 : onInk.withValues(alpha: 0.6), size: 24);
        }),
        labelTextStyle: WidgetStateProperty.resolveWith((s) {
          final on = s.contains(WidgetState.selected);
          final c = isClinic ? (on ? ink : muted) : (on ? (isShop ? Colors.white : accent2) : onInk.withValues(alpha: 0.62));
          return TextStyle(fontFamily: kFont, fontSize: 11.5, fontWeight: on ? FontWeight.w800 : FontWeight.w600, color: c, letterSpacing: isLawyer ? 0.3 : 0);
        }),
      ),
      snackBarTheme: SnackBarThemeData(behavior: SnackBarBehavior.floating, backgroundColor: ink, shape: RoundedRectangleBorder(borderRadius: r)),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: paper,
        surfaceTintColor: Colors.transparent,
        showDragHandle: true,
        dragHandleColor: line,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(isLawyer ? 14 : 28))),
      ),
      dialogTheme: DialogThemeData(backgroundColor: card, surfaceTintColor: Colors.transparent, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(isLawyer ? 12 : 24)), titleTextStyle: heading(18)),
      chipTheme: ChipThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(isLawyer ? 8 : 20)),
        backgroundColor: card,
        selectedColor: isShop ? accent.withValues(alpha: 0.16) : isClinic ? accent : ink.withValues(alpha: 0.10),
        disabledColor: line,
        side: BorderSide(color: isLawyer ? accent.withValues(alpha: 0.55) : line),
        checkmarkColor: isShop ? accent : ink,
        labelStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700, color: text),
        secondaryLabelStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w800, color: text),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(style: SegmentedButton.styleFrom(
        backgroundColor: card,
        foregroundColor: text,
        selectedBackgroundColor: isShop ? accent : ink,
        selectedForegroundColor: Colors.white,
        side: BorderSide(color: line),
        textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700),
      )),
      tabBarTheme: TabBarThemeData(
        labelColor: isShop ? accent : ink,
        unselectedLabelColor: muted,
        indicatorColor: isShop ? accent : (isLawyer ? accent : ink),
        dividerColor: line,
        labelStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w800, fontSize: 14),
        unselectedLabelStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600, fontSize: 14),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? Colors.white : null),
        trackColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? (isShop ? accent : isLawyer ? accent : ink) : null),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(color: isShop ? accent : (isLawyer ? accent : ink)),
      popupMenuTheme: PopupMenuThemeData(color: card, surfaceTintColor: Colors.transparent, textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600, fontSize: 14, color: text)),
      dropdownMenuTheme: const DropdownMenuThemeData(textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600)),
      toggleButtonsTheme: const ToggleButtonsThemeData(textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700)),
      badgeTheme: BadgeThemeData(backgroundColor: isShop ? const Color(0xFFFF4D6D) : const Color(0xFFC0392B)),
    );
  }
}
