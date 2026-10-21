import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:platform_core/platform_core.dart' show AppLocale;

import 'core/state.dart';
import 'core/strings.dart';
import 'core/theme.dart';
import 'screens/shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await AppLocale.load();
  await AppState.I.load();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(statusBarColor: Colors.transparent, statusBarIconBrightness: Brightness.dark));
  runApp(const OpenApp());
}

class OpenApp extends StatelessWidget {
  const OpenApp({super.key});

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<Locale>(
        valueListenable: AppLocale.notifier,
        builder: (_, locale, __) => MaterialApp(
          title: tr('open.brand'),
          debugShowCheckedModeBanner: false,
          locale: locale,
          supportedLocales: const [Locale('ar'), Locale('en')],
          localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
          theme: openTheme(),
          home: const Shell(),
        ),
      );
}
