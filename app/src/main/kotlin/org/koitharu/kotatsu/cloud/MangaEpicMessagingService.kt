package org.koitharu.kotatsu.cloud

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.main.ui.MainActivity

/**
 * Manga Epic: receives Firebase Cloud Messaging pushes.
 *
 * Notification-payload messages sent while the app is in the background are displayed
 * by the Firebase SDK itself; this service handles foreground and data messages.
 * Tokens are stored locally now and uploaded to the user's devices table once
 * accounts exist (phase 3). Message text never carries sensitive data.
 */
@AndroidEntryPoint
class MangaEpicMessagingService : FirebaseMessagingService() {

	@Inject
	lateinit var settings: AppSettings

	override fun onNewToken(token: String) {
		super.onNewToken(token)
		settings.fcmToken = token
	}

	override fun onMessageReceived(message: RemoteMessage) {
		super.onMessageReceived(message)
		if (!RemoteConfigManager.isNotificationsEnabled) {
			return
		}
		val title = message.notification?.title ?: message.data["title"] ?: return
		val body = message.notification?.body ?: message.data["body"].orEmpty()
		showNotification(title, body)
	}

	private fun showNotification(title: String, body: String) {
		val manager = NotificationManagerCompat.from(this)
		manager.createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
				.setName(getString(R.string.cloud_announcements_channel))
				.build(),
		)
		val intent = Intent(this, MainActivity::class.java)
			.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
		val pendingIntent = PendingIntent.getActivity(
			this,
			0,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
		val notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(title)
			.setContentText(body)
			.setStyle(NotificationCompat.BigTextStyle().bigText(body))
			.setAutoCancel(true)
			.setContentIntent(pendingIntent)
			.build()
		try {
			manager.notify(System.currentTimeMillis().toInt(), notification)
		} catch (e: SecurityException) {
			// Notifications permission not granted (Android 13+); nothing to do.
		}
	}

	private companion object {
		const val CHANNEL_ID = "announcements"
	}
}
