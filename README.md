# PulseKraft

A speed test that tells you what the number is worth.

Most speed tests show one confident figure and hope you do not think about
it. This one measures five things, publishes the method it used, and states
plainly what it cannot tell you.

**Status: the measurement engine is built and tested. The test is not wired
to it yet, and the app shows no number.** A speed test that renders a
plausible result before it can measure one is the exact failure this project
exists to avoid, so the first screen says so rather than faking it.

## What it measures

| | |
|---|---|
| **Idle latency** | The floor. DNS, connect, TLS and first byte, timed separately so a slow handshake cannot hide inside an average |
| **Latency under load** | The number commercial tests leave out. A 200 Mbps line at 200 ms loaded latency is a bad line for a call |
| **Download** | Application-level goodput, with TCP slow start excluded |
| **Upload** | The same, honestly |
| **Stability** | Latency sampled over time, with a verdict of *steady*, *varied* or *spiky* — which is how you learn your WiFi is fine and your line is not |

## The problem it is built around

There is no single correct speed number, and every existing tool picks the
flattering one.

A paired-test study (MacMillan et al., SIGMETRICS '23) ran Ookla's test and
M-Lab's ndt7 against the same connections and found Ookla reporting **12%
higher at 200 ms RTT and 56% higher at 500 ms**. The reason is in Ookla's own
documentation: samples are sorted by speed, the two fastest and the bottom
quarter are removed, and the rest averaged. The discarded quarter is exactly
the slow-start and congested part, so the number is a property of the filter
rather than of the connection.

ndt7 goes the other way — one TCP connection, no filtering — which is
truthful about what a single app experiences and under-reports the raw
capacity of a high-latency path.

**Neither is wrong. They answer different questions.** PulseKraft measures
both and shows the gap, instead of picking one.

## What it will not do

- **No packet-loss figure.** Loss cannot be observed over HTTP, because TCP
  hides the retransmissions. LibreSpeed's own maintainer puts it plainly: you
  cannot measure packet loss over HTTP. A number here would be invented.
- **No outlier filtering.** Nothing is discarded as an outlier, no best-of-N
  is taken, and no protocol-overhead fudge factor is applied. LibreSpeed
  ships an `overheadCompensationFactor` and admits its results come out
  "slightly too optimistic" outside a typical IPv4 connection.
- **No claim about your last mile.** It measures the path to Cloudflare's
  nearest edge, which is usually the fastest point on your route.
- **No silent attribution.** The kernel counters that distinguish an
  app-limited test from a network-limited one live in `TCP_INFO`, which
  Android does not expose. So this app cannot tell you whether a low result
  came from your line or from your phone, and says so.

## Data

- **INTERNET**, because measuring a connection means using one.
- **ACCESS_NETWORK_STATE**, to know whether the network is metered and what
  it is.

Nothing else. No camera, location, microphone, storage, notifications,
accounts, ads, analytics, or background work. No results are stored, on
device or off. Backup is off, so a device transfer carries nothing.

A **light** test is the default and costs about 20 MB. The cost is stated
before it starts, and a heavier test is opt-in.

## Build

```sh
git clone git@github.com:kedharsairam/pulsekraft.git
cd pulsekraft
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

JDK 17, Android SDK (compileSdk 37, minSdk 26). Kotlin, Jetpack Compose, no
HTTP client yet — deliberately, see below.

Release APKs are debug-signed on purpose. This project has no release
keystore: no store distribution, GitHub releases only. What you install is a
build the author also built.

## Why there is no HTTP library yet

A receive buffer is not a performance tweak. On a path with a meaningful
round trip, throughput is bounded by `window x 8 / round_trip`, and the
window has to be set **before the connection is made** — a receive window
above 64 KB has to be requested prior to connect, because the scale option is
negotiated in the handshake. A stock HTTP client opens the socket for you and
will not let you do that.

On a 200 ms path with a 64 KB buffer, the ceiling is 2.6 Mbps. A 2.6 Mbps
result on a gigabit line is not a slow line, it is a small buffer — and an app
that reports it without saying so is lying in the most technical way
available. `core/Buffer.kt` exists to make sure that cannot happen here, and
`Buffer.mayBeBufferLimited` is the self-check that says so on screen.

`SO_RCVBUF` is also only a hint: the kernel may refuse, and it allocates
twice the value requested. The value read back is the only one that means
anything, and it is the one this app reports.

## Tests

56 JVM tests covering the maths: unit conversion, percentiles, the grace
window, RFC 3550 jitter, the stability thresholds, buffer sizing, the
bandwidth-delay product, and the published method strings.

The method is a value in the app, not a comment in a file, and there is a
test asserting it still says the things it promises. If the aggregation ever
changes and the on-screen text does not, the suite fails.

## Design

Dark only, and blue-violet.

The palette is measured, not chosen by eye. Every colour clears WCAG contrast
against the background (text 4.5:1, accents 3:1), and every pair that has to
be told apart at a glance clears ΔE 0.10 in OKLab — a perceptually uniform
space, so the number means what it looks like.

There is deliberately **no "good" green**. Amber-for-problem beside
green-for-fine is the classic colour-vision confusion, and a state that
exists only as a colour is a state a screen reader user and a colourblind
user both lose. Good news is unstyled: the absence of a warning is the
signal.

## Licence

MIT. See [LICENSE](LICENSE).
