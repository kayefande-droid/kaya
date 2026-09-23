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
  List<String> logLines = [];
  bool showLog = false;
  StreamSubscription? _evtSub;

  @override
  void initState() {
    super.initState();
    _loadPads();
    _loadNotifs();
    _loadLog();
    _evtSub = widget.state.bridge.events.listen((e) {
      if (e.type == 'notif') _loadNotifs();
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

  Future<void> _loadPads() async {
    final pads = await widget.state.bridge.listGamepads();
    if (mounted) setState(() => gamepads = pads);
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
                      ScaffoldMessenger.of(this.context).showSnackBar(
                        const SnackBar(
                          content: Text('Grant Do Not Disturb access so calls and '
                              'WhatsApp still come through while games are quieted.'),
                        ),
                      );
                      await state.bridge.gameFocusDnd();
                    }
                    await state.loadFeatures();
                  },
                  title: const Text('Game focus (battery & quiet)'),
                  subtitle: Text(
                    state.dndGranted
                        ? 'On game launch: battery-friendly locks + DND priority '
                            'so only calls and WhatsApp break through.'
                        : 'Needs one-time Do Not Disturb access.',
                  ),
                ),
                if (!state.overlayOk)
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.picture_in_picture_alt_rounded, color: KayaColors.warn),
                    title: const Text('Allow display over other apps'),
                    subtitle: const Text(
                      'Needed once for the floating live monitor during games.',
                    ),
                    trailing: const Icon(Icons.chevron_right_rounded, color: KayaColors.inkFaint),
                    onTap: () async {
                      await state.bridge.requestOverlay();
                      await state.loadFeatures();
                    },
                  ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Boost session'),
          KayaCard(
            child: Column(
              children: [
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
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('VPN consent is required once.')),
                        );
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
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(
                                  content: Text(
                                    'Enable Kaya in Accessibility settings first — '
                                    'it projects controller presses as taps.',
                                  ),
                                ),
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
                          if (url != null) {
                            await widget.state.bridge.updateInstall(url);
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
