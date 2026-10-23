import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart' show kFont;

const Color kNavy = Color(0xFF0D1535);
const Color kNavy2 = Color(0xFF18266B);
const Color kAccent = Color(0xFFFF5A2C);
const Color kAccent2 = Color(0xFFFF8A3D);
const Color kGold = Color(0xFFFFC53D);
const Color kGreen = Color(0xFF12B76A);
const Color kRed = Color(0xFFE5484D);
const Color kBg = Color(0xFFF3F4F9);
const Color kInk = Color(0xFF0F1530);
const Color kMute = Color(0xFF667085);
const Color kLine = Color(0xFFE5E8F1);
const Color kSoft = Color(0xFFF7F8FC);

const LinearGradient kAccentGradient = LinearGradient(colors: [kAccent, kAccent2], begin: Alignment.topLeft, end: Alignment.bottomRight);

List<BoxShadow> softShadow([double a = .10]) => [BoxShadow(color: kNavy.withValues(alpha: a), blurRadius: 24, offset: const Offset(0, 10), spreadRadius: -12)];

ThemeData openTheme() {
  final scheme = ColorScheme.fromSeed(seedColor: kAccent, primary: kAccent, secondary: kNavy, surface: Colors.white).copyWith(onPrimary: Colors.white);
  return ThemeData(
    useMaterial3: true,
    fontFamily: kFont,
    colorScheme: scheme,
    scaffoldBackgroundColor: kBg,
    splashFactory: InkSparkle.splashFactory,
    appBarTheme: const AppBarTheme(backgroundColor: kBg, foregroundColor: kInk, elevation: 0, scrolledUnderElevation: 0, surfaceTintColor: Colors.transparent, centerTitle: true, titleTextStyle: TextStyle(fontFamily: kFont, color: kInk, fontSize: 18, fontWeight: FontWeight.w800)),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: Colors.white,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: kLine)),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: kLine)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: kAccent, width: 1.6)),
      errorBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: kRed)),
      labelStyle: const TextStyle(color: kMute, fontWeight: FontWeight.w600),
    ),
    snackBarTheme: SnackBarThemeData(behavior: SnackBarBehavior.floating, backgroundColor: kNavy, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)), contentTextStyle: const TextStyle(fontFamily: kFont, color: Colors.white, fontWeight: FontWeight.w600)),
    bottomSheetTheme: const BottomSheetThemeData(backgroundColor: Colors.white, surfaceTintColor: Colors.transparent, shape: RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(26)))),
    tabBarTheme: const TabBarThemeData(labelStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w800, fontSize: 14), unselectedLabelStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600, fontSize: 14)),
    popupMenuTheme: const PopupMenuThemeData(textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600, fontSize: 14, color: kInk)),
    dropdownMenuTheme: const DropdownMenuThemeData(textStyle: TextStyle(fontFamily: kFont, fontWeight: FontWeight.w600)),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700))),
    segmentedButtonTheme: SegmentedButtonThemeData(style: SegmentedButton.styleFrom(textStyle: const TextStyle(fontFamily: kFont, fontWeight: FontWeight.w700))),
    dividerTheme: const DividerThemeData(color: kLine, thickness: 1, space: 1),
  );
}
