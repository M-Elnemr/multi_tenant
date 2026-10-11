import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';

import 'auth.dart';

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
  ApiClient({required this.baseUrl, required this.session, http.Client? client, this.maxAttempts = 5, this.attemptTimeout = const Duration(seconds: 20)}) : _http = client ?? _shared;

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

  Future<dynamic> get(String path, {Map<String, String?>? query}) => _send('GET', path, query: query);
  Future<dynamic> post(String path, [Object? body, String? idempotencyKey]) => _send('POST', path, body: body, idempotencyKey: idempotencyKey);
  Future<dynamic> patch(String path, Object? body) => _send('PATCH', path, body: body);
  Future<dynamic> put(String path, Object? body) => _send('PUT', path, body: body);
  Future<dynamic> delete(String path, [Object? body]) => _send('DELETE', path, body: body);

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

  static Future<bool>? _refreshing;
  Future<bool> _refreshOnce() {
    final running = _refreshing;
    if (running != null) return running;
    final f = session.refresh(this).whenComplete(() => _refreshing = null);
    return _refreshing = f;
  }

  Future<dynamic> _send(String method, String path, {Object? body, Map<String, String?>? query, String? idempotencyKey, bool retried = false}) async {
    final token = await session.accessToken();

    final res = await _sendWithRetry(method, path, query, body, idempotencyKey, token);
    // never refresh because of an auth call itself (a rejected refresh must not trigger another refresh), and share one refresh between parallel requests
    if (res.statusCode == 401 && !retried && !_open(path) && await _refreshOnce()) {
      return _send(method, path, body: body, query: query, idempotencyKey: idempotencyKey, retried: true);
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
