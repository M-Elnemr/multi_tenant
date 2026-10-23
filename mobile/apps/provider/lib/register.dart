import 'dart:async';
import 'dart:io';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

/// Creates a shop or a clinic from the phone: pick the kind, fill name / phone / password / web address, and the owner is signed in.
/// Same API as the website (`onboarding/tenants`), so the account works on both.
class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key, required this.api, required this.onRegistered});
  final ApiClient api;
  /// Called once the account exists and the session is saved: the app reloads the businesses and opens the new one.
  final Future<void> Function() onRegistered;
  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

String _slugify(String s) {
  final t = s.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '-').replaceAll(RegExp(r'^-+|-+$'), '');
  return t.length > 40 ? t.substring(0, 40) : t;
}

/// 01x xxxxxxxx (Egypt) or an international +number, like the website.
bool _validMobile(String p) => RegExp(r'^(\+\d{8,15}|01[0125]\d{8}|1[0125]\d{8})$').hasMatch(p.trim());

class _RegisterScreenState extends State<RegisterScreen> {
  static const _root = String.fromEnvironment('ROOT_DOMAIN', defaultValue: 'elmanassa.shop');
  final _name = TextEditingController();
  final _phone = TextEditingController();
  final _pw = TextEditingController();
  final _slug = TextEditingController();
  final _other = TextEditingController();
  final _rand = Random().nextInt(36 * 36 * 36 * 36).toRadixString(36).padLeft(4, '0');
  final _key = '${DateTime.now().microsecondsSinceEpoch}-${Random().nextInt(1 << 30)}';   // one per screen, like the website, so a retry never creates twice
  String? _type; // STORE | CLINIC
  bool _slugEdited = false;
  String? _slugState; // ok | taken | invalid | checking | null
  Timer? _debounce;
  List<Map<String, dynamic>> _options = const [];
  final _cats = <String>{};
  String? _error;
  bool _busy = false;
  String? _preparing; // the new address, while its secure certificate is being made

  @override
  void dispose() {
    _debounce?.cancel();
    for (final c in [_name, _phone, _pw, _slug, _other]) { c.dispose(); }
    super.dispose();
  }

  String get _auto {
    final b = _slugify(_name.text);
    return b.length >= 3 ? b : '${_type == 'CLINIC' ? 'clinic' : 'shop'}-$_rand';
  }

  void _pick(String type) {
    setState(() { _type = type; _error = null; _cats.clear(); _options = const []; });
    _syncSlug();
    () async {
      try {
        final r = await widget.api.get('onboarding/categories', query: {'type': type}) as List;
        if (mounted && _type == type) setState(() => _options = r.cast<Map<String, dynamic>>());
      } catch (_) {/* the category is optional */}
    }();
  }

  /// The address follows the name until the owner types in the field themselves.
  void _syncSlug() {
    if (!_slugEdited) _slug.text = _auto;
    _check();
  }

  void _check() {
    _debounce?.cancel();
    final v = _slug.text.trim();
    if (v.length < 3) { setState(() => _slugState = null); return; }
    setState(() => _slugState = 'checking');
    _debounce = Timer(const Duration(milliseconds: 350), () async {
      try {
        final r = await widget.api.get('onboarding/slug-available', query: {'slug': v}) as Map<String, dynamic>;
        if (!mounted || _slug.text.trim() != v) return;
        if (r['available'] != true && !_slugEdited) {
          _slug.text = '$v-${Random().nextInt(36 * 36 * 36).toRadixString(36)}';   // taken: quietly try another
          _check();
          return;
        }
        setState(() => _slugState = r['available'] == true ? 'ok' : 'taken');
      } catch (_) {
        if (mounted && _slug.text.trim() == v) setState(() => _slugState = 'invalid');
      }
    });
  }

  bool get _valid {
    final otherOk = !_cats.contains('other') || _other.text.trim().length >= 2;
    return _name.text.trim().length >= 2 && _validMobile(_phone.text) && _pw.text.length >= 8 && _slugState == 'ok' && otherOk && _cats.length <= 5;
  }

  Future<void> _submit() async {
    if (!_valid || _busy) return;
    setState(() { _busy = true; _error = null; });
    try {
      final r = await widget.api.post('onboarding/tenants', {
        'type': _type,
        'name': _name.text.trim(),
        'slug': _slug.text.trim(),
        'ownerFirstName': _name.text.trim(),
        'phone': _phone.text.trim(),
        'password': _pw.text,
        'locale': S.of(context).code,
        if (_cats.isNotEmpty) 'categories': _cats.toList(),
        if (_cats.contains('other')) 'otherCategory': _other.text.trim(),
      }, _key) as Map<String, dynamic>;
      await widget.api.session.save(r['tokens'] as Map<String, dynamic>);
      final host = r['host'] as String?;
      if (host != null && mounted) {
        setState(() { _busy = false; _preparing = host; });
        await _waitForCertificate(host);
      }
      if (!mounted) return;
      await widget.onRegistered();
      if (mounted) Navigator.of(context).popUntil((r) => r.isFirst);
    } on ApiException catch (e) {
      if (mounted) setState(() { _error = errorText(e); _busy = false; _preparing = null; });
    } catch (e) {
      if (mounted) setState(() { _error = errorText(e); _busy = false; _preparing = null; });
    }
  }

  /// A brand-new address gets its secure certificate within about a minute and refuses connections until then. The shop opens on that address,
  /// so wait here (up to 3 minutes) instead of failing on the first screen.
  Future<void> _waitForCertificate(String host) async {
    const insecure = bool.fromEnvironment('INSECURE_HTTP');
    final deadline = DateTime.now().add(const Duration(minutes: 3));
    while (mounted && DateTime.now().isBefore(deadline)) {
      try {
        final c = HttpClient()..connectionTimeout = const Duration(seconds: 6);
        final req = await c.getUrl(Uri.parse('${insecure ? 'http' : 'https'}://$host/api/v1/tenant/resolve'));
        final res = await req.close().timeout(const Duration(seconds: 8));
        await res.drain<void>();
        c.close(force: true);
        return;
      } catch (_) {
        await Future<void>.delayed(const Duration(seconds: 3));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final scheme = Theme.of(context).colorScheme;
    if (_preparing != null) {
      return Scaffold(
        body: SafeArea(child: Center(child: Padding(padding: const EdgeInsets.all(28), child: Column(mainAxisSize: MainAxisSize.min, children: [
          Container(width: 72, height: 72, decoration: BoxDecoration(color: scheme.primary.withValues(alpha: 0.12), shape: BoxShape.circle), child: Icon(Icons.check_rounded, size: 40, color: scheme.primary)),
          const SizedBox(height: 18),
          Text(ar ? 'تم إنشاء حسابك' : 'Your account is ready', style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
          const SizedBox(height: 8),
          Text(ar ? 'نجهّز عنوانك الآمن، يستغرق نحو دقيقة…' : 'Preparing your secure address, about a minute…', textAlign: TextAlign.center),
          const SizedBox(height: 14),
          Text(_preparing!, textDirection: TextDirection.ltr, style: const TextStyle(fontFamily: 'monospace', fontWeight: FontWeight.w700)),
          const SizedBox(height: 22),
          const CircularProgressIndicator(),
        ])))),
      );
    }
    return Scaffold(
      appBar: AppBar(title: Text(_type == null ? (ar ? 'إنشاء حساب' : 'Create an account') : (_type == 'STORE' ? (ar ? 'متجر جديد' : 'New shop') : (ar ? 'عيادة جديدة' : 'New clinic'))), leading: BackButton(onPressed: () => _type == null ? Navigator.of(context).pop() : setState(() { _type = null; _error = null; }))),
      body: _type == null ? _chooser(ar, scheme) : _form(ar, scheme),
    );
  }

  Widget _chooser(bool ar, ColorScheme scheme) => ListView(padding: const EdgeInsets.all(20), children: [
        Text(ar ? 'ماذا تريد أن تنشئ؟' : 'What would you like to create?', style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
        const SizedBox(height: 4),
        Text(ar ? 'تجربة مجانية، ويمكنك الدخول من الهاتف أو الموقع بنفس الحساب.' : 'Free trial. Use the same account on your phone and on the website.', style: TextStyle(color: scheme.onSurfaceVariant)),
        const SizedBox(height: 18),
        for (final t in [
          ('STORE', Icons.storefront_rounded, ar ? 'متجر إلكتروني' : 'Online shop', ar ? 'بيع منتجاتك، استقبل الطلبات وأدر المخزون.' : 'Sell products, take orders and manage stock.'),
          ('CLINIC', Icons.medical_services_rounded, ar ? 'عيادة' : 'Clinic', ar ? 'حجوزات، ملفات مرضى وطابور الانتظار.' : 'Bookings, patient records and the waiting queue.'),
        ])
          Padding(
            padding: const EdgeInsets.only(bottom: 14),
            child: Material(
              color: Colors.white,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22), side: BorderSide(color: scheme.outlineVariant, width: 1.5)),
              child: InkWell(
                borderRadius: BorderRadius.circular(22),
                onTap: () => _pick(t.$1),
                child: Padding(padding: const EdgeInsets.all(18), child: Row(children: [
                  Container(width: 54, height: 54, decoration: BoxDecoration(color: scheme.primary.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(16)), child: Icon(t.$2, color: scheme.primary, size: 28)),
                  const SizedBox(width: 14),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(t.$3, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800)), const SizedBox(height: 3), Text(t.$4, style: TextStyle(color: scheme.onSurfaceVariant, height: 1.35))])),
                  Icon(Icons.chevron_right_rounded, color: scheme.onSurfaceVariant),
                ])),
              ),
            ),
          ),
      ]);

  Widget _form(bool ar, ColorScheme scheme) {
    final store = _type == 'STORE';
    final phoneBad = _phone.text.isNotEmpty && !_validMobile(_phone.text);
    Widget slugNote() => switch (_slugState) {
          'checking' => Text(ar ? 'جارٍ التحقق…' : 'Checking…', style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
          'ok' => Text(ar ? '✓ العنوان متاح' : '✓ Available', style: const TextStyle(fontSize: 12, color: Color(0xFF059669))),
          'taken' => Text(ar ? 'هذا العنوان مستخدم، جرّب غيره' : 'This address is taken, try another', style: TextStyle(fontSize: 12, color: scheme.error)),
          'invalid' => Text(ar ? 'عنوان غير صالح' : 'Not a valid address', style: TextStyle(fontSize: 12, color: scheme.error)),
          _ => const SizedBox.shrink(),
        };
    return ListView(padding: const EdgeInsets.all(20), children: [
      TextField(controller: _name, autofocus: true, maxLength: 200, textInputAction: TextInputAction.next, onChanged: (_) { setState(() {}); _syncSlug(); }, decoration: InputDecoration(labelText: store ? (ar ? 'اسم المتجر' : 'Shop name') : (ar ? 'اسم العيادة' : 'Clinic name'), counterText: '', prefixIcon: Icon(store ? Icons.storefront_rounded : Icons.local_hospital_rounded))),
      const SizedBox(height: 14),
      TextField(controller: _phone, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, textInputAction: TextInputAction.next, onChanged: (_) => setState(() {}), decoration: InputDecoration(labelText: ar ? 'رقم الموبايل' : 'Mobile number', helperText: ar ? 'رقمك لتسجيل الدخول — بدون رسائل SMS' : 'Your sign-in number. No SMS codes.', errorText: phoneBad ? (ar ? 'رقم غير صحيح' : 'Not a valid mobile number') : null, prefixIcon: const Icon(Icons.phone_rounded))),
      const SizedBox(height: 14),
      PasswordField(controller: _pw, label: ar ? 'كلمة المرور (8 أحرف على الأقل)' : 'Password (at least 8 characters)', textInputAction: TextInputAction.next),
      const SizedBox(height: 14),
      // an ordinary field, filled in for the owner; typing in it stops the automatic suggestion
      TextField(controller: _slug, textDirection: TextDirection.ltr, autocorrect: false, enableSuggestions: false, maxLength: 40, textInputAction: TextInputAction.done,
          onChanged: (v) {
            final clean = v.toLowerCase().replaceAll(RegExp(r'[^a-z0-9-]'), '');
            if (clean != v) _slug.value = TextEditingValue(text: clean, selection: TextSelection.collapsed(offset: clean.length));
            _slugEdited = true;
            _check();
          },
          decoration: InputDecoration(labelText: ar ? 'عنوان موقعك' : 'Your web address', counterText: '', suffixText: '.$_root', prefixIcon: const Icon(Icons.link_rounded))),
      Padding(padding: const EdgeInsets.only(top: 4, left: 4, right: 4), child: slugNote()),
      if (_options.isNotEmpty) ...[
        const SizedBox(height: 18),
        Text('${store ? (ar ? 'ماذا تبيع؟' : 'What do you sell?') : (ar ? 'التخصص' : 'Specialty')} (${ar ? 'اختياري' : 'optional'})', style: const TextStyle(fontWeight: FontWeight.w800)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 4, children: [
          for (final o in _options)
            FilterChip(
              label: Text((ar ? o['nameAr'] : o['nameEn']) as String? ?? o['code'] as String),
              selected: _cats.contains(o['code']),
              onSelected: (v) => setState(() { v && _cats.length < 5 ? _cats.add(o['code'] as String) : _cats.remove(o['code']); }),
            ),
        ]),
        if (_cats.contains('other')) Padding(padding: const EdgeInsets.only(top: 10), child: TextField(controller: _other, maxLength: 100, onChanged: (_) => setState(() {}), decoration: InputDecoration(labelText: ar ? 'اكتب الاسم' : 'Type the name', counterText: ''))),
      ],
      if (_error != null) Container(margin: const EdgeInsets.only(top: 16), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: scheme.error.withValues(alpha: 0.08), borderRadius: BorderRadius.circular(14)), child: Row(children: [Icon(Icons.error_outline_rounded, color: scheme.error, size: 20), const SizedBox(width: 8), Expanded(child: Text(_error!, style: TextStyle(color: scheme.error, fontWeight: FontWeight.w600)))])),
      const SizedBox(height: 20),
      AnimatedButton(label: ar ? 'إنشاء' : 'Create', loading: _busy, onPressed: _valid ? _submit : null),
      const SizedBox(height: 10),
      Text(ar ? 'ستبدأ بتجربة مجانية. يمكنك إضافة نطاقك الخاص لاحقًا.' : 'You start with a free trial. You can add your own domain later.', textAlign: TextAlign.center, style: TextStyle(fontSize: 12, color: scheme.onSurfaceVariant)),
    ]);
  }
}
