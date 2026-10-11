import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// App language. Arabic is the default whatever the phone's language is; the choice is remembered.
class AppLocale {
  AppLocale._();
  static const _key = 'app.locale';
  static final ValueNotifier<Locale> notifier = ValueNotifier(const Locale('ar'));

  static bool get isAr => notifier.value.languageCode != 'en';

  /// Call once before runApp.
  static Future<void> load() async {
    try {
      final p = await SharedPreferences.getInstance();
      final saved = p.getString(_key);
      if (saved == 'en' || saved == 'ar') notifier.value = Locale(saved!);
    } catch (_) {/* storage unavailable: stay Arabic */}
  }

  static Future<void> set(String code) async {
    notifier.value = Locale(code == 'en' ? 'en' : 'ar');
    try {
      final p = await SharedPreferences.getInstance();
      await p.setString(_key, notifier.value.languageCode);
    } catch (_) {/* not persisted, still applied */}
  }

  static Future<void> toggle() => set(isAr ? 'en' : 'ar');
}
