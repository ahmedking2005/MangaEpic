package org.koitharu.kotatsu.parsers.site.madara.ar

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getDoubleOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * RocksManga (rocksmanga.com) — rewritten in-house (own sources, part 4,
 * cycle 3) against the site's current platform, live-verified 2026-11-20.
 *
 * The site was rebuilt as a custom Next.js app backed by a public JSON API
 * at api.rocksmanga.com; nothing of the old WordPress/Madara structure (nor
 * of the intermediate custom theme the previous parser scraped) remains, so
 * the old parser's selectors matched nothing and the source showed an empty
 * list. This implementation uses:
 *
 *  - list/search: `GET /manga?page={1-based}&page_size=30` with `search`,
 *    `sort` (recently_updated/new/most_viewed/highest_rated/alphabetical),
 *    `type` (MANGA/MANHWA/MANHUA) and `status` (RELEASING/COMPLETED)
 *    parameters — each verified live to actually filter;
 *  - details: `GET /manga/{slug}` (synopsis, genres, rating, authors);
 *  - chapters: `GET /manga/{slug}/chapters` (complete list, ascending);
 *  - pages: the reader page HTML embeds every page image on the API host
 *    under /storage/chapters/{id}/pages/{n}.webp — extracted by pattern,
 *    de-duplicated and ordered by page number.
 *
 * Genre filtering is deliberately NOT advertised: the public endpoint
 * silently ignores every genre parameter encoding (genre, genres, genre_id,
 * genre_ids), so offering it would create a dead filter. Genres still show
 * on the details screen, from the details payload.
 *
 * The class stays in the madara package for historical reasons only; the
 * site no longer runs Madara and nothing here uses the Madara machinery.
 * House rules honoured: the domain comes only from [configKeyDomain], no
 * member signature mentions the generated source type, and a chapter with
 * no readable pages fails with a [ParseException] instead of returning an
 * empty list.
 */
@MangaSourceParser("ROCKSMANGA", "RocksManga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ROCKSMANGA, pageSize = 30, searchPageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("rocksmanga.com")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet(), // genre filtering is not exposed by the API (see class doc)
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://api.")
			append(domain)
			append("/manga?page=")
			append(page + 1)
			append("&page_size=")
			append(PAGE_SIZE)
			append("&sort=")
			append(
				when (order) {
					SortOrder.NEWEST -> "new"
					SortOrder.POPULARITY -> "most_viewed"
					SortOrder.RATING -> "highest_rated"
					SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "alphabetical"
					else -> "recently_updated"
				},
			)
			filter.query?.takeIf { it.isNotBlank() }?.let {
				append("&search=")
				append(it.urlEncoded())
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				apiType(type)?.let {
					append("&type=")
					append(it)
				}
			}
			filter.states.oneOrThrowIfMany()?.let { state ->
				apiStatus(state)?.let {
					append("&status=")
					append(it)
				}
			}
		}
		val json = webClient.httpGet(url).parseJson()
		val items = json.optJSONArray("items") ?: return emptyList()
		val result = ArrayList<Manga>(items.length())
		for (i in 0 until items.length()) {
			val item = items.optJSONObject(i) ?: continue
			val slug = item.getStringOrNull("slug") ?: continue
			val relativeUrl = "/manga/$slug"
			result += Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				publicUrl = relativeUrl.toAbsoluteUrl(domain),
				title = item.getStringOrNull("title") ?: slug,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = parseState(item.getStringOrNull("status")),
				coverUrl = apiAssetUrl(item.getStringOrNull("cover_url")),
				largeCoverUrl = apiAssetUrl(item.getStringOrNull("cover_url")),
				source = source,
				contentRating = if (item.getBooleanOrDefault("is_nsfw", false)) {
					ContentRating.ADULT
				} else {
					ContentRating.SAFE
				},
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("/manga/").trim('/') { it == '/' || it == '?' }
			.substringBefore('?').substringBefore('#')
		if (slug.isEmpty() || slug.contains('/')) return manga
		val detailsUrl = "https://api.$domain/manga/${slug.urlEncoded()}"
		val root = webClient.httpGet(detailsUrl).parseJson()
		val details = root.optJSONObject("manga")
			?: throw ParseException("RocksManga: manga not found", detailsUrl)

		val chaptersJson = webClient.httpGet("$detailsUrl/chapters").parseJson()
			.optJSONArray("chapters")
		val chapters = parseChapters(slug, chaptersJson)

		val cover = apiAssetUrl(details.getStringOrNull("cover_url")) ?: manga.coverUrl
		val ratingCount = details.getIntOrDefault("ratings_count", 0)
		val rating = if (ratingCount > 0) {
			(details.getDoubleOrDefault("average_rating", 0.0) / 10.0).toFloat().coerceIn(0f, 1f)
		} else {
			RATING_UNKNOWN
		}
		return manga.copy(
			title = details.getStringOrNull("title") ?: manga.title,
			altTitles = manga.altTitles + parseAltTitles(details.getStringOrNull("alternative_titles")),
			coverUrl = cover,
			largeCoverUrl = cover,
			description = details.getStringOrNull("synopsis"),
			tags = parseGenres(details.optJSONArray("genres")),
			state = parseState(details.getStringOrNull("status")) ?: manga.state,
			authors = parseNames(details.optJSONArray("authors")) +
				parseNames(details.optJSONArray("artists")),
			rating = rating,
			chapters = chapters,
			contentRating = if (details.getBooleanOrDefault("is_nsfw", false)) {
				ContentRating.ADULT
			} else {
				manga.contentRating
			},
			publicUrl = "/manga/$slug".toAbsoluteUrl(domain),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val html = webClient.httpGet(chapterUrl).parseRaw()
		// The reader page carries the pages in two forms: the first ones as
		// absolute URLs (preload links / img tags), the full list as storage
		// paths inside the page's data payload. Match the storage path, which
		// both forms share, and rebuild the absolute URL on the API host.
		val pattern = Regex(
			"/storage/chapters/(\\d+)/pages/(\\d+)\\.(webp|jpe?g|png)(\\?ver=\\d+)?",
		)
		val byNumber = LinkedHashMap<Int, String>()
		for (match in pattern.findAll(html)) {
			val (chapterId, numberText, extension, version) = match.destructured
			val number = numberText.toIntOrNull() ?: continue
			byNumber.putIfAbsent(
				number,
				"https://api.$domain/storage/chapters/$chapterId/pages/$numberText.$extension$version",
			)
		}
		if (byNumber.isEmpty()) {
			throw ParseException("RocksManga: chapter pages not found", chapterUrl)
		}
		return byNumber.entries.sortedBy { it.key }.map { (_, pageUrl) ->
			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseChapters(slug: String, chaptersJson: JSONArray?): List<MangaChapter> {
		if (chaptersJson == null) return emptyList()
		val chapters = ArrayList<MangaChapter>(chaptersJson.length())
		for (i in 0 until chaptersJson.length()) {
			val item = chaptersJson.optJSONObject(i) ?: continue
			val numberText = item.getStringOrNull("number") ?: continue
			val number = numberText.toFloatOrNull() ?: continue
			val relativeUrl = "/manga/$slug/chapter/$numberText"
			val title = item.getStringOrNull("title")?.takeIf { it.isNotBlank() }
			chapters += MangaChapter(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = when {
					title != null -> "الفصل ${formatChapterNumber(number)} - $title"
					else -> "الفصل ${formatChapterNumber(number)}"
				},
				number = number,
				volume = 0,
				branch = null,
				uploadDate = parseIsoDate(item.getStringOrNull("created_at")),
				scanlator = item.optJSONObject("uploader")?.getStringOrNull("display_name"),
				source = source,
			)
		}
		return chapters.sortedBy { it.number }
	}

	private fun parseGenres(genresJson: JSONArray?): Set<MangaTag> {
		if (genresJson == null) return emptySet()
		val tags = LinkedHashSet<MangaTag>()
		for (i in 0 until genresJson.length()) {
			val genre = genresJson.optJSONObject(i) ?: continue
			val slug = genre.getStringOrNull("slug") ?: continue
			val name = genre.getStringOrNull("name") ?: continue
			tags += MangaTag(key = slug, title = name, source = source)
		}
		return tags
	}

	private fun parseNames(namesJson: JSONArray?): Set<String> {
		if (namesJson == null) return emptySet()
		val names = LinkedHashSet<String>()
		for (i in 0 until namesJson.length()) {
			when (val value = namesJson.opt(i)) {
				is JSONObject -> value.getStringOrNull("name")?.let { names += it }
				is String -> if (value.isNotBlank()) names += value
			}
		}
		return names
	}

	private fun parseAltTitles(raw: String?): Set<String> {
		if (raw.isNullOrBlank()) return emptySet()
		return runCatching {
			val array = JSONArray(raw)
			val titles = LinkedHashSet<String>()
			for (i in 0 until array.length()) {
				array.optJSONObject(i)?.getStringOrNull("title")?.let {
					if (it.isNotBlank()) titles += it
				}
			}
			titles
		}.getOrDefault(emptySet())
	}

	private fun apiAssetUrl(path: String?): String? = when {
		path.isNullOrBlank() -> null
		path.startsWith("http://") || path.startsWith("https://") -> path
		path.startsWith("/") -> "https://api.$domain$path"
		else -> "https://api.$domain/$path"
	}

	private fun parseState(status: String?): MangaState? = when (status?.uppercase(Locale.US)) {
		"RELEASING", "ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"HIATUS", "ON_HIATUS" -> MangaState.PAUSED
		"CANCELLED", "CANCELED", "DROPPED" -> MangaState.ABANDONED
		else -> null
	}

	private fun apiType(type: ContentType): String? = when (type) {
		ContentType.MANGA -> "MANGA"
		ContentType.MANHWA -> "MANHWA"
		ContentType.MANHUA -> "MANHUA"
		else -> null
	}

	private fun apiStatus(state: MangaState): String? = when (state) {
		MangaState.ONGOING -> "RELEASING"
		MangaState.FINISHED -> "COMPLETED"
		else -> null
	}

	private fun formatChapterNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

	private fun parseIsoDate(value: String?): Long {
		if (value.isNullOrBlank()) return 0
		for (pattern in ISO_DATE_PATTERNS) {
			try {
				val format = SimpleDateFormat(pattern, Locale.US).apply {
					timeZone = TimeZone.getTimeZone("UTC")
				}
				return format.parse(value)?.time ?: 0
			} catch (e: Exception) {
				// try the next pattern
			}
		}
		return 0
	}

	private companion object {
		const val PAGE_SIZE = 30
		val ISO_DATE_PATTERNS = arrayOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
			"yyyy-MM-dd'T'HH:mm:ss'Z'",
			"yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
			"yyyy-MM-dd'T'HH:mm:ssXXX",
		)
	}
}
