import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Game-themed animated backdrop:
///  - slow parallax "hyperspace" particle field
///  - soft horizon glow tinted by the active accent
/// Runs at a calm tick rate; when [boostActive] it speeds up (the fun part).
class KayaBackdrop extends StatefulWidget {
  const KayaBackdrop({
    super.key,
    required this.accent,
    this.boostActive = false,
    this.child,
  });

  final Color accent;
  final bool boostActive;
  final Widget? child;

  @override
  State<KayaBackdrop> createState() => _KayaBackdropState();
}

class _KayaBackdropState extends State<KayaBackdrop>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(seconds: 6),
  )..repeat();

  static const _starCount = 90;
  final List<_Star> _stars = List.generate(_starCount, (i) => _Star.seeded(i));

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        // horizon glow
        DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.bottomCenter,
              end: Alignment.topCenter,
              stops: const [0, 0.35, 1],
              colors: [
                widget.accent.withValues(alpha: widget.boostActive ? 0.20 : 0.10),
                widget.accent.withValues(alpha: 0.04),
                Colors.transparent,
              ],
            ),
          ),
        ),
        // particle field
        AnimatedBuilder(
          animation: _c,
          builder: (context, _) {
            return CustomPaint(
              painter: _StarPainter(
                stars: _stars,
                t: _c.value,
                accent: widget.accent,
                speed: widget.boostActive ? 3.2 : 1.0,
              ),
            );
          },
        ),
        if (widget.child != null) widget.child!,
      ],
    );
  }
}

class _Star {
  _Star.seeded(int seed)
      : x = math.Random(seed).nextDouble(),
        y = math.Random(seed + 99).nextDouble(),
        depth = 0.25 + math.Random(seed + 199).nextDouble() * 0.75,
        drift = 0.5 + math.Random(seed + 299).nextDouble();

  final double x;
  final double y;
  final double depth; // 0 far .. 1 near
  final double drift;
}

class _StarPainter extends CustomPainter {
  _StarPainter({
    required this.stars,
    required this.t,
    required this.accent,
    required this.speed,
  });

  final List<_Star> stars;
  final double t;
  final Color accent;
  final double speed;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()..strokeCap = StrokeCap.round;
    for (final s in stars) {
      // vertical drift with parallax; wraps around
      final speedFactor = speed * s.depth;
      final yy = ((s.y + t * 0.16 * speedFactor * s.drift) % 1.0) * size.height;
      final xx = (s.x * size.width) +
          math.sin(t * 2 * math.pi * 0.2 + s.x * 10) * 6 * s.depth;

      final alpha = (0.12 + 0.5 * s.depth).clamp(0.0, 0.65);
      final len = 2 + 10 * s.depth * speed;
      paint.color = accent.withValues(alpha: alpha);
      paint.strokeWidth = 1 + 1.6 * s.depth;
      canvas.drawLine(Offset(xx, yy), Offset(xx, yy + len), paint);
    }
  }

  @override
  bool shouldRepaint(covariant _StarPainter old) =>
      old.t != t || old.accent != accent || old.speed != speed;
}
