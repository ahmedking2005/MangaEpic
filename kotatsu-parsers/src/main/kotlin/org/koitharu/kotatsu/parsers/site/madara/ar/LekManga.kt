package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANGALEK", "LekManga", "ar")
internal class LekManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGALEK, "mangalik.net", pageSize = 10) {

	// The site (formerly lekmanga.net, which now 301-redirects every request here)
	// renders no chapter list into the details page; chapters load only through the
	// admin-ajax manga_get_chapters call, so the default /ajax/chapters/ path
	// returns the details page itself and an empty chapter list.
	override val postReq = true
}
