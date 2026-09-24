import 'dart:async';

import 'package:flutter/material.dart';

import '../state/app_state.dart';
import 'theme.dart';
import 'widgets.dart';

/// Tuning screen: session locks, battery, controller bridge.
class TuningScreen extends StatefulWidget {
  const TuningScreen({super.key, required this.state});

  final AppState state;

  @override
  State<TuningScreen> createState() => _TuningScreenState();
}

class _TuningScreenState extends State<TuningScreen> {
  List<Map<String, Object?>> gamepads = [];
  bool mappingArmed = false;
  List<Map<String, Object?>> notifs = [];
  Map<String, Object?>? updateInfo;
  bool checkingUpdate = false;
  int downloadPct = -1; // -1 idle · 0..100 download · 100+ verified/handing off
  List<String> logLines = [];
  bool showLog = false;
  String bubbleSide = 'left'; // dock side of the floating live monitor
  StreamSubscription? _evtSub;

  @override
  void initState() {
    super.initState();
    _loadPads();
    _loadNotifs();
    _loadLog();
    _loadBubbleSide();
    _evtSub = widget.state.bridge.events.listen((e) {
      if (e.type == 'notif') _loadNotifs();
      if (e.type == 'update') _onUpdateEvent(e.data);
    });
  }

  @override
  void dispose() {
    _evtSub?.cancel();
    super.dispose();
  }

  Future<void> _loadNotifs() async {
    final list = await widget.state.bridge.notifList();
    if (mounted) setState(() => notifs = list);
  }

  Future<void> _loadLog() async {
    final lines = await widget.state.bridge.crashLogRead();
    if (mounted) setState(() => logLines = lines);
  }

  Future<void> _checkUpdate() async {
    setState(() => checkingUpdate = true);
    final info = await widget.state.bridge.updateCheck();
    if (mounted) {
      setState(() {
        updateInfo = info;
        checkingUpdate = false;
      });
    }
  }

  /// Live download progress for the in-app update, pushed by KayaUpdater
  /// through KayaEventHub ('update' events).
  void _onUpdateEvent(Map<String, Object?> data) {
    if (!mounted) return;
    switch (data['phase']?.toString()) {
      case 'progress':
        setState(() => downloadPct = (data['pct'] as num?)?.toInt() ?? 0);
      case 'verified' || 'installing':
        setState(() => downloadPct = 101);
      case 'failed':
        setState(() => downloadPct = -1);
    }
  }

  Future<void> _loadPads() async {
    final pads = await widget.state.bridge.listGamepads();
    if (mounted) setState(() => gamepads = pads);
  }

  Future<void> _loadBubbleSide() async {
    final side = await widget.state.bridge.bubbleSide();
    if (mounted) setState(() => bubbleSide = side);
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        children: [
          Text('Tuning', style: Theme.of(context).appBarTheme.titleTextStyle),
          const SizedBox(height: 14),
          const SectionHeader(title: 'Automation'),
          const _CardHint(
            topic: 'Automation',
            explanation:
                'Engine auto-pilot arms the DNS engine, radio locks and the '
                'live bubble the moment you launch a boosted game — and '
                'disarms when you leave it. Game Focus quiets non-essential '
                'notifications during a session using Android\'s Do Not '
                'Disturb — while calls ALWAYS ring (from anyone) and call, '
                'media and game volume are never changed.',
          ),
          KayaCard(
            child: Column(
              children: [
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: state.autopilotOn,
                  onChanged: (v) => state.setAutopilot(v),
                  title: const Text('Engine auto-pilot'),
                  subtitle: const Text(
                    'Launching a boosted game arms the fast lane, locks and '
                    'live monitor automatically; disarms after you leave.',
                  ),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: state.gameFocusOn,
                  onChanged: (v) async {
                    await state.setGameFocus(v);
                    if (!mounted) return;
                    if (v && !state.dndGranted) {
                      showKayaSnack(this.context, 'Grant Do Not Disturb access so calls and WhatsApp still come through while games are quieted.');
                      await state.bridge.gameFocusDnd();
                    }
                    await state.loadFeatures();
                  },
                  title: const Text('Game focus (battery & quiet)'),
                  subtitle: Text(
                    state.dndGranted
                        ? 'On game launch: battery-friendly locks + DND quiet '
                            'mode. Calls ALWAYS ring — from anyone — and call, '
                            'media and game volume are never changed.'
                        : 'Needs one-time Do Not Disturb access. Calls always '
                            'ring and volumes are never muted.',
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Boost session'),
          KayaCard(
            child: Column(
              children: [
                // Inline overlay status: shows at a glance whether the floating
                // live monitor will appear during a session, and routes to the
                // one-tap grant when it won't.
                _ActionRow(
                  icon: Icons.picture_in_picture_alt_rounded,
                  title: 'Live monitor (floating bubble)',
                  subtitle: state.overlayOk
                      ? 'Granted — docks on the $bubbleSide edge whenever a session arms.'
                      : 'Not granted — tap to allow "display over other apps" once.',
                  trailing: state.overlayOk
                      ? const Icon(Icons.check_circle_rounded, color: KayaColors.lane)
                      : const Icon(Icons.chevron_right_rounded, color: KayaColors.inkFaint),
                  onTap: () async {
                    if (state.overlayOk) {
                      if (context.mounted) {
                        showKayaSnack(context, 'Overlay ready — the bubble rides over your game on the $bubbleSide edge.');
                      }
                    } else {
                      await state.bridge.requestOverlay();
                      await state.loadFeatures();
                    }
                  },
                ),
                // Dock side: left (default) or right edge of the screen.
                // Applied live — a visible bubble re-docks instantly.
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: Row(
                    children: [
                      const Icon(Icons.swap_horiz_rounded, color: KayaColors.lane),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              'Live monitor dock side',
                              style: TextStyle(fontWeight: FontWeight.w700),
                            ),
                            Text(
                              bubbleSide == 'right'
                                  ? 'Right edge — still draggable up and down.'
                                  : 'Left edge — still draggable up and down.',
                              style: const TextStyle(fontSize: 12.5),
                            ),
                          ],
                        ),
                      ),
                      SegmentedButton<String>(
                        segments: const [
                          ButtonSegment(
                            value: 'left',
                            icon: Icon(Icons.align_horizontal_left_rounded, size: 18),
                            label: Text('Left'),
                          ),
                          ButtonSegment(
                            value: 'right',
                            icon: Icon(Icons.align_horizontal_right_rounded, size: 18),
                            label: Text('Right'),
                          ),
                        ],
                        selected: {bubbleSide},
                        onSelectionChanged: (sel) async {
                          final side = sel.first;
                          setState(() => bubbleSide = side);
                          await widget.state.bridge.setBubbleSide(side);
                        },
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 8),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: state.boostOn,
                  onChanged: (v) async {
                    if (v) {
                      await state.bridge.boostStart();
                    } else {
                      await state.bridge.boostStop();
                    }
                    state.setBoostOn(v);
                  },
                  title: const Text('Performance locks'),
                  subtitle: const Text(
                    'Wi-Fi low-latency mode + CPU wake lock while boosted.',
                  ),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  value: state.engineOn,
                  onChanged: (v) async {
                    if (v) {
                      final ok = await state.armBoost();
                      if (!ok && context.mounted) {
                        showKayaSnack(context, 'VPN consent is required once.');
                      }
                    } else {
                      await state.bridge.vpnStop();
                      state.setEngineOn(false);
                    }
                  },
                  title: const Text('Smart DNS engine'),
                  subtitle: const Text(
                    'On-device matchmaker steering via raced anycast resolvers.',
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Battery & data'),
          const _CardHint(
            topic: 'Battery & data',
            explanation:
                'Battery exemption keeps the boost locks alive when the screen '
                'locks — without it Android throttles the session mid-match. '
                'Usage access only counts which apps use bandwidth in the '
                'background so the live monitor can show it; nothing is read '
                'beyond usage statistics.',
          ),
          KayaCard(
            child: Column(
              children: [
                _ActionRow(
                  icon: Icons.battery_charging_full_rounded,
                  title: 'Ignore battery optimisation',
                  subtitle: state.batteryExempt
                      ? 'Granted — locks hold without throttling.'
                      : 'Let locks survive in the background.',
                  trailing: state.batteryExempt
                      ? const Icon(Icons.check_circle_rounded, color: KayaColors.lane)
                      : const Icon(Icons.chevron_right_rounded, color: KayaColors.inkFaint),
                  onTap: () async {
                    await state.bridge.requestIgnoreBatteryOptimizations();
                    await state.refreshPermissions();
                  },
                ),
                _ActionRow(
                  icon: Icons.dataset_linked_rounded,
                  title: 'Usage access',
                  subtitle: state.usageStatsOk
                      ? 'Granted — background mitigation can see heavy apps.'
                      : 'Needed to sample background traffic.',
                  trailing: state.usageStatsOk
                      ? const Icon(Icons.check_circle_rounded, color: KayaColors.lane)
                      : const Icon(Icons.chevron_right_rounded, color: KayaColors.inkFaint),
                  onTap: () async {
                    await state.bridge.grantUsageStats();
                    await state.refreshPermissions();
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Controller bridge'),
          KayaCard(
            child: Column(
              children: [
                if (gamepads.isEmpty)
                  const Text(
                    'No controllers detected. Pair a PS4 / Xbox / V8 Bluetooth pad, then refresh.',
                    style: TextStyle(color: KayaColors.inkDim, height: 1.4),
                  )
                else
                  for (final pad in gamepads)
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      leading: const Icon(Icons.sports_esports_rounded, color: KayaColors.lane),
                      title: Text(pad['name']?.toString() ?? 'Gamepad'),
                      subtitle: Text(
                        'vendor ${pad['vendor']} · product ${pad['product']}',
                        style: const TextStyle(fontSize: 12),
                      ),
                    ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    OutlinedButton(
                      onPressed: _loadPads,
                      style: OutlinedButton.styleFrom(foregroundColor: KayaColors.lane),
                      child: const Text('Refresh'),
                    ),
                    const SizedBox(width: 10),
                    FilledButton(
                      style: FilledButton.styleFrom(
                        backgroundColor: mappingArmed ? KayaColors.hot : KayaColors.lane,
                        foregroundColor: KayaColors.obsidian,
                      ),
                      onPressed: () async {
                        if (mappingArmed) {
                          await state.bridge.clearControllerMapping();
                          setState(() => mappingArmed = false);
                        } else {
                          final ok = await state.bridge.accessibilityEnabled();
                          if (!ok) {
                            if (context.mounted) {
                              showKayaSnack(
                                context,
                                'Enable Kaya in Accessibility settings first — it projects controller presses as taps.',
                              );
                            }
                            await state.bridge.openAccessibilitySettings();
                            return;
                          }
                          await state.bridge.setControllerMapping([]);
                          await state.bridge.controllerBridgeStart();
                          setState(() => mappingArmed = true);
                        }
                      },
                      child: Text(mappingArmed ? 'Disarm bridge' : 'Arm bridge'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  state.accessibilityOk
                      ? 'Accessibility: enabled — Kaya can project controller input.'
                      : 'Accessibility: off. The bridge needs it to inject taps for '
                          'games without native controller support.',
                  style: const TextStyle(color: KayaColors.inkFaint, fontSize: 12, height: 1.4),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          SectionHeader(
            title: 'Notifications',
            action: TextButton(
              onPressed: _loadNotifs,
              child: const Text('Refresh'),
            ),
          ),
          KayaCard(
            child: Column(
              children: [
                if (notifs.isEmpty)
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          'No notifications mirrored yet. Kaya reads them on-device '
                          'only when you grant Notification access — nothing leaves '
                          'your phone.',
                          style: TextStyle(color: KayaColors.inkDim, height: 1.4),
                        ),
                      ),
                      TextButton(
                        onPressed: () => widget.state.bridge.notifGrant(),
                        child: const Text('Grant'),
                      ),
                    ],
                  )
                else ...[
                  Row(
                    children: [
                      Text(
                        '${notifs.where((n) => n['read'] != true).length} unread',
                        style: const TextStyle(
                          color: KayaColors.lane,
                          fontWeight: FontWeight.w800,
                          fontSize: 12.5,
                        ),
                      ),
                      const Spacer(),
                      TextButton(
                        onPressed: () async {
                          await widget.state.bridge.notifMarkAll();
                          await _loadNotifs();
                        },
                        child: const Text('Mark all read'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  for (final n in notifs.take(12))
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      leading: Icon(
                        n['read'] == true
                            ? Icons.notifications_none_rounded
                            : Icons.notifications_active_rounded,
                        color: n['read'] == true ? KayaColors.inkFaint : KayaColors.lane,
                      ),
                      title: Text(
                        '${n['appLabel'] ?? ''} · ${n['title'] ?? ''}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontWeight: n['read'] == true ? FontWeight.w500 : FontWeight.w800,
                          fontSize: 13,
                        ),
                      ),
                      subtitle: Text(
                        '${n['text'] ?? ''}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 12),
                      ),
                      trailing: IconButton(
                        icon: const Icon(Icons.close_rounded, size: 18),
                        onPressed: () async {
                          await widget.state.bridge.notifClear(n['key']?.toString() ?? '');
                          await _loadNotifs();
                        },
                      ),
                      onTap: () async {
                        await widget.state.bridge.notifMarkRead(n['key']?.toString() ?? '');
                        await _loadNotifs();
                      },
                    ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Updates'),
          KayaCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  updateInfo == null
                      ? 'Kaya updates straight from the GitHub releases — no store,'
                          ' no accounts.'
                      : (updateInfo!['updateAvailable'] == true
                          ? 'Kaya ${updateInfo!['latestVersion']} is available '
                              '(you have ${updateInfo!['currentVersion']}).'
                          : 'You are on the latest release '
                              '(${updateInfo!['currentVersion']}).'),
                  style: const TextStyle(height: 1.4),
                ),
                if (downloadPct >= 0) ...[
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: ClipRRect(
                          borderRadius: BorderRadius.circular(6),
                          child: LinearProgressIndicator(
                            value: downloadPct >= 100 ? null : downloadPct / 100,
                            minHeight: 6,
                            backgroundColor: KayaColors.lane.withValues(alpha: 0.15),
                            valueColor: const AlwaysStoppedAnimation<Color>(KayaColors.lane),
                          ),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Text(
                        downloadPct >= 100
                            ? 'Checksum verified — installer…'
                            : '$downloadPct%',
                        style: const TextStyle(fontSize: 12, color: KayaColors.lane),
                      ),
                    ],
                  ),
                ],
                const SizedBox(height: 10),
                Row(
                  children: [
                    FilledButton.icon(
                      style: FilledButton.styleFrom(
                        backgroundColor: KayaColors.lane,
                        foregroundColor: KayaColors.obsidian,
                      ),
                      onPressed: checkingUpdate ? null : _checkUpdate,
                      icon: checkingUpdate
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: KayaColors.obsidian,
                              ),
                            )
                          : const Icon(Icons.system_update_alt_rounded),
                      label: const Text('Check for updates'),
                    ),
                    if (updateInfo?['updateAvailable'] == true) ...[
                      const SizedBox(width: 10),
                      OutlinedButton.icon(
                        style: OutlinedButton.styleFrom(
                          foregroundColor: KayaColors.lane,
                          side: BorderSide(color: KayaColors.lane.withValues(alpha: 0.5)),
                        ),
                        onPressed: () async {
                          final url = updateInfo?['apkUrl']?.toString();
                          if (url == null) return;
                          setState(() => downloadPct = 0);
                          final ok = await widget.state.bridge.updateInstall(url);
                          if (!context.mounted) return;
                          setState(() => downloadPct = -1);
                          if (ok) {
                            showKayaSnack(context, 'Checksum verified against SHA256SUMS.txt — handing to the installer.');
                          } else {
                            showKayaSnack(context, 'Update cancelled: checksum could not be verified. Nothing was installed.');
                          }
                        },
                        icon: const Icon(Icons.download_rounded, size: 18),
                        label: const Text('Download & install'),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Diagnostics'),
          KayaCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        logLines.isEmpty
                            ? 'No errors recorded. If the app ever closes unexpectedly, '
                                'the reason lands here.'
                            : '${logLines.length} recent lines — tap to ${showLog ? 'hide' : 'view'}.',
                        style: const TextStyle(color: KayaColors.inkDim, fontSize: 12.5, height: 1.4),
                      ),
                    ),
                    if (logLines.isNotEmpty)
                      TextButton(
                        onPressed: () => setState(() => showLog = !showLog),
                        child: Text(showLog ? 'Hide' : 'View'),
                      ),
                    if (logLines.isNotEmpty)
                      TextButton(
                        onPressed: () async {
                          await widget.state.bridge.crashLogClear();
                          await _loadLog();
                        },
                        child: const Text('Clear'),
                      ),
                  ],
                ),
                if (showLog)
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: KayaColors.obsidian,
                      borderRadius: BorderRadius.circular(KayaRadius.chip),
                      border: Border.all(color: KayaColors.hairline),
                    ),
                    child: SelectableText(
                      logLines.join('\n'),
                      style: const TextStyle(fontSize: 10.5, color: KayaColors.inkDim, height: 1.35),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'About Kaya'),
          const KayaCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Zero servers. Zero accounts.',
                  style: TextStyle(fontWeight: FontWeight.w800),
                ),
                SizedBox(height: 6),
                Text(
                  'The DNS engine races public anycast resolvers from this device; '
                  'boost locks are Android platform APIs; nothing leaves your phone '
                  'except the game traffic itself.',
                  style: TextStyle(color: KayaColors.inkDim, fontSize: 12.5, height: 1.45),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// One-line explainer above a section card: topic in bold, then the "why".
/// Cheap, always-visible education so users don't have to leave the screen.
class _CardHint extends StatelessWidget {
  const _CardHint({required this.topic, required this.explanation});

  final String topic;
  final String explanation;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 6, right: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.help_outline_rounded, size: 15, color: KayaColors.inkFaint),
          const SizedBox(width: 6),
          Expanded(
            child: Text.rich(
              TextSpan(
                children: [
                  TextSpan(
                    text: '$topic — ',
                    style: const TextStyle(
                      color: KayaColors.inkDim,
                      fontWeight: FontWeight.w700,
                      fontSize: 11.5,
                    ),
                  ),
                  TextSpan(
                    text: explanation,
                    style: const TextStyle(
                      color: KayaColors.inkFaint,
                      fontSize: 11.5,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ActionRow extends StatelessWidget {
  const _ActionRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.trailing,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final Widget trailing;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      onTap: onTap,
      leading: Icon(icon, color: KayaColors.lane),
      title: Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
      subtitle: Text(subtitle, style: const TextStyle(fontSize: 12.5)),
      trailing: trailing,
    );
  }
}
