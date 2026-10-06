import 'package:http/http.dart' as http;
import 'dart:convert';

/// Resolved identity of a store/clinic address.
class TenantInfo {
  TenantInfo({required this.baseUrl, required this.host, required this.name, required this.type, required this.currency, required this.locale, required this.primaryColor});
  final String baseUrl;
  final String host;
  final String name;
  final String type; // STORE | CLINIC
  final String currency;
  final String locale;
  final String? primaryColor;

  static String? _color(Object? v) => v is String && RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(v) ? v : null;

  /// Accepts "fashion", "fashion.example.com" or a full URL. A bare slug is expanded with [rootDomain]
  /// (set at build time: --dart-define=ROOT_DOMAIN=platform.com).
  static Future<TenantInfo> resolve(String input, {String rootDomain = const String.fromEnvironment('ROOT_DOMAIN', defaultValue: 'platform.localtest.me'), bool insecure = const bool.fromEnvironment('INSECURE_HTTP')}) async {
    var host = input.trim().toLowerCase().replaceFirst(RegExp(r'^https?://'), '').split('/').first;
    if (!host.contains('.') && !host.contains(':')) host = '$host.$rootDomain';
    final base = '${insecure ? 'http' : 'https'}://$host';
    final res = await http.get(Uri.parse('$base/api/v1/tenant/resolve'), headers: {'Accept': 'application/json'});
    final j = jsonDecode(utf8.decode(res.bodyBytes)) as Map<String, dynamic>;
    if (res.statusCode != 200 || j['kind'] != 'TENANT') throw StateError('No store or clinic at $host');
    final b = (j['branding'] as Map?) ?? const {};
    return TenantInfo(baseUrl: base, host: host, name: j['name'] as String, type: j['type'] as String, currency: j['currency'] as String, locale: j['locale'] as String, primaryColor: _color(b['primary_color']));
  }
}
