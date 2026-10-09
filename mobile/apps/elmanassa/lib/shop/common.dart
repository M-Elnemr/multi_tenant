import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

/// Egypt's 27 governorates (stable codes shared with the website and the server).
const governorates = <(String, String, String)>[
  ('CAI', 'القاهرة', 'Cairo'), ('GIZ', 'الجيزة', 'Giza'), ('ALX', 'الإسكندرية', 'Alexandria'), ('QLY', 'القليوبية', 'Qalyubia'), ('SHR', 'الشرقية', 'Sharqia'),
  ('DKH', 'الدقهلية', 'Dakahlia'), ('GHR', 'الغربية', 'Gharbia'), ('MNF', 'المنوفية', 'Monufia'), ('BHR', 'البحيرة', 'Beheira'), ('KFS', 'كفر الشيخ', 'Kafr El Sheikh'),
  ('DMT', 'دمياط', 'Damietta'), ('PSD', 'بورسعيد', 'Port Said'), ('ISM', 'الإسماعيلية', 'Ismailia'), ('SUZ', 'السويس', 'Suez'), ('FYM', 'الفيوم', 'Faiyum'),
  ('BNS', 'بني سويف', 'Beni Suef'), ('MNY', 'المنيا', 'Minya'), ('AST', 'أسيوط', 'Asyut'), ('SHG', 'سوهاج', 'Sohag'), ('QNA', 'قنا', 'Qena'),
  ('LXR', 'الأقصر', 'Luxor'), ('ASN', 'أسوان', 'Aswan'), ('RSS', 'البحر الأحمر', 'Red Sea'), ('WAD', 'الوادي الجديد', 'New Valley'), ('MTR', 'مطروح', 'Matrouh'),
  ('NSN', 'شمال سيناء', 'North Sinai'), ('SSN', 'جنوب سيناء', 'South Sinai'),
];

String govName(String? code, bool ar) {
  for (final g in governorates) { if (g.$1 == code) return ar ? g.$2 : g.$3; }
  return '';
}

/// A product or banner picture. Public-bucket images are linked directly, the rest go through the API's file route.
String? imageUrl(String base, Map<String, dynamic>? m, {String variant = 'medium'}) {
  if (m == null) return null;
  final mb = m['mediaBase'] ?? m['imageMediaBase'];
  final ext = m['mediaExt'] ?? m['imageMediaExt'];
  if (mb is String && ext is String) return '$base$mb-$variant.$ext';
  final u = (m['url'] ?? m['imageUrl']) as String?;
  if (u == null || u.isEmpty) return null;
  final full = u.startsWith('http') ? u : '$base$u';
  return full.contains('/files/') ? '$full${full.contains('?') ? '&' : '?'}variant=$variant' : full;
}

String fileImage(String base, String? id, {String variant = 'thumb'}) => id == null ? '' : '$base/api/v1/files/$id/content?variant=$variant';

Future<void> openUrl(String url) async => launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
Future<void> callPhone(String phone) async => launchUrl(Uri(scheme: 'tel', path: phone));
Future<void> whatsapp(String phone, [String message = '']) async => openUrl('https://wa.me/${phone.replaceAll(RegExp(r'\D'), '')}${message.isEmpty ? '' : '?text=${Uri.encodeComponent(message)}'}');

class CartLine {
  CartLine({required this.variantId, required this.name, required this.label, required this.priceMinor, required this.qty, this.image});
  final String variantId, name, label;
  final String? image;
  final int priceMinor;
  int qty;
  Map<String, dynamic> toJson() => {'v': variantId, 'n': name, 'l': label, 'p': priceMinor, 'q': qty, 'i': image};
  static CartLine fromJson(Map<String, dynamic> j) => CartLine(variantId: j['v'] as String, name: j['n'] as String, label: j['l'] as String, priceMinor: (j['p'] as num).toInt(), qty: (j['q'] as num).toInt(), image: j['i'] as String?);
}

/// The cart, kept on the phone per store so it survives closing the app.
class Cart extends ValueNotifier<List<CartLine>> {
  Cart(this.host) : super([]) { _load(); }
  final String host;
  String get _key => 'cart:$host';

  Future<void> _load() async {
    try {
      final raw = (await SharedPreferences.getInstance()).getString(_key);
      if (raw != null) value = (jsonDecode(raw) as List).map((e) => CartLine.fromJson(e as Map<String, dynamic>)).toList();
    } catch (_) {}
  }

  void _save() {
    SharedPreferences.getInstance().then((p) => p.setString(_key, jsonEncode(value.map((l) => l.toJson()).toList()))).catchError((_) => false);
  }

  void add(CartLine l) {
    final i = value.indexWhere((x) => x.variantId == l.variantId);
    if (i >= 0) { value[i].qty = (value[i].qty + l.qty).clamp(1, 99); value = [...value]; } else { value = [...value, l]; }
    _save();
  }

  void setQty(CartLine l, int q) {
    if (q < 1) { value = value.where((x) => x != l).toList(); } else { l.qty = q.clamp(1, 99); value = [...value]; }
    _save();
  }

  void clear() { value = []; _save(); }
  int get count => value.fold(0, (n, l) => n + l.qty);
  int get total => value.fold(0, (n, l) => n + l.priceMinor * l.qty);
}

/// Everything the store screens share: where to talk to, the cart, the store's profile and category list.
class StoreCtx {
  StoreCtx({required this.tenant, required this.api, required this.cart, required this.profile, required this.categories});
  final TenantInfo tenant;
  final ApiClient api;
  final Cart cart;
  final Map<String, dynamic> profile; // /shop/profile
  final List<Map<String, dynamic>> categories;
  Map<String, dynamic> get p => (profile['profile'] as Map).cast<String, dynamic>();
  String get base => tenant.baseUrl;
  String get name => (p['storeName'] as String?) ?? tenant.name;
  String? get logoId => ((p['branding'] as Map?)?['logoFileId']) as String?;
  bool get isOpen => p['isOpen'] != false;
  List<Map<String, dynamic>> get shippingMethods => ((profile['shippingMethods'] as List?) ?? []).cast<Map<String, dynamic>>();
}

Widget pill(BuildContext context, String text, {Color? bg, Color? fg}) => Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg ?? Theme.of(context).colorScheme.primary, borderRadius: BorderRadius.circular(99)),
      child: Text(text, style: TextStyle(color: fg ?? Colors.white, fontSize: 11, fontWeight: FontWeight.w800)),
    );

/// Small helper: debounce rapid input (typing in search, address changes) before hitting the server.
class Debouncer {
  Debouncer([this.ms = 350]);
  final int ms;
  Timer? _t;
  void run(void Function() f) { _t?.cancel(); _t = Timer(Duration(milliseconds: ms), f); }
  void dispose() => _t?.cancel();
}
