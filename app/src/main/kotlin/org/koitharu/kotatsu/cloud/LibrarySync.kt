package org.koitharu.kotatsu.cloud

import android.net.Uri
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.MangaLinkResolver
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity

/**
 * Manga Epic: library sync with Supabase (phase 4).
 *
 * Local-first: the Room database stays the source of truth on the device; sync runs in
 * batches at well-defined moments (sign-in, app start, manual) — never per page turn.
 * A manga is identified across devices by (source, url), the same pair the app itself
 * uses. Progress conflicts resolve last-write-wins by the row's updated_at. A failed
 * sync changes nothing locally and is retried at the next trigger.
 */
@Singleton
class LibrarySync @Inject constructor(
	private val db: MangaDatabase,
	private val supabase: SupabaseClient,
	private val settings: AppSettings,
	private val linkResolver: MangaLinkResolver,
) {

	private val json = Json { ignoreUnknownKeys = true }
	private val mutex = Mutex()

	/** Runs one full sync cycle; a no-op (success) when signed out or sync is disabled remotely. */
	suspend fun syncAll(): Result<Unit> {
		if (!supabase.isSignedIn || !RemoteConfigManager.isSyncEnabled) {
			return Result.success(Unit)
		}
		if (!mutex.tryLock()) {
			return Result.success(Unit) // a sync is already running
		}
		return try {
			runCatching {
				val serverFavourites = fetchFavourites()
				pullFavourites(serverFavourites)
				pushFavourites(serverFavourites)
				val serverProgress = fetchProgress()
				pullProgress(serverProgress)
				pushProgress(serverProgress)
				settings.lastSyncAt = System.currentTimeMillis()
			}
		} finally {
			mutex.unlock()
		}
	}

	// region Favourites

	private suspend fun fetchFavourites(): List<FavouriteRow> {
		val body = supabase.select(
			"favorites",
			"select=source,manga_key,title,cover_url,added_at,updated_at&order=updated_at.asc",
		).getOrThrow()
		return json.decodeFromString<List<FavouriteRow>>(body)
	}

	private suspend fun pullFavourites(serverRows: List<FavouriteRow>) {
		val favouritesDao = db.getFavouritesDao()
		val mangaDao = db.getMangaDao()
		val localKeys = favouritesDao.findAll()
			.mapTo(HashSet()) { keyOf(it.manga.source, it.manga.url) }
		val defaultCategoryId = db.getFavouriteCategoriesDao().findAll().firstOrNull()?.categoryId?.toLong() ?: return
		for (row in serverRows) {
			if (keyOf(row.source, row.mangaKey) in localKeys) {
				continue
			}
			// Resolve through the source itself so the local row gets the exact id the
			// parser would generate — the same path the app uses for server-matched links.
			val manga = runCatching {
				linkResolver.resolveExact(MangaSource(row.source), row.mangaKey, row.title)
			}.getOrNull() ?: continue
			mangaDao.upsert(manga.toEntity())
			favouritesDao.upsert(
				FavouriteEntity(
					mangaId = manga.id,
					categoryId = defaultCategoryId,
					sortKey = 0,
					isPinned = false,
					createdAt = row.addedAtMillis ?: System.currentTimeMillis(),
					deletedAt = 0L,
				),
			)
		}
	}

	private suspend fun pushFavourites(serverRows: List<FavouriteRow>) {
		val userId = supabase.userId
		val local = db.getFavouritesDao().findAll()
		val localKeys = local.mapTo(HashSet()) { keyOf(it.manga.source, it.manga.url) }
		// Uploads (idempotent upserts, batched).
		for (chunk in local.chunked(BATCH_SIZE)) {
			val rows = buildJsonArray {
				for (item in chunk) {
					add(
						buildJsonObject {
							put("user_id", userId)
							put("source", item.manga.source)
							put("manga_key", item.manga.url)
							put("title", item.manga.title)
							put("cover_url", item.manga.coverUrl)
						},
					)
				}
			}
			supabase.upsert("favorites", rows.toString(), "user_id,source,manga_key").getOrThrow()
		}
		// Deletions: a server row that vanished locally AND predates the last successful
		// sync was deleted on this device. Rows newer than the last sync may come from
		// another device and are kept.
		val lastSync = settings.lastSyncAt
		if (lastSync > 0L) {
			for (row in serverRows) {
				val updated = row.updatedAtMillis ?: continue
				if (keyOf(row.source, row.mangaKey) !in localKeys && updated < lastSync) {
					supabase.delete(
						"favorites",
						"source=eq.${Uri.encode(row.source)}&manga_key=eq.${Uri.encode(row.mangaKey)}",
					)
				}
			}
		}
	}

	// endregion

	// region Reading progress

	private suspend fun fetchProgress(): List<ProgressRow> {
		val body = supabase.select(
			"reading_progress",
			"select=source,manga_key,chapter_key,page,percent,updated_at&order=updated_at.asc",
		).getOrThrow()
		return json.decodeFromString<List<ProgressRow>>(body)
	}

	private suspend fun pullProgress(serverRows: List<ProgressRow>) {
		val mangaDao = db.getMangaDao()
		val historyDao = db.getHistoryDao()
		val chaptersDao = db.getChaptersDao()
		for (row in serverRows) {
			val manga = mangaDao.findBySourceAndUrl(row.source, row.mangaKey) ?: continue
			val serverUpdated = row.updatedAtMillis ?: continue
			val local = historyDao.find(manga.id)
			if (local != null && local.updatedAt >= serverUpdated) {
				continue
			}
			val chapterId = if (row.chapterKey.isEmpty()) {
				local?.chapterId ?: 0L
			} else {
				chaptersDao.findAll(manga.id).firstOrNull { it.url == row.chapterKey }?.chapterId
					?: local?.chapterId ?: 0L
			}
			if (local == null) {
				historyDao.insert(
					HistoryEntity(
						mangaId = manga.id,
						createdAt = serverUpdated,
						updatedAt = serverUpdated,
						chapterId = chapterId,
						page = row.page,
						scroll = 0f,
						percent = row.percent,
						deletedAt = 0L,
						chaptersCount = 0,
					),
				)
			} else {
				historyDao.update(
					mangaId = manga.id,
					page = row.page,
					chapterId = chapterId,
					scroll = local.scroll,
					percent = row.percent,
					chapters = local.chaptersCount,
					updatedAt = serverUpdated,
				)
			}
		}
	}

	private suspend fun pushProgress(serverRows: List<ProgressRow>) {
		val userId = supabase.userId
		val serverByKey = serverRows.associateBy { keyOf(it.source, it.mangaKey) }
		val historyDao = db.getHistoryDao()
		val chaptersDao = db.getChaptersDao()
		var offset = 0
		while (true) {
			val page = historyDao.findAll(offset, PAGE_SIZE)
			if (page.isEmpty()) {
				break
			}
			val rows = buildJsonArray {
				for (item in page) {
					val manga = item.manga
					val server = serverByKey[keyOf(manga.source, manga.url)]
					if (server != null && (server.updatedAtMillis ?: 0L) >= item.history.updatedAt) {
						continue // the server already holds a newer state
					}
					val chapterKey = chaptersDao.find(manga.id, item.history.chapterId)?.url.orEmpty()
					add(
						buildJsonObject {
							put("user_id", userId)
							put("source", manga.source)
							put("manga_key", manga.url)
							put("chapter_key", chapterKey)
							put("page", item.history.page)
							put("percent", item.history.percent)
							put("updated_at", Instant.ofEpochMilli(item.history.updatedAt).toString())
						},
					)
				}
			}
			if (rows.size > 0) {
				supabase.upsert("reading_progress", rows.toString(), "user_id,source,manga_key").getOrThrow()
			}
			offset += page.size
		}
	}

	// endregion

	private fun keyOf(source: String, mangaKey: String): String = source + "" + mangaKey

	@Serializable
	private data class FavouriteRow(
		val source: String,
		@kotlinx.serialization.SerialName("manga_key") val mangaKey: String,
		val title: String = "",
		@kotlinx.serialization.SerialName("cover_url") val coverUrl: String? = null,
		@kotlinx.serialization.SerialName("added_at") val addedAt: String? = null,
		@kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,
	) {
		val addedAtMillis: Long? = addedAt.toEpochMillis()
		val updatedAtMillis: Long? = updatedAt.toEpochMillis()
	}

	@Serializable
	private data class ProgressRow(
		val source: String,
		@kotlinx.serialization.SerialName("manga_key") val mangaKey: String,
		@kotlinx.serialization.SerialName("chapter_key") val chapterKey: String = "",
		val page: Int = 0,
		val percent: Float = 0f,
		@kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,
	) {
		val updatedAtMillis: Long? = updatedAt.toEpochMillis()
	}

	private companion object {
		const val BATCH_SIZE = 100
		const val PAGE_SIZE = 500
	}
}

private fun String?.toEpochMillis(): Long? =
	this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
