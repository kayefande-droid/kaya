import 'package:flutter/material.dart';

/// Kaya's hand-tuned visual identity.
///
/// Dark obsidian surfaces, one neon "fast lane" green, hand-drawn feeling
/// shapes (soft corners, thin strokes, dotted trims) — designed to feel like
/// it came off a sketchbook, not a template.
abstract final class KayaColors {
  static const obsidian = Color(0xFF0A0A0C);
  static const charcoal = Color(0xFF131318);
  static const slate = Color(0xFF1C1C24);
  static const hairline = Color(0xFF26262F);
  static const lane = Color(0xFF00E56A); // the fast-lane green
  static const laneSoft = Color(0xFF7DFFB2);
  static const laneDeep = Color(0xFF00B354);
  static const ink = Color(0xFFF4F6F4);
  static const inkDim = Color(0xFF9BA3A0);
  static const inkFaint = Color(0xFF5C6360);
  static const warn = Color(0xFFFFC24B);
  static const hot = Color(0xFFFF5A5A);
  static const cool = Color(0xFF5AC8FF);
}

abstract final class KayaRadius {
  static const card = 20.0;
  static const sheet = 28.0;
  static const chip = 12.0;
}

class KayaTheme {
  static ThemeData dark() {
    final base = ThemeData.dark(useMaterial3: true);
    return base.copyWith(
      scaffoldBackgroundColor: KayaColors.obsidian,
      colorScheme: base.colorScheme.copyWith(
        primary: KayaColors.lane,
        secondary: KayaColors.laneSoft,
        surface: KayaColors.charcoal,
        onSurface: KayaColors.ink,
        error: KayaColors.hot,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          fontFamilyFallback: ['Montserrat', 'Segoe UI', 'Roboto'],
          fontSize: 20,
          fontWeight: FontWeight.w700,
          color: KayaColors.ink,
          letterSpacing: 0.2,
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: KayaColors.charcoal,
        indicatorColor: KayaColors.lane.withValues(alpha: 0.16),
        height: 64,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        iconTheme: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return const IconThemeData(color: KayaColors.lane);
          }
          return const IconThemeData(color: KayaColors.inkFaint);
        }),
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          final color =
              states.contains(WidgetState.selected) ? KayaColors.lane : KayaColors.inkFaint;
          return TextStyle(
            fontSize: 11.5,
            fontWeight: FontWeight.w600,
            color: color,
            letterSpacing: 0.3,
          );
        }),
      ),
      cardTheme: CardThemeData(
        color: KayaColors.charcoal,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(KayaRadius.card),
          side: const BorderSide(color: KayaColors.hairline, width: 1),
        ),
      ),
      dividerTheme: const DividerThemeData(color: KayaColors.hairline, thickness: 1),
      sliderTheme: const SliderThemeData(
        activeTrackColor: KayaColors.lane,
        thumbColor: KayaColors.laneSoft,
        inactiveTrackColor: KayaColors.slate,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? KayaColors.obsidian : KayaColors.inkDim,
        ),
        trackColor: WidgetStateProperty.resolveWith(
          (s) => s.contains(WidgetState.selected) ? KayaColors.lane : KayaColors.slate,
        ),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: KayaColors.slate,
        contentTextStyle: const TextStyle(color: KayaColors.ink),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(KayaRadius.chip)),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
}
