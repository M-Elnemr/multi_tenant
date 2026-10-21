import 'package:flutter/material.dart';

import '../core/api.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';

/// Phone + password, no SMS or OTP. One screen for signing in and creating an account.
class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key, this.register = false});
  final bool register;
  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  late bool _register = widget.register;
  final _name = TextEditingController(), _phone = TextEditingController(), _pass = TextEditingController();
  bool _busy = false, _show = false;
  String _error = '';

  @override
  void dispose() {
    _name.dispose();
    _phone.dispose();
    _pass.dispose();
    super.dispose();
  }

  Future<void> _go() async {
    setState(() { _busy = true; _error = ''; });
    try {
      final res = Map<String, dynamic>.from(await OpenApi.post(_register ? 'auth/register' : 'auth/login', {if (_register) 'name': _name.text.trim(), 'phone': _phone.text.trim(), 'password': _pass.text}) as Map);
      AppState.I.signIn(s0(res['token']), Map<String, dynamic>.from(res['account'] as Map));
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      setState(() => _error = e.text);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String s0(Object? v) => v == null ? '' : '$v';

  @override
  Widget build(BuildContext context) => Scaffold(
        body: ListView(padding: EdgeInsets.zero, children: [
          Container(
            padding: EdgeInsets.fromLTRB(24, MediaQuery.of(context).padding.top + 14, 24, 34),
            decoration: const BoxDecoration(gradient: LinearGradient(colors: [kNavy, kNavy2], begin: Alignment.topLeft, end: Alignment.bottomRight), borderRadius: BorderRadius.vertical(bottom: Radius.circular(34))),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              IconButton(onPressed: () => Navigator.maybePop(context), icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white, size: 20), padding: EdgeInsets.zero, alignment: AlignmentDirectional.centerStart),
              const SizedBox(height: 10),
              const Text('👋', style: TextStyle(fontSize: 40)),
              const SizedBox(height: 8),
              Text(_register ? tr('open.auth.createAccount') : tr('open.auth.welcomeBack'), style: const TextStyle(color: Colors.white, fontSize: 27, fontWeight: FontWeight.w900)),
              const SizedBox(height: 4),
              Text(tr('open.auth.sub'), style: const TextStyle(color: Colors.white70)),
            ]),
          ),
          Padding(
            padding: const EdgeInsets.all(20),
            child: Column(children: [
              Container(
                padding: const EdgeInsets.all(4),
                decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(99), border: Border.all(color: kLine)),
                child: Row(children: [for (final r in [false, true]) Expanded(child: GestureDetector(onTap: () => setState(() { _register = r; _error = ''; }), child: AnimatedContainer(duration: const Duration(milliseconds: 200), padding: const EdgeInsets.symmetric(vertical: 11), decoration: BoxDecoration(color: _register == r ? kNavy : Colors.transparent, borderRadius: BorderRadius.circular(99)), alignment: Alignment.center, child: Text(r ? tr('open.auth.register') : tr('open.signIn'), style: TextStyle(fontWeight: FontWeight.w900, color: _register == r ? Colors.white : kMute)))))]),
              ),
              const SizedBox(height: 18),
              if (_register) ...[TextField(controller: _name, textInputAction: TextInputAction.next, decoration: InputDecoration(labelText: tr('open.checkout.name'))), const SizedBox(height: 12)],
              TextField(controller: _phone, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, textInputAction: TextInputAction.next, decoration: InputDecoration(labelText: tr('open.checkout.phone'), hintText: '01xxxxxxxxx')),
              const SizedBox(height: 12),
              TextField(controller: _pass, obscureText: !_show, onSubmitted: (_) => _go(), decoration: InputDecoration(labelText: tr('open.auth.password'), suffixIcon: IconButton(icon: Icon(_show ? Icons.visibility_off_outlined : Icons.visibility_outlined), onPressed: () => setState(() => _show = !_show)))),
              if (_register) Padding(padding: const EdgeInsets.only(top: 6), child: Align(alignment: AlignmentDirectional.centerStart, child: Text(tr('error.WEAK_PASSWORD'), style: const TextStyle(color: kMute, fontSize: 12)))),
              if (_error.isNotEmpty) Container(width: double.infinity, margin: const EdgeInsets.only(top: 14), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFEF3F2), borderRadius: BorderRadius.circular(12)), child: Text(_error, style: const TextStyle(color: Color(0xFFB42318), fontWeight: FontWeight.w800))),
              const SizedBox(height: 20),
              GradientButton(label: _register ? tr('open.auth.register') : tr('open.signIn'), busy: _busy, onPressed: _go),
              const SizedBox(height: 14),
              Text(tr('open.auth.guestNote'), style: const TextStyle(color: kMute, fontSize: 12.5)),
            ]),
          ),
        ]),
      );
}
