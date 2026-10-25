import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api_client.dart';
import 'auth.dart';
import 'strings.dart';
import 'ui.dart';

/// Phone/email + password on ONE screen. Colleagues invited by a business (no password yet) are moved to the one-time-code step
/// automatically; "forgot password" uses the same code flow. No SMS anywhere.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.api, required this.title, required this.onLoggedIn, this.app, this.subtitle, this.footer});
  final ApiClient api;
  final String title;
  final String? subtitle;
  /// "business" or "client": the server refuses the other kind of account with a clear message.
  final String? app;
  final Widget? footer;
  final void Function(Map<String, dynamic> user) onLoggedIn;
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

enum _Step { login, activate, reset }

class _LoginScreenState extends State<LoginScreen> {
  final _id = TextEditingController();
  final _pw = TextEditingController();
  final _pin = TextEditingController();
  final _totp = TextEditingController();   // authenticator code, asked only when the account has two-step sign-in on
  bool _mfa = false;
  _Step _step = _Step.login;
  String? _error;
  bool _busy = false;

  String get _idKey => 'login.last.${widget.app ?? 'app'}';

  @override
  void initState() {
    super.initState();
    _restoreId();
  }

  /// The number/email used last time is filled in again (kept after sign-out on purpose).
  Future<void> _restoreId() async {
    try {
      final v = (await SharedPreferences.getInstance()).getString(_idKey);
      if (v != null && v.isNotEmpty && _id.text.isEmpty && mounted) setState(() => _id.text = v);
    } catch (_) {}
  }

  @override
  void dispose() { _id.dispose(); _pw.dispose(); _pin.dispose(); _totp.dispose(); super.dispose(); }

  void _go(_Step s) => setState(() { _step = s; _error = null; _mfa = false; _pw.clear(); _pin.clear(); _totp.clear(); });

  Future<void> _submit() async {
    final auth = AuthRepository(widget.api);
    final id = _id.text.trim();
    if (id.isEmpty || _pw.text.isEmpty || (_step != _Step.login && _pin.text.trim().isEmpty) || (_mfa && _totp.text.trim().isEmpty)) return;
    setState(() { _busy = true; _error = null; });
    try {
      final Map<String, dynamic> user = switch (_step) {
        _Step.login => await auth.login(id, _pw.text, app: widget.app, totpCode: _mfa ? _totp.text.trim() : null),
        _Step.activate => await auth.activate(id, _pin.text.trim(), _pw.text),
        _Step.reset => await auth.resetPassword(id, _pin.text.trim(), _pw.text),
      };
      try { await (await SharedPreferences.getInstance()).setString(_idKey, id); } catch (_) {}
      widget.onLoggedIn(user);
    } on ApiException catch (e) {
      if (e.code == 'ACTIVATION_REQUIRED' && _step == _Step.login) {
        _go(_Step.activate);
      } else if (e.code == 'MFA_REQUIRED' && _step == _Step.login && !_mfa) {
        setState(() { _mfa = true; _error = null; });   // the password was right: now ask for the authenticator code
      } else {
        setState(() => _error = errorText(e));
      }
    } catch (e) {
      setState(() => _error = errorText(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final scheme = Theme.of(context).colorScheme;
    final login = _step == _Step.login;
    return Scaffold(
      body: ListView(padding: EdgeInsets.zero, children: [
        GradientHeader(
          height: 300,
          child: SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                const Align(alignment: AlignmentDirectional.centerEnd, child: LanguageChip(onDark: true)),
                const Spacer(),
                const FadeSlideIn(child: Center(child: DecoratedBox(decoration: BoxDecoration(color: Colors.white, shape: BoxShape.circle, boxShadow: [BoxShadow(color: Color(0x33000000), blurRadius: 24, offset: Offset(0, 10))]), child: Padding(padding: EdgeInsets.all(14), child: BrandLogo(size: 74))))),
                const SizedBox(height: 14),
                FadeSlideIn(index: 1, child: Text(widget.title, textAlign: TextAlign.center, style: const TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.w900))),
                const SizedBox(height: 4),
                FadeSlideIn(index: 2, child: Text(widget.subtitle ?? s.signInHint, textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withValues(alpha: 0.85), fontSize: 14))),
                const SizedBox(height: 38),
              ]),
            ),
          ),
        ),
        Transform.translate(
          offset: const Offset(0, -24),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: FadeSlideIn(
              index: 3,
              child: Card(
                elevation: 6,
                margin: EdgeInsets.zero,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(26)),
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 24, 20, 20),
                  child: AnimatedSize(
                    duration: const Duration(milliseconds: 280),
                    curve: Curves.easeOutCubic,
                    alignment: Alignment.topCenter,
                    child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                      TextField(controller: _id, enabled: login, textInputAction: TextInputAction.next, decoration: InputDecoration(labelText: s.phone, prefixIcon: const Icon(Icons.person_outline_rounded)), keyboardType: TextInputType.emailAddress, textDirection: TextDirection.ltr),
                      AnimatedSwitcher(
                        duration: const Duration(milliseconds: 260),
                        transitionBuilder: (c, a) => FadeTransition(opacity: a, child: SizeTransition(sizeFactor: a, alignment: Alignment.topCenter, child: c)),
                        child: login
                            ? const SizedBox.shrink(key: ValueKey('none'))
                            : Padding(key: const ValueKey('pin'), padding: const EdgeInsets.only(top: 14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                                Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: scheme.primary.withValues(alpha: 0.08), borderRadius: BorderRadius.circular(14)), child: Text(_step == _Step.reset ? s.resetInfo : s.firstTime, style: TextStyle(color: scheme.primary, fontWeight: FontWeight.w600, height: 1.4))),
                                const SizedBox(height: 14),
                                TextField(controller: _pin, textInputAction: TextInputAction.next, decoration: InputDecoration(labelText: s.code_, prefixIcon: const Icon(Icons.pin_outlined)), keyboardType: TextInputType.number, textDirection: TextDirection.ltr),
                              ])),
                      ),
                      if (login && _mfa) ...[
                        const SizedBox(height: 14),
                        TextField(controller: _totp, textInputAction: TextInputAction.next, autofocus: true, decoration: InputDecoration(labelText: s.mfaCode, helperText: s.mfaHint, prefixIcon: const Icon(Icons.shield_outlined)), textDirection: TextDirection.ltr, autofillHints: const [AutofillHints.oneTimeCode]),
                      ],
                      const SizedBox(height: 14),
                      PasswordField(controller: _pw, label: login ? s.password : s.newPassword, textInputAction: TextInputAction.done, onSubmitted: (_) => _submit()),
                      AnimatedSwitcher(
                        duration: const Duration(milliseconds: 220),
                        child: _error == null
                            ? const SizedBox.shrink(key: ValueKey('ok'))
                            : Container(key: ValueKey(_error), margin: const EdgeInsets.only(top: 14), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: scheme.error.withValues(alpha: 0.08), borderRadius: BorderRadius.circular(14)), child: Row(children: [Icon(Icons.error_outline_rounded, color: scheme.error, size: 20), const SizedBox(width: 8), Expanded(child: Text(_error!, style: TextStyle(color: scheme.error, fontWeight: FontWeight.w600)))])),
                      ),
                      const SizedBox(height: 18),
                      AnimatedButton(label: login ? s.login : s.setPassword, loading: _busy, onPressed: _submit),
                      const SizedBox(height: 6),
                      if (login) TextButton(onPressed: () => _go(_Step.reset), child: Text(s.forgotPassword)) else TextButton(onPressed: () => _go(_Step.login), child: Text(s.back)),
                    ]),
                  ),
                ),
              ),
            ),
          ),
        ),
        if (widget.footer != null) Padding(padding: const EdgeInsets.fromLTRB(20, 0, 20, 24), child: widget.footer),
      ]),
    );
  }
}
