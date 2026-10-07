import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'api_client.dart';

/// Tokens live in the OS keystore (Keychain / Keystore), never in plain preferences.
/// The session is per tenant host so one phone can be logged in to several stores and clinics.
class Session {
  Session(this.host, {FlutterSecureStorage? storage}) : _s = storage ?? const FlutterSecureStorage();

  final String host;
  final FlutterSecureStorage _s;
  String get _a => 'access:$host';
  String get _r => 'refresh:$host';
  String get _e => 'expires:$host';

  Future<String?> accessToken() async => _s.read(key: _a);
  Future<bool> get loggedIn async => (await _s.read(key: _r)) != null;

  Future<void> save(Map<String, dynamic> tokens) async {
    await _s.write(key: _a, value: tokens['accessToken'] as String);
    await _s.write(key: _r, value: tokens['refreshToken'] as String);
    await _s.write(key: _e, value: DateTime.now().add(Duration(seconds: (tokens['expiresIn'] as num?)?.toInt() ?? 900)).toIso8601String());
  }

  Future<void> clear() async {
    await _s.delete(key: _a);
    await _s.delete(key: _r);
    await _s.delete(key: _e);
  }

  /// Rotates the refresh token. Returns false (and clears the session) if the server rejects it.
  Future<bool> refresh(ApiClient api) async {
    final refresh = await _s.read(key: _r);
    if (refresh == null) return false;
    try {
      final r = await api.post('auth/refresh', {'refreshToken': refresh}) as Map<String, dynamic>;
      await save(r);
      return true;
    } catch (_) {
      await clear();
      return false;
    }
  }
}

class AuthRepository {
  AuthRepository(this.api);
  final ApiClient api;

  /// 'ENTER_PASSWORD' or 'ACTIVATE' (first time: needs the one-time code from the business, no SMS).
  Future<String> checkIdentifier(String identifier) async {
    final r = await api.post('auth/check-identifier', {'identifier': identifier}) as Map<String, dynamic>;
    return r['next'] as String;
  }

  Future<Map<String, dynamic>> login(String identifier, String password) async {
    final r = await api.post('auth/login', {'identifier': identifier, 'password': password}) as Map<String, dynamic>;
    await api.session.save(r);
    return r['user'] as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> activate(String identifier, String pin, String newPassword) async {
    final r = await api.post('auth/activate', {'identifier': identifier, 'pin': pin, 'newPassword': newPassword}) as Map<String, dynamic>;
    await api.session.save(r);
    return r['user'] as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> me() async => await api.get('auth/me') as Map<String, dynamic>;

  /// Changes the password; the server revokes other sessions and returns fresh tokens for this device.
  Future<void> changePassword(String current, String next) async {
    final r = await api.post('auth/change-password', {'currentPassword': current, 'newPassword': next}) as Map<String, dynamic>;
    await api.session.save(r);
  }

  Future<void> logout() async {
    final token = await api.session._s.read(key: api.session._r);
    if (token != null) {
      try {
        await api.post('auth/logout', {'refreshToken': token});
      } catch (_) {}
    }
    await api.session.clear();
  }
}
