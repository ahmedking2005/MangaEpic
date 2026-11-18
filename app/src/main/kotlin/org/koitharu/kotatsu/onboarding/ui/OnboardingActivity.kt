package org.koitharu.kotatsu.onboarding.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.ViewOutlineProvider
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.ActivityOnboardingBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.main.ui.MainActivity
import java.io.File
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

/**
 * Manga Epic first-run wizard, shown once on the first launch:
 * 1) restore from a backup file (or start fresh), 2) set the profile name and picture,
 * 3) choose the colour scheme. Everything stays changeable later in Settings.
 */
@AndroidEntryPoint
class OnboardingActivity : BaseActivity<ActivityOnboardingBinding>() {

	@Inject
	lateinit var settings: AppSettings

	private var currentStep = STEP_RESTORE
	private var restorePicked = false
	private lateinit var appliedScheme: ColorScheme
	private val schemeIds = HashMap<Int, ColorScheme>()

	private val restoreCall = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) {
			restorePicked = true
			bindStep()
			router.showBackupRestoreDialog(uri)
		}
	}

	private val avatarCall = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		if (uri != null) {
			saveAvatar(uri)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityOnboardingBinding.inflate(layoutInflater))
		appliedScheme = settings.colorScheme
		currentStep = savedInstanceState?.getInt(STATE_STEP) ?: STEP_RESTORE
		bindProfileStep()
		buildThemeRows()
		with(viewBinding) {
			buttonRestore.setOnClickListener { restoreCall.tryLaunch(arrayOf("*/*")) }
			buttonNext.setOnClickListener { showStep(STEP_PROFILE) }
			buttonNext2.setOnClickListener {
				saveProfileName()
				showStep(STEP_THEME)
			}
			buttonFinish.setOnClickListener { finishOnboarding() }
		}
		onBackPressedDispatcher.addCallback(
			this,
			object : OnBackPressedCallback(true) {
				override fun handleOnBackPressed() {
					if (currentStep > STEP_RESTORE) {
						showStep(currentStep - 1)
					} else {
						isEnabled = false
						onBackPressedDispatcher.onBackPressed()
					}
				}
			},
		)
		bindStep()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putInt(STATE_STEP, currentStep)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
		return insets
	}

	private fun showStep(step: Int) {
		currentStep = step
		bindStep()
	}

	private fun bindStep() {
		with(viewBinding) {
			stepRestore.isVisible = currentStep == STEP_RESTORE
			stepProfile.isVisible = currentStep == STEP_PROFILE
			stepTheme.isVisible = currentStep == STEP_THEME
			stepTitle.setText(
				when (currentStep) {
					STEP_RESTORE -> R.string.onboarding_welcome_title
					STEP_PROFILE -> R.string.profile
					else -> R.string.color_theme
				},
			)
			stepIndicator.text = getString(R.string.onboarding_step, currentStep, STEP_THEME)
			buttonNext.setText(if (restorePicked) R.string.next else R.string.onboarding_start_fresh)
		}
	}

	private fun bindProfileStep() {
		val avatar = viewBinding.onboardingAvatar
		avatar.outlineProvider = object : ViewOutlineProvider() {
			override fun getOutline(view: View, outline: Outline) {
				outline.setOval(0, 0, view.width, view.height)
			}
		}
		avatar.clipToOutline = true
		avatar.setOnClickListener { avatarCall.launch("image/*") }
		viewBinding.onboardingName.setText(settings.profileName)
		loadAvatar()
	}

	private fun loadAvatar() {
		val file = File(filesDir, "profile/avatar.jpg")
		if (file.exists()) {
			viewBinding.onboardingAvatar.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
		}
	}

	private fun saveProfileName() {
		settings.profileName = viewBinding.onboardingName.text?.toString()?.trim().orEmpty()
	}

	private fun saveAvatar(uri: Uri) {
		lifecycleScope.launch(Dispatchers.IO) {
			runCatching {
				val source = contentResolver.openInputStream(uri)?.use { input ->
					BitmapFactory.decodeStream(input)
				} ?: return@runCatching
				val size = minOf(source.width, source.height)
				val cropped = Bitmap.createBitmap(
					source,
					(source.width - size) / 2,
					(source.height - size) / 2,
					size,
					size,
				)
				val scaled = Bitmap.createScaledBitmap(cropped, AVATAR_SIZE, AVATAR_SIZE, true)
				val file = File(filesDir, "profile/avatar.jpg")
				file.parentFile?.mkdirs()
				file.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 90, out) }
			}
			withContext(Dispatchers.Main) { loadAvatar() }
		}
	}

	private fun buildThemeRows() {
		val group = viewBinding.themeGroup
		val circleSize = TypedValue.applyDimension(
			TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics,
		).toInt()
		for (scheme in ColorScheme.getAvailableList()) {
			val radio = newRadioButton(getString(scheme.titleResId))
			val wrapped = ContextThemeWrapper(this, scheme.styleResId)
			val primary = wrapped.getThemeColor(appcompatR.attr.colorPrimary, Color.GRAY)
			val circle = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(primary)
				setSize(circleSize, circleSize)
			}
			radio.setCompoundDrawablesWithIntrinsicBounds(circle, null, null, null)
			radio.compoundDrawablePadding = circleSize / 2
			schemeIds[radio.id] = scheme
			group.addView(radio)
			if (scheme == settings.colorScheme) {
				group.check(radio.id)
			}
		}
		group.setOnCheckedChangeListener { _, checkedId ->
			val scheme = schemeIds[checkedId] ?: return@setOnCheckedChangeListener
			if (scheme != appliedScheme) {
				settings.colorScheme = scheme
				recreate()
			}
		}
	}

	private fun newRadioButton(label: String): RadioButton {
		val radio = RadioButton(this)
		radio.id = View.generateViewId()
		radio.setText(label)
		radio.layoutParams = RadioGroup.LayoutParams(
			RadioGroup.LayoutParams.MATCH_PARENT,
			RadioGroup.LayoutParams.WRAP_CONTENT,
		)
		val vertical = TypedValue.applyDimension(
			TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics,
		).toInt()
		radio.setPadding(radio.paddingStart, vertical, radio.paddingEnd, vertical)
		radio.textSize = 16f
		return radio
	}

	private fun finishOnboarding() {
		settings.isOnboardingCompleted = true
		startActivity(Intent(this, MainActivity::class.java))
		finish()
	}

	private companion object {
		const val STEP_RESTORE = 1
		const val STEP_PROFILE = 2
		const val STEP_THEME = 3
		const val STATE_STEP = "onboarding_step"
		const val AVATAR_SIZE = 512
	}
}
