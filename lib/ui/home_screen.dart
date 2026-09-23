import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../state/app_state.dart';
import 'network_screen.dart';
import 'theme.dart';
import 'widgets.dart';
import 'kaya_logo.dart';

/// Home dashboard: the Boost & Launch pulse button sits at the center,
/// ringed by live latency chips and the recent DNS steering feed.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.state, required this.onOpenLibrary});

  final AppState state;
  final VoidCallback onOpenLibrary;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  void initState() {
    super.initState();
    widget.state.startBenchmark();
  }

  @override
  void dispose() {
    widget.state.stopBenchmark();
    super.dispose();
  }

  Future<void> _toggleBoost() async {
    HapticFeedback.mediumImpact();
    final state = widget.state;
    if (state.engineOn || state.boostOn) {
      await state.disarm();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Fast lane disengaged.')),
        );
      }
      return;
    }
    final ok = await state.armBoost();
    if (!mounted) return;
    if (ok && state.boostOn) {
      HapticFeedback.heavyImpact();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Fast lane live: DNS steered, locks held.')),
      );
    } else if (ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Engine armed, but the boost locks were refused — '
              'check Battery → Unrestricted, then retry.'),
        ),
      );
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('VPN consent denied — the engine needs it once.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final active = state.engineOn || state.boostOn;
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        children: [
          Row(
            children: [
              const KayaLogo(size: 40),
              const SizedBox(width: 12),
              const Text(
                'KAYA',
                style: TextStyle(
                  fontSize: 22,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 3.5,
                ),
              ),
              const Spacer(),
              _StatusPill(active: active),
            ],
          ),
          const SizedBox(height: 28),
          Center(
            child: BoostButton(
              label: active ? 'FAST LANE LIVE' : 'BOOST',
              active: active,
              onPressed: _toggleBoost,
            ),
          ),
          const SizedBox(height: 18),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              PingChip(ms: state.lastPing, compact: false),
              const SizedBox(width: 10),
              _MiniStat(label: 'JITTER', value: state.jitter > 0 ? '${state.jitter.round()}' : '—'),
              const SizedBox(width: 18),
              _MiniStat(label: 'STABILITY', value: '${state.stability.round()}%'),
            ],
          ),
          const SizedBox(height: 18),
          KayaCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'LIVE LATENCY',
                  style: TextStyle(
                    color: KayaColors.inkFaint,
                    fontSize: 10.5,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 1.2,
                  ),
                ),
                const SizedBox(height: 8),
                PingSparkline(samples: state.pingHistory),
              ],
            ),
          ),
          const SizedBox(height: 14),
          SectionHeader(
            title: 'DNS Steering Feed',
            action: TextButton(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => Scaffold(
                    appBar: AppBar(title: const Text('Smart DNS engine')),
                    body: NetworkScreen(state: widget.state),
                  ),
                ),
              ),
              child: const Text('Open engine'),
            ),
          ),
          if (state.dnsLog.isEmpty)
            const KayaCard(
              child: Text(
                'Nothing resolved yet. Launch a game and the engine will show every matchmaker lookup it steers here.',
                style: TextStyle(color: KayaColors.inkDim, height: 1.4),
              ),
            )
          else
            ...state.dnsLog.take(5).map(
                  (t) => Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: KayaCard(
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                      accent: t.category == 'game',
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              t.domain,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontWeight: FontWeight.w600),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Text(
                            t.resolver,
                            style: const TextStyle(color: KayaColors.lane, fontSize: 12.5),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
          const SizedBox(height: 14),
          KayaCard(
            child: Row(
              children: [
                const Icon(Icons.sports_esports_rounded, color: KayaColors.lane),
                const SizedBox(width: 12),
                const Expanded(
                  child: Text(
                    'Boost a game',
                    style: TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
                OutlinedButton.icon(
                  onPressed: widget.onOpenLibrary,
                  icon: const Icon(Icons.grid_view_rounded, size: 18),
                  label: const Text('Library'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: KayaColors.lane,
                    side: BorderSide(color: KayaColors.lane.withValues(alpha: 0.5)),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          Text(
            state.batteryExempt
                ? 'Battery: unrestricted — locks can hold at full performance.'
                : 'Battery optimisation may throttle the boost. Allow "unrestricted" for Kaya.',
            style: const TextStyle(color: KayaColors.inkFaint, fontSize: 12, height: 1.4),
          ),
        ],
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.active});

  final bool active;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: active ? KayaColors.lane.withValues(alpha: 0.15) : KayaColors.slate,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(
          color: active ? KayaColors.lane : KayaColors.hairline,
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: active ? KayaColors.lane : KayaColors.inkFaint,
            ),
          ),
          const SizedBox(width: 8),
          Text(
            active ? 'FAST LANE' : 'IDLE',
            style: TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w800,
              letterSpacing: 1.4,
              color: active ? KayaColors.lane : KayaColors.inkFaint,
            ),
          ),
        ],
      ),
    );
  }
}

class _MiniStat extends StatelessWidget {
  const _MiniStat({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(
          value,
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
        ),
        Text(
          label,
          style: const TextStyle(
            color: KayaColors.inkFaint,
            fontSize: 10,
            fontWeight: FontWeight.w700,
            letterSpacing: 1.1,
          ),
        ),
      ],
    );
  }
}
