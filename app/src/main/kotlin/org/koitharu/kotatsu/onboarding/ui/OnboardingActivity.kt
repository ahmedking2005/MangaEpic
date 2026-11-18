package org.koitharu.kotatsu.onboarding.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.prefs.StartPage
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.ActivityOnboardingBinding
import org.koitharu.kotatsu.main.ui.MainActivity
import javax.inject.Inject
import com.google.android.material.R as materialR

/**
 * Manga Epic first-run wizard, shown once on the first launch:
 * 1) restore from a backup file (or start fresh), 2) choose the start page,
 * 3) choose the colour scheme. Everything stays changeable later in Settings.
 */
@AndroidEntryPoint
class OnboardingActivity : BaseActivity<ActivityOnboardingBinding>() {

	@Inject
	lateinit var settings: AppSettings

	private var currentStep = STEP_RESTORE
	private var restorePicked = false
	private lateinit var appliedScheme: ColorScheme
	private val startPageIds = HashMap<Int, StartPage>()
	private val schemeIds = HashMap<Int, ColorScheme>()

	private val restoreCall = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) {
			restorePicked = true
			bindStep()
			router.showBackupRestoreDialog(uri)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityOnboardingBinding.inflate(layoutInflater))
		appliedScheme = settings.colorScheme
		currentStep = savedInstanceState?.getInt(STATE_STEP) ?: STEP_RESTORE
		buildStartPageRows()
		buildThemeRows()
		with(viewBinding) {
			buttonRestore.setOnClickListener { restoreCall.tryLaunch(arrayOf("*/*")) }
			buttonNext.setOnClickListener { showStep(STEP_START_PAGE) }
			buttonNext2.setOnClickListener { showStep(STEP_THEME) }
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

	private fun showStep(step: Int) {
		currentStep = step
		bindStep()
	}

	private fun bindStep() {
		with(viewBinding) {
			stepRestore.isVisible = currentStep == STEP_RESTORE
			stepStartPage.isVisible = currentStep == STEP_START_PAGE
			stepTheme.isVisible = currentStep == STEP_THEME
			stepTitle.setText(
				when (currentStep) {
					STEP_RESTORE -> R.string.onboarding_welcome_title
					STEP_START_PAGE -> R.string.start_page
					else -> R.string.color_theme
				},
			)
			stepIndicator.text = getString(R.string.onboarding_step, currentStep, STEP_THEME)
			buttonNext.setText(if (restorePicked) R.string.next else R.string.onboarding_start_fresh)
		}
	}

	private fun buildStartPageRows() {
		val group = viewBinding.startPageGroup
		for (page in StartPage.entries) {
			val titleRes = when (page) {
				StartPage.DOWNLOADS -> R.string.downloads
				StartPage.LATEST -> R.string.latest_updates
				StartPage.FAVORITES -> R.string.favourites
				StartPage.EXPLORE -> R.string.explore
				StartPage.PROFILE -> R.string.profile
			}
			val radio = newRadioButton(getString(titleRes))
			startPageIds[radio.id] = page
			group.addView(radio)
			if (page == settings.startPage) {
				group.check(radio.id)
			}
		}
		group.setOnCheckedChangeListener { _, checkedId ->
			startPageIds[checkedId]?.let { settings.startPage = it }
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
			val primary = MaterialColors.getColor(wrapped, materialR.attr.colorPrimary, Color.GRAY)
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

	private fun newRadioButton(text: String): RadioButton {
		return RadioButton(this).apply {
			id = View.generateViewId()
			this.text = text
			layoutParams = RadioGroup.LayoutParams(
				RadioGroup.LayoutParams.MATCH_PARENT,
				RadioGroup.LayoutParams.WRAP_CONTENT,
			)
			val vertical = TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics,
			).toInt()
			setPadding(paddingStart, vertical, paddingEnd, vertical)
			textSize = 16f
		}
	}

	private fun finishOnboarding() {
		settings.isOnboardingCompleted = true
		startActivity(Intent(this, MainActivity::class.java))
		finish()
	}

	private companion object {
		const val STEP_RESTORE = 1
		const val STEP_START_PAGE = 2
		const val STEP_THEME = 3
		const val STATE_STEP = "onboarding_step"
	}
}
