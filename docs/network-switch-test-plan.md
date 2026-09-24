# Kaya — Network-Switch / Watchdog Test Plan

**Applies to:** v1.1.7+ (fail-open engine)
**Where:** a real Android 8.0+ device. The emulator ANRs and its NAT blocks
ICMP, so TUN-level connectivity checks must run on hardware.
**Goal:** prove the v1.1.7 promise — the engine never breaks connectivity,
and a wedged tunnel recovers on its own.

---

## Reference behavior (what the code should do)

| Behavior | Expected |
|---|---|
| Engine armed | "FAST LANE LIVE"; normal browsing keeps working (full-tunnel, own package excluded) |
| DNS on cache hit | Instant answer |
| DNS on miss | Background race answers the requester directly; read loop never waits |
| All resolvers fail | Stale last-known-good answers for 30 min (feed shows `resolver*`); SERVFAIL only if there is truly nothing; cache never poisoned |
| Tunnel wedged (starved read loop + climbing write failures, or >250 read errors) | Watchdog rebuilds interface in place; ~20 s cooldown between rebuilds |
| Watchdog events | `vpn` events: `watchdog-restart` (reason `starved` or `error-loop`) then `active` |

---

## Setup

- Install `Kaya-arm64.apk` from the v1.1.7 release (or later).
- Two independent networks with internet: **Wi-Fi** + **mobile data**
  (a second phone's hotspot works if mobile data is unavailable).
- Optional but recommended: USB debugging for logcat observation.
- Baseline sanity: with the engine OFF, browsing + DNS work normally on both networks.

## Observation tools

- In-app: DNS Steering Feed (home), live bubble feed, Tuning → crash log (must stay empty).
- `adb logcat | grep -E "watchdog-restart|watchdog|vpn"` — expect ≤1 `watchdog-restart`
  per incident, always followed by `active`.
- `adb shell ip addr show tun0` — present iff the engine is armed.

---

## Test matrix

### T1 · Idle sanity (baseline with engine armed)
Arm the fast lane on Wi-Fi. Browse 3–4 fresh sites, run a speed test, stream video 2 min.
**Pass:** no stalls or DNS errors; feed shows real lookups; Tuning → crash log empty.

### T2 · Wi-Fi off → on (core watchdog test)
Arm on Wi-Fi. Keep traffic flowing (video / auto-refreshing page). Turn Wi-Fi **off**,
wait 15–20 s, turn it **on**.
**Pass:** within ~30 s of Wi-Fi returning, new lookups resolve (fresh site loads; feed
updates). At most **one** `watchdog-restart` + `active` in logcat. No app interaction
needed. **Fail:** resolution still dead >60 s after Wi-Fi returns, or >1 rebuild per incident.

### T3 · Wi-Fi → mobile data handoff (no blackout)
Arm on Wi-Fi with mobile data enabled. Toggle Wi-Fi **off** so data takes over seamlessly.
**Pass:** traffic continues on data; DNS keeps resolving (protected sockets ride the
active network). Note seconds-to-first-successful-lookup. No anomalous data spikes.

### T4 · Airplane mode full blackout
Arm on Wi-Fi. Airplane mode **on** 30 s, then **off** (Wi-Fi reconnects).
**Pass:** same as T2 — full self-recovery, ≤1 rebuild, app stable, ring state consistent.

### T5 · Engine toggle during blackout
Repeat T4, but toggle the fast lane OFF→ON while airplane mode is still on.
**Pass:** toggle responds immediately (no hang/ANR); once connectivity returns, the
engine works. `startSafely()` during no-network must not crash.

### T6 · Captive portal (hotel / coffee-shop style)
Arm the engine, then join a captive-portal SSID **before** authenticating.
**Pass:** the portal login page still loads (fail-open path must not hard-block portal
domains); after auth, lookups flow normally. Mark **N/A** if no captive portal is available.

### T7 · Long-session soak
Arm on Wi-Fi; play a game or stream 30+ min across background app switches.
**Pass:** no stalls; battery/thermals normal; bubble keeps updating; zero (or justified)
watchdog events.

### T8 · Rapid-toggle stress
Fast lane OFF→ON ×10 quickly; then Wi-Fi toggle ×5 while armed.
**Pass:** no crash/ANR; final engine state matches the UI; `tun0` exists iff armed;
watchdog cooldown holds (≤1 rebuild per 20 s).

### T9 · DNS fail-open drill (resolver outage)
With the engine armed, block outbound 53 to 1.1.1.1 / 8.8.8.8 / 9.9.9.9
(router firewall, or a hotspot you control).
**Pass:** previously-visited sites keep loading from stale cache (up to 30 min); brand-new
domains may eventually SERVFAIL but the tunnel never wedges; feed shows `resolver*` rows.
**If a router firewall isn't available:** mark blocked — v1.1.8 candidate below adds a
built-in way to simulate this.

---

## Results

| # | Device / Android | Result | Notes (rebuilds seen, seconds-to-recover, logcat excerpts) |
|---|---|---|---|
| T1 | | ☐ pass ☐ fail | |
| T2 | | ☐ pass ☐ fail | |
| T3 | | ☐ pass ☐ fail | |
| T4 | | ☐ pass ☐ fail | |
| T5 | | ☐ pass ☐ fail | |
| T6 | | ☐ pass ☐ fail | |
| T7 | | ☐ pass ☐ fail | |
| T8 | | ☐ pass ☐ fail | |
| T9 | | ☐ pass ☐ fail ☐ N/A | |

## Known emulator limitations (why this plan is real-device-first)

- Process-attach timeouts, MainActivity/System-UI ANR loops (v1.1.6–v1.1.7 sessions).
- NAT blocks ICMP echo even when TCP flows, so ping-based checks are meaningless there.
- tun0 existence and DNS lookups *were* verified on emulator in earlier releases;
  full connectivity recovery (T2–T4) needs hardware.

## v1.1.8 candidates surfaced by this plan

1. **"Simulate resolver outage" debug toggle** in Tuning (drops all upstream DNS for
   N seconds) so T9 is testable on any device without firewall tricks.
2. **NetworkCallback-driven proactive rebuild** — listen for network loss/regain
   (`registerNetworkCallback` / LinkProperties) and rebuild the interface on regain
   instead of waiting for the reactive watchdog; also log the network-switch cause.
3. **Private DNS (strict mode) handling** — the codebase currently has zero
   `privateDns` awareness; devices with Private DNS strict mode bypass our resolver
   selection, so the bubble/feed can misreport what actually resolved a name.
