package org.koitharu.kotatsu.cloud

import java.io.IOException
import java.util.concurrent.TimeUnit
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.core.prefs.AppSettings

/**
 * Manga Epic: minimal Supabase client (Auth + PostgREST) over OkHttp.
 *
 * Built on the libraries the app already ships (OkHttp + kotlinx-serialization), so no
 * new dependencies were needed. Only the publishable key is embedded — it is a public
 * identifier by design; every table is protected by Row Level Security (auth.uid() =
 * user_id), and the service_role key never leaves the Supabase dashboard.
 * Sessions live in [AppSettings]; access tokens are refreshed transparently.
 */
@Singleton
class SupabaseClient @Inject constructor(
	private val settings: AppSettings,
) {

	private val http = OkHttpClient.Builder()
		.connectTimeout(20, TimeUnit.SECONDS)
		.readTimeout(30, TimeUnit.SECONDS)
		.writeTimeout(20, TimeUnit.SECONDS)
		.build()

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	private val refreshMutex = Mutex()

	val isSignedIn: Boolean
		get() = settings.supabaseRefreshToken.isNotEmpty() && settings.supabaseUserId.isNotEmpty()

	val userId: String
		get() = settings.supabaseUserId

	val userEmail: String
		get() = settings.supabaseUserEmail

	// region Auth

	/**
	 * Creates an account. The result is `true` when a session was returned (signed in
	 * immediately) and `false` when the project requires e-mail confirmation first —
	 * in that case no session exists yet and the user signs in after confirming.
	 */
	suspend fun signUp(email: String, password: String): Result<Boolean> =
		authRequest("/auth/v1/signup", email, password)

	/** Signs in; the result is `true` when a session was established. */
	suspend fun signIn(email: String, password: String): Result<Boolean> =
		authRequest("/auth/v1/token?grant_type=password", email, password)

	private suspend fun authRequest(path: String, email: String, password: String): Result<Boolean> {
		val body = buildJsonObject {
			put("email", email)
			put("password", password)
		}
		return request(path, "POST", body.toString(), useSessionToken = false).mapCatching { responseBody ->
			val obj = json.decodeFromString(JsonElement.serializer(), responseBody) as? kotlinx.serialization.json.JsonObject
				?: throw IOException("Unexpected auth response")
			val accessToken = (obj["access_token"] as? kotlinx.serialization.json.JsonPrimitive)?.content
			if (accessToken.isNullOrEmpty()) {
				// Sign-up with e-mail confirmation pending returns the bare user, no session.
				false
			} else {
				val session = json.decodeFromString(AuthResponse.serializer(), responseBody)
				saveSession(session, email)
				true
			}
		}
	}

	/** Manga Epic: starts the Google OAuth flow (PKCE). Returns the URL to open in the browser. */
	fun googleAuthorizeUrl(): String = oauthAuthorizeUrl("google")

	/** Manga Epic: starts the Facebook OAuth flow (PKCE). Returns the URL to open in the browser. */
	fun facebookAuthorizeUrl(): String = oauthAuthorizeUrl("facebook")

	private fun oauthAuthorizeUrl(provider: String): String {
		val verifier = createPkceVerifier()
		settings.supabasePkceVerifier = verifier
		val challenge = Base64.encodeToString(
			MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
			Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
		)
		return "$BASE_URL/auth/v1/authorize?provider=$provider" +
			"&redirect_to=" + Uri.encode(GOOGLE_REDIRECT_URL) +
			"&code_challenge=$challenge&code_challenge_method=s256"
	}

	/** Manga Epic: finishes an OAuth flow (Google/Facebook) after the browser redirects back with a code. */
	suspend fun exchangeOAuthCode(code: String): Result<Boolean> {
		val verifier = settings.supabasePkceVerifier
		if (verifier.isEmpty()) {
			return Result.failure(IllegalStateException("Missing PKCE verifier"))
		}
		settings.supabasePkceVerifier = ""
		return authRequest(
			"/auth/v1/token?grant_type=pkce",
			buildJsonObject {
				put("auth_code", code)
				put("code_verifier", verifier)
			},
		)
	}

	private fun createPkceVerifier(): String {
		val bytes = ByteArray(48)
		SecureRandom().nextBytes(bytes)
		return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
	}

	suspend fun signOut() {
		val token = settings.supabaseAccessToken
		if (token.isNotEmpty()) {
			// Best effort: invalidate the session server-side, then always clear locally.
			runCatching { request("/auth/v1/logout", "POST", null, useSessionToken = true) }
		}
		clearSession()
	}

	private fun saveSession(session: AuthResponse, fallbackEmail: String) {
		settings.supabaseAccessToken = session.accessToken
		settings.supabaseRefreshToken = session.refreshToken
		settings.supabaseUserId = session.user.id
		settings.supabaseUserEmail = session.user.email ?: fallbackEmail
		settings.supabaseTokenExpiresAt = System.currentTimeMillis() + session.expiresIn * 1000L
	}

	private fun clearSession() {
		settings.supabaseAccessToken = ""
		settings.supabaseRefreshToken = ""
		settings.supabaseUserId = ""
		settings.supabaseUserEmail = ""
		settings.supabaseTokenExpiresAt = 0L
	}

	/** A currently valid access token, refreshing the session first when it is expired. */
	suspend fun validAccessToken(): String? {
		val access = settings.supabaseAccessToken
		val refresh = settings.supabaseRefreshToken
		if (access.isEmpty() || refresh.isEmpty()) {
			return null
		}
		if (System.currentTimeMillis() < settings.supabaseTokenExpiresAt - EXPIRY_SKEW_MS) {
			return access
		}
		return refreshMutex.withLock {
			// Re-check inside the lock: another caller may have refreshed already.
			if (System.currentTimeMillis() < settings.supabaseTokenExpiresAt - EXPIRY_SKEW_MS) {
				return@withLock settings.supabaseAccessToken
			}
			val body = buildJsonObject { put("refresh_token", refresh) }
			val result = request("/auth/v1/token?grant_type=refresh_token", "POST", body.toString(), useSessionToken = false)
			result.fold(
				onSuccess = { responseBody ->
					val session = json.decodeFromString(AuthResponse.serializer(), responseBody)
					saveSession(session, settings.supabaseUserEmail)
					settings.supabaseAccessToken
				},
				onFailure = {
					clearSession()
					null
				},
			)
		}
	}

	// endregion

	// region PostgREST

	/** Inserts or updates rows in [table]; [onConflict] is the table's unique key list. */
	suspend fun upsert(table: String, rowsJson: String, onConflict: String): Result<Unit> {
		val path = "/rest/v1/$table?on_conflict=$onConflict"
		return request(path, "POST", rowsJson, useSessionToken = true, prefer = "resolution=merge-duplicates,return=minimal")
			.mapCatching { }
	}

	/** Selects rows from [table]; [query] is the raw PostgREST query string (already encoded). */
	suspend fun select(table: String, query: String): Result<String> =
		request("/rest/v1/$table?$query", "GET", null, useSessionToken = true)

	/** Deletes rows from [table] matching [filter] (e.g. "source=eq.X&manga_key=eq.Y"). */
	suspend fun delete(table: String, filter: String): Result<Unit> =
		request("/rest/v1/$table?$filter", "DELETE", null, useSessionToken = true, prefer = "return=minimal")
			.mapCatching { }

	// endregion

	private suspend fun request(
		path: String,
		method: String,
		body: String?,
		useSessionToken: Boolean,
		prefer: String? = null,
	): Result<String> = withContext(Dispatchers.IO) {
		runCatching {
			val token = if (useSessionToken) {
				validAccessToken() ?: throw IOException("Not signed in")
			} else {
				PUBLISHABLE_KEY
			}
			val builder = Request.Builder()
				.url(BASE_URL + path)
				.header("apikey", PUBLISHABLE_KEY)
				.header("Authorization", "Bearer $token")
			if (prefer != null) {
				builder.header("Prefer", prefer)
			}
			when (method) {
				"GET" -> builder.get()
				"DELETE" -> builder.delete()
				else -> builder.post((body ?: "").toRequestBody(JSON_MEDIA_TYPE))
			}
			http.newCall(builder.build()).execute().use { response ->
				val text = response.body?.string().orEmpty()
				if (!response.isSuccessful) {
					throw IOException(parseErrorMessage(text) ?: "HTTP ${response.code}")
				}
				text
			}
		}
	}

	private fun parseErrorMessage(body: String): String? = runCatching {
		val element = json.decodeFromString(JsonElement.serializer(), body)
		val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
		sequenceOf("msg", "message", "error_description", "error")
			.mapNotNull { key -> (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content }
			.firstOrNull { it.isNotBlank() }
	}.getOrNull()

	@Serializable
	private data class AuthUser(
		val id: String,
		val email: String? = null,
	)

	@Serializable
	private data class AuthResponse(
		@kotlinx.serialization.SerialName("access_token") val accessToken: String,
		@kotlinx.serialization.SerialName("refresh_token") val refreshToken: String,
		@kotlinx.serialization.SerialName("expires_in") val expiresIn: Long = 3600L,
		val user: AuthUser,
	)

	companion object {
		const val BASE_URL = "https://lfdefhhldvdevcrkzmgf.supabase.co"

		/** Public by design (ships in the app); data protection is enforced by RLS. */
		const val PUBLISHABLE_KEY = "sb_publishable_6IbWjisFaq6wwUY2n2tG7w_zPlx8Uxo"

		/** Deep link the OAuth browser flow returns to (allow-listed in Supabase Auth settings). */
		const val GOOGLE_REDIRECT_URL = "com.mangaepic.app://login-callback"

		private const val EXPIRY_SKEW_MS = 60_000L
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
