import 'package:flutter/material.dart';

import 'native/kaya_bridge.dart';
import 'state/app_state.dart';
import 'ui/games_screen.dart';
import 'ui/home_screen.dart';
import 'ui/kaya_logo.dart';
import 'ui/network_screen.dart';
import 'ui/theme.dart';
import 'ui/tuning_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const KayaApp());
}

class KayaApp extends StatelessWidget {
  const KayaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Kaya',
      debugShowCheckedModeBanner: false,
      theme: KayaTheme.dark(),
      home: const KayaShell(),
    );
  }
}

class KayaShell extends StatefulWidget {
  const KayaShell({super.key});

  @override
  State<KayaShell> createState() => _KayaShellState();
}

class _KayaShellState extends State<KayaShell> {
  late final AppState state = AppState(KayaBridge())..loadLibrary();
  int _tab = 0;

  @override
  void dispose() {
    state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: AnimatedBuilder(
        animation: state,
        builder: (context, _) {
          return IndexedStack(
            index: _tab,
            children: [
              HomeScreen(
                state: state,
                onOpenLibrary: () => setState(() => _tab = 1),
              ),
              GamesScreen(state: state),
              NetworkScreen(state: state),
              TuningScreen(state: state),
            ],
          );
        },
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.bolt_rounded),
            selectedIcon: Icon(Icons.bolt_rounded),
            label: 'Boost',
          ),
          NavigationDestination(
            icon: Icon(Icons.sports_esports_rounded),
            selectedIcon: Icon(Icons.sports_esports_rounded),
            label: 'Games',
          ),
          NavigationDestination(
            icon: Icon(Icons.lan_rounded),
            selectedIcon: Icon(Icons.lan_rounded),
            label: 'Network',
          ),
          NavigationDestination(
            icon: Icon(Icons.tune_rounded),
            selectedIcon: Icon(Icons.tune_rounded),
            label: 'Tuning',
          ),
        ],
      ),
      floatingActionButton: _tab == 0
          ? null
          : FloatingActionButton(
              backgroundColor: KayaColors.slate,
              foregroundColor: KayaColors.lane,
              onPressed: () => setState(() => _tab = 0),
              child: const KayaLogo(size: 30),
            ),
    );
  }
}
