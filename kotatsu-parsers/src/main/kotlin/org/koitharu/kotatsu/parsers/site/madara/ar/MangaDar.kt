package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

/**
 * Manga Epic: new source for mangadar.com (Ahmed's request, 2026-11-19).
 *
 * Live-structure check 2026-11-19: WordPress + Madara family — covers under
 * /wp-content/uploads/, details at /manga/<slug>/ (verified with /manga/one-piece/).
 * The catalog mixes novels in, so listings keep only titles whose chapters are
 * images (MadaraParser.mangaChaptersOnly). Verify in the parser test harness
 * before shipping; if the ajax listing does not mark novel chapters as text,
 * drop the flag and filter by the site's type badge instead.
 */
@MangaSourceParser("MANGADAR", "MangaDar", "ar")
internal class MangaDar(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGADAR, "mangadar.com") {

	override val mangaChaptersOnly = true
}
