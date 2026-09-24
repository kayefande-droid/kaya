import 'dart:convert';

import 'package:flutter/material.dart';

import '../state/app_state.dart';
import 'theme.dart';
import 'widgets.dart';

/// Network screen: the Smart Anycast DNS engine's control room.
/// Benchmark resolvers, inspect the steering rules, pin Private DNS.
class NetworkScreen extends StatefulWidget {
  const NetworkScreen({super.key, required this.state});

  final AppState state;

  @override
  State<NetworkScreen> createState() => _NetworkScreenState();
}

class _NetworkScreenState extends State<NetworkScreen> {
  Map<String, int?> benchmarks = {};
  Map<String, int?> gameBench = {};
  Map<String, int> gameBest = {};
  Map<String, int> gameWorst = {};
  Map<String, int>? gameFirstRun; // first benchmark baseline (persisted)
  Map<String, int>? gameDelta; // median - baseline, per endpoint
  static const baselineKey = 'bench_baseline_v1';
  Map<String, Map<String, String>> endpointHosts = {}; // 'game · label' -> {host}
  List<String> gameHosts = [];
  Map<String, String> winners = {};
  bool benching = false;
  bool gameBenching = false;

  static const resolvers = [
    ('Cloudflare', '1.1.1.1'),
    ('Google', '8.8.8.8'),
    ('Quad9', '9.9.9.9'),
    ('OpenDNS', '208.67.222.222'),
  ];

  static const resolverNote =
    'These are the resolvers Kaya races from your device. Lower DNS ms '
    'means faster matchmaker lookups; the engine keeps the winner per domain.';
  // Surfaced beneath the resolver card; kept here so the wording stays
  // in one place with the benchmark code.

  @override
  void initState() {
    super.initState();
    _load();
    _loadEndpoints();
    _loadBaseline();
  }

  /// Baseline survives restarts: "before" keeps meaning before, even if the
  /// app was killed between the two runs.
  Future<void> _loadBaseline() async {
    final raw = await widget.state.bridge.prefsGetString(baselineKey);
    if (raw == null || !mounted) return;
    try {
      final decoded = (jsonDecode(raw) as Map).cast<String, dynamic>().map(
            (k, v) => MapEntry(k, (v as num).toInt()),
          );
      setState(() => gameFirstRun = decoded);
    } catch (_) {
      // Corrupt baseline: ignore, next run becomes the new baseline.
    }
  }

  Future<void> _saveBaseline() async {
    final base = gameFirstRun;
    if (base == null) return;
    await widget.state.bridge.prefsSetString(baselineKey, jsonEncode(base));
  }

  Future<void> _loadEndpoints() async {
    final rows = await widget.state.bridge.endpointList();
    if (!mounted) return;
    setState(() {
      for (final r in rows) {
        endpointHosts['${r['game']} · ${r['label']}'] = {
          'host': (r['host'] ?? '') as String,
        };
      }
    });
  }

  Future<void> _load() async {
    final hosts = await widget.state.bridge.getGameHosts();
    final w = await widget.state.bridge.getSteeringWinners();
    if (mounted) {
      setState(() {
        gameHosts = hosts;
        winners = w;
      });
    }
  }

  Future<void> _benchmark() async {
    setState(() => benching = true);
    // Race every resolver in parallel (three rounds, median kept natively
    // per probe call) so one slow resolver doesn't stretch the whole run.
    final futures = <Future<void>>[
      for (final (name, ip) in resolvers)
        widget.state.bridge
            .dnsProbe(ip, 'www.activision.com')
            .then((ms) => benchmarks[name] = ms),
    ];
    await Future.wait(futures);
    if (mounted) setState(() => benching = false);
  }

  Future<void> _gameBenchmark() async {
    setState(() => gameBenching = true);
    final rows = await widget.state.bridge.gameBenchmark(rounds: 5);
    final results = <String, int?>{};
    final best = <String, int>{};
    final worst = <String, int>{};
    for (final row in rows) {
      final label = '${row['game']} · ${row['label']}';
      results[label] = (row['ms'] as num?)?.toInt();
      final b = (row['bestMs'] as num?)?.toInt();
      final w = (row['worstMs'] as num?)?.toInt();
      if (b != null) best[label] = b;
      if (w != null) worst[label] = w;
    }
    if (!mounted) return;
    // Delta against the persisted first run (before/after arming).
    var becameBaseline = false;
    Map<String, int>? delta;
    final base = gameFirstRun;
    if (base == null) {
      gameFirstRun = Map<String, int>.from(
        results..removeWhere((_, v) => v == null),
      );
      becameBaseline = true;
    } else {
      delta = {
        for (final e in results.entries)
          if (e.value != null && base[e.key] != null) e.key: e.value! - base[e.key]!,
      };
    }
    setState(() {
      gameBench = results;
      gameBest = best;
      gameWorst = worst;
      gameDelta = delta;
      gameBenching = false;
    });
    if (becameBaseline) await _saveBaseline();
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        children: [
          Text('Network', style: Theme.of(context).appBarTheme.titleTextStyle),
          const SizedBox(height: 4),
          Text(
            state.engineOn
                ? 'Engine active — DNS is being answered on-device.'
                : 'Engine idle. Arm the Fast Lane to start steering.',
            style: const TextStyle(color: KayaColors.inkDim, fontSize: 13),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Anycast resolver benchmark'),
          KayaCard(
            child: Column(
              children: [
                const Padding(
                  padding: EdgeInsets.only(bottom: 6),
                  child: Text(
                    resolverNote,
                    style: TextStyle(color: KayaColors.inkFaint, fontSize: 11.5, height: 1.4),
                  ),
                ),
                for (final (name, ip) in resolvers)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 7),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(name, style: const TextStyle(fontWeight: FontWeight.w700)),
                              Text(ip, style: const TextStyle(color: KayaColors.inkFaint, fontSize: 12)),
                            ],
                          ),
                        ),
                        _BenchBadge(ms: benchmarks[name]),
                      ],
                    ),
                  ),
                const SizedBox(height: 4),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(backgroundColor: KayaColors.lane, foregroundColor: KayaColors.obsidian),
                    onPressed: benching ? null : _benchmark,
                    icon: benching
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: KayaColors.obsidian,
                            ),
                          )
                        : const Icon(Icons.speed_rounded),
                    label: Text(benching ? 'Racing resolvers…' : 'Run benchmark'),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Game connection benchmark'),
          KayaCard(
            child: Column(
              children: [
                const Text(
                  'Real TCP handshakes from this device to each game\'s public '
                  'edge — median of 5, plus best/worst (jitter spread). Run '
                  'once with the Fast Lane off, then again armed: the first '
                  'run of the session is kept as the baseline and every later '
                  'run shows the delta per endpoint. In-game ping still '
                  'depends on the matchmaker\'s datacenter pick.',
                  style: TextStyle(color: KayaColors.inkDim, fontSize: 12.5, height: 1.45),
                ),
                const SizedBox(height: 10),
                if (gameDelta != null) ...[
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 10),
                    decoration: BoxDecoration(
                      color: KayaColors.slate,
                      borderRadius: BorderRadius.circular(KayaRadius.chip),
                    ),
                    child: Text(
                      'vs baseline (first run this session): '
                      '${gameDelta!.values.where((d) => d < 0).length} improved · '
                      '${gameDelta!.values.where((d) => d == 0).length} same · '
                      '${gameDelta!.values.where((d) => d > 0).length} worse',
                      style: const TextStyle(fontSize: 12, color: KayaColors.inkDim),
                    ),
                  ),
                  const SizedBox(height: 8),
                ] else if (gameFirstRun != null) ...[
                  Text(
                    'Baseline stored (${gameFirstRun!.length} endpoints) — '
                    'arm the Fast Lane and run again to compare.',
                    style: const TextStyle(fontSize: 12, color: KayaColors.inkFaint),
                  ),
                  Align(
                    alignment: Alignment.centerRight,
                    child: TextButton(
                      onPressed: () async {
                        await widget.state.bridge.prefsSetString(baselineKey, '');
                        setState(() {
                          gameFirstRun = null;
                          gameDelta = null;
                        });
                      },
                      child: const Text('Clear baseline',
                          style: TextStyle(fontSize: 12)),
                    ),
                  ),
                ],
                if (gameBench.isNotEmpty)
                  for (final e in gameBench.entries)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 5),
                      child: Row(
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  e.key,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(fontSize: 13),
                                ),
                                if (endpointHosts[e.key]?['host']?.isNotEmpty == true)
                                  Text(
                                    endpointHosts[e.key]!['host']!,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: const TextStyle(
                                        fontSize: 10.5, color: KayaColors.inkFaint),
                                  ),
                              ],
                            ),
                          ),
                          if (gameBest[e.key] != null &&
                              gameWorst[e.key] != null &&
                              gameWorst[e.key]! - gameBest[e.key]! > 0)
                            Padding(
                              padding: const EdgeInsets.only(right: 6),
                              child: Text(
                                '±${gameWorst[e.key]! - gameBest[e.key]!}',
                                style: const TextStyle(
                                    fontSize: 10.5, color: KayaColors.inkFaint),
                              ),
                            ),
                          if (gameDelta?[e.key] != null)
                            Padding(
                              padding: const EdgeInsets.only(right: 6),
                              child: Text(
                                gameDelta![e.key]! <= 0
                                    ? '${gameDelta![e.key]!}'
                                    : '+${gameDelta![e.key]!}',
                                style: TextStyle(
                                  fontSize: 11,
                                  fontWeight: FontWeight.w700,
                                  color: gameDelta![e.key]! < 0
                                      ? KayaColors.lane
                                      : (gameDelta![e.key]! == 0
                                          ? KayaColors.inkFaint
                                          : KayaColors.hot),
                                ),
                              ),
                            ),
                          _BenchBadge(ms: e.value),
                        ],
                      ),
                    ),
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: KayaColors.cool,
                      foregroundColor: KayaColors.obsidian,
                    ),
                    onPressed: gameBenching ? null : _gameBenchmark,
                    icon: gameBenching
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: KayaColors.obsidian,
                            ),
                          )
                        : const Icon(Icons.sports_esports_rounded),
                    label: Text(gameBenching ? 'Probing game edges…' : 'Benchmark game connection'),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          const SectionHeader(title: 'Steered hosts'),
          KayaCard(
            child: gameHosts.isEmpty
                ? const Text(
                    'Steering rules load once the engine reports in.',
                    style: TextStyle(color: KayaColors.inkDim),
                  )
                : Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final host in gameHosts)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                          decoration: BoxDecoration(
                            color: KayaColors.slate,
                            borderRadius: BorderRadius.circular(KayaRadius.chip),
                            border: Border.all(color: KayaColors.hairline),
                          ),
                          child: Text(
                            host,
                            style: const TextStyle(fontSize: 12, color: KayaColors.inkDim),
                          ),
                        ),
                    ],
                  ),
          ),
          if (winners.isNotEmpty) ...[
            const SizedBox(height: 10),
            KayaCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'LEARNED WINNERS (this session)',
                    style: TextStyle(
                      color: KayaColors.inkFaint,
                      fontSize: 10.5,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 1.1,
                    ),
                  ),
                  const SizedBox(height: 8),
                  for (final e in winners.entries)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 3),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              e.key,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 13),
                            ),
                          ),
                          Text(
                            e.value,
                            style: const TextStyle(color: KayaColors.lane, fontSize: 12.5),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 16),
          const SectionHeader(title: 'Private DNS (system-wide fallback)'),
          KayaCard(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.state.privateDns['mode'] == 'hostname'
                      ? 'Private DNS → ${widget.state.privateDns['specifier']}'
                      : 'Private DNS is off or automatic.',
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 6),
                const Text(
                  'Pins secure DNS for every app, even when the engine is off. Try one.one.one.one (Cloudflare) or dns.google.',
                  style: TextStyle(color: KayaColors.inkDim, fontSize: 12.5, height: 1.4),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    for (final (label, host) in [('Cloudflare', 'one.one.one.one'), ('Google', 'dns.google')])
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: OutlinedButton(
                          style: OutlinedButton.styleFrom(
                            foregroundColor: KayaColors.lane,
                            side: BorderSide(color: KayaColors.lane.withValues(alpha: 0.5)),
                          ),
                          onPressed: () async {
                            final ok = await widget.state.bridge.setPrivateDns(host);
                            await widget.state.refreshPermissions();
                            if (context.mounted) {
                              showKayaSnack(
                                context,
                                ok
                                    ? 'Private DNS pinned to $label.'
                                    : 'Denied — grant WRITE_SECURE_SETTINGS via adb (see README).',
                              );
                            }
                          },
                          child: Text(label),
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () async {
                    await widget.state.bridge.clearDnsCache();
                    await _load();
                  },
                  icon: const Icon(Icons.restart_alt_rounded, size: 18),
                  label: const Text('Reset learned routes'),
                  style: OutlinedButton.styleFrom(foregroundColor: KayaColors.inkDim),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _BenchBadge extends StatelessWidget {
  const _BenchBadge({required this.ms});

  final int? ms;

  @override
  Widget build(BuildContext context) {
    return PingChip(ms: ms, compact: true);
  }
}
