import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';

/// Messenger used to show a banner when a push arrives while the app is open (the system shows it when the app is closed).
final GlobalKey<ScaffoldMessengerState> messengerKey = GlobalKey<ScaffoldMessengerState>();

/// Firebase Cloud Messaging for "it's your turn". Safe before Firebase is set up: without google-services.json
/// initialisation fails quietly and the queue screen simply keeps refreshing itself.
class PushService {
  static bool _started = false;

  /// [register] sends the phone's token to the clinic server so the doctor's "call" reaches this phone.
  static Future<void> start(Future<void> Function(String token) register) async {
    if (_started) return;
    try {
      await Firebase.initializeApp();
      final m = FirebaseMessaging.instance;
      await m.requestPermission();
      final token = await m.getToken();
      if (token != null) await register(token);
      m.onTokenRefresh.listen((t) => register(t));
      FirebaseMessaging.onMessage.listen((msg) {
        final n = msg.notification;
        if (n == null) return;
        messengerKey.currentState?.showSnackBar(SnackBar(
          duration: const Duration(seconds: 10),
          content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(n.title ?? '', style: const TextStyle(fontWeight: FontWeight.w700)),
            if ((n.body ?? '').isNotEmpty) Text(n.body!),
          ]),
        ));
      });
      _started = true;
    } catch (_) {
      // Firebase is not configured for this build yet
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
