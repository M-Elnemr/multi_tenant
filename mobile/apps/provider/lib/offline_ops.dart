import 'dart:async';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

// What the clinic can do without internet. Every action updates the saved screens at once (optimistic) and is sent later, in order, once.
// Patients are saved under 'warm:patients' (the whole list, so search and details work offline).

const _warmPatients = 'warm:patients';

Map<String, dynamic> _m(Object? v) => Map<String, dynamic>.from(v as Map);
List<Map<String, dynamic>> _l(Object? v) => ((v as List?) ?? const []).map(_m).toList();
bool _ar() => AppLocale.isAr;

Future<List<Map<String, dynamic>>> _patients(OfflineStore s) async {
  final v = await s.read(_warmPatients);
  return v == OfflineStore.miss ? <Map<String, dynamic>>[] : _l(v);
}

Future<Map<String, dynamic>?> _patient(OfflineStore s, Object? id) async {
  for (final p in await _patients(s)) {
    if (p['id'] == id) return p;
    for (final k in _l(p['dependents'])) { if (k['id'] == id) return k; }
  }
  return null;
}

String _nameOf(Map<String, dynamic>? p) => p == null ? '' : '${p['firstName'] ?? ''} ${p['lastName'] ?? ''}'.trim();

Map<String, dynamic> _queueOf(Object? d) {
  final q = _m(d);
  return {'inProgress': _l(q['inProgress']), 'waiting': _l(q['waiting']), 'reserved': _l(q['reserved'])};
}

int _nextNumber(Map<String, dynamic> q, Object? doctorId) {
  var n = 0;
  for (final e in [..._l(q['waiting']), ..._l(q['inProgress'])]) {
    if (doctorId != null && e['doctorId'] != doctorId) continue;
    final x = (e['queueNumber'] as num?)?.toInt() ?? 0;
    if (x > n) n = x;
  }
  return n + 1;
}

Future<void> _patchQueue(OfflineStore s, Map<String, dynamic> Function(Map<String, dynamic> q) fn) =>
    s.patchWhere('clinic/queue', (d, k) => d is Map ? fn(_queueOf(d)) : d);

Map<String, dynamic>? _take(List<Map<String, dynamic>> list, String id) {
  final i = list.indexWhere((e) => e['id'] == id);
  return i < 0 ? null : list.removeAt(i);
}

/// Registers every offline-capable action on the clinic's store.
void registerClinicOffline(OfflineStore store) {
  store.fallback = _fallback(store);

  void action(String name, String ar, String en, Map<String, dynamic> Function(Map<String, dynamic> q, String id) change) {
    store.handlers.add(OfflineHandler('POST', RegExp('^clinic/appointments/([^/]+)/$name\$'), _ar() ? ar : en, (s, m, body) async {
      final id = m.group(1)!;
      await _patchQueue(s, (q) => change(q, id));
      return OfflineResult(<String, dynamic>{'id': id});
    }));
  }

  action('call', 'نداء مريض', 'Call patient', (q, id) {
    return q..['waiting'] = [for (final e in _l(q['waiting'])) e['id'] == id ? {...e, 'calledAt': DateTime.now().toIso8601String()} : e];
  });
  action('start', 'بدء الكشف', 'Start visit', (q, id) {
    final waiting = _l(q['waiting']);
    final e = _take(waiting, id);
    if (e == null) return q;
    return q..['waiting'] = waiting..['inProgress'] = [..._l(q['inProgress']), {...e, 'status': 'IN_PROGRESS'}];
  });
  action('complete', 'إنهاء الكشف', 'Finish visit', (q, id) {
    final inProgress = _l(q['inProgress']);
    _take(inProgress, id);
    return q..['inProgress'] = inProgress;
  });
  action('no-show', 'لم يحضر', 'No show', (q, id) {
    final waiting = _l(q['waiting']);
    _take(waiting, id);
    return q..['waiting'] = waiting;
  });
  action('remove-from-queue', 'إخراج من الطابور', 'Remove from queue', (q, id) {
    final waiting = _l(q['waiting']);
    final e = _take(waiting, id);
    q['waiting'] = waiting;
    if (e != null && e['isWalkIn'] != true) {
      q['reserved'] = [..._l(q['reserved']), {...e, 'status': 'CONFIRMED', 'queueNumber': null, 'calledAt': null}];
    }
    return q;
  });
  action('check-in', 'إضافة من المحجوزين للطابور', 'Add reserved patient to queue', (q, id) {
    final reserved = _l(q['reserved']);
    final e = _take(reserved, id);
    if (e == null) return q;
    final n = _nextNumber(q, e['doctorId']);
    return q..['reserved'] = reserved..['waiting'] = [..._l(q['waiting']), {...e, 'status': 'CHECKED_IN', 'queueNumber': n, 'waitedMinutes': 0, 'checkedInAt': DateTime.now().toIso8601String()}];
  });

  store.handlers.add(OfflineHandler('POST', RegExp(r'^clinic/queue/reorder$'), _ar() ? 'ترتيب الطابور' : 'Reorder queue', (s, m, body) async {
    final ids = ((body as Map)['ids'] as List).cast<String>();
    await _patchQueue(s, (q) {
      final waiting = _l(q['waiting']);
      final byId = {for (final e in waiting) e['id'] as String: e};
      final numbers = waiting.map((e) => (e['queueNumber'] as num?)?.toInt() ?? 0).toList()..sort();
      final ordered = <Map<String, dynamic>>[];
      for (final id in ids) { final e = byId.remove(id); if (e != null) ordered.add(e); }
      ordered.addAll(byId.values);
      for (var i = 0; i < ordered.length && i < numbers.length; i++) { ordered[i] = {...ordered[i], 'queueNumber': numbers[i]}; }
      return q..['waiting'] = ordered;
    });
    return OfflineResult(<String, dynamic>{'reordered': ids.length});
  }));

  store.handlers.add(OfflineHandler('PATCH', RegExp(r'^clinic/appointments/([^/]+)/visit-type$'), _ar() ? 'نوع الزيارة' : 'Visit type', (s, m, body) async {
    final id = m.group(1)!, vt = (body as Map)['visitType'];
    await _patchQueue(s, (q) {
      for (final k in ['waiting', 'inProgress', 'reserved']) { q[k] = [for (final e in _l(q[k])) e['id'] == id ? {...e, 'visitType': vt} : e]; }
      return q;
    });
    return OfflineResult(<String, dynamic>{'id': id, 'visitType': vt});
  }));

  store.handlers.add(OfflineHandler('POST', RegExp(r'^clinic/appointments/walk-in$'), _ar() ? 'إضافة للطابور' : 'Add to queue', (s, m, body) async {
    final b = _m(body);
    final patient = await _patient(s, b['patientId']);
    final docsRaw = await s.read('clinic/doctors');
    final doctor = docsRaw == OfflineStore.miss ? null : _l(docsRaw).cast<Map<String, dynamic>?>().firstWhere((d) => d!['id'] == b['doctorId'], orElse: () => null);
    final id = OfflineStore.tempId();
    var number = 1;
    await _patchQueue(s, (q) {
      number = _nextNumber(q, b['doctorId']);
      final item = {
        'id': id, 'status': 'CHECKED_IN', 'queueNumber': number, 'checkedInAt': DateTime.now().toIso8601String(), 'calledAt': null, 'waitedMinutes': 0,
        'doctorId': b['doctorId'], 'doctorName': doctor?['displayName'] ?? '', 'serviceName': b['visitType'] == 'FOLLOW_UP' ? (_ar() ? 'إعادة' : 'Follow-up') : (_ar() ? 'كشف' : 'Consultation'),
        'visitType': b['visitType'], 'patientId': b['patientId'], 'patientName': _nameOf(patient), 'patientCode': patient?['patientCode'], 'patientPhone': patient?['phone'],
        'patientAgeYears': patient?['ageYears'], 'isWalkIn': true,
      };
      return q..['waiting'] = [..._l(q['waiting']), item];
    });
    return OfflineResult(<String, dynamic>{'id': id, 'status': 'CHECKED_IN', 'queueNumber': number}, tempId: id);
  }));

  store.handlers.add(OfflineHandler('POST', RegExp(r'^clinic/patients$'), _ar() ? 'مريض جديد' : 'New patient', (s, m, body) async {
    final b = _m(body);
    final id = OfflineStore.tempId();
    final guardianId = b['guardianPatientId'];
    final guardian = guardianId == null ? null : await _patient(s, guardianId);
    final patient = <String, dynamic>{
      'id': id, 'patientCode': _ar() ? 'جديد' : 'NEW', 'firstName': b['firstName'], 'lastName': '', 'phone': b['phone'], 'ageYears': b['ageYears'], 'ageMonths': b['ageMonths'],
      'sex': b['sex'], 'hasPortal': (b['initialPassword'] ?? '') != '', 'createdAt': DateTime.now().toIso8601String(), 'dependents': <Map<String, dynamic>>[],
      if (guardian != null) 'guardianName': _nameOf(guardian),
    };
    final all = await _patients(s);
    if (guardianId != null) {
      for (var i = 0; i < all.length; i++) {
        if (all[i]['id'] == guardianId) all[i] = {...all[i], 'dependents': [..._l(all[i]['dependents']), patient]};
      }
    } else {
      all.insert(0, patient);
    }
    await s.put(_warmPatients, all);
    // the first page of the plain list (no search) shows it too
    await s.patchWhere('clinic/patients?', (d, k) {
      if (d is! Map || k.contains('q=') || !k.contains('page=1&') || d['data'] is! List) return d;
      final rows = _l(d['data']);
      if (guardianId != null) {
        return {...d, 'data': [for (final r in rows) r['id'] == guardianId ? {...r, 'dependents': [..._l(r['dependents']), patient]} : r]};
      }
      return {...d, 'data': [patient, ...rows]};
    });
    return OfflineResult(patient, tempId: id);
  }));

  store.handlers.add(OfflineHandler('PATCH', RegExp(r'^clinic/patients/([^/]+)$'), _ar() ? 'تعديل مريض' : 'Edit patient', (s, m, body) async {
    final id = m.group(1)!, b = _m(body);
    Map<String, dynamic> apply(Map<String, dynamic> p) => p['id'] == id ? {...p, ...b} : p;
    await s.put(_warmPatients, [for (final p in await _patients(s)) {...apply(p), 'dependents': [for (final k in _l(p['dependents'])) apply(k)]}]);
    await s.patchWhere('clinic/patients?', (d, k) => d is Map && d['data'] is List ? {...d, 'data': [for (final p in _l(d['data'])) {...apply(p), 'dependents': [for (final k in _l(p['dependents'])) apply(k)]}]} : d);
    await s.patchWhere('clinic/patients/$id', (d, k) => d is Map && k == 'clinic/patients/$id' ? {...d, ...b} : d);
    return OfflineResult(<String, dynamic>{'id': id});
  }));

  store.handlers.add(OfflineHandler('POST', RegExp(r'^clinic/patients/([^/]+)/set-password$'), _ar() ? 'كلمة مرور المريض' : 'Patient password', (s, m, body) async => OfflineResult(<String, dynamic>{})));
}

/// Answers a read that has no saved copy from the patients saved on this phone.
Future<Object?> Function(String, Map<String, String>) _fallback(OfflineStore s) => (path, query) async {
      if (path == 'clinic/patients') {
        final q = (query['q'] ?? '').toLowerCase().trim();
        final page = int.tryParse(query['page'] ?? '1') ?? 1, size = int.tryParse(query['pageSize'] ?? '20') ?? 20;
        final all = await _patients(s);
        if (all.isEmpty) return null;
        bool hit(Map<String, dynamic> p) => '${p['firstName']} ${p['lastName'] ?? ''} ${p['phone'] ?? ''} ${p['patientCode'] ?? ''}'.toLowerCase().contains(q);
        final found = q.isEmpty ? all : all.where((p) => hit(p) || _l(p['dependents']).any(hit)).toList();
        final from = (page - 1) * size;
        final slice = from >= found.length ? <Map<String, dynamic>>[] : found.sublist(from, (from + size).clamp(0, found.length));
        return {'data': slice, 'meta': {'page': page, 'pageSize': size, 'total': found.length, 'hasNext': from + size < found.length}};
      }
      final detail = RegExp(r'^clinic/patients/([^/]+)$').firstMatch(path);
      if (detail != null) return await _patient(s, detail.group(1));
      if (RegExp(r'^clinic/patients/[^/]+/timeline$').hasMatch(path)) return {'visits': [], 'prescriptions': [], 'labOrders': [], 'appointments': []};
      return null;
    };

// ---------------------------------------------------------------- keeping the phone's copy fresh

DateTime? _lastWarm;

/// Saves what the doctor needs to work without internet: the queue, the whole patient list, the details of the patients in line and the most recent ones.
Future<void> warmUp(Ctx ctx, {bool force = false}) async {
  final off = ctx.api.offline;
  if (off == null || ctx.type != 'CLINIC') return;
  if (!force && _lastWarm != null && DateTime.now().difference(_lastWarm!) < const Duration(minutes: 4)) return;
  if (await off.isOffline() || off.pending.value > 0 || off.syncing.value) return;
  _lastWarm = DateTime.now();
  try {
    if (ctx.canAny(['appointment.manage', 'appointment.read'])) await ctx.api.get('clinic/queue');
    for (final p in ['clinic/doctors', 'clinic/branches', 'clinic/services']) { try { await ctx.api.get(p); } catch (_) {} }
    if (!ctx.can('patient.read')) return;
    final all = <Map<String, dynamic>>[];
    for (var page = 1; page <= 60; page++) {
      final r = await ctx.api.get('clinic/patients', query: {'page': '$page', 'pageSize': '100'}) as Map;
      all.addAll(_l(r['data']));
      if ((r['meta'] as Map?)?['hasNext'] != true) break;
    }
    await off.put(_warmPatients, all);
    // details of the people in line first, then the newest patients
    final ids = <String>{};
    final q = await ctx.api.peek('clinic/queue');
    if (q is Map) { for (final k in ['inProgress', 'waiting', 'reserved']) { for (final e in _l(q[k])) { if (e['patientId'] != null) ids.add('${e['patientId']}'); } } }
    for (final p in all.take(150)) { ids.add('${p['id']}'); for (final k in _l(p['dependents'])) { ids.add('${k['id']}'); } }
    final clinical = ctx.canAny(['medical_note.create', 'prescription.create', 'lab_order.create', 'medical_note.read', 'prescription.read', 'lab_order.read']);
    final list = ids.toList();
    for (var i = 0; i < list.length; i += 4) {
      await Future.wait([
        for (final id in list.skip(i).take(4)) () async {
          try {
            await ctx.api.get('clinic/patients/$id');
            if (clinical) await ctx.api.get('clinic/patients/$id/timeline');
          } catch (_) {}
        }(),
      ]);
      if (await off.isOffline()) break;
    }
  } catch (_) {}
}

// ---------------------------------------------------------------- the status bar

/// A thin strip under the app bar: offline, syncing, or changes the server refused.
class OfflineBanner extends StatelessWidget {
  const OfflineBanner({super.key, required this.store});
  final OfflineStore store;

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return ListenableBuilder(
      listenable: Listenable.merge([store.online, store.pending, store.failed, store.syncing]),
      builder: (context, _) {
        final offline = !store.online.value, n = store.pending.value, bad = store.failed.value, syncing = store.syncing.value;
        String? text;
        Color color = const Color(0xFFD98200);
        IconData icon = Icons.cloud_off_rounded;
        if (bad > 0) { text = ar ? '$bad تغييرات لم تُرسل — اضغط للمراجعة' : '$bad change(s) were not sent — tap to review'; color = const Color(0xFFD14343); icon = Icons.error_outline_rounded; }
        else if (offline) { text = ar ? 'لا يوجد اتصال — تُحفظ تغييراتك وتُرسل تلقائيًا${n > 0 ? ' ($n)' : ''}' : 'Offline — your changes are saved and sent automatically${n > 0 ? ' ($n)' : ''}'; }
        else if (n > 0 || syncing) { text = ar ? 'جارٍ مزامنة $n تغيير...' : 'Syncing $n change(s)...'; color = const Color(0xFF1F6A99); icon = Icons.sync_rounded; }
        return AnimatedSize(
          duration: const Duration(milliseconds: 260),
          curve: Curves.easeOutCubic,
          child: text == null
              ? const SizedBox(width: double.infinity)
              : Material(
                  color: color,
                  child: InkWell(
                    onTap: () => showSyncIssues(context, store),
                    child: Padding(padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7), child: Row(children: [
                      Icon(icon, color: Colors.white, size: 18),
                      const SizedBox(width: 8),
                      Expanded(child: Text(text, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 13))),
                    ])),
                  ),
                ),
        );
      },
    );
  }
}

Future<void> showSyncIssues(BuildContext context, OfflineStore store) async {
  final ar = S.of(context).ar;
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (c) => StatefulBuilder(builder: (c, set) => FutureBuilder<List<OfflineOp>>(
      future: store.ops(),
      builder: (c, snap) {
        final ops = snap.data ?? const <OfflineOp>[];
        return SafeArea(child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.of(c).size.height * 0.7),
          child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(16, 0, 16, 16), children: [
            Text(ar ? 'التغييرات المنتظرة' : 'Waiting changes', style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
            const SizedBox(height: 8),
            if (ops.isEmpty) emptyState(ar ? 'كل شيء متزامن' : 'Everything is in sync', icon: Icons.cloud_done_rounded),
            for (final o in ops) Card(child: ListTile(
              leading: Icon(o.state == 'failed' ? Icons.error_outline_rounded : Icons.schedule_rounded, color: o.state == 'failed' ? const Color(0xFFD14343) : const Color(0xFFD98200)),
              title: Text(o.label ?? o.path, style: const TextStyle(fontWeight: FontWeight.w800)),
              subtitle: Text(o.state == 'failed' ? (o.error ?? '') : (ar ? 'بانتظار الإرسال' : 'Waiting to be sent')),
              trailing: o.state == 'failed'
                  ? Row(mainAxisSize: MainAxisSize.min, children: [
                      IconButton(icon: const Icon(Icons.refresh_rounded), onPressed: () async { await store.retry(o.id); set(() {}); }),
                      IconButton(icon: const Icon(Icons.delete_outline_rounded), onPressed: () async { await store.discard(o.id); set(() {}); }),
                    ])
                  : null,
            )),
          ]),
        ));
      },
    )),
  );
}
