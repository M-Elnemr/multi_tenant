import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'theme.dart';
import 'ui.dart';

/// The opening animation: the logo pops in over pulsing rings while a heartbeat line draws itself across the screen, then the name and tagline rise in.
class AnimatedSplash extends StatefulWidget {
  const AnimatedSplash({super.key, required this.title, required this.tagline, this.from, this.to});
  final String title, tagline;
  final Color? from, to;
  @override
  State<AnimatedSplash> createState() => _AnimatedSplashState();
}

class _AnimatedSplashState extends State<AnimatedSplash> with TickerProviderStateMixin {
  late final AnimationController _intro = AnimationController(vsync: this, duration: const Duration(milliseconds: 1700))..forward();
  late final AnimationController _pulse = AnimationController(vsync: this, duration: const Duration(milliseconds: 1800))..repeat();

  @override
  void dispose() { _intro.dispose(); _pulse.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final a = widget.from ?? kBrandBlue, b = widget.to ?? kBrandGreen;
    return Scaffold(
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topRight, end: Alignment.bottomLeft, colors: [a, Color.lerp(a, b, 0.6)!, b])),
        child: AnimatedBuilder(
          animation: Listenable.merge([_intro, _pulse]),
          builder: (context, _) {
            final t = _intro.value;
            final logoScale = Curves.elasticOut.transform((t / 0.55).clamp(0.0, 1.0));
            final line = Curves.easeInOutCubic.transform(((t - 0.35) / 0.5).clamp(0.0, 1.0));
            final text = Curves.easeOutCubic.transform(((t - 0.55) / 0.45).clamp(0.0, 1.0));
            return Stack(alignment: Alignment.center, children: [
              CustomPaint(size: const Size(320, 320), painter: _RingsPainter(_pulse.value)),
              Column(mainAxisSize: MainAxisSize.min, children: [
                Transform.scale(
                  scale: logoScale,
                  child: Container(
                    width: 132, height: 132,
                    decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle, boxShadow: [BoxShadow(color: Color(0x44000000), blurRadius: 34, offset: Offset(0, 14))]),
                    child: const Center(child: BrandLogo(size: 86)),
                  ),
                ),
                const SizedBox(height: 26),
                SizedBox(width: 240, height: 50, child: CustomPaint(painter: _EcgPainter(line))),
                const SizedBox(height: 10),
                Opacity(opacity: text, child: Transform.translate(offset: Offset(0, (1 - text) * 18), child: Column(children: [
                  Text(widget.title, style: const TextStyle(color: Colors.white, fontSize: 34, fontWeight: FontWeight.w900, letterSpacing: 0.5)),
                  const SizedBox(height: 6),
                  Text(widget.tagline, style: TextStyle(color: Colors.white.withValues(alpha: 0.88), fontSize: 15, fontWeight: FontWeight.w600)),
                ]))),
              ]),
              Positioned(bottom: 54, child: Opacity(opacity: text, child: const _Dots())),
            ]);
          },
        ),
      ),
    );
  }
}

class _RingsPainter extends CustomPainter {
  _RingsPainter(this.v);
  final double v;
  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    for (var i = 0; i < 3; i++) {
      final p = (v + i / 3) % 1;
      final paint = Paint()..style = PaintingStyle.stroke..strokeWidth = 2.5..color = Colors.white.withValues(alpha: (1 - p) * 0.35);
      canvas.drawCircle(c - const Offset(0, 78), 66 + p * 110, paint);
    }
  }
  @override
  bool shouldRepaint(_RingsPainter o) => o.v != v;
}

class _EcgPainter extends CustomPainter {
  _EcgPainter(this.t);
  final double t;
  @override
  void paint(Canvas canvas, Size size) {
    final w = size.width, h = size.height, m = h / 2;
    final path = Path()
      ..moveTo(0, m)
      ..lineTo(w * 0.28, m)
      ..lineTo(w * 0.34, m - h * 0.18)
      ..lineTo(w * 0.40, m)
      ..lineTo(w * 0.45, m)
      ..lineTo(w * 0.50, m + h * 0.2)
      ..lineTo(w * 0.57, m - h * 0.5)
      ..lineTo(w * 0.64, m + h * 0.34)
      ..lineTo(w * 0.69, m)
      ..lineTo(w * 0.78, m)
      ..lineTo(w * 0.83, m - h * 0.22)
      ..lineTo(w * 0.89, m)
      ..lineTo(w, m);
    final metric = path.computeMetrics().first;
    final drawn = metric.extractPath(0, metric.length * t);
    canvas.drawPath(drawn, Paint()..style = PaintingStyle.stroke..strokeWidth = 3..strokeCap = StrokeCap.round..strokeJoin = StrokeJoin.round..color = Colors.white);
    if (t > 0 && t < 1) {
      final tan = metric.getTangentForOffset(metric.length * t);
      if (tan != null) canvas.drawCircle(tan.position, 5, Paint()..color = Colors.white);
    }
  }
  @override
  bool shouldRepaint(_EcgPainter o) => o.t != t;
}

class _Dots extends StatefulWidget {
  const _Dots();
  @override
  State<_Dots> createState() => _DotsState();
}

class _DotsState extends State<_Dots> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))..repeat();
  @override
  void dispose() { _c.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _c,
        builder: (context, _) => Row(mainAxisSize: MainAxisSize.min, children: [
          for (var i = 0; i < 3; i++)
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 4),
              width: 9, height: 9,
              decoration: BoxDecoration(shape: BoxShape.circle, color: Colors.white.withValues(alpha: 0.35 + 0.65 * (0.5 + 0.5 * math.sin((_c.value * 2 * math.pi) - i * 1.1)))),
            ),
        ]),
      );
}

/// Shows [splash] for at least [minimum] and until [ready], then fades into [child].
class SplashGate extends StatefulWidget {
  const SplashGate({super.key, required this.ready, required this.splash, required this.child, this.minimum = const Duration(milliseconds: 2100)});
  final bool ready;
  final Widget splash, child;
  final Duration minimum;
  @override
  State<SplashGate> createState() => _SplashGateState();
}

class _SplashGateState extends State<SplashGate> {
  bool _waited = false;
  @override
  void initState() {
    super.initState();
    Future.delayed(widget.minimum, () { if (mounted) setState(() => _waited = true); });
  }
  @override
  Widget build(BuildContext context) => AnimatedSwitcher(
        duration: const Duration(milliseconds: 520),
        switchInCurve: Curves.easeOutCubic,
        switchOutCurve: Curves.easeIn,
        child: _waited && widget.ready ? KeyedSubtree(key: const ValueKey('content'), child: widget.child) : KeyedSubtree(key: const ValueKey('splash'), child: widget.splash),
      );
}
