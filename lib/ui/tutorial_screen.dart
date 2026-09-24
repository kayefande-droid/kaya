import 'package:flutter/material.dart';

import '../state/app_state.dart';
import 'theme.dart';
import 'widgets.dart';

/// In-app tutorial: first-run auto-open (once, persisted) plus a permanent
/// "How Kaya works" entry in Tuning → Learn. Short, honest, skippable.
class TutorialScreen extends StatefulWidget {
  const TutorialScreen({super.key, required this.state});

  final AppState state;

  @override
  State<TutorialScreen> createState() => _TutorialScreenState();

  static const _prefsKey = 'tutorial_seen';

  /// True when the tutorial has been completed at least once.
  static Future<bool> seen(AppState state) =>
      state.bridge.prefsGetBool(_prefsKey);

  /// Mark the tutorial as completed.
  static Future<void> markSeen(AppState state) =>
      state.bridge.prefsSetBool(_prefsKey, true);
}

class _TutorialScreenState extends State<TutorialScreen> {
  int _step = 0;
  late final List<_StepDef> _steps = _buildSteps(context);

  static List<_StepDef> _buildSteps(BuildContext context) {
    return [
      _StepDef(
        icon: Icons.bolt_rounded,
        title: '1 · What Kaya does',
        body: 'Kaya speeds up how your phone reaches game servers — the DNS '
            'handshakes and the radio path. It never modifies your game, '
            'never injects input, and never reads game traffic. If a number '
            'is shown, it was really measured on your phone.',
      ),
      _StepDef(
        icon: Icons.vpn_key_rounded,
        title: '2 · Arm the Fast Lane',
        body: 'Boost tab → tap BOOST. The first time, Android asks you to '
            'allow the on-device DNS engine (the VPN prompt — that\'s Kaya\'s '
            'engine, not a VPN company). The floating live monitor docks on '
            'the screen edge during every session.',
      ),
      _StepDef(
        icon: Icons.sports_esports_rounded,
        title: '3 · Pick your game',
        body: 'Games tab → tap a title to boost it manually, or flip '
            'Auto-boost so arming happens the moment you launch the game. '
            'Every installed app is boostable — games just float to the top.',
      ),
      _StepDef(
        icon: Icons.speed_rounded,
        title: '4 · Measure, don\'t guess',
        body: 'Network tab → run both benchmarks. Resolver benchmark: which '
            'DNS is fastest for you. Game benchmark: real handshakes to your '
            'game\'s servers. Run before and after arming to see exactly what '
            'the Fast Lane changes.',
      ),
      _StepDef(
        icon: Icons.phone_in_talk_rounded,
        title: '5 · Calls always come through',
        body: 'Game Focus quiets notifications during a session, but it is '
            'built so calls ALWAYS ring — from anyone, not just favorite '
            'contacts — and your alarms, media and game volume are never '
            'touched. You decide everything in Tuning.',
      ),
      _StepDef(
        icon: Icons.tune_rounded,
        title: '6 · Tune it your way',
        body: 'Tuning: dock the live monitor left or right, keep sessions '
            'alive past lock (battery), map a controller, and more. Every '
            'section has a "why" hint — tap the ? icon in each card if '
            'something isn\'t clear.',
      ),
    ];
  }

  @override
  Widget build(BuildContext context) {
    final step = _steps[_step];
    final last = _step == _steps.length - 1;
    return Scaffold(
      backgroundColor: KayaColors.obsidian,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text('How Kaya works',
                      style: Theme.of(context).appBarTheme.titleTextStyle),
                  const Spacer(),
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Skip'),
                  ),
                ],
              ),
              const SizedBox(height: 24),
              Expanded(
                child: Center(
                  child: KayaCard(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(step.icon, color: KayaColors.lane, size: 34),
                        const SizedBox(height: 14),
                        Text(step.title,
                            style: const TextStyle(
                                fontSize: 19, fontWeight: FontWeight.w800)),
                        const SizedBox(height: 10),
                        Text(step.body,
                            style: const TextStyle(
                                height: 1.5, fontSize: 14.5,
                                color: KayaColors.inkDim)),
                      ],
                    ),
                  ),
                ),
              ),
              Row(
                children: [
                  for (var i = 0; i < _steps.length; i++)
                    Container(
                      width: i == _step ? 18 : 7,
                      height: 7,
                      margin: const EdgeInsets.only(right: 6),
                      decoration: BoxDecoration(
                        color: i == _step
                            ? KayaColors.lane
                            : KayaColors.hairline,
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 14),
              Row(
                children: [
                  if (_step > 0)
                    OutlinedButton(
                      onPressed: () => setState(() => _step--),
                      style: OutlinedButton.styleFrom(
                          foregroundColor: KayaColors.inkDim),
                      child: const Text('Back'),
                    ),
                  const Spacer(),
                  FilledButton(
                    style: FilledButton.styleFrom(
                      backgroundColor: KayaColors.lane,
                      foregroundColor: KayaColors.obsidian,
                    ),
                    onPressed: () {
                      if (last) {
                        TutorialScreen.markSeen(widget.state);
                        Navigator.of(context).pop();
                      } else {
                        setState(() => _step++);
                      }
                    },
                    child: Text(last ? 'Got it' : 'Next'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StepDef {
  _StepDef({required this.icon, required this.title, required this.body});

  final IconData icon;
  final String title;
  final String body;
}
