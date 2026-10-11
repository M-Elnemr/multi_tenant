package com.elmanassa.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createQueueChannels()
    }

    /** The server names these channels in its pushes: "queue" (your turn: loud, heads-up) and "queue_updates" (position changes). */
    private fun createQueueChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val turn = NotificationChannel("queue", "دورك في العيادة / Your turn", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Called by the doctor"
            enableVibration(true)
            setSound(sound, audio)
        }
        val updates = NotificationChannel("queue_updates", "تقدّم الدور / Queue updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Patients before you"
            setSound(sound, audio)
        }
        nm.createNotificationChannel(turn)
        nm.createNotificationChannel(updates)
    }
}
