import 'package:flutter/material.dart';

import 'api_client.dart';
import 'auth.dart';
import 'strings.dart';
import 'tenant.dart';

Color brandColor(TenantInfo? t) {
  final c = t?.primaryColor;
  if (c == null) return const Color(0xFF0F766E);
  return Color(int.parse('FF${c.substring(1)}', radix: 16));
}

ThemeData themeFor(TenantInfo? t) => ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: brandColor(t)), useMaterial3: true, inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder()));

String errorText(Object e) => e is ApiException ? e.message : e.toString();

/// Asks for a store/clinic address once and remembers it. Each app embeds this as its first screen.
class TenantGate extends StatefulWidget {
  const TenantGate({super.key, required this.expectedType, required this.builder, required this.initial});
  final String expectedType; // STORE, CLINIC or ANY
  final TenantInfo? initial;
  final Widget Function(BuildContext, TenantInfo, ApiClient) builder;
  @override
  State<TenantGate> createState() => _TenantGateState();
}

class _TenantGateState extends State<TenantGate> {
  final _c = TextEditingController();
  TenantInfo? _tenant;
  ApiClient? _api;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _set(widget.initial);
  }

  void _set(TenantInfo? t) {
    _tenant = t;
    _api = t == null ? null : ApiClient(baseUrl: t.baseUrl, session: Session(t.host));
  }

  Future<void> _go() async {
    setState(() { _busy = true; _error = null; });
    try {
      final t = await TenantInfo.resolve(_c.text);
      if (widget.expectedType != 'ANY' && t.type != widget.expectedType) throw StateError('Wrong kind of address (${t.type})');
      setState(() => _set(t));
    } catch (e) {
      setState(() => _error = errorText(e));
    } finally {
      setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (_tenant != null && _api != null) {
      return Theme(data: themeFor(_tenant), child: widget.builder(context, _tenant!, _api!));
    }
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            TextField(controller: _c, decoration: InputDecoration(labelText: s.address, hintText: s.addressHint), keyboardType: TextInputType.url, textDirection: TextDirection.ltr),
            if (_error != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
            const SizedBox(height: 16),
            FilledButton(onPressed: _busy ? null : _go, child: _busy ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)) : Text(s.continue_)),
          ]),
        ),
      ),
    );
  }
}

/// Phone/email + password; first-time users enter the PIN they were given and pick a password (no SMS).
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.api, required this.title, required this.onLoggedIn});
  final ApiClient api;
  final String title;
  final void Function(Map<String, dynamic> user) onLoggedIn;
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _id = TextEditingController();
  final _pw = TextEditingController();
  final _pin = TextEditingController();
  String _step = 'identify'; // identify | password | activate
  String? _error;
  bool _busy = false;

  Future<void> _submit() async {
    final auth = AuthRepository(widget.api);
    setState(() { _busy = true; _error = null; });
    try {
      if (_step == 'identify') {
        final next = await auth.checkIdentifier(_id.text.trim());
        setState(() => _step = next == 'ACTIVATE' ? 'activate' : 'password');
      } else if (_step == 'password') {
        widget.onLoggedIn(await auth.login(_id.text.trim(), _pw.text));
      } else {
        widget.onLoggedIn(await auth.activate(_id.text.trim(), _pin.text.trim(), _pw.text));
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
    return Scaffold(
      appBar: AppBar(title: Text(widget.title)),
      body: ListView(padding: const EdgeInsets.all(24), children: [
        TextField(controller: _id, enabled: _step == 'identify', decoration: InputDecoration(labelText: s.phone), keyboardType: TextInputType.emailAddress, textDirection: TextDirection.ltr),
        const SizedBox(height: 12),
        if (_step == 'activate') ...[
          Text(s.firstTime),
          const SizedBox(height: 12),
          TextField(controller: _pin, decoration: InputDecoration(labelText: s.code_), keyboardType: TextInputType.number, textDirection: TextDirection.ltr),
          const SizedBox(height: 12),
        ],
        if (_step != 'identify') TextField(controller: _pw, obscureText: true, decoration: InputDecoration(labelText: _step == 'activate' ? s.newPassword : s.password)),
        if (_error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error))),
        const SizedBox(height: 16),
        FilledButton(onPressed: _busy ? null : _submit, child: Text(_step == 'identify' ? s.continue_ : s.login)),
      ]),
    );
  }
}

/// Loads a future and shows spinner / error+retry / content.
class Async<T> extends StatefulWidget {
  const Async({super.key, required this.load, required this.builder});
  final Future<T> Function() load;
  final Widget Function(BuildContext, T, Future<void> Function() reload) builder;
  @override
  State<Async<T>> createState() => _AsyncState<T>();
}

class _AsyncState<T> extends State<Async<T>> {
  late Future<T> _f = widget.load();
  Future<void> _reload() async {
    setState(() { _f = widget.load(); });
    await _f.catchError((_) => null as T);
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<T>(
        future: _f,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
          if (snap.hasError) {
            return Center(child: Column(mainAxisSize: MainAxisSize.min, children: [Text(errorText(snap.error!)), TextButton(onPressed: _reload, child: Text(S.of(context).retry))]));
          }
          return widget.builder(context, snap.data as T, _reload);
        },
      );
}
