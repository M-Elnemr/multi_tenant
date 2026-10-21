import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';
import 'models.dart';

/// Everything that lives on the phone: the cart, the wishlist, the coupons, the sign-in and the purchases made as a guest.
/// Prices kept here are for display only; the server prices every order again at checkout.
class AppState extends ChangeNotifier {
  AppState._();
  static final AppState I = AppState._();

  final List<CartLine> cart = [];
  final List<WishItem> wishlist = [];
  final Map<String, String> coupons = {}; // shop id -> code
  final List<Map<String, dynamic>> groups = []; // guest purchases: {groupId, phone, at}
  Map<String, dynamic>? account;
  SharedPreferences? _p;

  int get cartCount => cart.fold(0, (a, l) => a + l.quantity);
  bool get signedIn => account != null && OpenApi.token != null;

  Future<void> load() async {
    try {
      _p = await SharedPreferences.getInstance();
    } catch (_) {
      return; // storage unavailable: everything still works for this session
    }
    List<T> read<T>(String k, T Function(Map<String, dynamic>) f) {
      try {
        final raw = _p!.getString(k);
        if (raw == null) return [];
        return (jsonDecode(raw) as List).map((e) => f(Map<String, dynamic>.from(e as Map))).toList();
      } catch (_) {
        return [];
      }
    }

    cart.addAll(read('cart', CartLine.fromJson));
    wishlist.addAll(read('wish', WishItem.fromJson));
    groups.addAll(read('groups', (m) => m));
    try {
      final c = _p!.getString('coupons');
      if (c != null) coupons.addAll(Map<String, String>.from(jsonDecode(c) as Map));
      OpenApi.token = _p!.getString('token');
      final a = _p!.getString('account');
      if (a != null) account = Map<String, dynamic>.from(jsonDecode(a) as Map);
    } catch (_) {/* start clean */}
    if (OpenApi.token == null) account = null;
    notifyListeners();
    if (OpenApi.token != null) refreshAccount();
  }

  void _save() {
    final p = _p;
    if (p == null) return;
    p.setString('cart', jsonEncode(cart.map((e) => e.toJson()).toList()));
    p.setString('wish', jsonEncode(wishlist.map((e) => e.toJson()).toList()));
    p.setString('groups', jsonEncode(groups));
    p.setString('coupons', jsonEncode(coupons));
    if (OpenApi.token == null) {
      p.remove('token');
      p.remove('account');
    } else {
      p.setString('token', OpenApi.token!);
      p.setString('account', jsonEncode(account));
    }
  }

  void _changed() {
    _save();
    notifyListeners();
  }

  // ---- cart ----
  void addToCart(CartLine line) {
    final i = cart.indexWhere((l) => l.variantId == line.variantId);
    if (i >= 0) {
      cart[i].quantity = (cart[i].quantity + line.quantity).clamp(1, 99);
      cart[i].unitPriceMinor = line.unitPriceMinor;
    } else {
      cart.add(line);
    }
    _changed();
  }

  void setQty(String variantId, int q) {
    final i = cart.indexWhere((l) => l.variantId == variantId);
    if (i < 0) return;
    cart[i].quantity = q.clamp(1, 99);
    _changed();
  }

  void removeLine(String variantId) {
    cart.removeWhere((l) => l.variantId == variantId);
    _changed();
  }

  void removeShop(String shopId) {
    cart.removeWhere((l) => l.shopId == shopId);
    coupons.remove(shopId);
    _changed();
  }

  void clearCart() {
    cart.clear();
    coupons.clear();
    _changed();
  }

  void setCoupon(String shopId, String? code) {
    if (code == null || code.isEmpty) {
      coupons.remove(shopId);
    } else {
      coupons[shopId] = code;
    }
    _changed();
  }

  // ---- wishlist ----
  bool wished(String productId) => wishlist.any((w) => w.id == productId);

  void toggleWish(Product p) {
    if (wished(p.id)) {
      wishlist.removeWhere((w) => w.id == p.id);
    } else {
      wishlist.insert(0, WishItem(id: p.id, shopSlug: p.shopSlug, slug: p.slug, name: p.name, priceMinor: p.price, currency: p.currency, image: p.medium));
      if (wishlist.length > 200) wishlist.removeLast();
    }
    _changed();
  }

  void removeWish(String productId) {
    wishlist.removeWhere((w) => w.id == productId);
    _changed();
  }

  /// Saves and tells listeners (used after code changed the lists directly).
  void refresh() => _changed();

  // ---- account ----
  void signIn(String token, Map<String, dynamic> acc) {
    OpenApi.token = token;
    account = acc;
    _changed();
  }

  Future<void> signOut() async {
    try {
      await OpenApi.post('auth/logout');
    } catch (_) {/* the token is forgotten here either way */}
    OpenApi.token = null;
    account = null;
    _changed();
  }

  Future<void> refreshAccount() async {
    try {
      account = Map<String, dynamic>.from(await OpenApi.get('me') as Map);
      _changed();
    } on ApiException catch (e) {
      if (e.status == 401) {
        OpenApi.token = null;
        account = null;
        _changed();
      }
    }
  }

  void rememberGroup(String groupId, String phone) {
    groups.removeWhere((g) => g['groupId'] == groupId);
    groups.insert(0, {'groupId': groupId, 'phone': phone, 'at': DateTime.now().millisecondsSinceEpoch});
    if (groups.length > 30) groups.removeLast();
    _changed();
  }
}
