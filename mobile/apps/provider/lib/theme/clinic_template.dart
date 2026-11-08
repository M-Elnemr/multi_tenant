import 'package:flutter/material.dart';

import 'app_template.dart';

/// CLINIC: calm deep teal with sage. Soft rounded paper cards with a gentle shadow (no hard borders), a light app bar,
/// generous spacing and a white rounded bottom bar with a sage pill on the selected tab.
const clinicTemplate = AppTemplate(
  kind: TemplateKind.clinic,
  ink: Color(0xFF0F3D3A),
  inkSoft: Color(0xFF1C5551),
  accent: Color(0xFFC4E0CF),
  accent2: Color(0xFF7FB89A),
  paper: Color(0xFFF3F7F4),
  card: Color(0xFFFFFFFF),
  line: Color(0xFFDCE8E1),
  muted: Color(0xFF5D736E),
  text: Color(0xFF14302D),
  onInk: Color(0xFFF1F8F4),
  heroGradient: [Color(0xFFC4E0CF), Color(0xFFE4F1E9)],
  accentGradient: [Color(0xFF0F3D3A), Color(0xFF2E6B63)],
  cardRadius: 26,
  controlRadius: 18,
  cardShadow: true,
  cardBorder: false,
  darkAppBar: false,
  headingWeight: FontWeight.w800,
  headingSpacing: 0,
  bodyHeight: 1.5,
  gap: 16,
  heroIcon: Icons.spa_rounded,
);

final ThemeData clinicTheme = clinicTemplate.buildTheme();
