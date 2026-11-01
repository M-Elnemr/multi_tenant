import 'package:flutter/material.dart';

import 'api_client.dart';
import 'locale.dart';
import 'server_text.g.dart';

/// The server's sentence for a field, in the app's language (the server writes English).
String serverText(String msg) {
  final ar = AppLocale.isAr;
  for (final k in [msg, msg.replaceFirst(RegExp(r'\s*[:"“].*$'), '').trim(), msg.replaceFirst(RegExp(r'\s+\S+$'), '').trim()]) {
    final t = kServerText[k];
    if (t != null) return t[ar ? 0 : 1];
  }
  return msg;
}

/// Field-level errors of a form: each message shows under its own field and the screen scrolls to the first one.
/// Give each field `key: fe.key('name')` and `errorText: fe.of('name')`. Paths are the server's ("variants[2].sku", "config.percent").
class FieldErrors extends ChangeNotifier {
  Map<String, String> errors = {};
  final Map<String, GlobalKey> _keys = {};

  GlobalKey key(String path) => _keys.putIfAbsent(path, () => GlobalKey(debugLabel: path));
  String? of(String path) => errors[path];
  /// Any error for this field or below it (a whole row: "variants[2]").
  String? under(String prefix) {
    for (final e in errors.entries) {
      if (e.key == prefix || e.key.startsWith('$prefix.') || e.key.startsWith('$prefix[')) return e.value;
    }
    return null;
  }
  bool get any => errors.isNotEmpty;

  void clear([String? path]) {
    if (path == null) { if (errors.isEmpty) return; errors = {}; } else if (errors.remove(path) == null) { return; }
    notifyListeners();
  }

  /// The form's own checks: {field: message or null}. True when everything is fine.
  bool check(Map<String, String?> local) {
    errors = {for (final e in local.entries) if (e.value != null) e.key: e.value!};
    notifyListeners();
    _scroll();
    return errors.isEmpty;
  }

  /// Takes a failed call's per-field errors. True when there were some (so the caller shows "fix the marked fields").
  bool take(Object e) {
    if (e is! ApiException || e.fields.isEmpty) return false;
    errors = {for (final f in e.fields.entries) f.key: serverText('${f.value}')};
    notifyListeners();
    _scroll();
    return true;
  }

  void _scroll() {
    if (errors.isEmpty) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      for (final path in errors.keys) {
        final candidates = [path, path.replaceFirst(RegExp(r'\..*$'), ''), path.replaceFirst(RegExp(r'\[.*$'), '')];
        for (final c in candidates) {
          final ctx = _keys[c]?.currentContext;
          if (ctx != null) {
            Scrollable.ensureVisible(ctx, duration: const Duration(milliseconds: 450), curve: Curves.easeOutCubic, alignment: 0.25);
            return;
          }
        }
      }
    });
  }
}
