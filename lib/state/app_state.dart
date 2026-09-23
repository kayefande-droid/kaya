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
    unawaited(_resyncSession());
    unawaited(loadFeatures());
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

  // ---- features (auto-pilot / focus / overlay) ----
  bool autopilotOn = true;
  bool gameFocusOn = true;
  bool dndGranted = false;
  bool overlayOk = false;
  String? autoGame; // game auto-pilot is currently holding a session for

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

  /// Called when the app returns to the foreground (e.g. from the overlay
  /// permission screen). Re-checks permissions and surfaces the live monitor
  /// if a boost session is active and the user just granted the overlay.
  Future<void> onResumed() async {
    final hadOverlay = overlayOk;
    await refreshPermissions();
    if (!hadOverlay && overlayOk && (engineOn || boostOn)) {
      await _bridge.bubbleShow('Kaya');
    }
  }

  Future<void> refreshPermissions() async {
    batteryExempt = await _bridge.isIgnoringBatteryOptimizations();
    usageStatsOk = await _bridge.hasUsageStats();
    accessibilityOk = await _bridge.accessibilityEnabled();
    final dns = await _bridge.getPrivateDns();
    if (dns != null) privateDns = dns;
    notifyListeners();
  }

  Future<void> loadFeatures() async {
    final ap = await _bridge.autopilotGet();
    if (ap != null) {
      autopilotOn = ap['enabled'] == true;
      final boosted = ap['boosted'];
      if (boosted is List) {
        boostedPackages
          ..clear()
          ..addAll(boosted.map((e) => e.toString()));
      }
    }
    final gf = await _bridge.gameFocusGet();
    if (gf != null) {
      gameFocusOn = gf['enabled'] == true;
      dndGranted = gf['dndGranted'] == true;
    }
    overlayOk = await _bridge.overlayGranted();
    notifyListeners();
  }

  Future<void> setAutopilot(bool value) async {
    autopilotOn = value;
    notifyListeners();
    await _bridge.autopilotSet(value);
  }

  Future<void> setGameFocus(bool value) async {
    gameFocusOn = value;
    notifyListeners();
    await _bridge.gameFocusSet(value);
  }

  /// Full startup sequence: consent -> engine -> boost locks + focus + HUD.
  Future<bool> armBoost() async {
    if (!engineOn) {
      final ok = await _bridge.vpnConsent();
      if (!ok) return false;
      final started = await _bridge.vpnStart();
      if (!started) return false;
      engineOn = true;
    }
    if (!boostOn) {
      final started = await _bridge.boostStart();
      if (!started) {
        // Engine can stay on its own; never leave the UI claiming both.
        notifyListeners();
        return engineOn;
      }
      boostOn = true;
    }
    await refreshPermissions();
    if (overlayOk) {
      await _bridge.bubbleShow('Kaya'); // live monitor over the session
    } else {
      // Never silently skip the live monitor: send the user to the one-tap
      // overlay screen. On return, refresh + show immediately.
      await _bridge.requestOverlay();
      await refreshPermissions();
      if (overlayOk) await _bridge.bubbleShow('Kaya');
    }
    notifyListeners();
    return true;
  }

  Future<void> disarm() async {
    await _bridge.bubbleHide();
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

  /// Launch a game; if it's boosted, auto-pilot arms the full session.
  Future<bool> launchGame(KayaApp app) async {
    final ok = await _bridge.launchApp(app.packageName);
    if (!ok) return false;
    if (!boostedPackages.contains(app.packageName)) return ok;
    // Live monitor must ride along. If the overlay permission is missing,
    // route through it once — the next launch shows the bubble instantly.
    if (!overlayOk) {
      await _bridge.requestOverlay();
      await refreshPermissions();
    }
    if (overlayOk) await _bridge.bubbleShow(app.label);
    return ok;
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
    final boosted = !boostedPackages.remove(packageName);
    if (boosted) boostedPackages.add(packageName);
    // Persist for the native auto-pilot (survives app restarts).
    unawaited(_bridge.autopilotBoost(packageName, boosted));
    notifyListeners();
  }

  void setSearch(String value) {
    search = value;
    notifyListeners();
  }

  /// Re-sync session flags with the native truth: the QS tile or the
  /// home-screen widget may have armed/disarmed the session while this
  /// Flutter engine was cold (or before the UI attached).
  Future<void> _resyncSession() async {
    final state = await _bridge.widgetState();
    if (state == null) return;
    engineOn = state['engine'] == true;
    boostOn = state['boost'] == true;
    final ping = (state['lastPing'] as num?)?.toInt();
    if (ping != null && pingHistory.isEmpty) _addPing(ping);
    notifyListeners();
  }

  // ---- events ----
  void _onEvent(KayaEvent event) {
    switch (event.type) {
      case 'session':
        // Tile/widget/service-driven flag changes. Ping values are ignored
        // here on purpose: probe callers already record their own samples,
        // and duplicates would skew the jitter math.
        final engine = event.data['engine'] as bool?;
        final boost = event.data['boost'] as bool?;
        if (engine != null) engineOn = engine;
        if (boost != null) boostOn = boost;
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
      case 'autopilot':
        final game = event.data['game']?.toString();
        autoGame = event.data['armed'] == true ? game : null;
      case 'focus':
      case 'notif':
      case 'mitigator':
      case 'update':
        // consumed by their feature screens through bridge.events
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


