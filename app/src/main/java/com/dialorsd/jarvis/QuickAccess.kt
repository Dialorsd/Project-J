package com.dialorsd.jarvis

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.content.IntentCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Home-screen widget: TALK (push-to-talk), LIVE (Live mode) and the next timer. Updated only when timers change. */
class JarvisWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, JarvisWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_jarvis)
            v.setOnClickPendingIntent(R.id.widget_talk, open(ctx, MainActivity.ACTION_LISTEN, 1))
            v.setOnClickPendingIntent(R.id.widget_live, open(ctx, MainActivity.ACTION_LIVE, 2))
            val next = Store.loadTimers().filter { it.kind == "timer" && it.endAt > System.currentTimeMillis() }.minByOrNull { it.endAt }
            v.setTextViewText(R.id.widget_timer, next?.let {
                "${it.label} · ${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it.endAt))}"
            } ?: "No timers")
            mgr.updateAppWidget(ids, v)
        }

        private fun open(ctx: Context, action: String, req: Int): PendingIntent =
            PendingIntent.getActivity(ctx, 700 + req,
                Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/**
 * Quick Settings tile. A tile may not start a microphone foreground service itself (while-in-use rule),
 * so it opens the app with "start Live"; tapping it while Live runs ends Live mode.
 */
class LiveTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = if (LiveSession.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (LiveSession.active) {
            LiveSession.stop(this)
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        val launch = {
            val i = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_LIVE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 703, i,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            }
        }
        if (isLocked) unlockAndRun { launch() } else launch()
    }
}

/**
 * Headset / media button: while the app is on screen or Live mode runs, a press toggles listening
 * (or mutes/unmutes Live). Not captured when the app is closed.
 */
object HeadsetButton {
    private var session: MediaSession? = null
    /** Set by the main screen: start/stop push-to-talk. */
    var onPress: (() -> Unit)? = null

    fun refresh(ctx: Context) {
        val want = Store.appVisible || LiveSession.active
        val app = ctx.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (want && session == null) create(app)
            else if (!want && session != null) { runCatching { session?.release() }; session = null }
        }
    }

    private fun create(ctx: Context) {
        val m = runCatching { MediaSession(ctx, "Jarvis") }.getOrNull() ?: return
        m.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev = IntentCompat.getParcelableExtra(mediaButtonIntent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    ?: return super.onMediaButtonEvent(mediaButtonIntent)
                if (ev.keyCode !in setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)) return super.onMediaButtonEvent(mediaButtonIntent)
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) press()
                return true
            }
        })
        m.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
        m.isActive = true
        session = m
    }

    private fun press() {
        if (LiveSession.active) LiveSession.toggleMute() else onPress?.invoke()
    }
}
