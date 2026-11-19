package org.koitharu.kotatsu.backups.ui.periodical

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.backups.domain.BackupUtils
import org.koitharu.kotatsu.backups.domain.ExternalBackupStorage
import org.koitharu.kotatsu.backups.ui.restore.RestoreService
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.resolveFile
import java.util.Date
import java.util.zip.ZipOutputStream
import javax.inject.Inject

enum class DriveAction {
	CONNECT,
	BACKUP_NOW,
	RESTORE,
}

@HiltViewModel
class PeriodicalBackupSettingsViewModel @Inject constructor(
	private val settings: AppSettings,
	private val telegramUploader: TelegramBackupUploader,
	private val backupStorage: ExternalBackupStorage,
	private val driveClient: DriveBackupClient,
	private val backupRepository: BackupRepository,
	@ApplicationContext private val appContext: Context,
) : BaseViewModel() {

	val isTelegramAvailable
		get() = telegramUploader.isAvailable

	val lastBackupDate = MutableStateFlow<Date?>(null)
	val backupsDirectory = MutableStateFlow<String?>("")
	val isTelegramCheckLoading = MutableStateFlow(false)
	val onActionDone = MutableEventFlow<ReversibleAction>()

	val driveAccountEmail = MutableStateFlow(settings.driveAccountEmail)
	val lastDriveBackupDate = MutableStateFlow<Date?>(
		settings.lastDriveBackupAt.takeIf { it > 0L }?.let(::Date),
	)
	val isDriveBusy = MutableStateFlow(false)
	val onDriveConsentRequired = MutableEventFlow<PendingIntent>()
	private var pendingDriveAction: DriveAction? = null

	init {
		updateSummaryData()
	}

	fun checkTelegram() {
		launchJob(Dispatchers.Default) {
			try {
				isTelegramCheckLoading.value = true
				telegramUploader.sendTestMessage()
				onActionDone.call(ReversibleAction(R.string.connection_ok, null))
			} finally {
				isTelegramCheckLoading.value = false
			}
		}
	}

	fun updateSummaryData() {
		updateBackupsDirectory()
		updateLastBackupDate()
	}

	fun runDriveAction(action: DriveAction) {
		launchJob(Dispatchers.Default) {
			try {
				isDriveBusy.value = true
				val token = try {
					driveClient.requireAccessToken()
				} catch (e: DriveBackupClient.ResolutionRequired) {
					pendingDriveAction = action
					onDriveConsentRequired.call(e.pendingIntent)
					return@launchJob
				}
				performDriveAction(action, token)
			} finally {
				isDriveBusy.value = false
			}
		}
	}

	fun onDriveConsentGranted() {
		val action = pendingDriveAction ?: return
		pendingDriveAction = null
		runDriveAction(action)
	}

	fun disconnectDrive() {
		settings.driveAccountEmail = null
		settings.isDriveBackupEnabled = false
		driveAccountEmail.value = null
	}

	private suspend fun performDriveAction(action: DriveAction, token: String) {
		when (action) {
			DriveAction.CONNECT -> {
				val email = driveClient.fetchAccountEmail(token)
				settings.driveAccountEmail = email
				driveAccountEmail.value = email
				onActionDone.call(ReversibleAction(R.string.backup_drive_connected, null))
			}

			DriveAction.BACKUP_NOW -> {
				val output = BackupUtils.createTempFile(appContext)
				try {
					ZipOutputStream(output.outputStream()).use {
						backupRepository.createBackup(it, null)
					}
					driveClient.uploadBackup(token, output)
					settings.lastDriveBackupAt = System.currentTimeMillis()
					lastDriveBackupDate.value = Date(settings.lastDriveBackupAt)
					onActionDone.call(ReversibleAction(R.string.backup_drive_uploaded, null))
				} finally {
					output.delete()
				}
			}

			DriveAction.RESTORE -> {
				val latest = driveClient.listBackups(token).firstOrNull()
				if (latest == null) {
					onActionDone.call(ReversibleAction(R.string.backup_drive_no_backup, null))
					return
				}
				val file = BackupUtils.createTempFile(appContext)
				driveClient.downloadBackup(token, latest.id, file)
				val started = RestoreService.start(
					appContext,
					Uri.fromFile(file),
					BackupSection.entries.toSet(),
				)
				check(started) { "Restore service did not start" }
				onActionDone.call(ReversibleAction(R.string.backup_drive_restoring, null))
			}
		}
	}

	private fun updateBackupsDirectory() = launchJob(Dispatchers.Default) {
		val dir = settings.periodicalBackupDirectory
		backupsDirectory.value = if (dir != null) {
			dir.toUserFriendlyString()
		} else {
			BackupUtils.getAppBackupDir(appContext).path
		}
	}

	private fun updateLastBackupDate() = launchJob(Dispatchers.Default) {
		lastBackupDate.value = backupStorage.getLastBackupDate()
	}

	private fun Uri.toUserFriendlyString(): String? {
		val df = DocumentFile.fromTreeUri(appContext, this)
		if (df?.canWrite() != true) {
			return null
		}
		return resolveFile(appContext)?.path ?: toString()
	}
}
