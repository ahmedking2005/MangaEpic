package org.koitharu.kotatsu.core.prefs

/**
 * The screen Manga Epic opens on, chosen by the user in the Appearance settings.
 * Manga Epic addition (2026-11-18).
 */
enum class StartPage {

	LATEST,
	DOWNLOADS,
	FAVORITES,
	EXPLORE,
	PROFILE,
	;

	/** The bottom-bar tab this start page selects, or null: the downloads screen is its own activity. */
	val navItem: NavItem?
		get() = when (this) {
			LATEST -> NavItem.EXPLORE
			DOWNLOADS -> null
			FAVORITES -> NavItem.FAVORITES
			EXPLORE -> NavItem.SOURCES
			PROFILE -> NavItem.PROFILE
		}
}
