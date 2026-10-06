import 'dart:convert';

import 'package:http/http.dart' as http;

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
  ApiClient({required this.baseUrl, required this.session, http.Client? client}) : _http = client ?? http.Client();

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

  Future<dynamic> _send(String method, String path, {Object? body, Map<String, String?>? query, String? idempotencyKey, bool retried = false}) async {
    final req = http.Request(method, _uri(path, query));
    req.headers['Accept'] = 'application/json';
    if (body != null) {
      req.headers['Content-Type'] = 'application/json';
      req.body = jsonEncode(body);
    }
    if (idempotencyKey != null) req.headers['Idempotency-Key'] = idempotencyKey;
    final token = await session.accessToken();
    if (token != null && !path.startsWith('auth/login')) req.headers['Authorization'] = 'Bearer $token';

    final res = await http.Response.fromStream(await _http.send(req));
    if (res.statusCode == 401 && !retried && await session.refresh(this)) {
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
