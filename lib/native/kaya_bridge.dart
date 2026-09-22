import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Typed wrapper over the native "kaya/channel" + "kaya/events" bridge.
class KayaBridge {
  static const _method = MethodChannel('kaya/channel');
  static const _events = EventChannel('kaya/events');

  final _controller = StreamController<KayaEvent>.broadcast();
  Stream<KayaEvent> get events => _controller.stream;

  bool _listening = false;

  void listen() {
    if (_listening) return;
    _listening = true;
    _events.receiveBroadcastStream().listen(
      (data) {
        try {
          final map = Map<String, Object?>.from(data as Map);
          _controller.add(
            KayaEvent(
              type: map['type'] as String? ?? '',
              data: Map<String, Object?>.from(map['data'] as Map? ?? {}),
            ),
          );
        } catch (e) {
          debugPrint('kaya event parse: $e');
        }
      },
      onError: (Object e) => debugPrint('kaya event error: $e'),
    );
  }

  Future<T?> _invoke<T>(String method, [Map<String, Object?> args = const {}]) async {
    try {
      return await _method.invokeMethod<T>(method, args);
    } on PlatformException catch (e) {
      debugPrint('$method failed: ${e.code} ${e.message}');
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  // ---- apps ------------------------------------------------------------
  Future<List<KayaApp>> listInstalledApps() async {
    final raw = await _invoke<List<Object?>>('listInstalledApps');
    if (raw == null) return [];
    return raw
        .map((e) => KayaApp.fromMap(Map<String, Object?>.from(e as Map)))
        .toList();
  }

  /// Convenience accessor used by widgets outside the main screens.
  static KayaBridge get shared => _shared;
  static final KayaBridge _shared = KayaBridge();

  Future<bool> launchApp(String packageName) async =>
      await _invoke<bool>('launchApp', {'package': packageName}) ?? false;

  // ---- engine + boost ----------------------------------------------------
  Future<bool> vpnConsent() async => await _invoke<bool>('vpnConsent') ?? false;
  Future<bool> vpnStart() async => await _invoke<bool>('vpnStart') ?? false;
  Future<bool> vpnStop() async => await _invoke<bool>('vpnStop') ?? false;
  Future<bool> boostStart() async => await _invoke<bool>('boostStart') ?? false;
  Future<bool> boostStop() async => await _invoke<bool>('boostStop') ?? false;

  // ---- permissions -------------------------------------------------------
  Future<bool> isIgnoringBatteryOptimizations() async =>
      await _invoke<bool>('isIgnoringBatteryOptimizations') ?? true;
  Future<void> requestIgnoreBatteryOptimizations() =>
      _invoke<void>('requestIgnoreBatteryOptimizations');
  Future<bool> hasUsageStats() async => await _invoke<bool>('hasUsageStats') ?? true;
  Future<void> grantUsageStats() => _invoke<void>('grantUsageStats');
  Future<bool> accessibilityEnabled() async =>
      await _invoke<bool>('accessibilityEnabled') ?? false;
  Future<void> openAccessibilitySettings() =>
      _invoke<void>('openAccessibilitySettings');

  Future<Map<String, String>?> getPrivateDns() async {
    final raw = await _invoke<Map<Object?, Object?>>('getPrivateDns');
    if (raw == null) return null;
    return raw.map((k, v) => MapEntry(k.toString(), v?.toString() ?? ''));
  }

  Future<bool> setPrivateDns(String hostname) async =>
      await _invoke<bool>('setPrivateDns', {'hostname': hostname}) ?? false;

  // ---- controllers ---------------------------------------------------------
  Future<List<Map<String, Object?>>> listGamepads() async {
    final raw = await _invoke<List<Object?>>('listGamepads');
    if (raw == null) return [];
    return raw.map((e) => Map<String, Object?>.from(e as Map)).toList();
  }

  Future<bool> setControllerMapping(List<Map<String, Object?>> mappings) async =>
      await _invoke<bool>('setControllerMapping', {'mappings': mappings}) ?? false;
  Future<bool> clearControllerMapping() async =>
      await _invoke<bool>('clearControllerMapping') ?? true;
  Future<bool> controllerBridgeStart() async =>
      await _invoke<bool>('controllerBridgeStart') ?? false;
  Future<bool> controllerBridgeStop() async =>
      await _invoke<bool>('controllerBridgeStop') ?? false;

  // ---- probes ----------------------------------------------------------------
  Future<Uint8List?> getAppIcon(String packageName) async =>
      _invoke<Uint8List>('getAppIcon', {'package': packageName});

  Future<int?> pingProbe(String host, int port) async =>
      _invoke<int>('pingProbe', {'host': host, 'port': port});
  Future<int?> dnsProbe(String server, String domain) async =>
      _invoke<int>('dnsProbe', {'server': server, 'domain': domain});

  Future<List<Map<String, Object?>>> getResolvers() async {
    final raw = await _invoke<List<Object?>>('getResolvers');
    if (raw == null) return [];
    return raw.map((e) => Map<String, Object?>.from(e as Map)).toList();
  }

  Future<List<String>> getGameHosts() async {
    final raw = await _invoke<List<Object?>>('getGameHosts');
    if (raw == null) return [];
    return raw.map((e) => e.toString()).toList();
  }

  Future<Map<String, String>> getSteeringWinners() async {
    final raw = await _invoke<Map<Object?, Object?>>('getSteeringWinners');
    if (raw == null) return {};
    return raw.map((k, v) => MapEntry(k.toString(), v.toString()));
  }

  Future<void> clearDnsCache() => _invoke<void>('clearDnsCache');
  Future<int> thermalStatus() async => await _invoke<int>('thermalStatus') ?? 0;

  Future<void> toast(String message) => _invoke<void>('toast', {'message': message});
}

class KayaEvent {
  KayaEvent({required this.type, required this.data});

  final String type;
  final Map<String, Object?> data;
}

class KayaApp {
  KayaApp({
    required this.packageName,
    required this.label,
    required this.isGame,
    required this.version,
  });

  final String packageName;
  final String label;
  final bool isGame;
  final String version;

  factory KayaApp.fromMap(Map<String, Object?> map) => KayaApp(
        packageName: map['packageName']?.toString() ?? '',
        label: map['label']?.toString() ?? '',
        isGame: map['isGame'] == true,
        version: map['version']?.toString() ?? '',
      );
}
