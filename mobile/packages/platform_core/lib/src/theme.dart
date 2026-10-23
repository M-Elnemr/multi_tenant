import 'package:animations/animations.dart';
import 'package:flutter/cupertino.dart' show CupertinoPageTransitionsBuilder;
import 'package:flutter/material.dart';

import 'tenant.dart';

const Color kBrandBlue = Color(0xFF1F6A99);
const Color kBrandGreen = Color(0xFF3D9A63);
const String kFont = 'packages/platform_core/Cairo';

Color brandColor(TenantInfo? t) {
  final c = t?.primaryColor;
  if (c == null) return kBrandBlue;
  return Color(int.parse('FF${c.substring(1)}', radix: 16));
}

/// Parses "#RRGGBB" from the API (null when missing/invalid).
Color? hexColor(Object? v) => v is String && RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(v) ? Color(int.parse('FF${v.substring(1)}', radix: 16)) : null;

/// Shared look for the apps: Cairo type, rounded Material 3 surfaces, soft shadows, smooth shared-axis page transitions.
ThemeData themeFor(TenantInfo? t, {Color? seed}) {
  final scheme = ColorScheme.fromSeed(seedColor: seed ?? brandColor(t), tertiary: kBrandGreen).copyWith(surface: const Color(0xFFF7FAFD));
  final radius = BorderRadius.circular(16);
  const line = Color(0xFFE2EAF2);
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    fontFamily: kFont,
    scaffoldBackgroundColor: const Color(0xFFF4F7FB),
    pageTransitionsTheme: const PageTransitionsTheme(builders: {
      TargetPlatform.android: SharedAxisPageTransitionsBuilder(transitionType: SharedAxisTransitionType.horizontal, fillColor: Color(0xFFF4F7FB)),
      TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
    }),
    appBarTheme: AppBarTheme(backgroundColor: Colors.transparent, foregroundColor: scheme.onSurface, elevation: 0, scrolledUnderElevation: 0, surfaceTintColor: Colors.transparent, centerTitle: true, titleTextStyle: TextStyle(fontFamily: kFont, color: scheme.onSurface, fontSize: 18, fontWeight: FontWeight.w800)),
    cardTheme: CardThemeData(color: Colors.white, elevation: 0, margin: const EdgeInsets.symmetric(vertical: 6), shadowColor: const Color(0x22102A43), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20), side: const BorderSide(color: line))),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
      border: OutlineInputBorder(borderRadius: radius, borderSide: const BorderSide(color: Color(0xFFD5DEE9))),
      enabledBorder: OutlineInputBorder(borderRadius: radius, borderSide: const BorderSide(color: Color(0xFFD5DEE9))),
      focusedBorder: OutlineInputBorder(borderRadius: radius, borderSide: BorderSide(color: scheme.primary, width: 2)),
    ),
    filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(54), disabledBackgroundColor: const Color(0xFFD5DEE9), disabledForegroundColor: const Color(0xFF4A5A6B), shape: RoundedRectangleBorder(borderRadius: radius), textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w800, fontSize: 16))),
    outlinedButtonTheme: OutlinedButtonThemeData(style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(54), disabledForegroundColor: const Color(0xFF6B7C8D), shape: RoundedRectangleBorder(borderRadius: radius), textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700))),
    navigationBarTheme: NavigationBarThemeData(backgroundColor: Colors.white, surfaceTintColor: Colors.transparent, indicatorColor: scheme.primary.withValues(alpha: 0.14), height: 70, labelTextStyle: WidgetStateProperty.all(const TextStyle(fontFamily: kFont, fontSize: 12, fontWeight: FontWeight.w700))),
    snackBarTheme: SnackBarThemeData(behavior: SnackBarBehavior.floating, shape: RoundedRectangleBorder(borderRadius: radius)),
    listTileTheme: ListTileThemeData(shape: RoundedRectangleBorder(borderRadius: radius)),
    // chips are solid and readable: white with a visible border and dark text; selected ones are the brand colour with white text (the Material defaults were pale on pale)
    chipTheme: ChipThemeData(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      backgroundColor: Colors.white,
      selectedColor: scheme.primary,
      disabledColor: const Color(0xFFE9EEF4),
      side: const BorderSide(color: Color(0xFFB9C7D6)),
      checkmarkColor: Colors.white,
      labelStyle: WidgetStateTextStyle.resolveWith((states) => TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700, color: states.contains(WidgetState.selected) ? Colors.white : const Color(0xFF14232B))),
    ),
    segmentedButtonTheme: SegmentedButtonThemeData(style: SegmentedButton.styleFrom(backgroundColor: Colors.white, foregroundColor: const Color(0xFF14232B), selectedBackgroundColor: scheme.primary, selectedForegroundColor: Colors.white, side: const BorderSide(color: Color(0xFFB9C7D6)), textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700))),
    // the components that build their own text style: set the app font on them explicitly (tabs, menus, dropdowns, text buttons, toggles)
    tabBarTheme: const TabBarThemeData(labelStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w800, fontSize: 14), unselectedLabelStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600, fontSize: 14)),
    popupMenuTheme: const PopupMenuThemeData(textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600, fontSize: 14, color: Color(0xFF14232B))),
    dropdownMenuTheme: const DropdownMenuThemeData(textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600)),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700))),
    toggleButtonsTheme: const ToggleButtonsThemeData(textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700)),

  );
}
