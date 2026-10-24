import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

String statusLabel(S s, String code) => switch (code) {
      'REQUESTED' || 'PENDING' => s.ar ? 'تم الطلب' : 'Requested',
      'PREPARING' => s.ar ? 'جاري التجهيز' : 'Preparing',
      'SHIPPED' => s.ar ? 'تم الشحن' : 'Shipped',
      'ARRIVED' => s.ar ? 'وصل' : 'Arrived',
      'RETURNED' => s.ar ? 'مرتجع' : 'Returned',
      'REFUNDED' => s.ar ? 'تم الاسترداد' : 'Refunded',
      'CANCELLED' => s.ar ? 'ملغي' : 'Cancelled',
      _ => code,
    };

/// Orders tab: follow any order with its number and phone (guests included), or sign in with Google to see all your orders.
class OrdersPage extends StatefulWidget {
  const OrdersPage({super.key, required this.ctx});
  final StoreCtx ctx;
  @override
  State<OrdersPage> createState() => _OrdersPageState();
}

class _OrdersPageState extends State<OrdersPage> {
  final number = TextEditingController(), phone = TextEditingController();
  int refresh = 0;

  @override
  void dispose() { number.dispose(); phone.dispose(); super.dispose(); }

  Future<void> _deleteAccount(BuildContext context, S s) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(s.ar ? 'حذف الحساب؟' : 'Delete your account?'),
        content: Text(s.ar
            ? 'سيتم حذف حسابك وعناوينك وقائمة المفضلة نهائيًا. تبقى طلباتك السابقة لدى المتاجر لأغراض المحاسبة والمرتجعات. لا يمكن التراجع.'
            : 'Your account, addresses and wishlist will be deleted permanently. Past orders stay with the shops for their records and returns. This cannot be undone.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: Text(s.ar ? 'إلغاء' : 'Cancel')),
          FilledButton(style: FilledButton.styleFrom(backgroundColor: Theme.of(c).colorScheme.error), onPressed: () => Navigator.pop(c, true), child: Text(s.ar ? 'احذف حسابي' : 'Delete')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await widget.ctx.api.delete('shop/me');
      try { await GoogleSignIn().signOut(); } catch (_) {}
      await widget.ctx.api.session.clear();
      if (mounted) setState(() => refresh++);
    } catch (e) {
      if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s.ar ? 'تعذّر حذف الحساب، حاول مرة أخرى' : 'Could not delete the account, try again')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final api = widget.ctx.api;
    final cs = Theme.of(context).colorScheme;
    return ListView(padding: const EdgeInsets.all(16), children: [
      Text(s.ar ? 'أين طلبي؟' : 'Where is my order?', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900)),
      const SizedBox(height: 4),
      Text(s.ar ? 'أدخل رقم الطلب ورقم الهاتف الذي استخدمته.' : 'Enter your order number and the phone you used.', style: const TextStyle(color: Colors.grey)),
      const SizedBox(height: 12),
      TextField(controller: number, decoration: InputDecoration(labelText: s.ar ? 'رقم الطلب' : 'Order number', border: const OutlineInputBorder(), hintText: 'ST-20261009-000001'), textDirection: TextDirection.ltr),
      const SizedBox(height: 10),
      TextField(controller: phone, keyboardType: TextInputType.phone, decoration: InputDecoration(labelText: s.ar ? 'رقم الموبايل' : 'Mobile number', border: const OutlineInputBorder())),
      const SizedBox(height: 10),
      FilledButton(
        onPressed: () { if (number.text.trim().isNotEmpty && phone.text.trim().isNotEmpty) Navigator.of(context).push(MaterialPageRoute(builder: (_) => TrackPage(ctx: widget.ctx, orderNumber: number.text.trim(), phone: phone.text.trim()))); },
        child: Padding(padding: const EdgeInsets.all(6), child: Text(s.ar ? 'تتبع' : 'Track')),
      ),
      const Divider(height: 40),
      FutureBuilder<bool>(
        key: ValueKey(refresh),
        future: api.session.loggedIn,
        builder: (context, snap) {
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          if (!snap.data!) {
            return Column(children: [
              Text(s.ar ? 'سجّل الدخول بجوجل لمتابعة كل طلباتك. يمكنك الطلب دون حساب.' : 'Sign in with Google to see all your orders. You can order without an account.', textAlign: TextAlign.center, style: TextStyle(color: cs.onSurfaceVariant)),
              const SizedBox(height: 12),
              GoogleSignInButton(api: api, onSignedIn: () => setState(() => refresh++)),
              const SizedBox(height: 18),
              PhoneSignIn(api: api, onSignedIn: () => setState(() => refresh++)),
            ]);
          }
          return Async<Map<String, dynamic>>(
            load: () async => await api.get('shop/orders') as Map<String, dynamic>,
            builder: (context, data, reload) {
              final orders = (data['data'] as List).cast<Map<String, dynamic>>();
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                _GenderPicker(api: api),
                _PasswordCard(api: api),
                if (orders.isEmpty) Center(child: Padding(padding: const EdgeInsets.all(12), child: Text(s.empty))) else Text(s.ar ? 'طلباتي' : 'My orders', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
                for (final o in orders) Card(child: ListTile(
                  title: Text(o['orderNumber'] as String, style: const TextStyle(fontWeight: FontWeight.w800), textDirection: TextDirection.ltr, textAlign: TextAlign.start),
                  subtitle: Text(statusLabel(s, o['status'] as String)),
                  trailing: Text(s.money(o['totalMinor'] as num, widget.ctx.tenant.currency), style: const TextStyle(fontWeight: FontWeight.w900)),
                  onTap: () async {
                    final detail = await api.get('shop/orders/${o['id']}') as Map<String, dynamic>;
                    final ph = (detail['customerPhoneSnapshot'] as String?) ?? '';
                    if (context.mounted) Navigator.of(context).push(MaterialPageRoute(builder: (_) => TrackPage(ctx: widget.ctx, orderNumber: o['orderNumber'] as String, phone: ph)));
                  },
                )),
                const SizedBox(height: 24),
                Center(child: TextButton.icon(
                  icon: const Icon(Icons.delete_outline),
                  style: TextButton.styleFrom(foregroundColor: cs.error),
                  label: Text(s.ar ? 'حذف حسابي' : 'Delete my account'),
                  onPressed: () => _deleteAccount(context, s),
                )),
              ]);
            },
          );
        },
      ),
    ]);
  }
}

/// One order's progress, shipment, items and (when allowed) a return request.
class TrackPage extends StatefulWidget {
  const TrackPage({super.key, required this.ctx, required this.orderNumber, required this.phone});
  final StoreCtx ctx;
  final String orderNumber, phone;
  @override
  State<TrackPage> createState() => _TrackPageState();
}

class _TrackPageState extends State<TrackPage> {
  late Future<Map<String, dynamic>> future = _load();
  String reason = 'SIZE';
  final details = TextEditingController();
  String? error;
  bool busy = false;

  Future<Map<String, dynamic>> _load() async => await widget.ctx.api.post('shop/track', {'orderNumber': widget.orderNumber, 'phone': widget.phone}) as Map<String, dynamic>;

  @override
  void dispose() { details.dispose(); super.dispose(); }

  Future<void> _return() async {
    setState(() { busy = true; error = null; });
    try {
      await widget.ctx.api.post('shop/track/return', {'orderNumber': widget.orderNumber, 'phone': widget.phone, 'reason': reason, 'details': details.text});
      setState(() => future = _load());
    } catch (e) {
      setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final cur = widget.ctx.tenant.currency;
    final cs = Theme.of(context).colorScheme;
    const steps = ['REQUESTED', 'PREPARING', 'SHIPPED', 'ARRIVED'];
    const reasons = {'DEFECTIVE': ['معيب أو تالف', 'Defective or damaged'], 'WRONG_ITEM': ['منتج خاطئ', 'Wrong item'], 'NOT_AS_DESCRIBED': ['غير مطابق للوصف', 'Not as described'], 'SIZE': ['المقاس غير مناسب', 'Size or fit'], 'CHANGED_MIND': ['غيرت رأيي', 'Changed my mind'], 'OTHER': ['سبب آخر', 'Other']};
    return Scaffold(
      appBar: AppBar(title: Text(widget.orderNumber, textDirection: TextDirection.ltr)),
      body: FutureBuilder<Map<String, dynamic>>(
        future: future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
          if (snap.hasError) return Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(errorText(snap.error!), textAlign: TextAlign.center)));
          final o = snap.data!;
          final status = o['status'] as String;
          final idx = steps.indexOf(status);
          final closed = ['CANCELLED', 'RETURNED', 'REFUNDED'].contains(status);
          final items = (o['items'] as List).cast<Map<String, dynamic>>();
          final ret = o['returnRequest'] as Map?;
          return ListView(padding: const EdgeInsets.all(16), children: [
            if (!closed) Row(children: [for (var i = 0; i < steps.length; i++) Expanded(child: Column(children: [
              CircleAvatar(radius: 18, backgroundColor: i <= idx ? cs.primary : cs.surfaceContainerHighest, child: i < idx ? const Icon(Icons.check, size: 18, color: Colors.white) : Text('${i + 1}', style: TextStyle(color: i <= idx ? Colors.white : Colors.grey, fontWeight: FontWeight.w800))),
              const SizedBox(height: 6),
              Text(statusLabel(s, steps[i]), textAlign: TextAlign.center, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: i <= idx ? null : Colors.grey)),
            ]))]) else Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: cs.surfaceContainerHighest, borderRadius: BorderRadius.circular(14)), child: Center(child: Text(statusLabel(s, status), style: const TextStyle(fontWeight: FontWeight.w900)))),
            if (status == 'REQUESTED' && o['confirmed'] == false) Padding(padding: const EdgeInsets.only(top: 14), child: Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: Colors.amber.shade50, borderRadius: BorderRadius.circular(14)), child: Text(s.ar ? '📞 سنتصل بك قريبًا لتأكيد طلبك.' : '📞 We will call you shortly to confirm your order.', style: TextStyle(color: Colors.amber.shade900, fontWeight: FontWeight.w700)))),
            if ((o['courierName'] as String?)?.isNotEmpty == true || (o['trackingNumber'] as String?)?.isNotEmpty == true) Card(margin: const EdgeInsets.only(top: 14), child: ListTile(
              leading: const Icon(Icons.local_shipping_outlined), title: Text(s.ar ? 'الشحنة' : 'Shipment', style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text('${o['courierName'] ?? ''} ${o['trackingNumber'] ?? ''}'.trim()),
              trailing: (o['trackingUrl'] as String?)?.isNotEmpty == true ? TextButton(onPressed: () => openUrl(o['trackingUrl'] as String), child: Text(s.ar ? 'تتبع' : 'Follow')) : null,
            )),
            const SizedBox(height: 14),
            for (final i in items) ListTile(contentPadding: EdgeInsets.zero, title: Text('${i['productNameSnapshot']} × ${i['quantity']}'), subtitle: (i['variantNameSnapshot'] as String?)?.isNotEmpty == true ? Text(i['variantNameSnapshot'] as String) : null, trailing: Text(s.money(i['lineTotalMinor'] as num, cur), style: const TextStyle(fontWeight: FontWeight.w800))),
            const Divider(),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(s.ar ? 'الشحن' : 'Delivery'), Text(((o['shippingMinor'] as num?) ?? 0) == 0 ? (s.ar ? 'مجاني' : 'Free') : s.money(o['shippingMinor'] as num, cur))]),
            if (((o['codFeeMinor'] as num?) ?? 0) > 0) Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(s.ar ? 'رسوم الدفع عند الاستلام' : 'COD fee'), Text(s.money(o['codFeeMinor'] as num, cur))]),
            const SizedBox(height: 6),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(s.total, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 17)), Text(s.money(o['totalMinor'] as num, cur), style: TextStyle(fontWeight: FontWeight.w900, fontSize: 17, color: cs.primary))]),
            if (ret != null) Padding(padding: const EdgeInsets.only(top: 14), child: Text('↩️ ${s.ar ? 'طلب الاسترجاع' : 'Return request'}: ${ret['status']}', style: const TextStyle(fontWeight: FontWeight.w800))),
            if (o['canReturn'] == true) ...[
              const Divider(height: 32),
              Text(s.ar ? 'طلب استرجاع' : 'Request a return', style: const TextStyle(fontWeight: FontWeight.w900)),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(initialValue: reason, decoration: const InputDecoration(border: OutlineInputBorder()), items: [for (final e in reasons.entries) DropdownMenuItem(value: e.key, child: Text(s.ar ? e.value[0] : e.value[1]))], onChanged: (v) => setState(() => reason = v ?? 'SIZE')),
              const SizedBox(height: 10),
              TextField(controller: details, maxLines: 3, decoration: InputDecoration(border: const OutlineInputBorder(), labelText: s.ar ? 'تفاصيل (اختياري)' : 'Details (optional)')),
              if (error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(error!, style: TextStyle(color: cs.error))),
              const SizedBox(height: 10),
              FilledButton(onPressed: busy ? null : _return, child: Text(s.ar ? 'إرسال الطلب' : 'Send request')),
            ],
          ]);
        },
      ),
    );
  }
}

/// Google sign-in for shop clients. The OAuth Web client id is public; the Android client is matched by package + signing key.
class GoogleSignInButton extends StatefulWidget {
  const GoogleSignInButton({super.key, required this.api, required this.onSignedIn});
  final ApiClient api;
  final VoidCallback onSignedIn;
  @override
  State<GoogleSignInButton> createState() => _GoogleSignInButtonState();
}

class _GoogleSignInButtonState extends State<GoogleSignInButton> {
  static const clientId = String.fromEnvironment('GOOGLE_CLIENT_ID', defaultValue: '424377343706-6tbunl2hl4ev6ekqu9h2h2ppanarhi89.apps.googleusercontent.com');
  String? error;
  bool busy = false;

  Future<void> _go() async {
    setState(() { busy = true; error = null; });
    try {
      final account = await GoogleSignIn(serverClientId: clientId, scopes: const ['email']).signIn();
      if (account == null) return;
      final idToken = (await account.authentication).idToken;
      if (idToken == null) throw StateError('No Google token');
      final r = await widget.api.post('auth/client/google', {'credential': idToken}) as Map<String, dynamic>;
      await widget.api.session.save(r);
      widget.onSignedIn();
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (clientId.isEmpty) return Text(s.ar ? 'تسجيل الدخول بجوجل سيتوفر قريبًا.' : 'Google sign-in is coming soon.', style: Theme.of(context).textTheme.bodySmall);
    return Column(mainAxisSize: MainAxisSize.min, children: [
      OutlinedButton.icon(onPressed: busy ? null : _go, icon: const Icon(Icons.login), label: Text(s.ar ? 'المتابعة بجوجل' : 'Continue with Google')),
      if (error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
    ]);
  }
}

/// "I am a man / a woman": so shops talk to the customer in the right form (أهلاً بيك / أهلاً بيكي). Saved on the account.
class _GenderPicker extends StatefulWidget {
  const _GenderPicker({required this.api});
  final dynamic api;
  @override
  State<_GenderPicker> createState() => _GenderPickerState();
}

class _GenderPickerState extends State<_GenderPicker> {
  String? _gender;
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    widget.api.get('shop/me').then((r) { if (mounted) setState(() { _gender = (r as Map)['gender'] as String?; _loaded = true; }); }).catchError((_) { if (mounted) setState(() => _loaded = true); });
  }

  Future<void> _set(String g) async {
    final old = _gender;
    setState(() => _gender = g);
    try { await widget.api.put('shop/me', {'gender': g}); } catch (_) { if (mounted) setState(() => _gender = old); }
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    if (!_loaded) return const SizedBox.shrink();
    return Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(ar ? 'أنا' : 'I am', style: const TextStyle(fontWeight: FontWeight.w900)),
      Text(ar ? 'عشان المتاجر تكلمك بالصيغة الصح.' : 'So shops speak to you correctly.', style: const TextStyle(fontSize: 12, color: Colors.grey)),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(child: ChoiceChip(label: Center(child: Text(ar ? 'ذكر' : 'Male')), selected: _gender == 'MALE', onSelected: (_) => _set('MALE'))),
        const SizedBox(width: 8),
        Expanded(child: ChoiceChip(label: Center(child: Text(ar ? 'أنثى' : 'Female')), selected: _gender == 'FEMALE', onSelected: (_) => _set('FEMALE'))),
      ]),
    ])));
  }
}

/// Phone and password: sign in, sign up (a code arrives on WhatsApp first, so only the owner of the number can create the account) and "forgot password".
/// Customers who ordered through WhatsApp were already sent their number and a temporary password there.
class PhoneSignIn extends StatefulWidget {
  const PhoneSignIn({super.key, required this.api, required this.onSignedIn});
  final ApiClient api;
  final VoidCallback onSignedIn;
  @override
  State<PhoneSignIn> createState() => _PhoneSignInState();
}

class _PhoneSignInState extends State<PhoneSignIn> {
  final _name = TextEditingController(), _phone = TextEditingController(), _password = TextEditingController(), _code = TextEditingController();
  String _mode = 'login'; // login | register | forgot
  bool _sent = false, _busy = false, _codes = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    widget.api.get('auth/client/config').then((r) { if (mounted) setState(() => _codes = (r as Map)['phoneRegisterEnabled'] == true); }).catchError((_) {});
  }

  @override
  void dispose() { for (final c in [_name, _phone, _password, _code]) { c.dispose(); } super.dispose(); }

  Future<void> _run(Future<void> Function() f) async {
    setState(() { _busy = true; _error = null; });
    try { await f(); } catch (e) { if (mounted) setState(() => _error = errorText(e)); } finally { if (mounted) setState(() => _busy = false); }
  }

  Future<void> _signedIn(dynamic r) async { await widget.api.session.save(r as Map<String, dynamic>); widget.onSignedIn(); }

  void _go(String m) => setState(() { _mode = m; _sent = false; _code.clear(); _password.clear(); _error = null; });

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final cs = Theme.of(context).colorScheme;
    InputDecoration dec(String l, {String? hint}) => InputDecoration(labelText: l, hintText: hint, border: const OutlineInputBorder(), isDense: true);
    return Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        ChoiceChip(label: Text(ar ? 'الدخول بالتليفون' : 'Sign in with phone'), selected: _mode == 'login', onSelected: (_) => _go('login')),
        if (_codes) ...[const SizedBox(width: 8), ChoiceChip(label: Text(ar ? 'حساب جديد' : 'New account'), selected: _mode == 'register', onSelected: (_) => _go('register'))],
      ]),
      const SizedBox(height: 8),
      Text(_mode == 'login' ? (ar ? 'رقم تليفونك وكلمة المرور. لو طلبت على واتساب، بعتنالك الاتنين هناك.' : 'Your phone and password. If you ordered on WhatsApp, we sent you both there.')
          : _mode == 'register' ? (ar ? 'هنبعتلك كود على واتساب عشان نتأكد إن الرقم بتاعك.' : 'We send a code on WhatsApp to make sure the number is yours.') : (ar ? 'هنبعتلك كود على واتساب عشان تختار كلمة مرور جديدة.' : 'We send a code on WhatsApp so you can choose a new password.'),
          textAlign: TextAlign.center, style: const TextStyle(fontSize: 12, color: Colors.grey)),
      const SizedBox(height: 10),
      if (_mode == 'register' && !_sent) ...[TextField(controller: _name, decoration: dec(ar ? 'الاسم' : 'Name')), const SizedBox(height: 8)],
      if (!_sent) ...[
        TextField(controller: _phone, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, decoration: dec(ar ? 'رقم الموبايل' : 'Mobile number')),
        const SizedBox(height: 8),
      ],
      if ((_mode == 'login' || _mode == 'register') && !_sent) ...[TextField(controller: _password, obscureText: true, decoration: dec(ar ? 'كلمة المرور (٨ حروف على الأقل)' : 'Password (8+ characters)')), const SizedBox(height: 8)],
      if (_sent) ...[
        Text(ar ? 'بعتنا كود من ٦ أرقام على واتساب بتاعك. شغال ١٠ دقايق.' : 'We sent a 6-digit code to your WhatsApp. It works for 10 minutes.', style: const TextStyle(color: Color(0xFF15803D), fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        TextField(controller: _code, keyboardType: TextInputType.number, maxLength: 6, textDirection: TextDirection.ltr, decoration: dec(ar ? 'الكود' : 'The code')),
        if (_mode == 'forgot') ...[const SizedBox(height: 8), TextField(controller: _password, obscureText: true, decoration: dec(ar ? 'كلمة المرور الجديدة (٨ حروف على الأقل)' : 'New password (8+ characters)'))],
      ],
      if (_error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_error!, style: TextStyle(color: cs.error))),
      const SizedBox(height: 10),
      FilledButton(
        onPressed: _busy ? null : () => _run(() async {
          if (_mode == 'login') {
            await _signedIn(await widget.api.post('auth/client/login', {'phone': _phone.text.trim(), 'password': _password.text}));
          } else if (!_sent) {
            if (_mode == 'register') { await widget.api.post('auth/client/register', {'name': _name.text.trim(), 'phone': _phone.text.trim(), 'password': _password.text}); } else { await widget.api.post('auth/client/password/forgot', {'phone': _phone.text.trim()}); }
            if (mounted) setState(() => _sent = true);
          } else if (_mode == 'register') {
            await _signedIn(await widget.api.post('auth/client/register/verify', {'phone': _phone.text.trim(), 'code': _code.text.trim()}));
          } else {
            await _signedIn(await widget.api.post('auth/client/password/reset', {'phone': _phone.text.trim(), 'code': _code.text.trim(), 'newPassword': _password.text}));
          }
        }),
        child: Text(_mode == 'login' ? (ar ? 'دخول' : 'Sign in') : !_sent ? (ar ? 'ابعتلي الكود على واتساب' : 'Send me the code on WhatsApp') : _mode == 'register' ? (ar ? 'اعمل حسابي' : 'Create my account') : (ar ? 'احفظ كلمة المرور' : 'Save the password')),
      ),
      if (_mode == 'login' && _codes) TextButton(onPressed: () => _go('forgot'), child: Text(ar ? 'نسيت كلمة المرور؟' : 'Forgot password?')),
      if (_mode == 'forgot' || _sent) TextButton(onPressed: () => _sent ? setState(() { _sent = false; _code.clear(); }) : _go('login'), child: Text(_sent ? (ar ? 'غيّر الرقم أو ابعت كود جديد' : 'Change the number or send a new code') : (ar ? 'رجوع' : 'Back'))),
    ])));
  }
}

/// Change the password: the temporary one sent on WhatsApp, or a first one for an account that signed up with Google.
class _PasswordCard extends StatefulWidget {
  const _PasswordCard({required this.api});
  final ApiClient api;
  @override
  State<_PasswordCard> createState() => _PasswordCardState();
}

class _PasswordCardState extends State<_PasswordCard> {
  final _current = TextEditingController(), _next = TextEditingController();
  Map<String, dynamic>? _me;
  String? _error;
  bool _busy = false, _saved = false;

  @override
  void initState() { super.initState(); _load(); }

  @override
  void dispose() { _current.dispose(); _next.dispose(); super.dispose(); }

  Future<void> _load() async {
    try { final r = await widget.api.get('shop/me') as Map<String, dynamic>; if (mounted) setState(() => _me = r); } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final me = _me;
    if (me == null) return const SizedBox.shrink();
    final must = me['mustChangePassword'] == true, has = me['hasPassword'] == true;
    return Card(shape: must ? RoundedRectangleBorder(borderRadius: BorderRadius.circular(12), side: const BorderSide(color: Color(0xFFFCD34D), width: 2)) : null, child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(ar ? 'كلمة المرور' : 'Password', style: const TextStyle(fontWeight: FontWeight.w900)),
      if (must) Padding(padding: const EdgeInsets.only(top: 4), child: Text(ar ? 'إنت بتستخدم كلمة المرور المؤقتة اللي بعتناها. اختار كلمة مرور خاصة بيك.' : 'You are using the temporary password we sent you. Please choose your own.', style: const TextStyle(color: Color(0xFF92400E), fontSize: 13))),
      if (has) ...[const SizedBox(height: 8), TextField(controller: _current, obscureText: true, decoration: InputDecoration(labelText: ar ? 'كلمة المرور الحالية' : 'Current password', isDense: true, border: const OutlineInputBorder()))],
      const SizedBox(height: 8),
      TextField(controller: _next, obscureText: true, decoration: InputDecoration(labelText: ar ? 'كلمة المرور الجديدة (٨ حروف على الأقل)' : 'New password (8+ characters)', isDense: true, border: const OutlineInputBorder())),
      if (_error != null) Padding(padding: const EdgeInsets.only(top: 6), child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
      if (_saved) Padding(padding: const EdgeInsets.only(top: 6), child: Text(ar ? 'تم الحفظ' : 'Saved', style: const TextStyle(color: Color(0xFF15803D)))),
      const SizedBox(height: 8),
      FilledButton(onPressed: _busy ? null : () async {
        setState(() { _busy = true; _error = null; _saved = false; });
        try {
          final r = await widget.api.put('shop/me/password', {if (has) 'current': _current.text, 'newPassword': _next.text}) as Map<String, dynamic>;
          await widget.api.session.save(r);
          _current.clear(); _next.clear();
          await _load();
          if (mounted) setState(() => _saved = true);
        } catch (e) { if (mounted) setState(() => _error = errorText(e)); } finally { if (mounted) setState(() => _busy = false); }
      }, child: Text(ar ? 'حفظ' : 'Save')),
    ])));
  }
}
