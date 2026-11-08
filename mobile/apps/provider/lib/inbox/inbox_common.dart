import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import '../common.dart';
import '../theme/templates.dart';

/// Shared bits of the shop inbox: words (Arabic / English), channel and status looks, avatar with channel badge, small helpers.
bool get inboxAr => AppLocale.isAr;
String L(String ar, String en) => inboxAr ? ar : en;

List<Map<String, dynamic>> maps(Object? v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

const kStatuses = ['NEEDS_REPLY', 'WAITING', 'UNHAPPY', 'TAKEOVER', 'OPEN', 'CLOSED'];
const kChannels = ['WEB', 'WHATSAPP', 'FACEBOOK', 'INSTAGRAM'];

String statusText(String s) => switch (s) {
      'NEEDS_REPLY' => L('محتاج رد', 'Needs reply'),
      'WAITING' => L('مستنية العميل', 'Waiting for customer'),
      'UNHAPPY' => L('عميل متضايق', 'Unhappy customer'),
      'TAKEOVER' => L('مع فريقك', 'With your team'),
      'OPEN' => L('مفتوحة', 'Open'),
      'CLOSED' => L('مقفولة', 'Closed'),
      _ => s,
    };

Color statusColor(String s) => switch (s) {
      'NEEDS_REPLY' => const Color(0xFFD64545),
      'WAITING' => const Color(0xFFC77700),
      'UNHAPPY' => const Color(0xFFC2185B),
      'TAKEOVER' => const Color(0xFF6D4AE0),
      'CLOSED' => const Color(0xFF7A8294),
      _ => const Color(0xFF1E9E5A),
    };

String channelText(Object? c) => switch ('$c') {
      'WHATSAPP' => L('واتساب', 'WhatsApp'),
      'FACEBOOK' => L('ماسنجر', 'Messenger'),
      'INSTAGRAM' => L('إنستجرام', 'Instagram'),
      _ => L('شات الموقع', 'Website chat'),
    };

Color channelColor(Object? c) => switch ('$c') {
      'WHATSAPP' => const Color(0xFF25D366),
      'FACEBOOK' => const Color(0xFF1877F2),
      'INSTAGRAM' => const Color(0xFFE1306C),
      _ => const Color(0xFF6D4AE0),
    };

IconData channelIcon(Object? c) => switch ('$c') {
      'WHATSAPP' => Icons.chat_rounded,
      'FACEBOOK' => Icons.messenger_rounded,
      'INSTAGRAM' => Icons.camera_alt_rounded,
      _ => Icons.language_rounded,
    };

String whoName(Map<String, dynamic> c) {
  final n = '${c['customerName'] ?? ''}'.trim();
  if (n.isNotEmpty) return n;
  final p = '${c['customerPhone'] ?? ''}'.trim();
  return p.isNotEmpty ? localPhone(p) : L('زائر', 'Guest');
}

String initialOf(String name) {
  final t = name.trim();
  return t.isEmpty ? '?' : String.fromCharCode(t.runes.first).toUpperCase();
}

/// "14:05" today, "امبارح" / "Yesterday", else "12/11".
String shortTime(String? iso) {
  final d = DateTime.tryParse(iso ?? '')?.toLocal();
  if (d == null) return '';
  final now = DateTime.now();
  String two(int n) => n.toString().padLeft(2, '0');
  final today = DateTime(now.year, now.month, now.day);
  final day = DateTime(d.year, d.month, d.day);
  final diff = today.difference(day).inDays;
  if (diff == 0) return '${two(d.hour)}:${two(d.minute)}';
  if (diff == 1) return L('امبارح', 'Yesterday');
  return '${d.day}/${d.month}';
}

String clock(String? iso) {
  final d = DateTime.tryParse(iso ?? '')?.toLocal();
  if (d == null) return '';
  String two(int n) => n.toString().padLeft(2, '0');
  return '${two(d.hour)}:${two(d.minute)}';
}

/// Customer avatar (initial on a tinted disc) with the channel's badge in the corner.
class ChatAvatar extends StatelessWidget {
  const ChatAvatar({super.key, required this.name, required this.channel, this.size = 48});
  final String name;
  final Object? channel;
  final double size;
  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final tint = t.isShop ? t.accent : t.ink;
    return SizedBox(
      width: size + 4,
      height: size + 4,
      child: Stack(children: [
        Container(
          width: size,
          height: size,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            shape: t.isLawyer ? BoxShape.rectangle : BoxShape.circle,
            borderRadius: t.isLawyer ? BorderRadius.circular(8) : null,
            gradient: t.isShop ? LinearGradient(colors: [t.accent.withValues(alpha: 0.22), t.accent2.withValues(alpha: 0.22)]) : null,
            color: t.isShop ? null : (t.isClinic ? t.accent.withValues(alpha: 0.6) : t.accent.withValues(alpha: 0.25)),
          ),
          child: Text(initialOf(name), style: TextStyle(fontWeight: FontWeight.w900, fontSize: size * 0.4, color: tint)),
        ),
        PositionedDirectional(
          end: 0,
          bottom: 0,
          child: Container(
            width: size * 0.4,
            height: size * 0.4,
            decoration: BoxDecoration(color: channelColor(channel), shape: BoxShape.circle, border: Border.all(color: t.card, width: 2)),
            child: Icon(channelIcon(channel), size: size * 0.22, color: Colors.white),
          ),
        ),
      ]),
    );
  }
}

/// Small coloured label.
class Tag extends StatelessWidget {
  const Tag(this.text, this.color, {super.key, this.icon});
  final String text;
  final Color color;
  final IconData? icon;
  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    return Container(
      margin: const EdgeInsetsDirectional.only(end: 6),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(t.isLawyer ? 5 : 20)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (icon != null) ...[Icon(icon, size: 11, color: color), const SizedBox(width: 3)],
        Text(text, style: TextStyle(color: color, fontSize: 11, fontWeight: FontWeight.w800)),
      ]),
    );
  }
}

Color accentOf(AppTemplate t) => t.isShop ? t.accent : (t.isLawyer ? const Color(0xFF7A5E31) : t.ink);
