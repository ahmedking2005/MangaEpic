package org.koitharu.kotatsu.cloud

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Manga Epic: automatic hourly library sync for signed-in users.
 *
 * Ahmed's requirement: sync must be automatic, not manual only. The worker runs
 * every hour while a session exists and no-ops when signed out, so scheduling it
 * once (KEEP) is enough — sign-in/sign-out never needs to reschedule.
 */
@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val supabase: SupabaseClient,
	private val librarySync: LibrarySync,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		if (!supabase.isSignedIn) {
			return Result.success()
		}
		return if (librarySync.syncAll().isSuccess) {
			Result.success()
		} else {
			Result.retry()
		}
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) {

		fun schedule() {
			val constraints = Constraints.Builder()
				.setRequiredNetworkType(NetworkType.CONNECTED)
				.build()
			val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(1, TimeUnit.HOURS)
				.setConstraints(constraints)
				.addTag(TAG)
				.build()
			workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
		}
	}

	private companion object {
		const val TAG = "library_sync_hourly"
	}
}
