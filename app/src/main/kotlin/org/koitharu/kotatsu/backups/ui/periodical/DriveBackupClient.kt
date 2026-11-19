package org.koitharu.kotatsu.backups.ui.periodical

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Drive backup in the app's private appDataFolder.
 *
 * Authorization uses the Google Identity Authorization API (no Firebase, no client
 * secret in the app — the Android OAuth client is identified by package + SHA-1).
 * All Drive calls are plain REST v3 over the app's OkHttp client.
 */
class DriveBackupClient @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient private val client: OkHttpClient,
) {

	/** Thrown when Google needs the user to approve access; the UI launches [pendingIntent]. */
	class ResolutionRequired(val pendingIntent: PendingIntent) : Exception()

	data class DriveBackupFile(
		val id: String,
		val name: String,
		val modifiedTime: String,
	)

	private val scopes = listOf(
		Scope(SCOPE_DRIVE_APPDATA),
		Scope("email"),
	)

	suspend fun authorize(): AuthorizationResult = suspendCancellableCoroutine { cont ->
		val request = AuthorizationRequest.builder()
			.setRequestedScopes(scopes)
			.build()
		Identity.getAuthorizationClient(context).authorize(request)
			.addOnSuccessListener { result -> cont.resume(result) }
			.addOnFailureListener { e -> cont.resumeWithException(e) }
	}

	/** Access token, or throws [ResolutionRequired] when user consent is needed. */
	suspend fun requireAccessToken(): String {
		val result = authorize()
		result.pendingIntent?.let { throw ResolutionRequired(it) }
		return checkNotNull(result.accessToken) { "Google did not return an access token" }
	}

	/** Silent token for background work; null when consent is required or auth fails. */
	suspend fun accessTokenOrNull(): String? = runCatchingCancellable {
		val result = authorize()
		if (result.pendingIntent != null) null else result.accessToken
	}.getOrNull()

	suspend fun fetchAccountEmail(token: String): String? = withContext(Dispatchers.IO) {
		runCatchingCancellable {
			val request = Request.Builder()
				.url("https://www.googleapis.com/oauth2/v3/userinfo")
				.header("Authorization", "Bearer $token")
				.build()
			client.newCall(request).await().use { response ->
				check(response.isSuccessful) { "userinfo failed: ${response.code}" }
				JSONObject(response.body.string()).optString("email").ifEmpty { null }
			}
		}.getOrNull()
	}

	suspend fun listBackups(token: String): List<DriveBackupFile> = withContext(Dispatchers.IO) {
		val url = DRIVE_FILES_URL.toHttpUrl().newBuilder()
			.addQueryParameter("spaces", "appDataFolder")
			.addQueryParameter("fields", "files(id,name,modifiedTime)")
			.addQueryParameter("orderBy", "modifiedTime desc")
			.addQueryParameter("pageSize", "50")
			.build()
		val request = Request.Builder()
			.url(url)
			.header("Authorization", "Bearer $token")
			.build()
		client.newCall(request).await().use { response ->
			check(response.isSuccessful) { "Drive list failed: ${response.code}" }
			val files = JSONObject(response.body.string()).optJSONArray("files")
			buildList {
				if (files != null) {
					for (i in 0 until files.length()) {
						val item = files.getJSONObject(i)
						add(
							DriveBackupFile(
								id = item.getString("id"),
								name = item.getString("name"),
								modifiedTime = item.optString("modifiedTime"),
							),
						)
					}
				}
			}
		}
	}

	/** Uploads [file] as a new backup, then trims the folder to the newest [MAX_BACKUPS]. */
	suspend fun uploadBackup(token: String, file: File) = withContext(Dispatchers.IO) {
		val metadata = JSONObject()
			.put("name", file.name)
			.put("parents", JSONArray().put("appDataFolder"))
			.toString()
		val body = MultipartBody.Builder()
			.setType("multipart/related".toMediaType())
			.addPart(
				headersOf("Content-Type", "application/json; charset=UTF-8"),
				metadata.toRequestBody(null),
			)
			.addPart(
				headersOf("Content-Type", "application/zip"),
				file.asRequestBody("application/zip".toMediaType()),
			)
			.build()
		val url = DRIVE_UPLOAD_URL.toHttpUrl().newBuilder()
			.addQueryParameter("uploadType", "multipart")
			.addQueryParameter("fields", "id")
			.build()
		val request = Request.Builder()
			.url(url)
			.header("Authorization", "Bearer $token")
			.post(body)
			.build()
		client.newCall(request).await().use { response ->
			check(response.isSuccessful) { "Drive upload failed: ${response.code}" }
		}
		trimBackups(token)
	}

	suspend fun downloadBackup(token: String, fileId: String, dest: File) = withContext(Dispatchers.IO) {
		val url = "$DRIVE_FILES_URL/$fileId".toHttpUrl().newBuilder()
			.addQueryParameter("alt", "media")
			.build()
		val request = Request.Builder()
			.url(url)
			.header("Authorization", "Bearer $token")
			.build()
		client.newCall(request).await().use { response ->
			check(response.isSuccessful) { "Drive download failed: ${response.code}" }
			dest.outputStream().use { out ->
				response.body.byteStream().copyTo(out)
			}
		}
	}

	private suspend fun trimBackups(token: String) {
		runCatchingCancellable {
			val backups = listBackups(token)
			for (extra in backups.drop(MAX_BACKUPS)) {
				deleteFile(token, extra.id)
			}
		}
	}

	private suspend fun deleteFile(token: String, fileId: String) = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url("$DRIVE_FILES_URL/$fileId")
			.header("Authorization", "Bearer $token")
			.delete()
			.build()
		client.newCall(request).await().use { response ->
			check(response.isSuccessful) { "Drive delete failed: ${response.code}" }
		}
	}

	private companion object {

		const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
		const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
		const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
		const val MAX_BACKUPS = 3
	}
}
