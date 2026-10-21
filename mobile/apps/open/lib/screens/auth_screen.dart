import 'package:flutter/material.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../core/api.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';

/// Sign in with Google: the same account the shops use, so one Google account is the same customer in every shop and in the Open Store.
class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key});
  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  Map<String, dynamic>? _cfg;
  bool _busy = false;
  String _error = '';

  @override
  void initState() {
    super.initState();
    OpenApi.get('auth/config').then((c) {
      if (mounted) setState(() => _cfg = Map<String, dynamic>.from(c as Map));
    }).catchError((_) {
      if (mounted) setState(() => _cfg = {'googleEnabled': false});
    });
  }

  Future<void> _go() async {
    final clientId = '${_cfg?['googleClientId'] ?? ''}';
    setState(() { _busy = true; _error = ''; });
    try {
      // The OAuth Web client id is public; the Android client is matched by package name + signing key.
      final account = await GoogleSignIn(serverClientId: clientId, scopes: const ['email']).signIn();
      if (account == null) return; // the shopper closed the Google sheet
      final idToken = (await account.authentication).idToken;
      if (idToken == null) throw StateError('No Google token');
      final res = Map<String, dynamic>.from(await OpenApi.post('auth/google', {'credential': idToken}) as Map);
      AppState.I.signIn('${res['token']}', Map<String, dynamic>.from(res['account'] as Map));
      if (mounted) Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      setState(() => _error = e.text);
    } catch (_) {
      setState(() => _error = tr('login.googleFailed'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final enabled = _cfg?['googleEnabled'] == true && '${_cfg?['googleClientId'] ?? ''}'.isNotEmpty;
    return Scaffold(
      body: ListView(padding: EdgeInsets.zero, children: [
        Container(
          padding: EdgeInsets.fromLTRB(24, MediaQuery.of(context).padding.top + 14, 24, 34),
          decoration: const BoxDecoration(gradient: LinearGradient(colors: [kNavy, kNavy2], begin: Alignment.topLeft, end: Alignment.bottomRight), borderRadius: BorderRadius.vertical(bottom: Radius.circular(34))),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            IconButton(onPressed: () => Navigator.maybePop(context), icon: const Icon(Icons.arrow_back_ios_new_rounded, color: Colors.white, size: 20), padding: EdgeInsets.zero, alignment: AlignmentDirectional.centerStart),
            const SizedBox(height: 10),
            const Text('👋', style: TextStyle(fontSize: 40)),
            const SizedBox(height: 8),
            Text(tr('open.auth.welcome'), style: const TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.w900)),
            const SizedBox(height: 4),
            Text(tr('open.auth.sub'), style: const TextStyle(color: Colors.white70)),
          ]),
        ),
        Padding(
          padding: const EdgeInsets.all(24),
          child: Column(children: [
            if (_cfg == null) const Shimmer(height: 52, radius: 99)
            else if (!enabled) Container(padding: const EdgeInsets.all(14), decoration: BoxDecoration(color: kSoft, borderRadius: BorderRadius.circular(14)), child: Text(tr('login.googleSoon'), textAlign: TextAlign.center, style: const TextStyle(color: kMute)))
            else
              OutlinedButton.icon(
                onPressed: _busy ? null : _go,
                style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(54), shape: const StadiumBorder(), side: const BorderSide(color: kLine), backgroundColor: Colors.white, foregroundColor: kInk),
                icon: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2.4, color: kAccent)) : const Icon(Icons.login_rounded),
                label: Text(tr('login.google'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)),
              ),
            if (_error.isNotEmpty) Container(width: double.infinity, margin: const EdgeInsets.only(top: 14), padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFEF3F2), borderRadius: BorderRadius.circular(12)), child: Text(_error, style: const TextStyle(color: Color(0xFFB42318), fontWeight: FontWeight.w800))),
            const SizedBox(height: 18),
            Text(tr('open.auth.sameAccount'), textAlign: TextAlign.center, style: const TextStyle(color: kMute, fontSize: 12.5, height: 1.5)),
            const SizedBox(height: 6),
            Text(tr('open.auth.guestNote'), textAlign: TextAlign.center, style: const TextStyle(color: kMute, fontSize: 12.5)),
          ]),
        ),
      ]),
    );
  }
}
