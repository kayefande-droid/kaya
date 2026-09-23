import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../native/kaya_bridge.dart';
import '../state/app_state.dart';
import '../widgets/app_icon.dart';
import 'theme.dart';
import 'widgets.dart';

/// The game library. Detected games float to the top; every app is boostable.
class GamesScreen extends StatelessWidget {
  const GamesScreen({super.key, required this.state});

  final AppState state;

  @override
  Widget build(BuildContext context) {
    final query = state.search.toLowerCase();
    final games = state.library
        .where((a) => a.isGame && (query.isEmpty || a.label.toLowerCase().contains(query)))
        .toList();
    final others = state.library
        .where((a) => !a.isGame && (query.isEmpty || a.label.toLowerCase().contains(query)))
        .toList();

    return SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 6),
            child: Row(
              children: [
                Text(
                  'Games',
                  style: Theme.of(context).appBarTheme.titleTextStyle,
                ),
                const Spacer(),
                IconButton(
                  onPressed: () => state.loadLibrary(),
                  icon: const Icon(Icons.refresh_rounded),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: TextField(
              onChanged: state.setSearch,
              decoration: InputDecoration(
                hintText: 'Search installed apps',
                prefixIcon: const Icon(Icons.search_rounded, color: KayaColors.inkFaint),
                filled: true,
                fillColor: KayaColors.slate,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(KayaRadius.chip),
                  borderSide: BorderSide.none,
                ),
              ),
            ),
          ),
          Expanded(
            child: state.libraryLoading
                ? const Center(child: CircularProgressIndicator(color: KayaColors.lane))
                : RefreshIndicator(
                    color: KayaColors.lane,
                    onRefresh: () => state.loadLibrary(),
                    child: ListView(
                      padding: const EdgeInsets.fromLTRB(20, 14, 20, 24),
                      children: [
                        if (games.isNotEmpty) ...[
                          const SectionHeader(title: 'Detected FPS & Games'),
                          Wrap(
                            spacing: 10,
                            runSpacing: 10,
                            children: [
                              for (final app in games) _GameCard(state: state, app: app),
                            ],
                          ),
                        ],
                        if (others.isNotEmpty) ...[
                          const SizedBox(height: 18),
                          const SectionHeader(title: 'All apps'),
                          Wrap(
                            spacing: 10,
                            runSpacing: 10,
                            children: [
                              for (final app in others) _GameCard(state: state, app: app),
                            ],
                          ),
                        ],
                        if (state.library.isEmpty)
                          const KayaCard(
                            child: Text(
                              'No launchable apps visible yet — tap refresh, or grant the library a moment.',
                              style: TextStyle(color: KayaColors.inkDim),
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

class _GameCard extends StatelessWidget {
  const _GameCard({required this.state, required this.app});

  final AppState state;
  final KayaApp app;

  @override
  Widget build(BuildContext context) {
    final boosted = state.boostedPackages.contains(app.packageName);
    return KayaCard(
      accent: boosted,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      child: SizedBox(
        width: 168,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                AppIcon(packageName: app.packageName, label: app.label, size: 40),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    app.label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () {
                      HapticFeedback.selectionClick();
                      state.toggleBoosted(app.packageName);
                    },
                    icon: Icon(
                      boosted ? Icons.bolt_rounded : Icons.bolt_rounded,
                      size: 17,
                    ),
                    label: Text(boosted ? 'Auto-boost' : 'Boost'),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: boosted ? KayaColors.lane : KayaColors.ink,
                      side: BorderSide(
                        color: boosted ? KayaColors.lane : KayaColors.hairline,
                      ),
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  style: IconButton.styleFrom(
                    backgroundColor: KayaColors.lane,
                    foregroundColor: KayaColors.obsidian,
                  ),
                  onPressed: () async {
                    HapticFeedback.heavyImpact();
                    final ok = await state.launchGame(app);
                    if (!ok && context.mounted) {
                      showKayaSnack(context, 'Could not launch ${app.label}');
                    }
                  },
                  icon: const Icon(Icons.play_arrow_rounded),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
