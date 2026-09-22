import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../native/kaya_bridge.dart' show KayaBridge;
import '../ui/theme.dart';

/// Renders the real launcher icon of an installed app.
/// Fetches PNG bytes over the bridge once per package, caches in-memory.
class AppIcon extends StatefulWidget {
  const AppIcon({super.key, required this.packageName, required this.label, this.size = 40});

  final String packageName;
  final String label;
  final double size;

  @override
  State<AppIcon> createState() => _AppIconState();
}

class _AppIconCache {
  static final Map<String, Uint8List?> _icons = {};
  static Uint8List? get(String pkg) => _icons[pkg];
  static void put(String pkg, Uint8List? bytes) => _icons[pkg] = bytes;
}

class _AppIconState extends State<AppIcon> {
  Uint8List? _bytes;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final cached = _AppIconCache.get(widget.packageName);
    if (cached != null) {
      if (mounted) {
        setState(() {
          _bytes = cached;
        });
      }
      return;
    }
    final bytes = await KayaBridge.shared.getAppIcon(widget.packageName);
    _AppIconCache.put(widget.packageName, bytes);
    if (mounted) {
      setState(() {
        _bytes = bytes;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    if (_bytes != null) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(size * 0.28),
        child: Image.memory(
          _bytes!,
          width: size,
          height: size,
          gaplessPlayback: true,
          fit: BoxFit.cover,
        ),
      );
    }
    // Fallback letter tile (also shown while loading).
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: KayaColors.slate,
        borderRadius: BorderRadius.circular(size * 0.28),
      ),
      child: Text(
        widget.label.isEmpty ? '?' : widget.label[0].toUpperCase(),
        style: const TextStyle(
          fontWeight: FontWeight.w900,
          color: KayaColors.laneSoft,
        ),
      ),
    );
  }
}
