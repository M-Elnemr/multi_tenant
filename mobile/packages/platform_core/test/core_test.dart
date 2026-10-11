import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:platform_core/platform_core.dart';

class _MemorySession extends Session {
  _MemorySession() : super('test.example.com');
  String? token = 'old';
  bool refreshed = false;
  @override
  Future<String?> accessToken() async => token;
  @override
  Future<bool> get loggedIn async => token != null;
  @override
  Future<bool> refresh(ApiClient api) async {
    refreshed = true;
    token = 'new';
    return true;
  }
}

void main() {
  test('money is formatted from integer minor units', () {
    expect(S('en').money(19999, 'EGP'), contains('199.99'));
    expect(S('en').money(null, 'EGP'), '-');
  });

  test('API client sends the bearer token, retries once after a 401 with a refreshed token, and surfaces business errors', () async {
    final session = _MemorySession();
    var calls = 0;
    final client = MockClient((req) async {
      calls++;
      if (req.headers['Authorization'] == 'Bearer old') return http.Response('{}', 401);
      if (req.url.path.endsWith('/boom')) return http.Response(jsonEncode({'code': 'PLAN_LIMIT_REACHED', 'message': 'limit'}), 403, headers: {'content-type': 'application/json'});
      return http.Response(jsonEncode({'ok': true, 'auth': req.headers['Authorization']}), 200, headers: {'content-type': 'application/json'});
    });
    final api = ApiClient(baseUrl: 'https://test.example.com', session: session, client: client);
    final r = await api.get('shop/profile') as Map;
    expect(r['auth'], 'Bearer new');
    expect(session.refreshed, isTrue);
    expect(calls, 2);
    await expectLater(api.get('boom'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'PLAN_LIMIT_REACHED')));
  });

  test('login endpoint never carries a stale token', () async {
    final session = _MemorySession();
    String? seen;
    final client = MockClient((req) async {
      seen = req.headers['Authorization'];
      return http.Response('{}', 200, headers: {'content-type': 'application/json'});
    });
    await ApiClient(baseUrl: 'https://t.example.com', session: session, client: client).post('auth/login', {'identifier': 'x', 'password': 'y'});
    expect(seen, isNull);
  });
  retryTests();
}

void retryTests() {
  test('a dropped connection is retried and the user never sees it', () async {
    var calls = 0;
    final api = ApiClient(
      baseUrl: 'https://x.example.com',
      session: _MemorySession(),
      maxAttempts: 3,
      client: MockClient((req) async {
        calls++;
        if (calls < 3) throw const SocketException('Connection timed out');
        return http.Response(jsonEncode({'ok': true}), 200, headers: {'content-type': 'application/json'});
      }),
    );
    expect(await api.get('anything'), {'ok': true});
    expect(calls, 3);
  });

  test('gives a clear NETWORK error after the last attempt', () async {
    final api = ApiClient(baseUrl: 'https://x.example.com', session: _MemorySession(), maxAttempts: 2, client: MockClient((req) async => throw const SocketException('down')));
    await expectLater(api.get('anything'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'NETWORK')));
  });

  test('a POST that may already have been received is not sent twice', () async {
    var calls = 0;
    final api = ApiClient(baseUrl: 'https://x.example.com', session: _MemorySession(), client: MockClient((req) async { calls++; throw http.ClientException('connection closed while receiving data'); }));
    await expectLater(api.post('shop/checkout', {'a': 1}), throwsA(isA<ApiException>()));
    expect(calls, 1);
  });
}
