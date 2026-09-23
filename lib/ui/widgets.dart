import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'theme.dart';

/// Shared hand-drawn-style widgets: cards, chips, the pulse Boost button and
/// a live sparkline for ping history.

/// Floating snack anchored clear of the bottom navigation bar — the default
/// placement slid under content (extendBody) and clipped text on Home.
void showKayaSnack(BuildContext context, String message) {
  ScaffoldMessenger.of(context)
    ..removeCurrentSnackBar()
    ..showSnackBar(
      SnackBar(
        content: Text(message),
        behavior: SnackBarBehavior.floating,
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 92),
        duration: const Duration(seconds: 3),
      ),
    );
}

class KayaCard extends StatelessWidget {
  const KayaCard({
    super.key,
    required this.child,
    this.onTap,
    this.accent = false,
    this.padding = const EdgeInsets.all(16),
  });

  final Widget child;
  final VoidCallback? onTap;
  final bool accent;
  final EdgeInsets padding;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: accent ? KayaColors.slate : KayaColors.charcoal,
      borderRadius: BorderRadius.circular(KayaRadius.card),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(KayaRadius.card),
        child: Container(
          padding: padding,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(KayaRadius.card),
            border: Border.all(
              color: accent ? KayaColors.lane.withValues(alpha: 0.5) : KayaColors.hairline,
            ),
          ),
          child: child,
        ),
      ),
    );
  }
}

class PingChip extends StatelessWidget {
  const PingChip({super.key, required this.ms, this.compact = false});

  final int? ms;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final value = ms;
    final (color, label) = switch (value) {
      null => (KayaColors.inkFaint, '—'),
      < 40 => (KayaColors.lane, '$value ms'),
      < 80 => (KayaColors.laneSoft, '$value ms'),
      < 130 => (KayaColors.warn, '$value ms'),
      _ => (KayaColors.hot, '$value ms'),
    };
    return Container(
      padding: EdgeInsets.symmetric(
        horizontal: compact ? 8 : 12,
        vertical: compact ? 3 : 6,
      ),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(KayaRadius.chip),
        border: Border.all(color: color.withValues(alpha: 0.55)),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: compact ? 11.5 : 13,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.2,
        ),
      ),
    );
  }
}

/// The signature Boost button: a big neon ring that breathes while armed.
class BoostButton extends StatefulWidget {
  const BoostButton({
    super.key,
    required this.label,
    required this.active,
    required this.onPressed,
    this.progress,
  });

  final String label;
  final bool active;
  final VoidCallback? onPressed;
  final double? progress; // 0..1 consent/step progress when non-null

  @override
  State<BoostButton> createState() => _BoostButtonState();
}

class _BoostButtonState extends State<BoostButton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1600))
        ..repeat();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final active = widget.active;
    // Scale to the viewport: tiny screens get a smaller ring, tablets don't
    // grow absurdly. 170–196 px keeps the composition on every phone.
    final side = (MediaQuery.sizeOf(context).width * 0.46).clamp(158.0, 196.0);
    return GestureDetector(
      onTap: widget.onPressed,
      child: AnimatedBuilder(
        animation: _c,
        builder: (context, child) {
          final t = _c.value;
          final breath = active ? (0.5 + 0.5 * math.sin(t * 2 * math.pi)) : 0.0;
          return Container(
            width: side,
            height: side,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: active ? KayaColors.lane.withValues(alpha: 0.12) : KayaColors.slate,
              border: Border.all(
                color: active ? KayaColors.lane : KayaColors.hairline,
                width: 3,
              ),
              boxShadow: active
                  ? [
                      BoxShadow(
                        color: KayaColors.lane.withValues(alpha: 0.35 + 0.25 * breath),
                        blurRadius: 42 + 18 * breath,
                        spreadRadius: 2 + 3 * breath,
                      ),
                    ]
                  : const [],
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (widget.progress != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 10),
                    child: SizedBox(
                      width: 120,
                      child: LinearProgressIndicator(
                        value: widget.progress,
                        minHeight: 4,
                        borderRadius: BorderRadius.circular(4),
                        backgroundColor: KayaColors.hairline,
                        color: KayaColors.lane,
                      ),
                    ),
                  ),
                Icon(
                  active ? Icons.flash_on_rounded : Icons.bolt_rounded,
                  color: active ? KayaColors.lane : KayaColors.inkDim,
                  size: side * 0.24,
                ),
                const SizedBox(height: 6),
                Text(
                  widget.label,
                  style: TextStyle(
                    color: active ? KayaColors.laneSoft : KayaColors.inkDim,
                    fontWeight: FontWeight.w800,
                    fontSize: side * 0.082,
                    letterSpacing: 1.2,
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

/// Live sparkline of recent ping samples.
class PingSparkline extends StatelessWidget {
  const PingSparkline({super.key, required this.samples, this.height = 44});

  final List<int> samples;
  final double height;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: Size(double.infinity, height),
      painter: _SparkPainter(samples),
    );
  }
}

class _SparkPainter extends CustomPainter {
  _SparkPainter(this.samples);

  final List<int> samples;

  @override
  void paint(Canvas canvas, Size size) {
    if (samples.length < 2) return;
    final maxV = (samples.reduce(math.max) + 10).clamp(30, 400).toDouble();
    final step = size.width / (samples.length - 1);
    final line = Path();
    var started = false;
    for (var i = 0; i < samples.length; i++) {
      final x = i * step;
      final y = size.height - (samples[i] / maxV) * (size.height - 4) - 2;
      if (!started) {
        line.moveTo(x, y);
        started = true;
      } else {
        line.lineTo(x, y);
      }
    }
    final fill = Path.from(line)
      ..lineTo(size.width, size.height)
      ..lineTo(0, size.height)
      ..close();
    canvas.drawPath(
      fill,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            KayaColors.lane.withValues(alpha: 0.35),
            KayaColors.lane.withValues(alpha: 0.0),
          ],
        ).createShader(Offset.zero & size),
    );
    canvas.drawPath(
      line,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.2
        ..strokeCap = StrokeCap.round
        ..color = KayaColors.lane,
    );
  }

  @override
  bool shouldRepaint(covariant _SparkPainter old) => old.samples != samples;
}

/// Small labelled stat used across screens.
class StatTile extends StatelessWidget {
  const StatTile({
    super.key,
    required this.label,
    required this.value,
    this.sub,
    this.color,
  });

  final String label;
  final String value;
  final String? sub;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label.toUpperCase(),
          style: const TextStyle(
            color: KayaColors.inkFaint,
            fontSize: 10.5,
            fontWeight: FontWeight.w700,
            letterSpacing: 1.1,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          style: TextStyle(
            color: color ?? KayaColors.ink,
            fontSize: 19,
            fontWeight: FontWeight.w800,
          ),
        ),
        if (sub != null)
          Text(
            sub!,
            style: const TextStyle(color: KayaColors.inkDim, fontSize: 11.5),
          ),
      ],
    );
  }
}

/// Section header with an optional trailing action.
class SectionHeader extends StatelessWidget {
  const SectionHeader({super.key, required this.title, this.action});

  final String title;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10, top: 4),
      child: Row(
        children: [
          Text(
            title,
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w800,
              letterSpacing: 0.2,
            ),
          ),
          const Spacer(),
          if (action != null) action!,
        ],
      ),
    );
  }
}
