import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'theme.dart';

/// Kaya's logo, drawn entirely in code: a hand-cut shield cheetah mark with
/// the "fast lane" K. Slight rotation and uneven node dots keep it feeling
/// hand-made rather than machine-generated.
class KayaLogo extends StatelessWidget {
  const KayaLogo({super.key, this.size = 96, this.animate = false});

  final double size;
  final bool animate;

  @override
  Widget build(BuildContext context) {
    final mark = CustomPaint(
      size: Size.square(size),
      painter: _KayaLogoPainter(),
    );
    if (!animate) return mark;
    return _PulsingLogo(child: mark);
  }
}

class _PulsingLogo extends StatefulWidget {
  const _PulsingLogo({required this.child});

  final Widget child;

  @override
  State<_PulsingLogo> createState() => _PulsingLogoState();
}

class _PulsingLogoState extends State<_PulsingLogo>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(seconds: 3))
        ..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (context, child) {
        final glow = 0.5 + 0.5 * math.sin(_c.value * math.pi);
        return DecoratedBox(
          decoration: BoxDecoration(
            boxShadow: [
              BoxShadow(
                color: KayaColors.lane.withValues(alpha: 0.25 * glow),
                blurRadius: 28 * glow + 8,
                spreadRadius: 2,
              ),
            ],
            shape: BoxShape.circle,
          ),
          child: child,
        );
      },
      child: widget.child,
    );
  }
}

class _KayaLogoPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final s = size.shortestSide / 96; // design grid: 96x96
    final shield = Path()
      ..moveTo(48 * s, 4 * s)
      ..lineTo(88 * s, 28 * s)
      ..lineTo(88 * s, 68 * s)
      ..lineTo(48 * s, 92 * s)
      ..lineTo(8 * s, 68 * s)
      ..lineTo(8 * s, 28 * s)
      ..close();

    // Obsidian fill with a hand-drawn double edge.
    canvas.drawShadow(shield, Colors.black, 6, true);
    canvas.drawPath(shield, Paint()..color = KayaColors.obsidian);
    canvas.drawPath(
      shield,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.4 * s
        ..color = KayaColors.lane,
    );

    // Fast-lane K.
    final k = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 7 * s
      ..strokeCap = StrokeCap.round
      ..color = KayaColors.lane;
    final stemX = 34 * s;
    canvas.drawLine(Offset(stemX, 28 * s), Offset(stemX, 68 * s), k);
    canvas.drawLine(Offset(stemX, 48 * s), Offset(62 * s, 28 * s), k);
    canvas.drawLine(Offset(stemX + 2 * s, 46 * s), Offset(64 * s, 68 * s), k);

    // Cheetah node dots — uneven on purpose (hand-placed feel).
    final dots = <Offset, double>{
      const Offset(20, 22): 2.6,
      const Offset(74, 24): 2.2,
      const Offset(18, 66): 2.2,
      const Offset(76, 68): 2.8,
      const Offset(48, 14): 2.0,
      const Offset(48, 82): 2.4,
    };
    for (final entry in dots.entries) {
      canvas.drawCircle(
        entry.key * s,
        entry.value * s,
        Paint()..color = KayaColors.laneSoft,
      );
    }

    // Speed tick under the K.
    final tick = Paint()
      ..strokeWidth = 3 * s
      ..strokeCap = StrokeCap.round
      ..color = KayaColors.laneDeep;
    canvas.drawLine(Offset(30 * s, 76 * s), Offset(52 * s, 76 * s), tick);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
