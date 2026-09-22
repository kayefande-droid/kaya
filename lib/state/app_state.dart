import 'dart:async';

import 'package:flutter/foundation.dart';

import '../native/kaya_bridge.dart';

/// Central app state: engine/boost flags, live ping samples, DNS feed and
/// permission statuses. One ChangeNotifier for the whole UI.
class AppState extends ChangeNotifier {
  AppState(this._bridge) {
    _bridge.listen();
    _sub = _bridge.events.listen(_onEvent);
    unawaited(refreshPermissions());
  }

  final KayaBridge _bridge;
  late final StreamSubscription<KayaEvent> _sub;

  /// Public accessor so screens can call native methods directly.
  KayaBridge get bridge => _bridge;

  // ---- session flags ----
  bool engineOn = false;
  bool boostOn = false;
  bool controllerBridgeOn = false;

  // ---- permissions ----
  bool batteryExempt = false;
  bool usageStatsOk = false;
  bool accessibilityOk = false;
  Map<String, String> privateDns = {'mode': 'off', 'specifier': ''};

  // ---- live data ----
  final List<int> pingHistory = [];
  final List<DnsTrace> dnsLog = [];
  int? lastPing;
  int packetsRelayed = 0;
  int thermal = 0; // PowerManager thermal status

  // ---- library ----
  List<KayaApp> library = [];
  bool libraryLoading = false;
  String search = '';
  final Set<String> boostedPackages = {};

  Timer? _benchTimer;
  int benchRound = 0;

  // ---- actions ----
  Future<void> loadLibrary() async {
    libraryLoading = true;
    notifyListeners();
    library = await _bridge.listInstalledApps();
    libraryLoading = false;
    notifyListeners();
  }

  Future<void> refreshPermissions() async {
    batteryExempt = await _bridge.isIgnoringBatteryOptimizations();
    usageStatsOk = await _bridge.hasUsageStats();
    accessibilityOk = await _bridge.accessibilityEnabled();
    final dns = await _bridge.getPrivateDns();
    if (dns != null) privateDns = dns;
    notifyListeners();
  }

  /// Full startup sequence: consent -> engine -> boost locks.
  Future<bool> armBoost() async {
    if (!engineOn) {
      final ok = await _bridge.vpnConsent();
      if (!ok) return false;
      final started = await _bridge.vpnStart();
      if (!started) return false;
      engineOn = true;
    }
    if (!boostOn) {
      await _bridge.boostStart();
      boostOn = true;
    }
    await refreshPermissions();
    notifyListeners();
    return true;
  }

  Future<void> disarm() async {
    if (engineOn) {
      await _bridge.vpnStop();
      engineOn = false;
    }
    if (boostOn) {
      await _bridge.boostStop();
      boostOn = false;
    }
    notifyListeners();
  }

  void setBoostOn(bool value) {
    boostOn = value;
    notifyListeners();
  }

  void setEngineOn(bool value) {
    engineOn = value;
    notifyListeners();
  }

  void toggleBoosted(String packageName) {
    if (!boostedPackages.remove(packageName)) {
      boostedPackages.add(packageName);
    }
    notifyListeners();
  }

  void setSearch(String value) {
    search = value;
    notifyListeners();
  }

  // ---- events ----
  void _onEvent(KayaEvent event) {
    switch (event.type) {
      case 'vpn':
        final state = event.data['state']?.toString();
        if (state == 'active') engineOn = true;
        if (state == 'stopped' || state == 'error') engineOn = false;
      case 'dns':
        dnsLog.insert(
          0,
          DnsTrace(
            domain: event.data['domain']?.toString() ?? '?',
            resolver: event.data['resolver']?.toString() ?? '?',
            category: event.data['category']?.toString() ?? 'other',
            at: DateTime.now(),
          ),
        );
        if (dnsLog.length > 60) dnsLog.removeLast();
      case 'ping_sample':
        final ms = (event.data['ms'] as num?)?.toInt();
        if (ms != null) _addPing(ms);
      case 'udp_packet':
        packetsRelayed++;
      case 'controller':
        // surfaced by the controller screen through its own listener
        break;
    }
    notifyListeners();
  }

  void _addPing(int ms) {
    lastPing = ms;
    pingHistory.add(ms);
    if (pingHistory.length > 60) pingHistory.removeAt(0);
    notifyListeners();
  }

  /// Public benchmark loop: pings the current edge every 2s while on Home.
  Timer? startBenchmark() {
    _benchTimer?.cancel();
    _benchTimer = Timer.periodic(const Duration(seconds: 2), (_) async {
      final ms = await _bridge.pingProbe('cloudflare.com', 443);
      if (ms != null) {
        _addPing(ms);
        benchRound++;
      }
    });
    return _benchTimer;
  }

  void stopBenchmark() {
    _benchTimer?.cancel();
    _benchTimer = null;
  }

  double get jitter {
    if (pingHistory.length < 3) return 0;
    var sum = 0.0;
    for (var i = 1; i < pingHistory.length; i++) {
      sum += (pingHistory[i] - pingHistory[i - 1]).abs();
    }
    return sum / (pingHistory.length - 1);
  }

  double get stability {
    final j = jitter;
    if (pingHistory.length < 3) return 0;
    return (100 - j * 1.6).clamp(0, 100).toDouble();
  }

  @override
  void dispose() {
    _benchTimer?.cancel();
    _sub.cancel();
    super.dispose();
  }
}

class DnsTrace {
  DnsTrace({
    required this.domain,
    required this.resolver,
    required this.category,
    required this.at,
  });

  final String domain;
  final String resolver;
  final String category;
  final DateTime at;
}


