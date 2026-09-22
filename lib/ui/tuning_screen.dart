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

  @override
  void initState() {
    super.initState();
    _loadPads();
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
