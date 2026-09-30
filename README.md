# PulseKraft

A network speed test that tells you what the number is worth.

Most speed tests hand you a figure and leave you to work out whether it matters. PulseKraft measures four things about your connection, leads with a sentence about whether your calls will work, and publishes the exact method it used — in the app, not only here.

Kotlin and Compose. Dark only. One screen, no scrolling, no settings, no history.

<p align="center">
  <a href="https://github.com/kedharsairam/pulsekraft/releases/latest"><img src="https://img.shields.io/github/v/release/kedharsairam/pulsekraft?style=for-the-badge&label=Download" alt="Download APK"></a>
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="MIT License">
  <img src="https://img.shields.io/badge/Android-8.0%2B-blue?style=for-the-badge" alt="Android 8.0 and newer">
</p>

---

## What it measures

| | |
|---|---|
| **Idle latency** | Round-trip time on a persistent connection, with nothing else using the link. |
| **Latency under load** | The same round trip, sampled on a *second* connection while a transfer saturates the first. |
| **Download and upload** | Goodput over a measured window, at both directions. |
| **Stability** | Fifteen seconds watching latency for excursions, reported as a verdict and a range. |

Latency under load leads the result screen, because a connection can be fast and still ruin a call — and the ratio between idle and loaded latency is the only figure that predicts it.

It needs two connections for exactly that reason. Latency sampled on the same socket that is moving 100 Mbps measures the sender's queue, not the path.

## What it will not do

- **No packet-loss figure.** Loss cannot be observed over HTTP, because TCP retransmits it invisibly. The packets arrive, just late. A figure would be invented.
- **No outlier filtering.** Nothing is discarded, nothing is best-of-N, no protocol-overhead correction is applied.
- **No peak throughput.** A 1756 Mbps peak beside a 27 Mbps upload is the receive buffer being handed over in one read, not a property of the connection. A worst-case *latency* is shown, because a slow packet really did happen to a packet.
- **No claim about your last mile.** It measures the path to the nearest edge, which is usually the fastest point on the route.
- **No silent attribution.** It cannot tell whether a low result came from your line or from the phone, and it says so instead of guessing.
- **No history.** Results are never written down. Not to a file, not to a database, not off the device.

## Data

Two permissions, and the reason for each is in the manifest.

| Permission | Why |
|---|---|
| `INTERNET` | Measuring a connection means using one. |
| `ACCESS_NETWORK_STATE` | To say what a test will cost *before* spending it. |

Nothing else. No camera, location, microphone, storage, notifications, accounts, analytics, crash reporting, background work, or network calls to anywhere but the edge being measured. Backup is off, so a device transfer carries nothing away.

The default test moves up to **25 MB**; the heavier one up to **100 MB**. Both figures are on the idle screen before anything starts, next to the control that spends them.

- **Offline** — refused. There is nothing to measure, and a zero would be a claim about the line.
- **Roaming** — refused outright. Not because the measurement would be wrong, but because it is billed to whoever owns the network you are standing on. A carrier marking a roaming connection as unmetered does not get past this.
- **Metered, heavier test** — refused, with the way out named.
- **Metered, default test** — allowed, with the cost already on screen. There is no dialog: the figure was stated before the button was pressed, so the press is the agreement.

Where the platform cannot be asked, the answer is the cautious one. Every field defaults to disconnected-and-metered, because a revoked permission throws and some devices report no capabilities at all — and the cost of guessing wrong there is a data bill.

---

<details>
<summary><strong>How a measurement is taken</strong></summary>

Published in the app, and asserted by `MethodTest` — which checks the sentence against the arithmetic rather than against a list of words, because a disclosure test that greps for vocabulary cannot notice the claim going stale.

- Throughput is **goodput over the measured window**: bytes actually received divided by the seconds that transfer took, after a fixed grace window. Not the median of the per-interval rates, which reads high for the buffer reason above.
- **No samples are discarded**, **no best-of-N**, **no protocol-overhead correction**. Nothing is filtered into looking better than the line beneath it.
- Every latency sample comes from **one persistent connection**, so the figure measures the path rather than the handshake. Ten warm-up samples are taken and thrown away, and idle samples are spaced 500 ms apart — at a tighter cadence the edge throttles the probe and produces a baseline worse than the load it was meant to be measured against.
- The receive buffer is sized for a guess and then **checked against the result**. If a figure is sitting at the ceiling this app was capable of, the result screen says so — because a low number can be a slow line, or it can be this app's own receive buffer, and the second case is the one where the figure on screen is a fact about the app.
- Downloads are **byte-capped**, and so are uploads — `Content-Length` is a promise, and stopping early hangs the edge.

**Why there is no HTTP library.** The receive buffer has to be set *before* the socket connects, and a stock client does not let you. `SO_RCVBUF` is a hint the kernel may refuse and allocates twice what is asked for, so the app reads back what it was actually granted and checks its own results against that. Wrapping a client that has already connected would mean publishing a figure this app could not stand behind.

`net/Protocol.kt` — response parsing, chunk framing, status handling, short bodies — is pure and fully tested on the JVM with no device.

</details>

<details>
<summary><strong>Tests — 132 unit and 21 instrumented</strong></summary>

132 JVM unit tests, no device required:

| Suite | Covers |
|---|---|
| `ProtocolTest` (21) | Chunk framing, status codes, short bodies, truncated responses |
| `TransportHeadTest` (10) | The request bytes, byte for byte |
| `TransportSocketTest` (5) | A real TLS socket against a local server |
| `EdgeContractTest` (6) | The real edge's shape — skipped, not failed, with no network |
| `RateTest` (18) | Goodput, percentiles, nanosecond arithmetic, bufferbloat indexing |
| `BufferTest` (17) | Bandwidth-delay product, buffer ceilings, the self-check against a result |
| `VerdictTest` (16) | Every branch of the published sentence, including the cases that were once wrong |
| `LatencyTest` (14) | RFC 3550 jitter, stability verdicts, thresholds pinned by value |
| `PolicyTest` (10) | The data-cost rules, including a platform that tells us nothing |
| `MethodTest` (11) | That the published method still describes what the code computes |
| `ReadmeAccuracyTest` (4) | That this table is true, and that the README is not promising something the app does not do |

**21 instrumented tests** on a device, because the alternative was worse: producing the refused, offline, stopped and failed states by hand needs a phone in airplane mode, a roaming SIM, a socket that fails on cue and forty seconds of patience per case. `MeasureContent` is the screen as a pure function of its state, so every state is reachable in a millisecond. They cover what broke repeatedly and silently — a control falling off the bottom of an overflowing column, a touch target under 48dp, a degenerate series dividing by zero, two figures on one screen contradicting each other.

**The socket is tested.** A local TLS server, with a throwaway certificate generated by `keytool` when the suite first runs — nothing binary committed and no credential in the repository. It asserts that the granted receive buffer is read back rather than the requested one, that a request and its response cross, that an interim `100 Continue` is skipped rather than answered, and that a closed port is refused rather than hung.

**And the edge's contract is tested against the real edge.** A *performance figure* cannot be asserted — nobody knows what a phone on someone else's network will measure. But the edge's *shape* can be, and this app's method is built entirely on that shape:

- `__down?bytes=N` answers 200 with `Content-Length: N` and exactly N bytes, unchunked, at both ends of the size range
- `__up` answers 200 with `Content-Length: 0`, and that empty reply is the **success** — read as a failure, every upload would be reported as refused
- a large POST draws a `100 Continue` first, which is not the answer

If that changed, the app would report the wrong thing and nothing else in the suite would notice. Those six tests skip rather than fail on a machine with no route.

</details>

<details>
<summary><strong>Design</strong></summary>

Dark only, and the palette is measured rather than chosen: contrast verified against WCAG and the perceptual space, with no "good" green, which is the trap deuteranopia sets for anything that means success.

The interface follows the platform's own design language rather than imitating any particular product. The rules that actually shaped it:

- **Typography is the interface.** No dial and no gauge. A gauge makes the number a passenger inside a drawing, and on a live measurement it is the one thing that cannot be drawn accurately, because the value moves faster than the eye settles.
- **Tabular figures on every changing number.** With proportional figures a value ticking ten times a second is an illegible shimmer, because changing 1 to 8 reflows every glyph after it.
- **No scrolling on the primary screen.** A screen that scrolls is a screen whose hierarchy is unresolved.
- **Colour means something.** One accent per screen, reserved for the thing that matters.
- **Dynamic Type to 1.35x on the hero figure, then capped.** A display that reflows off the edge at 2x is a broken instrument, not an accessible one. Everything the trace shows is still on the result screen.
- **The figure, the verdict and the plot are spoken as phrases.** A screen reader used to announce "299" and then "Mbps" as two unrelated items, and a `Canvas` announces nothing at all — so the only picture in the app was invisible to a blind reader. The plot's description is generated from the measured numbers, so it cannot describe a watch that did not happen.
- **The control speaks its cost.** The cost line is not focusable, so a screen reader walked straight past "About 25 MB of mobile data" and stopped on a button labelled only "Run test". The control now says *"Run test. About 25 MB of mobile data."* — and when the connection cannot be used it says so, and stays in the tree, rather than silently disappearing from it.

**Portrait only**, deliberately. Every layout is a single column built around a figure sized to a thumb's width; rotated, the content is about 500dp tall in a 390dp viewport. A landscape layout would be a second design rather than an adaptation of this one.

</details>

<details>
<summary><strong>Build from source</strong></summary>

```sh
./gradlew :app:assembleDebug        # installable debug APK
./gradlew :app:assembleRelease      # release APK, debug-signed on purpose
./gradlew :app:testDebugUnitTest    # the whole unit suite
./gradlew :app:connectedDebugAndroidTest   # instrumented, needs a device
```

There is no release keystore and no store deployment. Releases are GitHub releases, and the APK attached to one is deliberately debug-signed so anyone can verify that what they downloaded is what is in this repository. Nothing about this app needs to be signed by a secret.

Minimum SDK 26, target 37.

</details>

<details>
<summary><strong>Source layout</strong></summary>

```
core/     Pure. No Android, no Compose. Fully unit-tested.
  Method.kt     the published measurement method, as app values
  Rate.kt       goodput, percentiles, nanosecond arithmetic
  Latency.kt    jitter, stability verdicts
  Buffer.kt     buffer sizing, and the check against a result
  Policy.kt     when it is sensible to spend data
  Verdict.kt    the sentence the result screen leads with

net/      The network.
  Protocol.kt       response parsing — pure, tested
  Transport.kt      sockets, TLS, buffer-before-connect
  Probe.kt          orchestration, phases, cancellation
  Reachability.kt   the one platform read, no logic in it

ui/       Compose.
  MeasureScreen.kt  the state machine, the thread, and the one screen
  MeasureContent.kt the screen as a pure function of its state
  IdleScreen.kt     the waiting state and the only control
  RunningScreen.kt  the reading, the plot and the tally
  ResultScreen.kt   the three ways a run can end
  LiveTrace.kt      the plot itself
  About.kt          what this app wants and why
  theme/            the measured palette
```

The screen was one 1717-line file for most of this project's life. It is split by state now, because a file that long hides its own structure from the person reading it — and because the state that decides whether to spend somebody's data should be the easiest one in the repository to find.

</details>

## Support

If you enjoy PulseKraft, buy me a coffee:

<p align="center">
  <a href="https://buymeacoffee.com/kedhartech"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" width="182"></a>
</p>

## Licence

MIT. See [LICENSE](LICENSE).

No third-party code was copied into this project, and none was forked. Everything here was written from scratch for this repository.
