import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import 'api_client.dart';

/// A change made while there was no internet, waiting to be sent.
class OfflineOp {
  OfflineOp({required this.id, required this.method, required this.path, required this.body, required this.idem, required this.tempId, required this.label, required this.state, required this.error, required this.created});
  final int id;
  final String method, path, idem, state;
  final Object? body;
  final String? tempId, label, error;
  final int created;
}

/// How one kind of write behaves offline: [apply] updates the cached screens at once (optimistic) and returns the answer the screen expects.
class OfflineHandler {
  OfflineHandler(this.method, this.path, this.label, this.apply);
  final String method;
  final RegExp path;
  final String label;
  final Future<OfflineResult> Function(OfflineStore store, RegExpMatch match, Object? body) apply;
}

class OfflineResult {
  OfflineResult(this.response, {this.tempId});
  final Object? response;
  /// Set when the action created something: later queued actions that mention this id are rewritten to the real id once it is known.
  final String? tempId;
}

/// Local database for one business: the last answer of every read (so the app opens instantly and works without internet) and the queue of
/// changes made offline, sent in order, once, when the connection returns (every request carries an Idempotency-Key the server replays safely).
class OfflineStore {
  OfflineStore(this.scope) {
    _sub = Connectivity().onConnectivityChanged.listen((r) {
      final none = r.isEmpty || r.every((x) => x == ConnectivityResult.none);
      if (none) { online.value = false; } else { _lastFail = null; unawaited(flush()); }
    });
  }

  final String scope;
  final List<OfflineHandler> handlers = [];
  final ValueNotifier<bool> online = ValueNotifier(true);
  final ValueNotifier<int> pending = ValueNotifier(0);
  final ValueNotifier<int> failed = ValueNotifier(0);
  final ValueNotifier<bool> syncing = ValueNotifier(false);
  /// Bumps after queued changes were sent, so screens reload the real data.
  final ValueNotifier<int> synced = ValueNotifier(0);
  /// Answers a read that has no stored copy (e.g. a search over the patients saved on this phone).
  Future<Object?> Function(String path, Map<String, String> query)? fallback;
  ApiClient? api;

  static Database? _db;
  static final Map<String, String> _hot = {};
  late final StreamSubscription<List<ConnectivityResult>> _sub;
  DateTime? _lastFail;
  static const Object miss = Object();

  void dispose() { _sub.cancel(); }

  static Future<Database> _open() async {
    return _db ??= await openDatabase(p.join(await getDatabasesPath(), 'offline_v1.db'), version: 1, onCreate: (db, v) async {
      await db.execute('CREATE TABLE kv (scope TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, t INTEGER NOT NULL, PRIMARY KEY (scope, k))');
      await db.execute('CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, method TEXT NOT NULL, path TEXT NOT NULL, body TEXT, idem TEXT NOT NULL, temp_id TEXT, label TEXT, state TEXT NOT NULL, error TEXT, created INTEGER NOT NULL)');
    });
  }

  /// Wipes everything saved on this phone (sign out).
  static Future<void> clearAll() async {
    _hot.clear();
    try { final d = await _open(); await d.delete('kv'); await d.delete('outbox'); } catch (_) {}
  }

  String key(String path, [Map<String, String?>? query]) {
    final q = <String, String>{};
    query?.forEach((k, v) { if (v != null && v.isNotEmpty) q[k] = v; });
    final keys = q.keys.toList()..sort();
    return keys.isEmpty ? path : '$path?${keys.map((k) => '$k=${q[k]}').join('&')}';
  }

  // ---------------------------------------------------------------- stored reads

  Future<Object?> read(String k) async {
    final hot = _hot['$scope|$k'];
    if (hot != null) return jsonDecode(hot);
    try {
      final rows = await (await _open()).query('kv', columns: ['v'], where: 'scope = ? AND k = ?', whereArgs: [scope, k], limit: 1);
      if (rows.isEmpty) return miss;
      _hot['$scope|$k'] = rows.first['v'] as String;
      return jsonDecode(rows.first['v'] as String);
    } catch (_) {
      return miss;
    }
  }

  Future<void> put(String k, Object? data) async {
    final s = jsonEncode(data);
    if (_hot['$scope|$k'] == s) return;
    _hot['$scope|$k'] = s;
    try { await (await _open()).insert('kv', {'scope': scope, 'k': k, 'v': s, 't': DateTime.now().millisecondsSinceEpoch}, conflictAlgorithm: ConflictAlgorithm.replace); } catch (_) {}
  }

  /// Changes every stored read whose key starts with [prefix] (an optimistic update of the screens that show that data).
  Future<void> patchWhere(String prefix, Object? Function(Object? data, String key) fn) async {
    try {
      final rows = await (await _open()).query('kv', columns: ['k', 'v'], where: 'scope = ? AND k LIKE ?', whereArgs: [scope, '${prefix.replaceAll('%', '')}%']);
      for (final r in rows) {
        final k = r['k'] as String;
        final next = fn(jsonDecode(r['v'] as String), k);
        await put(k, next);
      }
    } catch (_) {}
  }

  // ---------------------------------------------------------------- connectivity

  /// True when there is certainly no connection to try: the phone says so, or a request failed a moment ago.
  Future<bool> isOffline() async {
    final r = await Connectivity().checkConnectivity();
    if (r.isEmpty || r.every((x) => x == ConnectivityResult.none)) { online.value = false; return true; }
    final f = _lastFail;
    return f != null && DateTime.now().difference(f) < const Duration(seconds: 8);
  }

  void markOffline() { _lastFail = DateTime.now(); online.value = false; }
  void markOnline() { _lastFail = null; if (!online.value) online.value = true; }

  // ---------------------------------------------------------------- writes made offline

  OfflineHandler? handlerFor(String method, String path) {
    for (final h in handlers) { if (h.method == method && h.path.hasMatch(path)) return h; }
    return null;
  }

  static String newIdem() {
    final r = Random.secure();
    return 'off-${DateTime.now().microsecondsSinceEpoch}-${List.generate(6, (_) => r.nextInt(36).toRadixString(36)).join()}';
  }

  static String tempId() => 'tmp-${newIdem().substring(4)}';

  Future<Object?> queueWrite(String method, String path, Object? body, String idem) async {
    final h = handlerFor(method, path)!;
    final res = await h.apply(this, h.path.firstMatch(path)!, body);
    final d = await _open();
    await d.insert('outbox', {'scope': scope, 'method': method, 'path': path, 'body': body == null ? null : jsonEncode(body), 'idem': idem, 'temp_id': res.tempId, 'label': h.label, 'state': 'pending', 'created': DateTime.now().millisecondsSinceEpoch});
    await refreshCounts();
    if (!await isOffline()) unawaited(flush());   // already back online: send it right away
    return res.response;
  }

  Future<List<OfflineOp>> ops() async {
    final rows = await (await _open()).query('outbox', where: 'scope = ?', whereArgs: [scope], orderBy: 'id');
    return [for (final r in rows) OfflineOp(id: r['id'] as int, method: r['method'] as String, path: r['path'] as String, body: r['body'] == null ? null : jsonDecode(r['body'] as String), idem: r['idem'] as String, tempId: r['temp_id'] as String?, label: r['label'] as String?, state: r['state'] as String, error: r['error'] as String?, created: r['created'] as int)];
  }

  Future<void> refreshCounts() async {
    try {
      final d = await _open();
      pending.value = Sqflite.firstIntValue(await d.rawQuery("SELECT count(*) FROM outbox WHERE scope = ? AND state = 'pending'", [scope])) ?? 0;
      failed.value = Sqflite.firstIntValue(await d.rawQuery("SELECT count(*) FROM outbox WHERE scope = ? AND state = 'failed'", [scope])) ?? 0;
    } catch (_) {}
  }

  Future<void> discard(int id) async { await (await _open()).delete('outbox', where: 'id = ?', whereArgs: [id]); await refreshCounts(); synced.value++; }

  Future<void> retry(int id) async { await (await _open()).update('outbox', {'state': 'pending', 'error': null}, where: 'id = ?', whereArgs: [id]); await refreshCounts(); unawaited(flush()); }

  /// Sends the queued changes in the order they were made. Stops at the first connection problem; a change the server refuses is kept as "failed".
  Future<void> flush() async {
    final client = api;
    if (client == null || syncing.value) return;
    syncing.value = true;
    var changed = false;
    try {
      await refreshCounts();
      while (true) {
        final all = await ops();
        final next = all.where((o) => o.state == 'pending').toList();
        if (next.isEmpty) break;
        final op = next.first;
        try {
          final r = await client.sendRaw(op.method, op.path, op.body, op.idem);
          if (op.tempId != null && r is Map && r['id'] != null) await _substitute(op.tempId!, '${r['id']}');
          await (await _open()).delete('outbox', where: 'id = ?', whereArgs: [op.id]);
          changed = true;
          markOnline();
        } on ApiException catch (e) {
          if (e.code == 'NETWORK') { markOffline(); break; }
          await (await _open()).update('outbox', {'state': 'failed', 'error': e.message}, where: 'id = ?', whereArgs: [op.id]);
        }
        await refreshCounts();
      }
    } finally {
      syncing.value = false;
      await refreshCounts();
      if (changed) synced.value++;
    }
  }

  Future<void> _substitute(String temp, String real) async {
    final d = await _open();
    await d.rawUpdate("UPDATE outbox SET path = replace(path, ?, ?), body = replace(body, ?, ?) WHERE scope = ? AND state = 'pending'", [temp, real, temp, real, scope]);
  }
}
