import 'dart:convert';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart' show AppLocale;
import 'package:shared_preferences/shared_preferences.dart';

import '../core/api.dart';
import '../core/governorates.g.dart';
import '../core/models.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'auth_screen.dart';
import 'cart_screen.dart';
import 'orders_screen.dart';

final _phoneRe = RegExp(r'^(\+?20|0)?1[0125]\d{8}$');

String _uuid() {
  final r = Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}

class CheckoutScreen extends StatefulWidget {
  const CheckoutScreen({super.key});
  @override
  State<CheckoutScreen> createState() => _CheckoutScreenState();
}

class _CheckoutScreenState extends State<CheckoutScreen> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController(), _phone = TextEditingController(), _phone2 = TextEditingController(), _city = TextEditingController(), _area = TextEditingController(), _address = TextEditingController(), _landmark = TextEditingController(), _notes = TextEditingController();
  String _gov = '';
  Map<String, dynamic>? _quote;
  bool _busy = false;
  String _error = '';
  List<Map<String, dynamic>> _problems = [];
  String _key = _uuid();

  @override
  void initState() {
    super.initState();
    _prefill();
  }

  Future<void> _prefill() async {
    try {
      final p = await SharedPreferences.getInstance();
      final raw = p.getString('ocheck');
      if (raw != null) {
        final m = Map<String, dynamic>.from(jsonDecode(raw) as Map);
        _name.text = s(m['name']);
        _phone.text = s(m['phone']);
        _phone2.text = s(m['phone2']);
        _city.text = s(m['city']);
        _area.text = s(m['area']);
        _address.text = s(m['address']);
        _landmark.text = s(m['landmark']);
        _gov = s(m['gov']);
      }
    } catch (_) {/* no saved draft */}
    final acc = AppState.I.account;
    if (acc != null) {
      if (_name.text.isEmpty) _name.text = s(acc['name']);
      if (_phone.text.isEmpty) _phone.text = s(acc['phone']).replaceFirst('+20', '0');
    }
    if (mounted) setState(() {});
    _requote();
  }

  @override
  void dispose() {
    for (final c in [_name, _phone, _phone2, _city, _area, _address, _landmark, _notes]) {
      c.dispose();
    }
    super.dispose();
  }

  Map<String, dynamic> get _addr => {'recipientName': _name.text.trim(), 'phone': _phone.text.trim(), if (_phone2.text.trim().isNotEmpty) 'phone2': _phone2.text.trim(), 'addressLine1': _address.text.trim(), 'governorateCode': _gov, 'city': _city.text.trim().isEmpty ? _gov : _city.text.trim(), 'area': _area.text.trim(), 'landmark': _landmark.text.trim()};

  Future<void> _requote() async {
    if (AppState.I.cart.isEmpty) return;
    try {
      final q = Map<String, dynamic>.from(await OpenApi.post('cart/quote', cartRequest(address: _gov.isEmpty ? null : _addr)) as Map);
      if (mounted) setState(() => _quote = q);
    } catch (_) {/* the summary just stays as it was */}
  }

  Future<void> _submit() async {
    if (!(_form.currentState?.validate() ?? false) || _busy || AppState.I.cart.isEmpty) return;
    setState(() { _busy = true; _error = ''; _problems = []; });
    try {
      final body = {...cartRequest(address: _addr), if (_notes.text.trim().isNotEmpty) 'notes': _notes.text.trim()};
      final res = Map<String, dynamic>.from(await OpenApi.post('checkout', body, _key) as Map);
      AppState.I.rememberGroup(s(res['groupId']), _phone.text.trim());
      final failed = list(res['failed']).map((f) => s(f['shopId'])).toSet();
      // shops whose order was placed leave the cart; a shop that failed at the last moment stays so the shopper can retry it
      if (failed.isEmpty) {
        AppState.I.clearCart();
      } else {
        for (final l in [...AppState.I.cart]) {
          if (!failed.contains(l.shopId)) AppState.I.removeLine(l.variantId);
        }
      }
      try {
        (await SharedPreferences.getInstance()).remove('ocheck');
      } catch (_) {/* ignore */}
      if (!mounted) return;
      Navigator.of(context).pushAndRemoveUntil(MaterialPageRoute(builder: (_) => OrderDoneScreen(groupId: s(res['groupId']), phone: _phone.text.trim())), (r) => r.isFirst);
    } on ApiException catch (e) {
      setState(() {
        _error = e.text;
        if (e.code == 'CART_HAS_PROBLEMS' && e.fields is Map) _problems = list((e.fields as Map)['shops']);
      });
      _key = _uuid(); // a failed attempt must not be replayed as the same request
    } finally {
      if (mounted) setState(() => _busy = false);
      try {
        (await SharedPreferences.getInstance()).setString('ocheck', jsonEncode({'name': _name.text, 'phone': _phone.text, 'phone2': _phone2.text, 'city': _city.text, 'area': _area.text, 'address': _address.text, 'landmark': _landmark.text, 'gov': _gov}));
      } catch (_) {/* ignore */}
    }
  }

  Widget _title(int i, String t) => Padding(padding: const EdgeInsets.only(bottom: 12), child: Row(children: [Container(width: 26, height: 26, alignment: Alignment.center, decoration: const BoxDecoration(color: kNavy, shape: BoxShape.circle), child: Text('$i', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 12))), const SizedBox(width: 10), Text(t, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17))]));

  @override
  Widget build(BuildContext context) {
    final currency = _quote?['currency'] as String? ?? (AppState.I.cart.isEmpty ? 'EGP' : AppState.I.cart.first.currency);
    final shippingKnown = _quote?['shippingKnown'] == true;
    final hasErrors = _quote?['hasErrors'] == true;
    String? req(String? v, [int min = 2]) => (v ?? '').trim().length < min ? tr('error.VALIDATION_ERROR') : null;
    return Scaffold(
      appBar: AppBar(title: Text(tr('open.checkout.title'))),
      body: Form(
        key: _form,
        child: ListView(padding: const EdgeInsets.fromLTRB(16, 4, 16, 28), children: [
          if (!AppState.I.signedIn) Card2(margin: const EdgeInsets.only(bottom: 14), onTap: () async { await Navigator.of(context).push(MaterialPageRoute(builder: (_) => const AuthScreen())); if (mounted) _prefill(); }, child: Row(children: [Expanded(child: Text(tr('open.checkout.guestHint'), style: const TextStyle(fontWeight: FontWeight.w600))), Text(tr('open.signIn'), style: const TextStyle(color: kAccent, fontWeight: FontWeight.w900))])),
          Card2(margin: const EdgeInsets.only(bottom: 14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            _title(1, tr('open.checkout.contact')),
            TextFormField(controller: _name, textInputAction: TextInputAction.next, autofillHints: const [AutofillHints.name], decoration: InputDecoration(labelText: tr('open.checkout.name')), validator: req),
            const SizedBox(height: 12),
            TextFormField(controller: _phone, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, textInputAction: TextInputAction.next, autofillHints: const [AutofillHints.telephoneNumber], decoration: InputDecoration(labelText: tr('open.checkout.phone'), hintText: '01xxxxxxxxx'), validator: (v) => _phoneRe.hasMatch((v ?? '').replaceAll(RegExp(r'[\s-]'), '')) ? null : tr('error.INVALID_PHONE')),
            const SizedBox(height: 12),
            TextFormField(controller: _phone2, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: tr('open.checkout.phone2'), hintText: '01xxxxxxxxx'), validator: (v) => (v ?? '').trim().isEmpty || _phoneRe.hasMatch(v!.replaceAll(RegExp(r'[\s-]'), '')) ? null : tr('error.INVALID_PHONE')),
          ])),
          Card2(margin: const EdgeInsets.only(bottom: 14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            _title(2, tr('open.checkout.delivery')),
            DropdownButtonFormField<String>(
              initialValue: _gov.isEmpty ? null : _gov,
              isExpanded: true,
              decoration: InputDecoration(labelText: tr('open.checkout.governorate')),
              items: [for (final g in kGovernorates) DropdownMenuItem(value: g.code, child: Text(AppLocale.isAr ? g.ar : g.en))],
              validator: (v) => v == null || v.isEmpty ? tr('error.VALIDATION_ERROR') : null,
              onChanged: (v) { setState(() => _gov = v ?? ''); _requote(); },
            ),
            const SizedBox(height: 12),
            Row(children: [Expanded(child: TextFormField(controller: _city, decoration: InputDecoration(labelText: tr('open.checkout.city')))), const SizedBox(width: 10), Expanded(child: TextFormField(controller: _area, decoration: InputDecoration(labelText: tr('open.checkout.area'))))]),
            const SizedBox(height: 12),
            TextFormField(controller: _address, minLines: 2, maxLines: 3, decoration: InputDecoration(labelText: tr('open.checkout.address'), hintText: tr('open.checkout.addressHint')), validator: (v) => req(v, 5)),
            const SizedBox(height: 12),
            TextFormField(controller: _landmark, decoration: InputDecoration(labelText: tr('open.checkout.landmark'))),
            const SizedBox(height: 12),
            TextFormField(controller: _notes, minLines: 2, maxLines: 3, decoration: InputDecoration(labelText: tr('open.checkout.notes'))),
          ])),
          Card2(margin: const EdgeInsets.only(bottom: 14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            _title(3, tr('open.checkout.payment')),
            Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: const Color(0xFFFFF6F2), borderRadius: BorderRadius.circular(16), border: Border.all(color: kAccent, width: 1.6)), child: Row(children: [const Icon(Icons.radio_button_checked_rounded, color: kAccent), const SizedBox(width: 10), const Text('💵', style: TextStyle(fontSize: 24)), const SizedBox(width: 10), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(tr('open.trust.cod'), style: const TextStyle(fontWeight: FontWeight.w900)), Text(tr('open.trust.codSub'), style: const TextStyle(color: kMute, fontSize: 12.5))]))])),
          ])),
          Card2(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(tr('open.cart.summary'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)),
            const SizedBox(height: 12),
            for (final p in list(_quote?['shops']))
              Container(
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: kSoft, borderRadius: BorderRadius.circular(16)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [const Icon(Icons.storefront_rounded, size: 16), const SizedBox(width: 6), Expanded(child: Text(s(p['shopName']), style: const TextStyle(fontWeight: FontWeight.w900)))]),
                  if (p['error'] != null) Padding(padding: const EdgeInsets.only(top: 6), child: Text(ApiException(400, s((p['error'] as Map)['code']), s((p['error'] as Map)['message'])).text, style: const TextStyle(color: kRed, fontWeight: FontWeight.w800)))
                  else if (p['quote'] != null) ...() {
                    final q = Map<String, dynamic>.from(p['quote'] as Map);
                    return [
                      const SizedBox(height: 6),
                      for (final l in list(q['lines'])) _kv('${s(l['productName'])} × ${l['quantity']}', money(n(l['unitPriceMinor']) * n(l['quantity']), currency), muted: true),
                      if (n(q['discountMinor']) > 0) _kv(tr('open.cart.discount'), '−${money(q['discountMinor'], currency)}', green: true),
                      _kv('${tr('open.cart.shipping')}${q['etaMinDays'] != null ? ' (${tr('open.eta', {'min': q['etaMinDays'], 'max': q['etaMaxDays'] ?? q['etaMinDays']})})' : ''}', shippingKnown ? (n(q['shippingMinor']) == 0 ? tr('open.freeShipping') : money(q['shippingMinor'], currency)) : '—'),
                      if (n(q['codFeeMinor']) > 0) _kv(tr('open.checkout.codFee'), money(q['codFeeMinor'], currency)),
                    ];
                  }(),
                ]),
              ),
            const Divider(height: 22),
            Row(children: [Expanded(child: Text(tr('open.checkout.total'), style: const TextStyle(fontWeight: FontWeight.w900))), Text(_quote == null ? '—' : money(_quote!['totalMinor'], currency), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 24))]),
            if (!shippingKnown) Text(tr('open.checkout.pickGovernorate'), style: const TextStyle(color: kMute, fontSize: 12)),
          ])),
          if (_error.isNotEmpty || _problems.isNotEmpty)
            Container(
              width: double.infinity,
              margin: const EdgeInsets.only(top: 12),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(color: const Color(0xFFFEF3F2), borderRadius: BorderRadius.circular(14)),
              child: _problems.isNotEmpty ? Column(crossAxisAlignment: CrossAxisAlignment.start, children: [for (final p in _problems) Text('${s(p['shopName'])}: ${ApiException(400, s(p['code']), s(p['message'])).text}', style: const TextStyle(color: Color(0xFFB42318), fontWeight: FontWeight.w800))]) : Text(_error, style: const TextStyle(color: Color(0xFFB42318), fontWeight: FontWeight.w800)),
            ),
          const SizedBox(height: 16),
          GradientButton(label: tr('open.checkout.place'), busy: _busy, onPressed: hasErrors ? null : _submit),
          const SizedBox(height: 10),
          Center(child: Text(tr('open.checkout.splitNote'), textAlign: TextAlign.center, style: const TextStyle(color: kMute, fontSize: 12))),
        ]),
      ),
    );
  }

  Widget _kv(String k, String v, {bool muted = false, bool green = false}) => Padding(padding: const EdgeInsets.symmetric(vertical: 2), child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [Expanded(child: Text(k, maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(color: green ? const Color(0xFF067647) : (muted ? kMute : kInk), fontSize: 13))), const SizedBox(width: 8), Text(v, style: TextStyle(fontWeight: FontWeight.w800, fontSize: 13, color: green ? const Color(0xFF067647) : kInk))]));
}
