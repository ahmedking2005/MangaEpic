package org.koitharu.kotatsu.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.cloud.RemoteConfigManager

/**
 * Owns consent, SDK initialization, and the preloaded interstitial ads.
 *
 * Manga Epic: ad unit IDs come from BuildConfig (real AdMob IDs), and every entry
 * point is gated by the Firebase Remote Config `ads_enabled` kill-switch.
 *
 * All public methods are called on the main thread. Ads are never requested until
 * UMP reports that the app is allowed to request them.
 */
object AdMobManager {

	private const val TAG = "AdMobManager"
	private const val AD_PREFERENCES = "admob_preferences"
	private const val KEY_CHAPTER_CROSSINGS_SINCE_BREAK = "chapter_crossings_since_break"
	private const val KEY_LAST_CHAPTER_BREAK_AT = "last_chapter_break_at"
	private const val CHAPTER_CROSSINGS_BETWEEN_BREAKS = 3
	private const val CHAPTER_BREAK_MIN_INTERVAL_MS = 180_000L

	private var consentRequestStarted = false
	private var sdkInitializationStarted = false
	private var sdkInitialized = false
	private var consentResolutionFinished = false
	private val sdkReadyCallbacks = mutableListOf<(Boolean) -> Unit>()

	private var animeInterstitial: InterstitialAd? = null
	private var isAnimeInterstitialLoading = false

	/** Manga Epic kill-switch: Remote Config `ads_enabled` (default true). */
	fun isEnabled(): Boolean = RemoteConfigManager.isAdsEnabled

	@MainThread
	fun initialize(activity: Activity) {
		if (!isEnabled()) return
		if (consentRequestStarted) return
		consentRequestStarted = true

		val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
		val requestParameters = ConsentRequestParameters.Builder()
			.setTagForUnderAgeOfConsent(false)
			.build()

		consentInformation.requestConsentInfoUpdate(
			activity,
			requestParameters,
			{
				UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
					if (formError != null) {
						Log.w(TAG, "Consent form was not shown: ${formError.message}")
					}
					consentResolutionFinished = true
					if (consentInformation.canRequestAds()) {
						initializeMobileAds(activity.applicationContext)
					} else {
						resolveSdkReadyCallbacks(isReady = false)
					}
				}
			},
			{ requestError ->
				Log.w(TAG, "Consent information update failed: ${requestError.message}")
				consentResolutionFinished = true
				if (consentInformation.canRequestAds()) {
					initializeMobileAds(activity.applicationContext)
				} else {
					resolveSdkReadyCallbacks(isReady = false)
				}
			},
		)

		// Returning users may already have a valid consent state. This avoids delaying
		// the first ad request while the latest consent information is refreshed.
		if (consentInformation.canRequestAds()) {
			initializeMobileAds(activity.applicationContext)
		}
	}

	@MainThread
	fun isPrivacyOptionsRequired(context: Context): Boolean {
		return UserMessagingPlatform.getConsentInformation(context)
			.privacyOptionsRequirementStatus ==
			ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
	}

	@MainThread
	fun showPrivacyOptions(activity: Activity, onFinished: (String?) -> Unit) {
		UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
			if (UserMessagingPlatform.getConsentInformation(activity).canRequestAds()) {
				initializeMobileAds(activity.applicationContext)
			}
			onFinished(formError?.message)
		}
	}

	@MainThread
	fun loadNativeAd(
		context: Context,
		adUnitId: String = BuildConfig.ADMOB_FEED_NATIVE_ID,
		onLoaded: (NativeAd) -> Unit,
		onFailed: () -> Unit,
	) {
		if (!isEnabled()) {
			onFailed()
			return
		}
		runWhenSdkReady { isReady ->
			if (!isReady) {
				onFailed()
				return@runWhenSdkReady
			}
			AdLoader.Builder(context, adUnitId)
				.forNativeAd { nativeAd -> onLoaded(nativeAd) }
				.withNativeAdOptions(
					NativeAdOptions.Builder()
						.setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
						.setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
						.build(),
				)
				.withAdListener(
					object : AdListener() {
						override fun onAdFailedToLoad(error: LoadAdError) {
							Log.w(TAG, "Native ad failed to load: ${error.message}")
							onFailed()
						}
					},
				)
				.build()
				.loadAd(AdRequest.Builder().build())
		}
	}

	/**
	 * Manga Epic: shows the preloaded interstitial when the reader crosses from one
	 * chapter into the next. Frequency-capped (every $CHAPTER_CROSSINGS_BETWEEN_BREAKS
	 * crossings, at most once per few minutes) and never blocking: if no ad is ready
	 * the crossing simply proceeds and a preload is kicked off for the next one.
	 */
	@MainThread
	fun offerChapterBreakInterstitial(activity: Activity) {
		if (!isEnabled() || activity.isFinishing || activity.isDestroyed) return
		val preferences = activity.getSharedPreferences(AD_PREFERENCES, Context.MODE_PRIVATE)
		val crossings = preferences.getInt(KEY_CHAPTER_CROSSINGS_SINCE_BREAK, 0) + 1
		val lastShownAt = preferences.getLong(KEY_LAST_CHAPTER_BREAK_AT, 0L)
		val now = System.currentTimeMillis()
		preferences.edit().putInt(KEY_CHAPTER_CROSSINGS_SINCE_BREAK, crossings).apply()
		if (crossings < CHAPTER_CROSSINGS_BETWEEN_BREAKS) return
		if (now - lastShownAt < CHAPTER_BREAK_MIN_INTERVAL_MS) return
		val ad = animeInterstitial
		if (ad == null) {
			preloadAnimeInterstitial(activity.applicationContext)
			return
		}
		animeInterstitial = null
		preferences.edit()
			.putInt(KEY_CHAPTER_CROSSINGS_SINCE_BREAK, 0)
			.putLong(KEY_LAST_CHAPTER_BREAK_AT, now)
			.apply()
		ad.fullScreenContentCallback = object : FullScreenContentCallback() {
			override fun onAdDismissedFullScreenContent() {
				preloadAnimeInterstitial(activity.applicationContext)
			}

			override fun onAdFailedToShowFullScreenContent(error: AdError) {
				Log.w(TAG, "Chapter-break interstitial failed to show: ${error.message}")
				preloadAnimeInterstitial(activity.applicationContext)
			}
		}
		runCatching {
			ad.show(activity)
		}.onFailure {
			Log.w(TAG, "Chapter-break interstitial could not be shown", it)
			preloadAnimeInterstitial(activity.applicationContext)
		}
	}

	@MainThread
	private fun initializeMobileAds(context: Context) {
		if (sdkInitialized || sdkInitializationStarted) return
		sdkInitializationStarted = true
		MobileAds.initialize(context) {
			sdkInitialized = true
			resolveSdkReadyCallbacks(isReady = true)
			preloadAnimeInterstitial(context)
		}
	}

	@MainThread
	private fun runWhenSdkReady(callback: (Boolean) -> Unit) {
		when {
			sdkInitialized -> callback(true)
			consentResolutionFinished && !sdkInitializationStarted -> callback(false)
			else -> sdkReadyCallbacks += callback
		}
	}

	@MainThread
	private fun resolveSdkReadyCallbacks(isReady: Boolean) {
		val callbacks = sdkReadyCallbacks.toList()
		sdkReadyCallbacks.clear()
		callbacks.forEach { it(isReady) }
	}

	@MainThread
	private fun preloadAnimeInterstitial(context: Context) {
		if (!sdkInitialized || animeInterstitial != null || isAnimeInterstitialLoading) return
		isAnimeInterstitialLoading = true
		InterstitialAd.load(
			context,
			BuildConfig.ADMOB_INTERSTITIAL_ID,
			AdRequest.Builder().build(),
			object : InterstitialAdLoadCallback() {
				override fun onAdLoaded(ad: InterstitialAd) {
					isAnimeInterstitialLoading = false
					animeInterstitial = ad
				}

				override fun onAdFailedToLoad(error: LoadAdError) {
					isAnimeInterstitialLoading = false
					animeInterstitial = null
					Log.w(TAG, "Anime interstitial failed to load: ${error.message}")
				}
			},
		)
	}

}
