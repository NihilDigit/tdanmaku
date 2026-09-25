# tdanmaku

[![Maven Central](https://img.shields.io/maven-central/v/dev.nihildigit/tdanmaku)](https://central.sonatype.com/artifact/dev.nihildigit/tdanmaku)

A danmaku engine for Compose Multiplatform.

- **Every comment type.** Scrolling, top-pinned and bottom-pinned, plus author-positioned comments
  with motion, easing, alpha curves and rotation, including rotation about the Y axis with
  perspective. Density and frame-rate caps are configuration.
- **Nothing measured while drawing.** Text is laid out and its stroke and fill recorded into a
  display list once, before the comment reaches the screen. Each later frame translates that
  recording and replays it. The frame loop suspends while the screen is empty.
- **Compose all the way down.** A composable that draws into the host composition. It wraps no
  view, allocates no surface of its own and carries no theme; colours, font, stroke and opacity
  come from `DanmakuOptions`.

## Quick start

A clock, a set of options, and comments.

```kotlin
class PlayerClock(private val player: Player) : DanmakuClock {
    override val positionMillis get() = player.currentPosition
    override val isPlaying get() = player.isPlaying
    override val playbackSpeed get() = player.playbackParameters.speed
}
```

ExoPlayer advances `currentPosition` in 10 ms steps, and sampling a step function at 120 Hz leaves
some frames where nothing moves. Wrap such a clock in `SmoothedDanmakuClock`, which tracks the
reported position per frame and follows speed changes without jumping:

```kotlin
val clock = remember(player) { SmoothedDanmakuClock(PlayerClock(player)) }
```

```kotlin
val controller = rememberDanmakuController(
    clock = clock,
    options = DanmakuOptions(fontSizeSp = 15f, scrollShowArea = 0.75f),
    contentKey = episodeId,          // changing it recompiles from scratch
)

LaunchedEffect(pool) { controller.setPool(pool) }

Box {
    VideoSurface(player)
    DanmakuLayer(controller)         // above the picture, below the controls
}
```

The library owns text measurement and canvas geometry. The measurer and the render style derive from
the same `DanmakuOptions`, so layout and painting agree on the width of a comment by construction;
the layer reads its own canvas size and recompiles when it changes.

Call `controller.notifyChanged()` after a seek or a speed change. The frame loop suspends while
nothing is visible and observes only the clock, so external events reach it through that call. A
fallback poll bounds the delay when the call is omitted.

## How it works

Where a comment sits on screen is a pure function of playback time:

```
x = viewport.right - (t - emitTimeMillis) * speedPxPerMillis
```

Layout runs once. The scheduler picks a track, checks collisions and produces an immutable
`DanmakuFlightPlan`; the occupancy it used to get there is discarded. What remains is a timeline
indexed by time, which the renderer queries.

The timeline is queryable at any `t`, and three properties follow from that.

**Seeking is a query.** Asking for a different `t` yields the picture at that moment, so pause, seek
and playback speed require no branches in the engine.

**The frame loop can suspend.** A timeline answers when the next comment enters, which is what lets
an empty screen cost no vsync.

**Prewarming has an answer to work from.** Preparing a comment before it appears means asking what
will be on screen a second from now, and that question is answerable only where the future is
represented. The zero-measurement draw frame rests on the structure above.

Determinism holds across platforms. The only source of randomness is an FNV-1a hash of the comment
id; Kotlin does not specify `String.hashCode()` consistently across platforms. The same comment
lands on the same track at the same speed on JVM and on Native.

## Live and other open-ended sources

A live stream is a pool that only grows at the tail.

```kotlin
controller.appendNow(danmaku)                            // stamped with the current position
controller.trimBefore(clock.positionMillis - 15_000L)    // drop what has left the screen
```

`appendNow` timestamps the comment from the clock; server timestamps belong to a separate clock and
are ignored. It returns `false` for a comment that arrives out of order, since inserting one would
shift the sequence numbers after it and change the layout from that point on.

`trimBefore` is called by the consumer and cannot be undone. Without it a long stream retains every
comment and its flight plan.

A live source needs a monotonic clock of its own. A live HLS position is measured inside a sliding
window and moves backwards when old segments are dropped, which the compiler reads as a seek.

## Status

Early, and the API will change. Targets are `android`, `jvm`, `iosArm64` and `iosSimulatorArm64`;
the iOS targets are verified as far as klib compilation and have not run on a device.

## Credits

Built with reference to:

- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) (GPL-3.0)
- [Animeko](https://github.com/open-ani/animeko) (AGPL-3.0)
- [DanmakuFlameMaster](https://github.com/bilibili/DanmakuFlameMaster) (Apache-2.0)
- [canvas_danmaku](https://github.com/Predidit/canvas_danmaku) (MIT)

## License

GPL-3.0. See [LICENSE](LICENSE).
