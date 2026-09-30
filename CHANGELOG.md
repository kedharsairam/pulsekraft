# Changelog

All notable changes to PulseKraft.

The format follows [Keep a Changelog](https://keepachangelog.com/), and
this project uses [semantic versioning](https://semver.org/).

## [0.1.2] — 2026-10-01

The last two gaps, and one of them I had described as untestable.

### Added

- **The edge's contract, tested against the real edge.** A performance
  figure cannot be asserted — it depends on the network, and a test that
  did would fail for reasons unrelated to the code. The edge's *shape*
  can be, and this app's method is built entirely on it: that
  `__down?bytes=N` answers 200 with exactly N bytes and unchunked, that
  `__up` answers 200 with `Content-Length: 0` and that empty reply is
  the success, and that a large POST draws a `100 Continue` which is not
  the answer. If any of that changed the app would report the wrong
  thing and nothing else would notice. Six tests, skipped rather than
  failed with no route.

### Fixed

- **A screen reader never heard what a test would cost.** The cost line
  and the measurements list are not focusable, so TalkBack walked past
  "About 25 MB of mobile data" and stopped on a control labelled only
  "Run test". Found by enabling TalkBack and reading the accessibility
  tree, which is a different exercise from asserting the labels exist —
  the labels did exist, they were just not where a reader would go. The
  control now says *"Run test. About 25 MB of mobile data."*
- **A refused control was absent from the accessibility tree entirely.**
  It rendered a bare mark and returned early, so the one moment a person
  most needs to hear what is wrong was the one moment a screen reader
  found nothing. Silence is not the same as nothing to do here.
- A double full stop in the spoken refusal: "…for the full test..".

### Known gaps

- Which data centre answers, and its real latency under load. No test can
  pin that, and a test that mocked it would be testing the mock.
- TalkBack's speech does not reach logcat, so what is verified is the
  tree it reads, not how it sounds. That last judgement is a human's.

---

## [0.1.1] — 2026-10-01

Fixes found by auditing a finished v0.1.0 against its own documentation.

### Fixed

- **Rotating the phone mid-test destroyed the run.** Every piece of state
  lived in `remember`, so a configuration change threw away the
  composable and its values — including the handle on the worker thread.
  The measurement carried on with nothing able to reach it, could not be
  stopped, and its result was written to a state holder that no longer
  existed. State now lives in a `ViewModel`.
- **Landscape dropped four elements off the bottom of the idle screen**
  with no way to scroll to them. The screen is now portrait by decision
  rather than by accident, and the reason is recorded in the manifest.
- **The receive-buffer self-check was never shown.** It was implemented,
  documented, given five tests, and called from nowhere — while the
  README claimed the result screen said so. It does now.
- **A published disclosure was invisible.** `Method.KEEP_ALIVE`, the
  sentence answering "you are measuring handshakes", was written and
  tested and appeared in no screen. It is in the about sheet, and a test
  now fails if a published string is added without being shown.

### Added

- **Screen reader support.** The figure and its unit are one spoken
  phrase rather than two unrelated items, the verdict is a heading read
  before its evidence, and the plot describes itself from the numbers it
  was drawn from.
- **Ten tests for the request bytes.** `writeRequest` built its head
  inline into a socket stream, so it could not be tested at all. It is a
  pure function now, because a request missing its terminating blank line
  is one the server waits on rather than rejects — nothing logged,
  nothing thrown, a sixty-second timeout.

### Verified on a second device

A Pixel 8a on **mobile data**, which exercised the cost policy for real:
the idle screen read "About 25 MB of mobile data", selecting the heavier
profile re-priced it to 100 MB and refused immediately, and the run that
followed survived a rotation — the fix for the configuration-change bug
above, seen working rather than inferred.

Rotating the phone mid-test used to lose the run entirely.

### Fixed here

- **A refused control still said "Tap to measure this connection."** The
  mark greys and stops breathing, the refusal appears, and the line above
  kept inviting a press that would do nothing. Found on a real cellular
  connection; it now reads "Not available on this connection".
- **The result screen broke at large text sizes.** At 1.5x a line read
  "unloaded 135" with its unit silently cut off — the worst way to lose
  a word, because the number still looks like a number — and "135 ms
  jitter ±49" wrapped and dragged its label out of line with the rows
  around it. The table now stacks when the text outgrows it, and there
  is an instrumented test at 2x so it cannot come back.
- **A short viewport lost the middle of the idle screen.** The portrait
  lock is ignored on any display of 600dp or more, so a tablet and
  split-screen both got the clipped layout the lock was meant to
  prevent. The column now overflows and scrolls, which is a constraint
  rather than a hidden decision.

### Added

- **The socket is tested against a real one** — a local TLS server with
  a throwaway certificate generated at run time, asserting the granted
  buffer read-back, the request/response crossing, an interim
  `100 Continue` being skipped, and a closed port being refused. The
  previous note said this needed a certificate the project did not have;
  generating one takes a second.

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