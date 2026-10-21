import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http/io_client.dart';

import 'config.dart';
import 'strings.dart';

class ApiException implements Exception {
  ApiException(this.status, this.code, this.message, [this.fields]);
  final int status;
  final String code;
  final String message;
  final dynamic fields;

  /// The error as text in the shopper's language: error.<CODE> when the app knows it, else the server's message.
  String get text {
    final k = 'error.$code';
    final t = tr(k);
    return t != k ? t : (message.isNotEmpty ? message : tr('error.generic'));
  }

  @override
  String toString() => text;
}

/// The marketplace API. Browsing is public; the shopper's token (when signed in) travels in X-Open-Token.
class OpenApi {
  OpenApi._();
  static String? token;

  static final http.Client _http = IOClient(HttpClient()
    ..connectionTimeout = const Duration(seconds: 8)
    ..idleTimeout = const Duration(seconds: 20));

  static Uri _uri(String path, [Map<String, String?>? query]) {
    final q = <String, String>{};
    query?.forEach((k, v) {
      if (v != null && v.isNotEmpty) q[k] = v;
    });
    return Uri.parse('$kBaseUrl/api/v1/open/${path.replaceFirst(RegExp(r'^/'), '')}').replace(queryParameters: q.isEmpty ? null : q);
  }

  static Future<dynamic> get(String path, {Map<String, String?>? query}) => _send('GET', path, query: query);
  static Future<dynamic> post(String path, [Object? body, String? idempotencyKey]) => _send('POST', path, body: body ?? {}, idempotencyKey: idempotencyKey);
  static Future<dynamic> put(String path, Object body) => _send('PUT', path, body: body);
  static Future<dynamic> delete(String path) => _send('DELETE', path);

  static Future<dynamic> _send(String method, String path, {Map<String, String?>? query, Object? body, String? idempotencyKey}) async {
    final req = http.Request(method, _uri(path, query));
    req.headers['Accept'] = 'application/json';
    if (kHostOverride.isNotEmpty) req.headers['X-Forwarded-Host'] = kHostOverride;
    if (token != null) req.headers['X-Open-Token'] = token!;
    if (idempotencyKey != null) req.headers['Idempotency-Key'] = idempotencyKey;
    if (body != null) {
      req.headers['Content-Type'] = 'application/json';
      req.body = jsonEncode(body);
    }
    http.Response res;
    try {
      res = await http.Response.fromStream(await _http.send(req).timeout(const Duration(seconds: 25)));
    } on TimeoutException {
      throw ApiException(0, 'NETWORK', tr('error.generic'));
    } on SocketException {
      throw ApiException(0, 'NETWORK', tr('error.generic'));
    } on http.ClientException {
      throw ApiException(0, 'NETWORK', tr('error.generic'));
    }
    final text = utf8.decode(res.bodyBytes);
    dynamic data;
    try {
      data = text.isEmpty ? null : jsonDecode(text);
    } catch (_) {
      data = null;
    }
    if (res.statusCode >= 400) {
      final m = data is Map ? data : const {};
      throw ApiException(res.statusCode, '${m['code'] ?? 'ERROR'}', '${m['message'] ?? ''}', m['fields']);
    }
    return data;
  }
}
