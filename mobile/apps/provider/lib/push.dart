import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import 'common.dart';

/// The data of a push the user tapped (e.g. {type: LAB_RESULT_FROM_PATIENT, patientId, labOrderId}); the signed-in screen opens the right patient.
final ValueNotifier<Map<String, dynamic>?> pushOpened = ValueNotifier<Map<String, dynamic>?>(null);

/// Firebase push for staff phones: "a patient uploaded a test result", new booking requests... Needs android/app/google-services.json for
/// com.elmanassa.business; without it everything below quietly does nothing and the in-app notification list still works.
class PushService {
  static bool _listening = false;
  static bool registered = false;

  static Future<void> ensure(Future<void> Function(String token) register) async {
    try {
      if (Firebase.apps.isEmpty) await Firebase.initializeApp();
      final m = FirebaseMessaging.instance;
      await m.requestPermission();
      final token = await m.getToken();
      if (token != null) { await register(token); registered = true; }
      if (!_listening) {
        _listening = true;
        m.onTokenRefresh.listen((t) { register(t).catchError((_) {}); });
        FirebaseMessaging.onMessage.listen((msg) {
          final n = msg.notification;
          if (n != null && navigatorKey.currentContext != null) toast(navigatorKey.currentContext!, '${n.title ?? ''}\n${n.body ?? ''}'.trim());
        });
        FirebaseMessaging.onMessageOpenedApp.listen((msg) => pushOpened.value = Map<String, dynamic>.from(msg.data));
        final first = await m.getInitialMessage();
        if (first != null) pushOpened.value = Map<String, dynamic>.from(first.data);
      }
    } catch (_) {
      registered = false;   // not configured or offline: tried again next time
    }
  }

  static Future<String?> token() async {
    try {
      return await FirebaseMessaging.instance.getToken();
    } catch (_) {
      return null;
    }
  }
}
