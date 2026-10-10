package app.xan.music.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import app.xan.music.MainActivity
import app.xan.music.R
import app.xan.music.widget.MusicWidgetReceiver

/** A media-style notification backed by the same session as the in-app player. */
@OptIn(UnstableApi::class)
internal object ForegroundPlaybackNotification {
    fun build(context: Context, session: MediaSession, player: Player): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun control(action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                context,
                requestCode,
                Intent(context, MusicService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val playPauseIcon = if (player.playWhenReady) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        val playPauseLabel = if (player.playWhenReady) R.string.pause else R.string.play

        return Notification.Builder(context, MusicService.CHANNEL_ID)
            .setSmallIcon(R.drawable.xan_notification)
            .setContentTitle(player.mediaMetadata.title ?: context.getString(R.string.music_player))
            .setContentText(player.mediaMetadata.artist ?: "")
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_widget_skip_previous,
                    context.getString(R.string.previous),
                    control(MusicWidgetReceiver.ACTION_PREVIOUS, 1),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    playPauseIcon,
                    context.getString(playPauseLabel),
                    control(MusicWidgetReceiver.ACTION_PLAY_PAUSE, 2),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_widget_skip_next,
                    context.getString(R.string.next),
                    control(MusicWidgetReceiver.ACTION_NEXT, 3),
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.platformToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }
}
