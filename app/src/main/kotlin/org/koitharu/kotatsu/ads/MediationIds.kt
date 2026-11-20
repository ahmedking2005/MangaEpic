package org.koitharu.kotatsu.ads

/**
 * Manga Epic mediation reference IDs (Unity Ads only, via AdMob bidding).
 *
 * History: mediation first shipped with three networks (Mintegral + InMobi +
 * Unity Ads) in the 2026-11-20 mediation build (devel 83107a48). By the owner's
 * decision the same day (2026-11-20 — personal-use app, Unity-only mediation),
 * the Mintegral and InMobi adapters were removed from the app and their sections
 * deleted from this file; Unity Ads is the only mediation network that remains.
 *
 * IMPORTANT: AdMob Mediation does NOT read these values from the app. The mapping
 * between our AdMob ad units and each network's placements is configured in the
 * AdMob console (Mediation groups), and the networks' SDKs are initialized by the
 * Google adapters from that server-side config. This object exists as the single
 * in-repo record of the issued IDs — for support and for the app-ads.txt lines.
 * Fill every value from the network dashboard, then mirror the same values into
 * the AdMob mediation-group mappings.
 *
 * Formats map 1:1 onto our four AdMob placements:
 *  - feed native        -> BuildConfig.ADMOB_FEED_NATIVE_ID
 *  - details native     -> BuildConfig.ADMOB_DETAILS_NATIVE_ID
 *  - chapter-break      -> BuildConfig.ADMOB_INTERSTITIAL_ID (interstitial)
 *  - anime pause banner -> BuildConfig.ADMOB_ANIME_PAUSE_BANNER_ID (banner)
 *
 * Unity Ads covers only the interstitial and the banner: no Unity native placement
 * was created, so the two native units are served by Google demand only.
 */
object MediationIds {

	// ---- Unity Ads (dashboard: cloud.unity.com -> Unity Ads Monetization).
	// REAL issued values (account setup completed 2026-11-20 — not placeholders).
	// Project "Manga Epic"; mediation partner already set to Google AdMob in the
	// Unity dashboard; COPPA designation = not child-directed. Placements were
	// created with Setup = Bidding; the AdMob mapping form asks for Game ID +
	// Placement ID only.
	const val UNITY_GAME_ID = "6228781"

	// Unity project ID — reference only (dashboard/support use; AdMob never asks for it).
	const val UNITY_PROJECT_ID = "07162bc9-0b27-498c-b38a-d4432f93f796"

	const val UNITY_PLACEMENT_CHAPTER_INTERSTITIAL = "Interstitial_Android"
	const val UNITY_PLACEMENT_PAUSE_BANNER = "Banner_Android"

	// Exists in the Unity dashboard but UNUSED: the app has no rewarded format.
	const val UNITY_PLACEMENT_REWARDED_UNUSED = "Rewarded_Android"

	// The Unity dashboard Store ID is NOT configured (no Google Play listing yet).
	// Deferred: set it once the Play listing exists — Unity's live-serving review
	// and app-ads.txt verification key off the store listing.
}
