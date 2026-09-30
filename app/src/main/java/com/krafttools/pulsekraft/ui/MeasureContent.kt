package com.krafttools.pulsekraft.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.krafttools.pulsekraft.core.Volume
import com.krafttools.pulsekraft.ui.theme.PulsePalette

/**
 * The screen, as a pure function of its state.
 *
 * Split out of [MeasureScreen] so that every state can be rendered on a
 * device without a socket, a permission or a thread. The coordinator
 * that owns all three is at the bottom of the other file; everything
 * with a shape is here.
 *
 * This is the only reason an instrumented test for this app is possible
 * at all. Before it existed, the sole way to see the failed, stopped or
 * refused states was to produce them for real — which needs a phone in
 * airplane mode, a roaming SIM, and forty seconds of patience per case.
 */
@Composable
internal fun MeasureContent(
    state: MeasureState,
    methodOpen: Boolean,
    onRun: (Volume) -> Unit,
    onCancel: () -> Unit,
    onVolume: (Volume) -> Unit,
    onAbout: () -> Unit,
    onAgain: () -> Unit,
    onToggleMethod: () -> Unit,
    modifier: Modifier = Modifier,
) {
Column(
    modifier = modifier
        .fillMaxSize()
        .systemBarsPadding()
        .padding(horizontal = 24.dp, vertical = 12.dp),
) {
    // ONE flexible child, not two.
    //
    // This column used to hold the state block, a weight(1f) spacer
    // and the footer. DoneBlock's trace is also weight(1f), and both
    // of those were children of THIS scope — so the leftover height
    // was split evenly between a plot and an empty gap, which is why
    // the result screen had a band of nothing under its content that
    // nothing could fill.
    //
    // The content region takes the slack as one box and each block
    // lays itself out inside it. A block's own weight then resolves
    // against its own column, which is the only scope where that
    // means anything.
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            // Room above the footer's own rule. The phase log is
            // pushed to the bottom of this region by a weight, and
            // without this its last row sat on the divider with
            // four pixels between them, which reads as two rules and
            // a gap rather than as one screen.
            .padding(bottom = 14.dp),
        contentAlignment = Alignment.TopStart,
    ) {
    // Keyed on the class, not the value. Running state is a new
    // instance ten times a second, and keying on the instance would
    // start a crossfade on every live reading — the screen would
    // strobe. Keying on the class animates the three transitions
    // that are transitions (idle to running, running to result,
    // result back to idle) and nothing else.
    Crossfade(
        targetState = state::class,
        animationSpec = tween(340),
        label = "screen",
    ) { _ ->
        when (val current = state) {
            is MeasureState.Idle -> IdleBlock(
                volume = current.volume,
                link = current.link,
                refusal = current.refusal,
                onAbout = onAbout,
                // The refusal is decided by the coordinator, which owns the
                // platform read. This layer only reports what was chosen.
                onVolume = onVolume,
                onRun = { onRun(current.volume) },
            )

            is MeasureState.Running -> RunningBlock(current, onCancel = onCancel)

            is MeasureState.Stopped -> StoppedBlock(
                done = current.done,
                onAgain = onAgain,
            )

            is MeasureState.Failed -> FailedBlock(current)

            is MeasureState.Done -> DoneBlock(
                report = current.report,
                methodOpen = methodOpen,
                onToggleMethod = onToggleMethod,
                onAgain = onAgain,
            )
        }
    }
    }

    Footer()
}
}
