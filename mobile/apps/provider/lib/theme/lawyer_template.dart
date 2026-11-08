import 'package:flutter/material.dart';

import 'app_template.dart';

/// LAWYER: navy and brass on ivory. Classical and authoritative: tight radii, brass hairlines, a navy app bar ruled in brass,
/// a flat navy bottom bar with brass icons and a hero framed by a double brass rule.
const lawyerTemplate = AppTemplate(
  kind: TemplateKind.lawyer,
  ink: Color(0xFF14213D),
  inkSoft: Color(0xFF22325A),
  accent: Color(0xFFB08D57),
  accent2: Color(0xFFD9B97F),
  paper: Color(0xFFFBF8F1),
  card: Color(0xFFFFFDF8),
  line: Color(0xFFE9DFC8),
  muted: Color(0xFF6C6758),
  text: Color(0xFF14213D),
  onInk: Color(0xFFFBF8F1),
  heroGradient: [Color(0xFF14213D), Color(0xFF1D2D52)],
  accentGradient: [Color(0xFFB08D57), Color(0xFFD9B97F)],
  cardRadius: 10,
  controlRadius: 8,
  cardShadow: false,
  cardBorder: true,
  darkAppBar: true,
  headingWeight: FontWeight.w800,
  headingSpacing: 0.4,
  bodyHeight: 1.45,
  gap: 13,
  heroIcon: Icons.balance_rounded,
);

final ThemeData lawyerTheme = lawyerTemplate.buildTheme();
