package org.koitharu.kotatsu.backups.ui.periodical

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.settings.utils.EditTextFallbackSummaryProvider
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class PeriodicalBackupSettingsFragment : BasePreferenceFragment(R.string.periodic_backups),
	ActivityResultCallback<Uri?> {

	@Inject
	lateinit var telegramBackupUploader: TelegramBackupUploader

	private val viewModel by viewModels<PeriodicalBackupSettingsViewModel>()

	private val outputSelectCall = OpenDocumentTreeHelper(this, this)

	private val driveAuthLauncher = registerForActivityResult(
		ActivityResultContracts.StartIntentSenderForResult(),
	) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			viewModel.onDriveConsentGranted()
		}
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_backup_periodic)
		findPreference<PreferenceCategory>(AppSettings.KEY_BACKUP_TG)?.isVisible = viewModel.isTelegramAvailable
		findPreference<EditTextPreference>(AppSettings.KEY_BACKUP_TG_CHAT)?.summaryProvider =
			EditTextFallbackSummaryProvider(R.string.telegram_chat_id_summary)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.lastBackupDate.observe(viewLifecycleOwner, ::bindLastBackupInfo)
		viewModel.backupsDirectory.observe(viewLifecycleOwner, ::bindOutputSummary)
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(listView, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(listView))
		viewModel.isTelegramCheckLoading.observe(viewLifecycleOwner) {
			findPreference<Preference>(AppSettings.KEY_BACKUP_TG_TEST)?.isEnabled = !it
		}
		viewModel.driveAccountEmail.observe(viewLifecycleOwner, ::bindDriveAccount)
		viewModel.lastDriveBackupDate.observe(viewLifecycleOwner, ::bindDriveLastBackup)
		viewModel.isDriveBusy.observe(viewLifecycleOwner) { busy ->
			findPreference<Preference>(AppSettings.KEY_BACKUP_DRIVE_NOW)?.isEnabled =
				!busy && viewModel.driveAccountEmail.value != null
			findPreference<Preference>(AppSettings.KEY_BACKUP_DRIVE_RESTORE)?.isEnabled =
				!busy && viewModel.driveAccountEmail.value != null
		}
		viewModel.onDriveConsentRequired.observeEvent(viewLifecycleOwner) { pendingIntent ->
			try {
				driveAuthLauncher.launch(
					IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
				)
			} catch (e: Exception) {
				Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			}
		}
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		val result = when (preference.key) {
			AppSettings.KEY_BACKUP_PERIODICAL_OUTPUT -> outputSelectCall.tryLaunch(null)
			AppSettings.KEY_BACKUP_TG_OPEN -> telegramBackupUploader.openBotInApp(router)
			AppSettings.KEY_BACKUP_TG_TEST -> {
				viewModel.checkTelegram()
				true
			}

			AppSettings.KEY_BACKUP_DRIVE_ACCOUNT -> {
				if (viewModel.driveAccountEmail.value == null) {
					viewModel.runDriveAction(DriveAction.CONNECT)
				}
				true
			}

			AppSettings.KEY_BACKUP_DRIVE_DISCONNECT -> {
				viewModel.disconnectDrive()
				true
			}

			AppSettings.KEY_BACKUP_DRIVE_NOW -> {
				viewModel.runDriveAction(DriveAction.BACKUP_NOW)
				true
			}

			AppSettings.KEY_BACKUP_DRIVE_RESTORE -> {
				viewModel.runDriveAction(DriveAction.RESTORE)
				true
			}

			else -> return super.onPreferenceTreeClick(preference)
		}
		if (!result) {
			Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
		return true
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			context?.contentResolver?.takePersistableUriPermission(result, takeFlags)
			settings.periodicalBackupDirectory = result
			viewModel.updateSummaryData()
		}
	}

	private fun bindOutputSummary(path: String?) {
		val preference = findPreference<Preference>(AppSettings.KEY_BACKUP_PERIODICAL_OUTPUT) ?: return
		preference.summary = when (path) {
			null -> getString(R.string.invalid_value_message)
			"" -> null
			else -> path
		}
		preference.icon = if (path == null) {
			getWarningIcon()
		} else {
			null
		}
	}

	private fun bindDriveAccount(email: String?) {
		val connected = email != null
		findPreference<Preference>(AppSettings.KEY_BACKUP_DRIVE_ACCOUNT)?.summary =
			email ?: getString(R.string.backup_drive_not_connected)
		findPreference<Preference>(AppSettings.KEY_BACKUP_DRIVE_DISCONNECT)?.isVisible = connected
		findPreference<Preference>(AppSettings.KEY_BACKUP_DRIVE_ENABLED)?.isEnabled = connected
		val busy = viewModel.isDriveBusy.value
		findPreference<Preference>(AppSettings.KEY_BACKUP_DRIVE_NOW)?.isEnabled = connected && !busy
		findPreference<Preference>(AppSettings.KEY_BACKUP_DRIVE_RESTORE)?.isEnabled = connected && !busy
	}

	private fun bindDriveLastBackup(lastDriveBackupDate: Date?) {
		val preference = findPreference<Preference>(AppSettings.KEY_BACKUP_DRIVE_LAST) ?: return
		preference.summary = lastDriveBackupDate?.let {
			getString(
				R.string.backup_drive_last,
				DateUtils.getRelativeTimeSpanString(it.time),
			)
		}
		preference.isVisible = lastDriveBackupDate != null
	}

	private fun bindLastBackupInfo(lastBackupDate: Date?) {
		val preference = findPreference<Preference>(AppSettings.KEY_BACKUP_PERIODICAL_LAST) ?: return
		preference.summary = lastBackupDate?.let {
			preference.context.getString(
				R.string.last_successful_backup,
				DateUtils.getRelativeTimeSpanString(it.time),
			)
		}
		preference.isVisible = lastBackupDate != null
	}
}

