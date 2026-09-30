# Changelog

All notable changes to PulseKraft.

The format follows [Keep a Changelog](https://keepachangelog.com/), and
this project uses [semantic versioning](https://semver.org/).

## [0.1.0] — 2026-09-30

First release. A measurement engine, and an interface that treats its own
numbers as claims.

### Measured

- **Idle latency**, sampled on one persistent connection with ten warm-up
  samples discarded and idle samples spaced 500 ms apart.
- **Latency under load**, sampled on a second connection while a transfer
  saturates the first.
- **Download and upload** as goodput over a measured window. Download is
  byte-capped and time-boxed; upload is byte-capped only, because
  `Content-Length` is a promise and stopping early hangs the edge.
- **Stability** as fifteen seconds of watching for excursions, with a
  verdict and a range.
- **Bufferbloat** as the ratio of loaded to unloaded latency, checked
  against ITU-T G.114's 150 ms one-way figure rather than a chosen
  threshold.

### Interface

- One screen, no scrolling, five states designed: idle, running, result,
  failed, stopped.
- Typography-led. No dial, no gauge — a hand-drawn gauge on a live
  measurement is the one thing that cannot be drawn accurately, because
  the value moves faster than the eye settles.
- Tabular figures on every changing number, so a value ticking ten times
  a second is legible rather than a shimmer.
- The figure on screen is the last point of the smoothed series the plot
  draws, so the number and the line agree by construction.
- Live plot axis fitted to the signal it draws, with the range printed
  underneath. It autoscales while running and stays fixed afterwards, and
  the two are not meant to match: nobody compares a live trace to last
  week's, but a verdict has to mean the same thing every time.
- A running test can be stopped. It keeps the figures it had.
- An about sheet that states what this app wants permission for and why.

### Correctness

The published method, the verdict and the interface all disagreed with
the code at several points during development. What changed:

- **The headline was the instantaneous rate**, which spends real stretches
  of a transfer near zero because a socket buffer arrives in one read.
  This was the artefact the app already refused to report as a peak, in
  74sp.
- **The verdict consulted the bufferbloat ratio alone** and printed "Good
  for calls" over a 342 ms unloaded median. Absolute loaded latency is
  checked first, then resting latency, then stability, then the ratio.
- **The published method said "median of per-interval rates"** while the
  code had been changed to goodput over the measured window. The sentence
  is now generated from what the code does, and a test ties it to the
  arithmetic rather than to a list of words.
- **The idle baseline was sampled 100 ms apart**, which is a flood; the
  edge throttled the probe and produced a baseline worse than the load it
  was measured against, giving an impossible 0.33x ratio. Idle samples are
  now 500 ms apart.
- **Peak throughput is not shown.** It is the receive buffer, not the
  connection. Worst-case latency is shown, because that is real.
- **Sub-1.0 ratios are not printed as scores.** A dead heat reads as "no
  added latency"; below 0.85 it reads as "baseline unreliable".
- **The plot's area fill was removed.** A filled area encodes a
  cumulative quantity, and a transfer rate is not one.
- **The result screen had no way to start another test.**

### Data

- Two permissions. `INTERNET`, which this is the only app in this family
  to hold, and `ACCESS_NETWORK_STATE`, so a test's cost can be stated
  before it is spent.
- Refuses to run offline, while roaming, and the heavier profile on
  mobile data. Where the platform cannot be asked, the answer is the
  cautious one.
- The heavier test is selectable and priced at the point of choice.
- Nothing is stored, anywhere, ever. No history, and therefore no history
  screen.

### Build

- 106 JVM unit tests covering the parser, the arithmetic, the statistics,
  the policy, the verdict and the published method.
- 13 instrumented tests covering every state of the screen on a device,
  including the ones that cannot be produced on demand: refused, offline,
  stopped, failed. The screen was split into a pure render and a
  coordinator so those states are reachable in a millisecond rather than
  by putting a phone in airplane mode.
- Release APKs are debug-signed deliberately, so a downloaded APK can be
  checked against this repository. No release keystore.
- MIT.

### Known gaps

- **The transport is still untested.** The instrumented suite covers the
  screen; nothing covers a real socket, the platform read, or anything
  timing-dependent, and those are the parts where a real network is
  required. Mocking them would mean testing the mock.
- No result history, by design.

[0.1.0]: https://github.com/kedharsairam/pulsekraft/releases/tag/v0.1.0