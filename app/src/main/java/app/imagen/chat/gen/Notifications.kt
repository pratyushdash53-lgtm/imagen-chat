package app.imagen.chat.gen

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.imagen.chat.MainActivity
import app.imagen.chat.R
import app.imagen.chat.data.ChatMessage
import java.io.File

object Notifications {
    private const val CHANNEL_PROGRESS = "generation"
    private const val CHANNEL_RESULT = "results"
    const val PROGRESS_ID = 4242

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Image generation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an image is being generated"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Finished images", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when an image is ready or couldn't be made"
            },
        )
    }

    private fun openChat(context: Context, conversationId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversationId != null) intent.putExtra(MainActivity.EXTRA_CONVERSATION, conversationId)
        return PendingIntent.getActivity(
            context,
            conversationId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun progress(context: Context, count: Int): android.app.Notification {
        val cancel = PendingIntent.getService(
            context,
            1,
            Intent(context, GenerationService::class.java).setAction(GenerationService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle(if (count > 1) "Generating $count images" else "Generating your image")
            .setContentText("You can leave the app. We'll let you know when it's ready.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openChat(context, null))
            .addAction(0, "Cancel", cancel)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun done(context: Context, conversationId: String, title: String, message: ChatMessage, image: File?) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val preview = image?.let { decodeSampled(it, 1024) }
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle(title)
            .setContentText(if (message.mode == "edit") "Your edited image is ready" else "Your image is ready")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openChat(context, conversationId))
        if (preview != null) {
            builder.setLargeIcon(preview)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(preview).bigLargeIcon(null as Bitmap?))
        }
        manager.notify(conversationId.hashCode(), builder.build())
    }

    @SuppressLint("MissingPermission")
    fun failed(context: Context, conversationId: String, title: String, error: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_sparkle)
            .setContentTitle("Couldn't finish: $title")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openChat(context, conversationId))
            .build()
        manager.notify(conversationId.hashCode(), notification)
    }

    private fun decodeSampled(file: File, maxEdge: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: OutOfMemoryError) {
        null
    }
}
