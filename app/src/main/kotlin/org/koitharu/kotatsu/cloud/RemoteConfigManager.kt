package org.koitharu.kotatsu.cloud

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import org.koitharu.kotatsu.R

/**
 * Manga Epic: Firebase Remote Config access.
 *
 * All keys have in-app defaults (res/xml/remote_config_defaults.xml) that mirror the
 * console template, so the gates behave sanely even before the first successful fetch.
 * Values are fetched in the background at app start and re-checked when a fetch lands.
 * Never put secrets in Remote Config: these are public behaviour switches only.
 */
object RemoteConfigManager {

	private const val FETCH_INTERVAL_SECONDS = 43_200L // 12 hours

	@Volatile
	private var isInitialized = false

	fun init() {
		if (isInitialized) {
			return
		}
		isInitialized = true
		val config = FirebaseRemoteConfig.getInstance()
		config.setConfigSettingsAsync(
			FirebaseRemoteConfigSettings.Builder()
				.setMinimumFetchIntervalInSeconds(FETCH_INTERVAL_SECONDS)
				.build(),
		)
		config.setDefaultsAsync(R.xml.remote_config_defaults)
		config.fetchAndActivate()
	}

	/** Fetches the latest values now (throttled by the fetch interval) and runs [onComplete] after. */
	fun refresh(onComplete: () -> Unit) {
		FirebaseRemoteConfig.getInstance()
			.fetchAndActivate()
			.addOnCompleteListener { onComplete() }
	}

	private val config: FirebaseRemoteConfig
		get() = FirebaseRemoteConfig.getInstance()

	val isMaintenanceMode: Boolean
		get() = config.getBoolean("maintenance_mode")

	val isForceUpdateEnabled: Boolean
		get() = config.getBoolean("force_update")

	/** Oldest supported build number (the N in versionName "9.9.N"). */
	val minimumSupportedBuild: Int
		get() = config.getLong("minimum_supported_build").toInt()

	val latestBuild: Int
		get() = config.getLong("latest_build").toInt()

	val isAnnouncementEnabled: Boolean
		get() = config.getBoolean("announcement_enabled")

	val announcementMessage: String
		get() = config.getString("announcement_message")

	/** Kill switch for the cloud library sync (phase 4). */
	val isSyncEnabled: Boolean
		get() = config.getBoolean("enable_sync")

	/** Kill switch for announcement / new-chapter push notifications. */
	val isNotificationsEnabled: Boolean
		get() = config.getBoolean("enable_notifications")

	/** Kill switch for AdMob ads (native + interstitial). Default on. */
	val isAdsEnabled: Boolean
		get() = config.getBoolean("ads_enabled")

	/** Build number = last component of versionName "9.9.N"; 0 when it cannot be parsed. */
	fun currentBuild(versionName: String?): Int =
		versionName?.substringAfterLast('.')?.toIntOrNull() ?: 0
}
