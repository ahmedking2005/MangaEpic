package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.NovelChapterContent
import org.koitharu.kotatsu.parsers.model.NovelImage
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet

/**
 * KolNovel (ملوك الروايات) — rewritten in-house for the site's current home,
 * kolnovel.com, and its 'starless' theme (the previous target, free.kolnovel.com,
 * is dead and its selectors match nothing on the new theme).
 *
 * Written against a live spec sheet (agent_notes/source-spec-sheets-cycle1.md,
 * captured 2026-11-20 from the site itself):
 *  - Catalogue: /series/ archive, cards are article.kol-series-archive__item;
 *    pagination is the WordPress path form /series/page/{n}/ (the ?page=
 *    parameter is ignored by the theme); ordering via ?order=update|popular|
 *    title|latest, as before.
 *  - Genres live under /genre/{slug}/ with the same path pagination; search is
 *    the WordPress ?s= form rendering the same archive cards.
 *  - Details: /series/{slug}/ — h1.kol-series-editorial__title, synopsis in
 *    .kol-series-editorial__prose, chapters embedded in #chapters as
 *    a[data-kol-chapter-id], paginated by ?chapter_page={n}&chapter_order=.
 *  - Chapter text: .epcontent.entry-content inside the reading surface.
 *
 * House rules honoured: the domain comes only from [configKeyDomain], no
 * member signature mentions the generated source type, and a chapter with no
 * readable content fails with a [ParseException] instead of returning null.
 */
@MangaSourceParser("KOLNOVEL", "KolNovel", "ar", ContentType.NOVEL)
internal class KolNovel(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOLNOVEL, pageSize = 20, searchPageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("kolnovel.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val doc = webClient.httpGet("https://$domain/series/").parseHtml()
		val tags = doc.select("a[href*=/genre/]").mapNotNullToSet { a ->
			val href = a.attr("href")
			val key = href.substringAfter("/genre/").substringBefore('/').takeIf { it.isNotEmpty() }
				?: return@mapNotNullToSet null
			val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
			MangaTag(key = key, title = title, source = source)
		}
		return MangaListFilterOptions(availableTags = tags)
	}

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
			val tag = filter.tags.oneOrThrowIfMany()
			when {
				query != null -> {
					// WordPress search: page 1 is /?s=…, later pages are /page/{n}/?s=…
					if (page > 1) {
						append("/page/")
						append(page)
					}
					append("/?s=")
					append(query.urlEncoded())
				}
				tag != null -> {
					append("/genre/")
					append(tag.key)
					append('/')
					if (page > 1) {
						append("page/")
						append(page)
						append('/')
					}
				}
				else -> {
					append("/series/")
					if (page > 1) {
						append("page/")
						append(page)
						append('/')
					}
					append("?order=")
					append(
						when (order) {
							SortOrder.POPULARITY -> "popular"
							SortOrder.ALPHABETICAL -> "title"
							SortOrder.NEWEST -> "latest"
							else -> "update"
						},
					)
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("article.kol-series-archive__item").mapNotNull { article ->
			val coverLink = article.selectFirst("a.kol-series-archive__cover") ?: return@mapNotNull null
			val href = coverLink.attrAsRelativeUrl("href")
			val title = article.selectFirst("h3 a")?.text()?.trim()
				?.takeIf { it.isNotEmpty() }
				?: coverLink.selectFirst("img")?.attr("alt")?.trim()
				?: return@mapNotNull null
			val rating = article.selectFirst(".kol-series-archive__rating b")?.text()
				?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = rating,
				contentRating = null,
				coverUrl = article.selectFirst(".kol-series-archive__cover img")?.src(),
				tags = emptySet(),
				state = parseState(article.selectFirst(".kol-series-archive__status")?.text()),
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")
			?.takeIf { it.isNotBlank() } ?: manga.coverUrl
		val tags = doc.select("a[href*=/genre/]").mapNotNullToSet { a ->
			val key = a.attr("href").substringAfter("/genre/").substringBefore('/')
				.takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
			val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
			MangaTag(key = key, title = title, source = source)
		}
		val authors = doc.select(".kol-series-editorial__byline a").mapNotNullToSet { a ->
			a.text().trim().takeIf { it.isNotEmpty() }
		}
		val stateText = doc.select("[class*=__status]").joinToString(" ") { it.text() }
		return manga.copy(
			title = doc.selectFirst("h1.kol-series-editorial__title")?.text()?.trim()
				?.takeIf { it.isNotEmpty() } ?: manga.title,
			altTitles = setOfNotNull(
				doc.selectFirst(".kol-series-editorial__alt-title")?.text()?.trim()
					?.takeIf { it.isNotEmpty() },
			).ifEmpty { manga.altTitles },
			description = doc.selectFirst(".kol-series-editorial__prose")?.html()
				?.takeIf { it.isNotBlank() } ?: manga.description,
			coverUrl = cover,
			largeCoverUrl = cover,
			rating = doc.selectFirst("meta[itemprop=ratingValue]")?.attr("content")
				?.toFloatOrNull()?.div(10f) ?: manga.rating,
			state = parseState(stateText) ?: manga.state,
			authors = authors.ifEmpty { manga.authors },
			tags = tags.ifEmpty { manga.tags },
			chapters = loadChapters(manga.url.toAbsoluteUrl(domain), doc),
		)
	}

	/**
	 * The chapter list is embedded in the novel page and paginated in place:
	 * ?chapter_page={n} with chapter_order=oldest gives ascending order. Pages
	 * are followed until one adds no new chapter ids.
	 */
	private suspend fun loadChapters(novelUrl: String, firstDoc: Document): List<MangaChapter> {
		val seen = LinkedHashMap<Long, MangaChapter>()
		var doc = firstDoc
		var page = 1
		while (page <= MAX_CHAPTER_LIST_PAGES) {
			var added = 0
			val scope = doc.selectFirst("#chapters") ?: doc
			for (a in scope.select("a[data-kol-chapter-id]")) {
				val chapterId = a.attr("data-kol-chapter-id").toLongOrNull() ?: continue
				if (chapterId in seen) continue
				val href = a.attrAsRelativeUrl("href")
				val label = (a.selectFirst("strong")?.text() ?: a.text()).trim()
				val number = CHAPTER_NUMBER.find(label)?.value?.toFloatOrNull()
				seen[chapterId] = MangaChapter(
					id = generateUid(href),
					title = label.ifEmpty { "الفصل ${seen.size + 1}" },
					number = number ?: (seen.size + 1).toFloat(),
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = 0L,
					branch = null,
					source = source,
				)
				added++
			}
			if (added == 0) break
			page++
			if (page > MAX_CHAPTER_LIST_PAGES) break
			val nextUrl = novelUrl + (if (novelUrl.contains('?')) '&' else '?') +
				"chapter_volume=__kol_all__&chapter_order=oldest&chapter_page=" + page
			doc = webClient.httpGet(nextUrl).parseHtml()
		}
		return seen.values.sortedBy(MangaChapter::number)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = emptyList()

	override suspend fun getChapterContent(chapter: MangaChapter): NovelChapterContent {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val content = doc.selectFirst(".epcontent.entry-content")
			?: doc.selectFirst(".kol-app-text")
			?: throw ParseException("KolNovel: chapter text container not found", chapterUrl)
		content.select(
			"script, style, iframe, noscript, form, ins, .code-block, [id^=pf-], " +
				".kol-reader-floating-tools, .kol-rs-accordion, nav, button, a",
		).remove()
		content.select("img").forEach { img ->
			val lazy = img.attr("data-src").takeIf { it.isNotBlank() }
			if (lazy != null) img.attr("src", lazy)
		}
		content.select("p").forEach { p ->
			if (p.text().isBlank() && p.selectFirst("img") == null) p.remove()
		}
		if (content.text().isBlank() && content.selectFirst("img") == null) {
			throw ParseException("KolNovel: chapter content is empty", chapterUrl)
		}
		val images = content.select("img").mapNotNull { img ->
			img.src()?.let { url ->
				NovelImage(
					url = url,
					headers = mapOf(
						"Referer" to chapterUrl,
						"User-Agent" to config[userAgentKey],
					),
				)
			}
		}.distinctBy(NovelImage::url)
		return NovelChapterContent(html = content.html(), images = images)
	}

	private fun parseState(value: String?): MangaState? {
		val text = value.orEmpty()
		return when {
			text.contains("مكتمل") || text.contains("Completed", ignoreCase = true) -> MangaState.FINISHED
			text.contains("مستمر") || text.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
			else -> null
		}
	}

	private companion object {
		const val MAX_CHAPTER_LIST_PAGES = 100
		val CHAPTER_NUMBER = Regex("""\d+(?:\.\d+)?""")
	}
}
