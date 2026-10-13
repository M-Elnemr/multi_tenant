import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'api_client.dart';
import 'cache.dart';

/// Tokens live in the OS keystore (Keychain / Keystore), never in plain preferences.
/// The session is per tenant host so one phone can be logged in to several stores and clinics.
class Session {
  Session(this.host, {FlutterSecureStorage? storage, this.refreshBase}) : _s = storage ?? const FlutterSecureStorage();

  final String host;
  /// When one login is reused on several hosts (the Business app signs in on the platform address, then opens each business on its own address)
  /// the token is always refreshed on this base, so a refresh token is never presented to a different host.
  final String? refreshBase;
  final FlutterSecureStorage _s;
  String get _a => 'access:$host';
  String get _r => 'refresh:$host';
  String get _e => 'expires:$host';

  Future<String?> accessToken() async => _s.read(key: _a);
  /// A keystore that cannot be read right now counts as "still signed in" (the next request decides), never as signed out.
  Future<bool> get loggedIn async {
    try { return (await _s.read(key: _r)) != null; } catch (_) { return true; }
  }

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

  /// Rotates the refresh token. Only a real rejection by the server (the token is unknown/revoked) ends the login; an unreachable server
  /// (no signal, timeout, restart) leaves the saved login untouched so the user is never thrown out because of a bad connection.
  Future<RefreshResult> refresh(ApiClient api) async {
    String? refresh;
    try { refresh = await _s.read(key: _r); } catch (_) { return RefreshResult.unreachable; }
    if (refresh == null) return RefreshResult.rejected;
    try {
      final caller = refreshBase == null || refreshBase == api.baseUrl ? api : ApiClient(baseUrl: refreshBase!, session: this);
      final r = await caller.post('auth/refresh', {'refreshToken': refresh}) as Map<String, dynamic>;
      await save(r);
      return RefreshResult.ok;
    } on ApiException catch (e) {
      if (e.status == 400 || e.status == 401) {
        await clear();
        return RefreshResult.rejected;
      }
      return RefreshResult.unreachable;
    } catch (_) {
      return RefreshResult.unreachable;
    }
  }
}

enum RefreshResult { ok, rejected, unreachable }

class AuthRepository {
  AuthRepository(this.api);
  final ApiClient api;

  /// 'ENTER_PASSWORD' or 'ACTIVATE' (first time: needs the one-time code from the business, no SMS).
  Future<String> checkIdentifier(String identifier) async {
    final r = await api.post('auth/check-identifier', {'identifier': identifier}) as Map<String, dynamic>;
    return r['next'] as String;
  }

  /// `app` is "business" or "client" for the mobile apps (each refuses the other kind of account with a clear message).
  Future<Map<String, dynamic>> login(String identifier, String password, {String? app}) async {
    final r = await api.post('auth/login', {'identifier': identifier, 'password': password, if (app != null) 'app': app}) as Map<String, dynamic>;
    await api.session.save(r);
    return r['user'] as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> activate(String identifier, String pin, String newPassword) async {
    final r = await api.post('auth/activate', {'identifier': identifier, 'pin': pin, 'newPassword': newPassword}) as Map<String, dynamic>;
    await api.session.save(r);
    return r['user'] as Map<String, dynamic>;
  }

  /// Forgot password: the business issues a new one-time code, the user picks a new password with it.
  Future<Map<String, dynamic>> resetPassword(String identifier, String pin, String newPassword) async {
    final r = await api.post('auth/reset-password', {'identifier': identifier, 'pin': pin, 'newPassword': newPassword}) as Map<String, dynamic>;
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
    await ListCache.clear();
  }
}
