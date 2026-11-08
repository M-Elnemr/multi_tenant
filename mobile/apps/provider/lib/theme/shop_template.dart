import 'package:flutter/material.dart';

import 'app_template.dart';

/// SHOP: deep ink with violet → cyan "AI" accents. Modern and energetic: compact density, crisp hairline cards, a dark app bar,
/// a floating ink capsule as the bottom bar and a glowing hero on the dashboard.
const shopTemplate = AppTemplate(
  kind: TemplateKind.shop,
  ink: Color(0xFF0B0D12),
  inkSoft: Color(0xFF171A24),
  accent: Color(0xFF7C5CFF),
  accent2: Color(0xFF22D3EE),
  paper: Color(0xFFF4F5FA),
  card: Color(0xFFFFFFFF),
  line: Color(0xFFE4E6F0),
  muted: Color(0xFF666B80),
  text: Color(0xFF0B0D12),
  onInk: Color(0xFFF5F6FF),
  heroGradient: [Color(0xFF0B0D12), Color(0xFF1A1533), Color(0xFF0E2A36)],
  accentGradient: [Color(0xFF7C5CFF), Color(0xFF22D3EE)],
  cardRadius: 18,
  controlRadius: 14,
  cardShadow: false,
  cardBorder: true,
  darkAppBar: true,
  headingWeight: FontWeight.w900,
  headingSpacing: -0.2,
  bodyHeight: 1.35,
  gap: 12,
  heroIcon: Icons.auto_awesome_rounded,
);

final ThemeData shopTheme = shopTemplate.buildTheme();
