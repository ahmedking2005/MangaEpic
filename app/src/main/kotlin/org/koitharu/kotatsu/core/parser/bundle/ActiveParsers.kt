package org.koitharu.kotatsu.core.parser.bundle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The parsers in use right now, for the whole of the app.
 *
 * The app is built with a set of them, and can put a newer set it has downloaded in its place while it runs, see
 * [ParsersUpdater]. So nothing but this is to be asked which sources there are or handed a parser by: the parsers
 * the app was compiled with are only where it starts from.
 *
 * Kept outside of dependency injection because sources are looked up by name from places that have none, see
 * [org.koitharu.kotatsu.core.model.MangaSource].
 */
/** Sources removed from Manga Epic by name at the owner's request (2026-11-18). */
private val REMOVED_SOURCES = setOf(
	"MANGABALL_AR", "SCANS4U", "ARABTOONS", "ARABSHENTAI", "GMANGA", "HENTAISLAYER",
	"HENTAMAN", "MANGAATREND", "MANGAHUB_LINK", "MANHATIC", "NIJITRANSLATIONS", "PEACHBL",
	"WEBTOONEMPIRE", "XCOMIC", "YURIMOONSUB", "MANGAAILAND", "MANHATOK", "YOKAITEAM",
	"LONERTL", "XSANOMANGA", "MANGAROSE", "MANGAFLAME", "ONMA", "YONABAR",
	"OLAOE", "DILARTUBE", "FLARES", "ASQORG", "MANGASOUL", "AREASCANS",
	"MANJANOON", "WAVETEAMY",
	"MANGALINKNET", "MANGALEKO", "MANGALIONZ", "STELLARSABER", "ARAREASCANS",
)

/*
 * Source-set expansion (owner decision, 2026-11-20): MangaTek and AnyoneManga were
 * ported in from the MangaPeak parsers tree, taking the effective set to 26 working
 * sources (17 manga + 6 novels + 3 anime). Two further candidates were evaluated and
 * deliberately not added: MangaMello Plus (its API no longer answers) and
 * DespairManga (its site no longer serves a reader). No fence change was needed:
 * the new parsers pass the same locale/type/broken admission rules as the rest.
 *
 * Follow-up (2026-11-20): AnyoneManga initially declared its source via
 * MangaParserSource.valueOf(...), which the KSP processor silently skipped, so it
 * never reached the generated source set; it now uses the direct property form
 * like every other parser, and the 26-source set is confirmed in the built app.
 * Follow-up 2 (2026-11-20): AnyoneManga's catalogue helper moved out of its companion to a top-level function, because a companion member signature referencing the generated MangaParserSource type defers the class past KSP round-1 generation and drops it from the generated source set.
 *
 * Own sources, part 1 (2026-11-20): Rewayat's chapter-content resolution was rewritten
 * in-house — the slug/number pair is now resolved tolerantly from any URL shape the
 * parser emits (relative or stored absolute) and an unresolvable chapter throws a
 * ParseException with the reason instead of returning null, which the app surfaced as
 * "Novel content not supported". RistoAnime now points at ristoanime.cc, where its
 * site lives today; it previously targeted the old ristoanime.me domain.
 *
 * Own sources, part 2 (2026-11-20): KolNovel was rewritten in-house against a live
 * spec sheet. Its old target, free.kolnovel.com, is dead; the site now lives at
 * kolnovel.com on a new 'starless' theme, so the parser was rebuilt around the new
 * markup (archive cards, /series/page/{n}/ pagination, chapters embedded in the
 * details page, chapter text in .epcontent) instead of patching dead selectors.
 *
 * Follow-up (2026-11-20): KolNovel's two ParseException throws now pass the chapter
 * URL as the constructor's required url argument, matching the tree-wide convention
 * (Azoramoon, Rewayat) and fixing the compile errors that stopped build 103.
 *
 * Own sources, part 3 (2026-11-20): Cenele's chapter content was rewritten in-house.
 * The site moved its prose into a <story-fold> element (the text-left class the old
 * selectors waited for is only added by JavaScript) and replaced the load_chapter
 * AJAX action with a hash-suffixed transport whose config is published in the
 * chapter page itself; the parser now extracts the fold from the page, falls back
 * to that transport read at runtime, and throws a ParseException for genuinely
 * empty chapters instead of returning null or the promo shell.
 *
 * Own sources, part 4 (2026-11-20): RocksManga was rewritten in-house. The site was
 * rebuilt as a custom Next.js app on a public JSON API (api.rocksmanga.com), so
 * every inherited selector matched nothing and the source showed an empty list;
 * the parser now reads the catalogue, details and chapters from that API and the
 * page images from the reader page's embedded storage paths.
 *
 * Follow-up (2026-11-20): RocksManga's slug extraction mixed the vararg and
 * predicate forms of String.trim, which Kotlin rejects; it now trims the same two
 * characters ('/' and '?') with the parenthesized vararg form used across the
 * tree, fixing the compile errors that stopped build 108.
 *
 * App fix (2026-11-20): LiveMangaParser, the wrapper every repository call goes
 * through, delegated eighteen of the twenty MangaParser members but never
 * overrode getChapterContent or getVideoStreams, so both fell through to the
 * interface defaults — null chapter content for every novel source ("Novel
 * content not supported") and an empty stream list for every anime source —
 * no matter what the parsers themselves returned. Both are now delegated like
 * every other site call. This was the common-path cause behind the on-device
 * verdict that novels and anime did not play while lists and details worked.
 */

object ActiveParsers {

	/** What is waited for the parsers kept from the last run, by whoever needs the sources before they are loaded. */
	private const val STARTUP_TIMEOUT_MS = 1_000L

	/** The parsers this app was built with: what it starts from, and goes back to if newer ones turn out bad. */
	@OptIn(DirectParsersAccess::class)
	val builtIn: ParsersBundle = ParsersBundleImpl

	private val generations = MutableStateFlow(Generation(builtIn, number = 0))

	@Volatile
	private var startup: Startup? = null

	val bundle: ParsersBundle
		get() = current().bundle

	/** Goes up by one every time other parsers are put in use. */
	val generation: Int
		get() = current().number

	val isBuiltIn: Boolean
		get() = current().bundle === builtIn

	/** Every source there is, the broken ones included, always in the same order. */
	val sources: List<ParserMangaSource>
		get() = current().bundle.sources

	/** The sources to offer, in that same order. */
	val workingSources: Set<ParserMangaSource>
		get() = current().working

	fun findSource(name: String): ParserMangaSource? = current().bundle.findSource(name)

	/** The current [generation], then every later one. */
	fun observe(): Flow<Int> = generations.map { it.number }.distinctUntilChanged()

	/**
	 * Says that parsers kept from the last run are about to be loaded.
	 *
	 * Until [Startup.finish] is called, whoever asks for the sources waits, for a short time at most: a source that
	 * only the newer parsers have would otherwise be an unknown one for the first moments of every launch.
	 */
	internal fun beginStartup(): Startup {
		val result = Startup()
		startup = result
		return result
	}

	internal fun activate(bundle: ParsersBundle) {
		generations.update { previous ->
			if (previous.bundle === bundle) previous else Generation(bundle, previous.number + 1)
		}
	}

	/** Puts the parsers the app was built with back in use, if [bundle] is what is in use. */
	internal fun deactivate(bundle: ParsersBundle) {
		generations.update { previous ->
			if (previous.bundle === bundle && bundle !== builtIn) Generation(builtIn, previous.number + 1) else previous
		}
	}

	/** The parsers in use and their [generation], as of one same moment. */
	internal fun snapshot(): Generation = current()

	private fun current(): Generation {
		val pending = startup
		if (pending != null && Thread.currentThread() !== pending.loader) {
			if (!pending.await(STARTUP_TIMEOUT_MS)) {
				// Taking too long. Nobody else is to wait for it: the parsers come into use when they are ready
				startup = null
			}
		}
		return generations.value
	}

	internal class Startup {

		private val latch = CountDownLatch(1)

		/** The thread doing the loading, which is not to wait for itself. */
		@Volatile
		var loader: Thread? = null

		/** @return `false` if the loading was still going on after [timeoutMs] */
		fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

		fun finish() {
			if (startup === this) {
				startup = null
			}
			latch.countDown()
		}
	}

	internal class Generation(
		val bundle: ParsersBundle,
		val number: Int,
	) {

		/**
		 * Fork customization (Arabic edition): only Arabic sources are offered, and the broken ones are
		 * excluded as before. Every other locale — including the multi-language sources, whose locale is
		 * blank — is dropped here, at the one place every source list in the app is derived from, so a
		 * downloaded parsers bundle cannot bring them back either.
		 *
		 * Manga Epic additions: hentai sources are excluded categorically, and the sources in
		 * [REMOVED_SOURCES] are dropped by name at the owner's request (2026-11-18).
		 */
		val working: Set<ParserMangaSource> by lazy(LazyThreadSafetyMode.PUBLICATION) {
			Collections.unmodifiableSet(
				bundle.sources.filterNotTo(LinkedHashSet()) {
					it.isBroken || it.locale != "ar" ||
						it.contentType == ContentType.HENTAI || it.name in REMOVED_SOURCES
				},
			)
		}
	}
}
