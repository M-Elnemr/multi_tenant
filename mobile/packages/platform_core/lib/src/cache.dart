import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// Remembers the last good answer of a list (clinics, shops, businesses) so reopening a screen is instant while fresh data loads behind it.
class ListCache {
  ListCache._();
  static final Map<String, Object?> _mem = {};

  static Future<T?> read<T>(String key) async {
    if (_mem.containsKey(key)) return _mem[key] as T?;
    try {
      final raw = (await SharedPreferences.getInstance()).getString('cache.$key');
      if (raw == null) return null;
      final v = jsonDecode(raw);
      _mem[key] = v;
      return v as T?;
    } catch (_) {
      return null;
    }
  }

  static Future<void> write(String key, Object? value) async {
    _mem[key] = value;
    try { await (await SharedPreferences.getInstance()).setString('cache.$key', jsonEncode(value)); } catch (_) {}
  }

  /// Forget everything a signed-out user could see (called on logout).
  static Future<void> clear() async {
    _mem.clear();
    try {
      final p = await SharedPreferences.getInstance();
      for (final k in p.getKeys().where((k) => k.startsWith('cache.')).toList()) { await p.remove(k); }
    } catch (_) {}
  }
}

/// Shows [cached] at once (if any) and then replaces it with the result of [fetch]; a failed refresh keeps the cached data.
Stream<T> cachedThenFresh<T>(String key, Future<T> Function() fetch) async* {
  final c = await ListCache.read<T>(key);
  if (c != null) yield c;
  try {
    final fresh = await fetch();
    await ListCache.write(key, fresh);
    yield fresh;
  } catch (e) {
    if (c == null) rethrow;
  }
}
