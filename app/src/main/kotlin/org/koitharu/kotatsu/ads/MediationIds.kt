package org.koitharu.kotatsu.ads

/**
 * Manga Epic mediation reference IDs (Mintegral + InMobi + Unity Ads, AdMob bidding).
 *
 * IMPORTANT: AdMob Mediation does NOT read these values from the app. The mapping
 * between our AdMob ad units and each network's placements is configured in the
 * AdMob console (Mediation groups), and the networks' SDKs are initialized by the
 * Google adapters from that server-side config. This object exists as the single
 * in-repo record of the issued IDs — for support, for the app-ads.txt lines, and
 * for Mintegral/InMobi test-mode cross-checks. Fill every TODO from the network
 * dashboards, then mirror the same values into the AdMob mediation-group mappings.
 *
 * Formats map 1:1 onto our four AdMob placements:
 *  - feed native        -> BuildConfig.ADMOB_FEED_NATIVE_ID
 *  - details native     -> BuildConfig.ADMOB_DETAILS_NATIVE_ID
 *  - chapter-break      -> BuildConfig.ADMOB_INTERSTITIAL_ID (interstitial)
 *  - anime pause banner -> BuildConfig.ADMOB_ANIME_PAUSE_BANNER_ID (banner)
 *
 * Unity Ads covers only the interstitial and the banner: no Unity native placement
 * was created, so the two native units stay Google + Mintegral + InMobi only.
 */
object MediationIds {

	// ---- Mintegral (dashboard: dev.mintegral.com -> APP Setting / Placements & Units)
	// App-level values, shown once the "Manga Epic" app is created in Mintegral.
	const val MINTEGRAL_APP_ID = "TODO_MINTEGRAL_APP_ID"
	const val MINTEGRAL_APP_KEY = "TODO_MINTEGRAL_APP_KEY"

	// Per-placement values: each placement has BOTH a Placement ID and an Ad Unit ID;
	// the AdMob mapping form asks for all four fields (App Key, App ID, Placement ID,
	// Ad Unit ID). Placements must be created with Bidding Type = Header Bidding.
	const val MINTEGRAL_PLACEMENT_FEED_NATIVE = "TODO"
	const val MINTEGRAL_UNIT_FEED_NATIVE = "TODO"
	const val MINTEGRAL_PLACEMENT_DETAILS_NATIVE = "TODO"
	const val MINTEGRAL_UNIT_DETAILS_NATIVE = "TODO"
	const val MINTEGRAL_PLACEMENT_CHAPTER_INTERSTITIAL = "TODO"
	const val MINTEGRAL_UNIT_CHAPTER_INTERSTITIAL = "TODO"
	const val MINTEGRAL_PLACEMENT_PAUSE_BANNER = "TODO"
	const val MINTEGRAL_UNIT_PAUSE_BANNER = "TODO"

	// ---- InMobi (dashboard: publisher.inmobi.com -> Finance > Payment Settings for
	// the Account ID; Inventory for placements). Placements must be created with
	// Audience Bidding = On and Partner = Google SDK Bidding. The AdMob mapping
	// form asks for Account ID + Placement ID only.
	const val INMOBI_ACCOUNT_ID = "TODO_INMOBI_ACCOUNT_ID"

	const val INMOBI_PLACEMENT_FEED_NATIVE = "TODO"
	const val INMOBI_PLACEMENT_DETAILS_NATIVE = "TODO"
	const val INMOBI_PLACEMENT_CHAPTER_INTERSTITIAL = "TODO"
	const val INMOBI_PLACEMENT_PAUSE_BANNER = "TODO"

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
