import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';

/// Messenger used to show a banner when a push arrives while the app is open (the system shows it when the app is closed).
final GlobalKey<ScaffoldMessengerState> messengerKey = GlobalKey<ScaffoldMessengerState>();

/// Bumped whenever a queue push arrives while the app is open, so the queue screen refreshes itself at once.
final ValueNotifier<int> queuePing = ValueNotifier<int>(0);

/// Firebase Cloud Messaging: "it's your turn" and "N patients before you". Registration is retried until it works.
class PushService {
  static bool _listening = false;
  static bool registered = false;
  /// null = unknown, false = the user turned notifications off for this app.
  static final ValueNotifier<bool?> allowed = ValueNotifier<bool?>(null);

  /// [register] sends the phone's token to the server (under the patient's account) so the clinic's calls reach this phone.
  /// Safe to call again and again: it only does the work that is still missing.
  static Future<void> ensure(Future<void> Function(String token) register) async {
    try {
      if (Firebase.apps.isEmpty) await Firebase.initializeApp();
      final m = FirebaseMessaging.instance;
      final settings = await m.requestPermission();
      allowed.value = settings.authorizationStatus == AuthorizationStatus.authorized || settings.authorizationStatus == AuthorizationStatus.provisional;
      final token = await m.getToken();
      if (token != null) { await register(token); registered = true; }
      if (!_listening) {
        _listening = true;
        m.onTokenRefresh.listen((t) { register(t).catchError((_) {}); });
        FirebaseMessaging.onMessage.listen((msg) {
          final type = msg.data['type'] as String?;
          if (type == 'QUEUE_CALLED' || type == 'QUEUE_POSITION') queuePing.value++;
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
        FirebaseMessaging.onMessageOpenedApp.listen((_) => queuePing.value++);
      }
    } catch (_) {
      registered = false;   // tried again on the next app start / screen build
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
