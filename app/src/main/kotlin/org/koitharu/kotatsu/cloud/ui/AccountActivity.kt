package org.koitharu.kotatsu.cloud.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.cloud.RemoteConfigManager
import org.koitharu.kotatsu.cloud.SupabaseClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.databinding.ActivityAccountBinding

/**
 * Manga Epic: e-mail/password sign-in and registration (Supabase Auth).
 * On success the profile row and this device are registered server-side, then the
 * activity finishes with RESULT_OK so the Profile page can refresh.
 */
@AndroidEntryPoint
class AccountActivity : BaseActivity<ActivityAccountBinding>() {

	@Inject
	lateinit var supabase: SupabaseClient

	@Inject
	lateinit var settings: AppSettings

	private var isRegisterMode = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAccountBinding.inflate(layoutInflater))
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		viewBinding.buttonToggleMode.setOnClickListener {
			isRegisterMode = !isRegisterMode
			bindMode()
		}
		viewBinding.buttonSubmit.setOnClickListener { submit() }
		viewBinding.inputEmail.doAfterTextChanged { hideError() }
		viewBinding.inputPassword.doAfterTextChanged { hideError() }
		bindMode()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
		return insets
	}

	override fun onSupportNavigateUp(): Boolean {
		finish()
		return true
	}

	private fun bindMode() {
		viewBinding.accountTitle.setText(
			if (isRegisterMode) R.string.account_create else R.string.account_sign_in,
		)
		viewBinding.buttonSubmit.setText(
			if (isRegisterMode) R.string.account_create else R.string.account_sign_in,
		)
		viewBinding.buttonToggleMode.setText(
			if (isRegisterMode) R.string.account_have_account else R.string.account_no_account_yet,
		)
		hideError()
	}

	private fun submit() {
		val email = viewBinding.inputEmail.text?.toString()?.trim().orEmpty()
		val password = viewBinding.inputPassword.text?.toString().orEmpty()
		if (!email.contains('@') || password.length < 6) {
			showError(getString(R.string.account_invalid_input))
			return
		}
		setBusy(true)
		lifecycleScope.launch {
			val result = if (isRegisterMode) {
				supabase.signUp(email, password)
			} else {
				supabase.signIn(email, password)
			}
			setBusy(false)
			result.fold(
				onSuccess = { hasSession ->
					if (hasSession) {
						registerProfileAndDevice()
						setResult(RESULT_OK)
						finish()
					} else {
						// E-mail confirmation is pending: no session yet.
						isRegisterMode = false
						bindMode()
						showError(getString(R.string.account_check_email))
					}
				},
				onFailure = { error ->
					showError(error.message ?: getString(R.string.account_error_generic))
				},
			)
		}
	}

	/** Creates/updates the user's profile row and registers this device for pushes. */
	private suspend fun registerProfileAndDevice() {
		val userId = supabase.userId
		if (userId.isEmpty()) {
			return
		}
		val profile = buildJsonArray {
			add(
				buildJsonObject {
					put("user_id", userId)
					put("display_name", settings.profileName)
				},
			)
		}
		runCatching { supabase.upsert("profiles", profile.toString(), "user_id") }
		val token = settings.fcmToken
		if (token.isNotEmpty()) {
			val build = RemoteConfigManager.currentBuild(
				runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull(),
			)
			val device = buildJsonArray {
				add(
					buildJsonObject {
						put("user_id", userId)
						put("fcm_token", token)
						put("platform", "android")
						put("app_build", build)
					},
				)
			}
			runCatching { supabase.upsert("user_devices", device.toString(), "user_id,fcm_token") }
		}
	}

	private fun setBusy(busy: Boolean) {
		viewBinding.accountProgress.visibility = if (busy) View.VISIBLE else View.GONE
		viewBinding.buttonSubmit.isEnabled = !busy
		viewBinding.buttonToggleMode.isEnabled = !busy
	}

	private fun showError(message: String) {
		viewBinding.accountError.text = message
		viewBinding.accountError.visibility = View.VISIBLE
	}

	private fun hideError() {
		viewBinding.accountError.visibility = View.GONE
	}
}
