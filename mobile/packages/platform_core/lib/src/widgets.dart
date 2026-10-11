import 'package:flutter/material.dart';

import 'api_client.dart';
import 'auth.dart';
import 'strings.dart';
import 'tenant.dart';
import 'theme.dart';
import 'ui.dart';

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
            const FadeSlideIn(child: Center(child: BrandLogo(size: 140))),
            const SizedBox(height: 28),
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

