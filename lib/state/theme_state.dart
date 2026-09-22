import 'dart:math' as math;

import 'package:flutter/material.dart';

/// Four hand-tuned accent palettes. The whole app re-tints from these.
enum AccentOption {
  neon('Neon Lane', Color(0xFF00E56A), Color(0xFF7DFFB2), Color(0xFF00B354)),
  cyan('Cyber Cyan', Color(0xFF22D3EE), Color(0xFFA5F3FC), Color(0xFF0891B2)),
  violet('Void Violet', Color(0xFFA78BFA), Color(0xFFDDD6FE), Color(0xFF7C3AED)),
  amber('Sunset Amber', Color(0xFFFFB020), Color(0xFFFDE68A), Color(0xFFD97706));

  const AccentOption(this.label, this.accent, this.accentSoft, this.accentDeep);
  final String label;
  final Color accent;
  final Color accentSoft;
  final Color accentDeep;

  static AccentOption fromIndex(int i) =>
      AccentOption.values[i.clamp(0, AccentOption.values.length - 1)];
}

/// Notifies listeners (the shell) when the accent changes.
class ThemeState extends ChangeNotifier {
  AccentOption _option = AccentOption.neon;
  AccentOption get option => _option;

  set option(AccentOption value) {
    _option = value;
    notifyListeners();
  }

  Color get accent => _option.accent;
  Color get accentSoft => _option.accentSoft;
  Color get accentDeep => _option.accentDeep;

  /// Deterministic star field for the animated backdrop.
  List<Offset> stars(int count, Size canvas) {
    final rng = math.Random(1337);
    return List.generate(count, (i) {
      return Offset(
        rng.nextDouble() * canvas.width,
        rng.nextDouble() * canvas.height * 0.9,
      );
    });
  }
}
