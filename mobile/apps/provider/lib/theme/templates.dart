import 'package:flutter/material.dart';

import 'app_template.dart';
import 'clinic_template.dart';
import 'lawyer_template.dart';
import 'shop_template.dart';

export 'app_template.dart';
export 'clinic_template.dart';
export 'lawyer_template.dart';
export 'shop_template.dart';
export 'template_widgets.dart';

/// The template of a business type from the API (STORE, CLINIC, LAWYER).
AppTemplate templateFor(String? type) => switch (type) {
      'CLINIC' => clinicTemplate,
      'LAWYER' => lawyerTemplate,
      _ => shopTemplate,
    };

ThemeData themeOfTemplate(AppTemplate t) => switch (t.kind) {
      TemplateKind.clinic => clinicTheme,
      TemplateKind.lawyer => lawyerTheme,
      TemplateKind.shop => shopTheme,
    };

/// The template of the business that is open right now (null on the sign-in / picker screens). The app's MaterialApp listens to it,
/// so pages pushed on the root navigator (details, sheets, dialogs) get the same look as the panel.
final ValueNotifier<AppTemplate?> activeTemplate = ValueNotifier<AppTemplate?>(null);
