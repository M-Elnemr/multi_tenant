import 'dart:io' show SocketException;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'api_client.dart';
import 'cache.dart';
import 'locale.dart';
import 'strings.dart';
import 'theme.dart';

/// The Elmanassa logo (bundled with this package).
class BrandLogo extends StatelessWidget {
  const BrandLogo({super.key, this.size = 96});
  final double size;
  @override
  Widget build(BuildContext context) => Image.asset('assets/logo.png', package: 'platform_core', width: size, height: size, fit: BoxFit.contain);
}

/// Fades and slides its child in; `index` staggers list items.
class FadeSlideIn extends StatelessWidget {
  const FadeSlideIn({super.key, required this.child, this.index = 0, this.dy = 22});
  final Widget child;
  final int index;
  final double dy;
  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
        tween: Tween(begin: 0, end: 1),
        duration: Duration(milliseconds: 420 + (index.clamp(0, 10) * 70)),
        curve: Curves.easeOutCubic,
        builder: (context, v, c) => Opacity(opacity: v.clamp(0.0, 1.0), child: Transform.translate(offset: Offset(0, (1 - v) * dy), child: c)),
        child: child,
      );
}

/// Grey block with a light sweep while content loads.
class Skeleton extends StatefulWidget {
  const Skeleton({super.key, this.height = 72, this.width, this.radius = 18});
  final double height;
  final double? width;
  final double radius;
  @override
  State<Skeleton> createState() => _SkeletonState();
}

class _SkeletonState extends State<Skeleton> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1400))..repeat();
  @override
  void dispose() { _c.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _c,
        builder: (context, _) => Container(
          height: widget.height,
          width: widget.width,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(widget.radius),
            gradient: LinearGradient(begin: Alignment(-1.5 + _c.value * 3, 0), end: Alignment(-0.5 + _c.value * 3, 0), colors: const [Color(0xFFE6ECF4), Color(0xFFF6F9FC), Color(0xFFE6ECF4)]),
          ),
        ),
      );
}

/// Shrinks slightly while pressed: gives every tappable card a tactile feel.
class PressScale extends StatefulWidget {
  const PressScale({super.key, required this.child, this.onTap, this.scale = 0.97});
  final Widget child;
  final VoidCallback? onTap;
  final double scale;
  @override
  State<PressScale> createState() => _PressScaleState();
}

class _PressScaleState extends State<PressScale> {
  bool _down = false;
  @override
  Widget build(BuildContext context) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: widget.onTap == null ? null : (_) => setState(() => _down = true),
        onTapCancel: () => setState(() => _down = false),
        onTapUp: (_) => setState(() => _down = false),
        onTap: widget.onTap == null ? null : () { HapticFeedback.selectionClick(); widget.onTap!(); },
        child: AnimatedScale(scale: _down ? widget.scale : 1, duration: const Duration(milliseconds: 120), curve: Curves.easeOut, child: widget.child),
      );
}

/// Primary button with a built-in loading state that swaps the label for a spinner without resizing.
class AnimatedButton extends StatelessWidget {
  const AnimatedButton({super.key, required this.label, required this.onPressed, this.loading = false, this.icon});
  final String label;
  final VoidCallback? onPressed;
  final bool loading;
  final IconData? icon;
  @override
  Widget build(BuildContext context) => FilledButton(
        onPressed: loading ? null : onPressed,
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 200),
          child: loading
              ? const SizedBox(key: ValueKey('l'), height: 22, width: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white))
              : Row(key: const ValueKey('t'), mainAxisSize: MainAxisSize.min, children: [if (icon != null) ...[Icon(icon, size: 20), const SizedBox(width: 8)], Text(label)]),
        ),
      );
}

/// Brand gradient banner with soft circles; hosts a logo/title on login, the business picker, the shop list, etc.
class GradientHeader extends StatelessWidget {
  const GradientHeader({super.key, required this.child, this.colors, this.height, this.radius = 32});
  final Widget child;
  final List<Color>? colors;
  final double? height;
  final double radius;
  @override
  Widget build(BuildContext context) {
    final p = Theme.of(context).colorScheme.primary;
    final c = colors ?? [p, Color.lerp(p, kBrandGreen, 0.55)!];
    return Container(
      height: height,
      decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topRight, end: Alignment.bottomLeft, colors: c), borderRadius: BorderRadius.vertical(bottom: Radius.circular(radius))),
      child: ClipRRect(
        borderRadius: BorderRadius.vertical(bottom: Radius.circular(radius)),
        child: Stack(children: [
          PositionedDirectional(top: -50, end: -40, child: _blob(180, 0.12)),
          PositionedDirectional(bottom: -60, start: -30, child: _blob(150, 0.1)),
          child,
        ]),
      ),
    );
  }

  Widget _blob(double s, double a) => Container(width: s, height: s, decoration: BoxDecoration(shape: BoxShape.circle, color: Colors.white.withValues(alpha: a)));
}

/// Number that counts up when first shown.
class CountUp extends StatelessWidget {
  const CountUp({super.key, required this.value, this.style, this.format});
  final num value;
  final TextStyle? style;
  final String Function(num)? format;
  @override
  Widget build(BuildContext context) => TweenAnimationBuilder<double>(
        tween: Tween(begin: 0, end: value.toDouble()),
        duration: const Duration(milliseconds: 900),
        curve: Curves.easeOutCubic,
        builder: (context, v, _) => Text(format != null ? format!(v) : v.round().toString(), style: style),
      );
}

/// AR | EN pill. Switches the whole app instantly and remembers the choice.
class LanguageChip extends StatelessWidget {
  const LanguageChip({super.key, this.onDark = false});
  final bool onDark;
  @override
  Widget build(BuildContext context) => ValueListenableBuilder<Locale>(
        valueListenable: AppLocale.notifier,
        builder: (context, loc, _) {
          final ar = loc.languageCode != 'en';
          final fg = onDark ? Colors.white : Theme.of(context).colorScheme.primary;
          Widget seg(String t, bool on, String code) => GestureDetector(
                onTap: on ? null : () { HapticFeedback.selectionClick(); AppLocale.set(code); },
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 220),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                  decoration: BoxDecoration(color: on ? (onDark ? Colors.white : fg) : Colors.transparent, borderRadius: BorderRadius.circular(20)),
                  child: Text(t, style: TextStyle(fontWeight: FontWeight.w800, fontSize: 13, color: on ? (onDark ? Theme.of(context).colorScheme.primary : Colors.white) : fg)),
                ),
              );
          return Container(
            padding: const EdgeInsets.all(3),
            decoration: BoxDecoration(color: (onDark ? Colors.white : fg).withValues(alpha: 0.16), borderRadius: BorderRadius.circular(24), border: Border.all(color: (onDark ? Colors.white : fg).withValues(alpha: 0.35))),
            child: Row(mainAxisSize: MainAxisSize.min, children: [seg('العربية', ar, 'ar'), seg('EN', !ar, 'en')]),
          );
        },
      );
}

/// Password input with a show/hide toggle.
class PasswordField extends StatefulWidget {
  const PasswordField({super.key, required this.controller, required this.label, this.onSubmitted, this.textInputAction});
  final TextEditingController controller;
  final String label;
  final ValueChanged<String>? onSubmitted;
  final TextInputAction? textInputAction;
  @override
  State<PasswordField> createState() => _PasswordFieldState();
}

class _PasswordFieldState extends State<PasswordField> {
  bool _hide = true;
  @override
  Widget build(BuildContext context) => TextField(
        controller: widget.controller,
        obscureText: _hide,
        textInputAction: widget.textInputAction,
        onSubmitted: widget.onSubmitted,
        decoration: InputDecoration(labelText: widget.label, prefixIcon: const Icon(Icons.lock_outline_rounded), suffixIcon: IconButton(icon: Icon(_hide ? Icons.visibility_outlined : Icons.visibility_off_outlined), onPressed: () => setState(() => _hide = !_hide))),
      );
}

/// Human message for any error: known API codes are explained in the current language, network problems are named as such.
String errorText(Object e) {
  final ar = AppLocale.isAr;
  if (e is ApiException) {
    final m = <String, List<String>>{
      'NETWORK': ['تعذّر الاتصال بالخادم. تحقق من الإنترنت وأعد المحاولة', 'Could not reach the server. Check your internet and try again'],
      'INVALID_CREDENTIALS': ['رقم الهاتف/البريد أو كلمة المرور غير صحيحة', 'Wrong phone/email or password'],
      'ACTIVATION_REQUIRED': ['أول دخول: أدخل الكود المؤقت الذي حصلت عليه', 'First sign-in: enter the one-time code you were given'],
      'WRONG_APP_STAFF': ['هذا حساب نشاط تجاري. استخدم تطبيق Elmanassa Business', 'This is a business account. Use the Elmanassa Business app'],
      'WRONG_APP_PATIENT': ['هذا حساب مريض. استخدم تطبيق Elmanassa', 'This is a patient account. Use the Elmanassa app'],
      'NOT_A_MEMBER': ['هذا الحساب ليس له صلاحية هنا', 'This account has no access here'],
      'ACCOUNT_DISABLED': ['هذا الحساب غير مفعّل', 'This account is not active'],
      'TOO_MANY_ATTEMPTS': ['محاولات كثيرة، انتظر قليلاً ثم أعد المحاولة', 'Too many attempts, wait a moment and try again'],
      'ACTIVATION_INVALID': ['الكود غير صحيح أو منتهي', 'The code is wrong or expired'],
      'WEAK_PASSWORD': ['كلمة المرور قصيرة (8 أحرف على الأقل)', 'Password too short (8 characters minimum)'],
    }[e.code];
    if (m != null) return ar ? m[0] : m[1];
    return e.message;
  }
  if (e is SocketException || e.toString().contains('SocketException') || e.toString().contains('ClientException')) return ar ? 'لا يوجد اتصال بالإنترنت' : 'No internet connection';
  return e.toString();
}

/// Loads a future and shows skeletons / error+retry / content (fades in).
class Async<T> extends StatefulWidget {
  const Async({super.key, required this.load, required this.builder});
  final Future<T> Function() load;
  final Widget Function(BuildContext, T, Future<void> Function() reload) builder;
  @override
  State<Async<T>> createState() => _AsyncState<T>();
}

class _AsyncState<T> extends State<Async<T>> {
  late Future<T> _f = _track(widget.load());
  T? _last;
  bool _has = false;

  Future<T> _track(Future<T> f) => f.then((v) { _last = v; _has = true; return v; });

  /// Refreshes in the background: the list that is already on screen stays (no skeleton flash, no re-animation) until the new answer lands.
  Future<void> _reload() async {
    final next = _track(widget.load());
    try { await next; } catch (_) { /* the old data stays; a pull-to-refresh shows its own error */ }
    if (mounted) setState(() { _f = next; });
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<T>(
        future: _f,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done && !_has) {
            return const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(), SizedBox(height: 12), Skeleton(), SizedBox(height: 12), Skeleton()]));
          }
          if (snap.hasError && !_has) {
            return Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
              const Icon(Icons.cloud_off_rounded, size: 46, color: Color(0xFF9AA9BA)),
              const SizedBox(height: 10),
              Text(errorText(snap.error!), textAlign: TextAlign.center),
              const SizedBox(height: 6),
              TextButton.icon(onPressed: () { setState(() { _f = _track(widget.load()); }); }, icon: const Icon(Icons.refresh_rounded), label: Text(S.of(context).retry)),
            ])));
          }
          final body = widget.builder(context, (snap.hasData ? snap.data : _last) as T, _reload);
          return FadeSlideIn(child: body);
        },
      );
}

/// Like [Async] but remembers the last answer under [cacheKey]: reopening shows the old data at once and refreshes behind it.
/// Never an endless spinner: after the network retries are used up the error card with Retry appears (or the old data stays).
class CachedAsync<T> extends StatefulWidget {
  const CachedAsync({super.key, required this.cacheKey, required this.load, required this.builder});
  final String cacheKey;
  final Future<T> Function() load;
  final Widget Function(BuildContext, T, Future<void> Function() reload) builder;
  @override
  State<CachedAsync<T>> createState() => _CachedAsyncState<T>();
}

class _CachedAsyncState<T> extends State<CachedAsync<T>> {
  late Stream<T> _s = cachedThenFresh<T>(widget.cacheKey, widget.load);
  int _n = 0;

  Future<void> _reload() async {
    setState(() { _s = cachedThenFresh<T>(widget.cacheKey, widget.load); _n++; });
  }

  @override
  Widget build(BuildContext context) => StreamBuilder<T>(
        key: ValueKey(_n),
        stream: _s,
        builder: (context, snap) {
          if (snap.hasData) return widget.builder(context, snap.data as T, _reload);
          if (snap.hasError) {
            return Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
              const Icon(Icons.cloud_off_rounded, size: 46, color: Color(0xFF9AA9BA)),
              const SizedBox(height: 10),
              Text(errorText(snap.error!), textAlign: TextAlign.center),
              const SizedBox(height: 6),
              TextButton.icon(onPressed: _reload, icon: const Icon(Icons.refresh_rounded), label: Text(S.of(context).retry)),
            ])));
          }
          return const Padding(padding: EdgeInsets.all(16), child: Column(children: [Skeleton(), SizedBox(height: 12), Skeleton(), SizedBox(height: 12), Skeleton()]));
        },
      );
}
