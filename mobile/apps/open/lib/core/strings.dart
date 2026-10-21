import 'package:platform_core/platform_core.dart' show AppLocale;

import 'strings.g.dart';

/// Translate [key] into the current language ({name} placeholders are filled from [vars]); a missing key shows the key itself.
String tr(String key, [Map<String, Object?>? vars]) {
  final e = kStrings[key];
  var s = e == null ? key : (AppLocale.isAr ? e[1] : e[0]);
  vars?.forEach((k, v) => s = s.replaceAll('{$k}', '$v'));
  return s;
}
