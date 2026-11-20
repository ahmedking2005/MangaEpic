package org.koitharu.kotatsu.list.domain

import org.koitharu.kotatsu.parsers.model.MangaTag

/**
 * Some sources list every genre twice — once in English, once in Arabic —
 * which rendered as two rows of duplicate chips on the details page. This
 * table recognizes the common genres in either language so the presentation
 * layer can merge each pair into one chip (the Arabic name wins). Unknown
 * genres pass through untouched, keyed by their own normalized title, so
 * exact duplicates of those merge too.
 */
object GenreAliases {

	/** Canonical genre key -> Arabic display name. */
	private val CANONICAL: Map<String, String> = linkedMapOf(
		"action" to "أكشن",
		"adventure" to "مغامرات",
		"comedy" to "كوميدي",
		"drama" to "دراما",
		"crime" to "جريمة",
		"mystery" to "غموض",
		"thriller" to "إثارة",
		"horror" to "رعب",
		"romance" to "رومانسي",
		"fantasy" to "خيال",
		"science fiction" to "خيال علمي",
		"supernatural" to "خارق للطبيعة",
		"slice of life" to "حياة يومية",
		"sports" to "رياضة",
		"school" to "مدرسي",
		"military" to "عسكري",
		"war" to "حرب",
		"historical" to "تاريخي",
		"psychological" to "نفسي",
		"music" to "موسيقي",
		"game" to "ألعاب",
		"martial arts" to "فنون قتالية",
		"mecha" to "ميكا",
		"space" to "فضاء",
		"super power" to "قوى خارقة",
		"vampire" to "مصاصو دماء",
		"demons" to "شياطين",
		"magic" to "سحر",
		"harem" to "حريم",
		"isekai" to "إيسيكاي",
		"family" to "عائلي",
		"kids" to "أطفال",
		"shounen" to "شونين",
		"shoujo" to "شوجو",
		"seinen" to "سينين",
		"josei" to "جوسي",
	)

	/** Extra spellings that mean one of the canonical genres. */
	private val EXTRA_ALIASES: Map<String, String> = mapOf(
		"sci-fi" to "science fiction",
		"scifi" to "science fiction",
		"school life" to "school",
		"suspense" to "thriller",
		"superpower" to "super power",
		"super powers" to "super power",
		"another world" to "isekai",
		"كوميديا" to "comedy",
	)

	/** Normalized alias (either language) -> canonical key. */
	private val ALIASES: Map<String, String> = buildMap {
		for ((key, arabic) in CANONICAL) {
			put(key, key)
			put(normalize(arabic), key)
		}
		for ((alias, key) in EXTRA_ALIASES) {
			put(normalize(alias), key)
		}
	}

	fun canonicalKey(tag: MangaTag): String = ALIASES[normalize(tag.title)] ?: normalize(tag.title)

	fun isArabic(title: String): Boolean = title.any { it in '؀'..'ۿ' }

	/** The tag with its Arabic display name, when it is a known genre shown in English only. */
	fun localized(tag: MangaTag): MangaTag {
		if (isArabic(tag.title)) return tag
		val arabic = CANONICAL[canonicalKey(tag)] ?: return tag
		return tag.copy(title = arabic)
	}

	private fun normalize(raw: String): String {
		val folded = StringBuilder(raw.length)
		for (char in raw.trim().lowercase()) {
			when (char) {
				'ـ' -> Unit // tatweel
				'أ', 'إ', 'آ', 'ٱ' -> folded.append('ا')
				'ى', 'ئ' -> folded.append('ي')
				'ة' -> folded.append('ه')
				'ؤ' -> folded.append('و')
				else -> if (!char.isDigit() || folded.isNotEmpty()) folded.append(char)
			}
		}
		return folded.toString().replace(Regex("\\s+"), " ").trim()
	}
}
