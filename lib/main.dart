import 'package:flutter/material.dart';

import 'native/kaya_bridge.dart';
import 'state/app_state.dart';
import 'state/theme_state.dart';
import 'ui/games_screen.dart';
import 'ui/home_screen.dart';
import 'ui/kaya_backdrop.dart';
import 'ui/network_screen.dart';
import 'ui/theme.dart';
import 'ui/tuning_screen.dart';
import 'ui/tutorial_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const KayaApp());
}

class KayaApp extends StatelessWidget {
  const KayaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: themeState,
      builder: (context, _) {
        return MaterialApp(
          title: 'Kaya',
          debugShowCheckedModeBanner: false,
          theme: KayaTheme.dark(accent: themeState.accent),
          home: const KayaShell(),
        );
      },
    );
  }
}

/// Global theme state (single-shell app; a ChangeNotifier is plenty).
final ThemeState themeState = ThemeState();

class KayaShell extends StatefulWidget {
  const KayaShell({super.key});

  @override
  State<KayaShell> createState() => _KayaShellState();
}

class _KayaShellState extends State<KayaShell> with WidgetsBindingObserver {
  late final AppState state = AppState(KayaBridge.shared)..loadLibrary();
  int _tab = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _maybeShowTutorial();
  }

  /// First run only: open the tutorial once, then never again (persisted).
  Future<void> _maybeShowTutorial() async {
    if (await TutorialScreen.seen(state)) return;
    if (!mounted) return;
    await TutorialScreen.markSeen(state); // set early: no loop on back-out
    if (!mounted) return;
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => TutorialScreen(state: state)),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    state.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    // Returning from the overlay-permission screen: re-check and surface the
    // live monitor immediately if the user granted it.
    if (s == AppLifecycleState.resumed) {
      state.onResumed();
    }
  }

  static const _destinations = [
    (Icons.bolt_rounded, 'Boost'),
    (Icons.sports_esports_rounded, 'Games'),
    (Icons.lan_rounded, 'Network'),
    (Icons.tune_rounded, 'Tuning'),
  ];

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: Listenable.merge([state, themeState]),
      builder: (context, _) {
        final active = state.engineOn || state.boostOn;
        return Scaffold(
          extendBody: true,
          backgroundColor: Colors.transparent,
          body: KayaBackdrop(
            accent: themeState.accent,
            boostActive: active,
            child: SafeArea(
              bottom: false,
              child: IndexedStack(
                index: _tab,
                children: [
                  HomeScreen(state: state, onOpenLibrary: () => setState(() => _tab = 1)),
                  GamesScreen(state: state),
                  NetworkScreen(state: state),
                  TuningScreen(state: state),
                ],
              ),
            ),
          ),
          bottomNavigationBar: NavigationBar(
            selectedIndex: _tab,
            onDestinationSelected: (i) => setState(() => _tab = i),
            backgroundColor: KayaColors.charcoal.withValues(alpha: 0.96),
            destinations: [
              for (final (icon, label) in _destinations)
                NavigationDestination(
                  icon: Icon(icon),
                  selectedIcon: Icon(icon),
                  label: label,
                ),
            ],
          ),
          endDrawer: Drawer(
            backgroundColor: KayaColors.charcoal,
            width: 300,
            child: SafeArea(
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 8, vertical: 12),
                    child: Text(
                      'THEME',
                      style: TextStyle(
                        color: KayaColors.inkFaint,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 2,
                        fontSize: 12,
                      ),
                    ),
                  ),
                  for (final option in AccentOption.values)
                    ListTile(
                      leading: Container(
                        width: 28,
                        height: 28,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: option.accent,
                          border: Border.all(
                            color: themeState.option == option
                                ? KayaColors.ink
                                : Colors.transparent,
                            width: 2.5,
                          ),
                        ),
                      ),
                      title: Text(option.label),
                      trailing: themeState.option == option
                          ? const Icon(Icons.check_rounded, color: KayaColors.ink)
                          : null,
                      onTap: () {
                        themeState.option = option;
                        Navigator.of(context).pop();
                      },
                    ),
                  const Divider(height: 32),
                  ListTile(
                    leading: const Icon(Icons.open_in_new_rounded),
                    title: const Text('Project website'),
                    onTap: () {
                      Navigator.of(context).pop();
                      state.bridge.toast('Open kaya-booster.onrender.com in your browser');
                    },
                  ),
                  ListTile(
                    leading: const Icon(Icons.school_rounded),
                    title: const Text('How Kaya works'),
                    onTap: () {
                      Navigator.of(context).pop();
                      Navigator.of(context).push(
                        MaterialPageRoute<void>(
                            builder: (_) => TutorialScreen(state: state)),
                      );
                    },
                  ),
                  ListTile(
                    leading: const Icon(Icons.verified_user_rounded),
                    title: const Text('Safety & checksums'),
                    onTap: () {
                      Navigator.of(context).pop();
                      state.bridge.toast('SHA256SUMS.txt ships with every GitHub release');
                    },
                  ),
                  const AboutListTile(
                    icon: Icon(Icons.info_outline_rounded),
                    applicationName: 'Kaya',
                    applicationVersion: '1.1.7',
                    aboutBoxChildren: [
                      Text('Zero servers. Zero accounts. Hand-built.'),
                    ],
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
