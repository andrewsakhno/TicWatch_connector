package org.sand.ticwatch.watch.ecg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.sand.ticwatch.watch.R

/**
 * Full-screen recording screen: turns the display on and keeps it on while the strip is recorded, with a heart beating
 * at the measured rate, so it is obvious the watch is busy. Closes itself shortly after the recording finishes.
 */
class EcgCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { EcgCaptureContent() } }
        lifecycleScope.launch {
            EcgCaptureState.current.first { it == null || it.phase == EcgCapturePhase.FINISHED }
            delay(OUTCOME_SHOWN_MS)
            finish()
        }
    }

    private companion object {
        const val OUTCOME_SHOWN_MS = 3_000L
    }
}

@Composable
private fun EcgCaptureContent() {
    val progress by EcgCaptureState.current.collectAsState()
    val current = progress ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val elapsedMs = current.recordingStartedAtMs?.let { (now - it).coerceIn(0, current.recordingMs) } ?: 0L
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (current.phase == EcgCapturePhase.RECORDING) {
            CircularProgressIndicator(
                progress = elapsedMs.toFloat() / current.recordingMs,
                modifier = Modifier.fillMaxSize(),
                indicatorColor = HEART,
                strokeWidth = RING_WIDTH.dp,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BeatingHeart(current.heartRateBpm.takeIf { current.phase != EcgCapturePhase.FINISHED })
            Text(text = statusLine(current, elapsedMs), textAlign = TextAlign.Center, style = MaterialTheme.typography.title3)
            Text(
                text = stringResource(if (current.phase == EcgCapturePhase.FINISHED) R.string.ecg_sent else R.string.keep_still),
                style = MaterialTheme.typography.caption2,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun statusLine(progress: EcgCaptureProgress, elapsedMs: Long): String = when (progress.phase) {
    EcgCapturePhase.FINDING_PULSE -> stringResource(R.string.finding_pulse)
    EcgCapturePhase.RECORDING -> {
        val left = (progress.recordingMs - elapsedMs) / MS_PER_SECOND
        listOfNotNull(progress.heartRateBpm?.let { stringResource(R.string.bpm_int, it) }, stringResource(R.string.seconds_left, left))
            .joinToString(" · ")
    }
    EcgCapturePhase.FINISHED -> progress.outcome?.let { stringResource(it) }.orEmpty()
}

/** Lub-dub at [bpm] (a resting pace until measured); still once [bpm] is null after the recording. */
@Composable
private fun BeatingHeart(bpm: Int?) {
    val periodMs = (MS_PER_MINUTE / (bpm ?: RESTING_BPM)).toInt()
    val transition = rememberInfiniteTransition(label = "heart")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = periodMs, easing = LinearEasing)),
        label = "beat",
    )
    Canvas(modifier = Modifier.size(HEART_DP.dp)) {
        drawHeart(center, size.minDimension * HEART_FILL * heartScale(phase))
    }
}

private fun heartScale(phase: Float): Float {
    fun swell(at: Float, height: Float) = (1f - abs(phase - at) / SWELL_WIDTH).coerceAtLeast(0f) * height
    return 1f + swell(LUB_AT, LUB_SWELL) + swell(DUB_AT, DUB_SWELL)
}

private fun DrawScope.drawHeart(center: Offset, size: Float) {
    val half = size / 2
    val path = Path().apply {
        moveTo(center.x, center.y + half)
        cubicTo(center.x - size, center.y, center.x - half, center.y - size * HEART_LOBE, center.x, center.y - half * HEART_NOTCH)
        cubicTo(center.x + half, center.y - size * HEART_LOBE, center.x + size, center.y, center.x, center.y + half)
        close()
    }
    drawPath(path, HEART)
}

/**
 * Opens [EcgCaptureActivity]. A background start is allowed only with the overlay permission; without it the
 * activity goes out as a full-screen notification, which the system launches straight away while the screen is off.
 */
class ActivityEcgCaptureScreen(private val context: Context) : EcgCaptureScreen {

    override fun show() {
        val intent = Intent(context, EcgCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "Direct start of the recording screen failed", it) }
        if (Settings.canDrawOverlays(context)) return
        runCatching { postFullScreen(intent) }.onFailure { Log.w(TAG, "Recording screen notification failed", it) }
    }

    override fun hide() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun postFullScreen(intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.ecg_channel), NotificationManager.IMPORTANCE_HIGH))
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.ecg_notification_title))
            .setContentText(context.getString(R.string.ecg_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setOngoing(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "EcgCaptureScreen"
        const val CHANNEL_ID = "ecg_recording"
        const val NOTIFICATION_ID = 46
    }
}

private const val TICK_MS = 250L
private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000.0
private const val RESTING_BPM = 70
private const val RING_WIDTH = 6
private const val HEART_DP = 72
private const val HEART_FILL = 0.4f
private const val HEART_LOBE = 0.75f
private const val HEART_NOTCH = 0.4f
private const val LUB_AT = 0.1f
private const val DUB_AT = 0.3f
private const val SWELL_WIDTH = 0.1f
private const val LUB_SWELL = 0.25f
private const val DUB_SWELL = 0.12f
private val HEART = Color(0xFFE53935)
