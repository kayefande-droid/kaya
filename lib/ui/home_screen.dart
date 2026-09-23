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
      if (mounted) showKayaSnack(context, 'Fast lane disengaged.');
      return;
    }
    final ok = await state.armBoost();
    if (!mounted) return;
    if (ok && state.boostOn) {
      HapticFeedback.heavyImpact();
      showKayaSnack(context, 'Fast lane live: DNS steered, locks held.');
    } else if (ok) {
      showKayaSnack(context, 'Engine armed, but the boost locks were refused — check Battery → Unrestricted, then retry.');
    } else {
      showKayaSnack(context, 'VPN consent denied — the engine needs it once.');
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
              Flexible(child: _StatusPill(active: active)),
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
          AutoPilotCard(state: state, onOpenLibrary: widget.onOpenLibrary),
          const SizedBox(height: 14),
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
          // Bottom inset so the last line never hides under the nav bar.
          Padding(
            padding: EdgeInsets.only(bottom: MediaQuery.paddingOf(context).bottom + 72),
            child: Text(
              state.batteryExempt
                  ? 'Battery: unrestricted — locks can hold at full performance.'
                  : 'Battery optimisation may throttle the boost. Allow "unrestricted" for Kaya.',
              style: const TextStyle(color: KayaColors.inkFaint, fontSize: 12, height: 1.4),
            ),
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
        mainAxisSize: MainAxisSize.min,
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

/// Auto-arming indicator: shows the auto-pilot / floating-monitor state.
///
/// - A boosted game is live -> green: "auto-armed" with the game name,
///   and the floating monitor pill is confirmed on-screen.
/// - Waiting state -> dim: which watched signal is missing, with a direct
///   jump to fix it (usage access / overlay permission / game library).
class AutoPilotCard extends StatelessWidget {
  const AutoPilotCard({super.key, required this.state, required this.onOpenLibrary});

  final AppState state;
  final VoidCallback onOpenLibrary;

  @override
  Widget build(BuildContext context) {
    final armedGame = state.autoGame;
    final waiting = state.autopilotOn && armedGame == null;

    if (armedGame != null) {
      return KayaCard(
        accent: true,
        child: Row(
          children: [
            const Icon(Icons.radar_rounded, color: KayaColors.lane),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'AUTO-ARMED · ${armedGame.toUpperCase()}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: KayaColors.lane,
                      fontSize: 12,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 0.8,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    state.overlayOk
                        ? 'Live monitor on-screen · stands down when you leave'
                        : 'Monitor needs "Display over apps" — grant it in Tuning',
                    style: const TextStyle(
                      color: KayaColors.inkDim,
                      fontSize: 11.5,
                      height: 1.35,
                    ),
                  ),
                ],
              ),
            ),
            const _PulseDot(),
          ],
        ),
      );
    }

    return KayaCard(
      // Waiting state taps through to the library: mark games to watch.
      onTap: onOpenLibrary,
      child: Row(
      children: [
        Icon(
          Icons.radar_rounded,
          color: waiting ? KayaColors.inkFaint : KayaColors.lane,
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                waiting ? 'AUTO-ARM WAITING' : 'AUTO-ARM IS OFF',
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 0.8,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                waiting
                    ? 'Watching for boosted games — tap to pick some in the library.'
                    : 'Auto-pilot is off. Boosted games won\'t arm the fast lane automatically.',
                style: const TextStyle(
                  color: KayaColors.inkDim,
                  fontSize: 11.5,
                  height: 1.35,
                ),
              ),
            ],
          ),
        ),
        if (!waiting)
          TextButton(
            onPressed: () => state.setAutopilot(true),
            child: const Text('Turn on'),
          )
        else if (!state.usageStatsOk)
          TextButton(
            onPressed: () => state.bridge.grantUsageStats(),
            child: const Text('Fix'),
          )
        else if (!state.overlayOk)
          TextButton(
            onPressed: () => state.bridge.requestOverlay(),
            child: const Text('Fix'),
          )
        else
          const Icon(Icons.chevron_right_rounded, color: KayaColors.inkFaint)
      ],
      ),
    );
  }
}

/// Small pulsing green dot for the armed state.
class _PulseDot extends StatefulWidget {
  const _PulseDot();

  @override
  State<_PulseDot> createState() => _PulseDotState();
}

class _PulseDotState extends State<_PulseDot> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1400),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: Tween(begin: 0.35, end: 1.0).animate(
        CurvedAnimation(parent: _c, curve: Curves.easeInOut),
      ),
      child: Container(
        width: 9,
        height: 9,
        decoration: const BoxDecoration(
          shape: BoxShape.circle,
          color: KayaColors.lane,
        ),
      ),
    );
  }
}
