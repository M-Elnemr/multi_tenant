import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';

import 'auth.dart';
import 'offline.dart';

class ApiException implements Exception {
  ApiException(this.status, this.code, this.message);
  final int status;
  final String code;
  final String message;
  @override
  String toString() => message;
}

/// Talks to the platform API. The tenant (store/clinic) is chosen purely by the host in [baseUrl]
/// (e.g. https://fashion.example.com), exactly like the website: the server never trusts a tenant id from the client.
class ApiClient {
  ApiClient({required this.baseUrl, required this.session, http.Client? client, this.maxAttempts = 5, this.attemptTimeout = const Duration(seconds: 20), this.offline}) : _http = client ?? _shared {
    offline?.api = this;
  }

  /// When set (the Business app's clinic API), reads are saved on the phone and answered from there without internet, and the clinic's
  /// registered changes are queued and sent later.
  final OfflineStore? offline;

  /// One connection pool for the whole app. A connection that cannot be opened within 5 s is abandoned and retried (the server edge sometimes drops a
  /// new TCP connection; without this limit the screen would wait for the operating system's multi-minute timeout).
  static final http.Client _shared = IOClient(HttpClient()
    ..connectionTimeout = const Duration(seconds: 5)
    ..idleTimeout = const Duration(seconds: 20));

  final int maxAttempts;
  final Duration attemptTimeout;

  final String baseUrl; // scheme + host (+port), no trailing slash
  final Session session;
  final http.Client _http;

  Uri _uri(String path, [Map<String, String?>? query]) {
    final q = <String, String>{};
    query?.forEach((k, v) {
      if (v != null && v.isNotEmpty) q[k] = v;
    });
    return Uri.parse('$baseUrl/api/v1/${path.replaceFirst(RegExp(r'^/'), '')}').replace(queryParameters: q.isEmpty ? null : q);
  }

  Future<dynamic> get(String path, {Map<String, String?>? query}) async {
    final off = offline;
    if (off == null || !path.startsWith('clinic/')) return _send('GET', path, query: query);
    final k = off.key(path, query);
    if (await off.isOffline()) return _stored(off, k, path, query);
    try {
      final data = await _send('GET', path, query: query);
      off.markOnline();
      unawaited(off.put(k, data));
      return data;
    } on ApiException catch (e) {
      if (e.code != 'NETWORK') rethrow;
      off.markOffline();
      return _stored(off, k, path, query, cause: e);
    }
  }

  Future<dynamic> _stored(OfflineStore off, String k, String path, Map<String, String?>? query, {ApiException? cause}) async {
    final c = await off.read(k);
    if (c != OfflineStore.miss) return c;
    final f = off.fallback;
    if (f != null) {
      final r = await f(path, {for (final e in (query ?? const <String, String?>{}).entries) if (e.value != null) e.key: e.value!});
      if (r != null) return r;
    }
    throw cause ?? ApiException(0, 'NETWORK', 'No connection');
  }

  /// What the phone already has for [path] (null when nothing is stored): lets a screen show data the instant it opens.
  Future<Object?> peek(String path, {Map<String, String?>? query}) async {
    final off = offline;
    if (off == null) return null;
    final c = await off.read(off.key(path, query));
    return c == OfflineStore.miss ? null : c;
  }

  Future<dynamic> post(String path, [Object? body, String? idempotencyKey]) => _write('POST', path, body, idempotencyKey);
  Future<dynamic> patch(String path, Object? body) => _write('PATCH', path, body, null);
  Future<dynamic> put(String path, Object? body) => _write('PUT', path, body, null);
  Future<dynamic> delete(String path, [Object? body]) => _write('DELETE', path, body, null);

  /// Downloads a file the signed-in user may see (e.g. `files/<id>/content`) as bytes, with the same token renewal as every other request.
  Future<List<int>> getBytes(String path) async {
    var res = await _sendWithRetry('GET', path, null, null, null, await session.accessToken());
    if (res.statusCode == 401 && await _refreshOnce() == RefreshResult.ok) {
      res = await _sendWithRetry('GET', path, null, null, null, await session.accessToken());
    }
    if (res.statusCode >= 400) throw ApiException(res.statusCode, 'ERROR', res.reasonPhrase ?? 'Download failed');
    return res.bodyBytes;
  }

  /// Sends a queued change (used by the offline sync): no queuing, same Idempotency-Key as the first try.
  Future<dynamic> sendRaw(String method, String path, Object? body, String idem) => _send(method, path, body: body, idempotencyKey: idem);

  Future<dynamic> _write(String method, String path, Object? body, String? idem) async {
    final off = offline;
    if (off == null || !path.startsWith('clinic/') || off.handlerFor(method, path) == null) return _send(method, path, body: body, idempotencyKey: idem);
    final key = idem ?? OfflineStore.newIdem();
    // something is already waiting to be sent: later changes line up behind it so the order is kept
    if (await off.isOffline() || off.pending.value > 0 || off.syncing.value) return off.queueWrite(method, path, body, key);
    try {
      final r = await _send(method, path, body: body, idempotencyKey: key);
      off.markOnline();
      return r;
    } on ApiException catch (e) {
      if (e.code != 'NETWORK') rethrow;
      off.markOffline();
      return off.queueWrite(method, path, body, key);
    }
  }

  /// Uploads file bytes to the signed path the server returned from `files/presign` (e.g. /api/v1/files/<id>/content?exp=..&sig=..).
  Future<void> putFile(String signedPath, List<int> bytes, String contentType) async {
    final res = await _http.put(Uri.parse('$baseUrl$signedPath'), headers: {'Content-Type': contentType}, body: bytes);
    if (res.statusCode >= 400) {
      Map m = const {};
      try { m = jsonDecode(utf8.decode(res.bodyBytes)) as Map; } catch (_) {}
      throw ApiException(res.statusCode, (m['code'] ?? 'ERROR').toString(), (m['message'] ?? 'Upload failed').toString());
    }
  }

  /// A request that never reached the server (connection refused/timed out/reset before any answer) is simply tried again, up to
  /// [maxAttempts] times with a short pause, so a dropped connection is invisible to the user. Gives up with a clear NETWORK error.
  Future<http.Response> _sendWithRetry(String method, String path, Map<String, String?>? query, Object? body, String? idempotencyKey, String? token) async {
    Object? last;
    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
      final r = http.Request(method, _uri(path, query));
      r.headers['Accept'] = 'application/json';
      if (body != null) {
        r.headers['Content-Type'] = 'application/json';
        r.body = jsonEncode(body);
      }
      if (idempotencyKey != null) r.headers['Idempotency-Key'] = idempotencyKey;
      if (token != null && !_open(path)) r.headers['Authorization'] = 'Bearer $token';
      try {
        return await http.Response.fromStream(await _http.send(r).timeout(attemptTimeout));
      } on TimeoutException catch (e) {
        // the request may have reached the server: only repeat it when repeating is harmless
        if (method != 'GET' && idempotencyKey == null) throw ApiException(0, 'NETWORK', e.toString());
        last = e;
      } on SocketException catch (e) {
        last = e;
      } on http.ClientException catch (e) {
        if (method != 'GET' && idempotencyKey == null && !e.toString().contains('SocketException')) throw ApiException(0, 'NETWORK', e.toString());
        last = e;
      } on HandshakeException catch (e) {
        last = e;
      }
      if (attempt < maxAttempts) await Future<void>.delayed(Duration(milliseconds: 400 * attempt));
    }
    throw ApiException(0, 'NETWORK', last?.toString() ?? 'Connection problem');
  }

  /// Endpoints that are called without a session; sending an expired token to them can only get them rejected.
  static bool _open(String path) => RegExp(r'^auth/(login|refresh|check-identifier|activate|reset-password)').hasMatch(path);

  static Future<RefreshResult>? _refreshing;
  Future<RefreshResult> _refreshOnce() {
    final running = _refreshing;
    if (running != null) return running;
    final f = session.refresh(this).whenComplete(() => _refreshing = null);
    return _refreshing = f;
  }

  Future<dynamic> _send(String method, String path, {Object? body, Map<String, String?>? query, String? idempotencyKey, bool retried = false}) async {
    final token = await session.accessToken();

    final res = await _sendWithRetry(method, path, query, body, idempotencyKey, token);
    // never refresh because of an auth call itself (a rejected refresh must not trigger another refresh), and share one refresh between parallel requests
    if (res.statusCode == 401 && !retried && !_open(path)) {
      final r = await _refreshOnce();
      if (r == RefreshResult.ok) return _send(method, path, body: body, query: query, idempotencyKey: idempotencyKey, retried: true);
      // the token could not be renewed only because the server was unreachable: that is a connection problem, not a sign-out
      if (r == RefreshResult.unreachable) throw ApiException(0, 'NETWORK', 'Connection problem');
    }
    final text = utf8.decode(res.bodyBytes);
    final data = text.isEmpty ? null : jsonDecode(text);
    if (res.statusCode >= 400) {
      final m = data is Map ? data : const {};
      throw ApiException(res.statusCode, (m['code'] ?? 'ERROR').toString(), (m['message'] ?? res.reasonPhrase ?? 'Error').toString());
    }
    return data;
  }
}
