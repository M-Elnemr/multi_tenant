import 'package:shared_preferences/shared_preferences.dart';

/// Which side of the app the user chose ("clinics" or "shop"). Remembered, so reopening the app skips the choice screen until they switch
/// or log out.
class AppMode {
  static const _key = 'app.mode';
  static const clinics = 'clinics';
  static const shop = 'shop';

  static Future<String?> get() async {
    try { return (await SharedPreferences.getInstance()).getString(_key); } catch (_) { return null; }
  }

  static Future<void> set(String mode) async {
    try { await (await SharedPreferences.getInstance()).setString(_key, mode); } catch (_) {}
  }

  static Future<void> clear() async {
    try {
      final p = await SharedPreferences.getInstance();
      await p.remove(_key);
      await p.remove('clinic.last');
    } catch (_) {}
  }
}
