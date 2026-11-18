package org.koitharu.kotatsu.profile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.cloud.SupabaseClient
import org.koitharu.kotatsu.cloud.ui.AccountActivity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.formatDurationShort
import org.koitharu.kotatsu.stats.ui.views.BarChartView
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

/**
 * Profile tab (Manga Epic): a local profile — name and picture chosen by the user — plus their
 * reading statistics: today, the last 7 days as a chart, the all-time total and chapters read.
 */
@AndroidEntryPoint
class ProfileFragment : Fragment(R.layout.fragment_profile) {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var supabase: SupabaseClient

	private val viewModel by viewModels<ProfileViewModel>()

	private val accountLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		bindAccount()
	}

	private val avatarPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		if (uri != null) {
			saveAvatar(uri)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// Manga Epic: the banner and the avatar ring take the active colour scheme's primary
		// colour, so the whole page recolours when the user switches schemes.
		val primary = MaterialColors.getColor(view, appcompatR.attr.colorPrimary)
		val density = resources.displayMetrics.density
		val corner = 24 * density
		view.findViewById<View>(R.id.bannerView).background = GradientDrawable(
			GradientDrawable.Orientation.BOTTOM_TOP,
			intArrayOf(primary, ColorUtils.blendARGB(primary, Color.BLACK, 0.35f)),
		).apply {
			cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, corner, corner, corner, corner)
		}
		view.findViewById<View>(R.id.avatarFrame).background = GradientDrawable().apply {
			shape = GradientDrawable.OVAL
			setStroke((4 * density).toInt(), primary)
		}
		val avatarView = view.findViewById<ImageView>(R.id.avatarView)
		avatarView.outlineProvider = object : ViewOutlineProvider() {
			override fun getOutline(view: View, outline: Outline) {
				outline.setOval(0, 0, view.width, view.height)
			}
		}
		avatarView.clipToOutline = true
		avatarView.setOnClickListener { avatarPicker.launch("image/*") }
		view.findViewById<TextView>(R.id.nameView).setOnClickListener { showNameDialog() }
		bindProfile(view)
		bindAccount(view)
		val todayValue = view.findViewById<TextView>(R.id.todayValue)
		val weekValue = view.findViewById<TextView>(R.id.weekValue)
		val weekChart = view.findViewById<BarChartView>(R.id.weekChart)
		val weekCard = view.findViewById<View>(R.id.weekCard)
		val totalValue = view.findViewById<TextView>(R.id.totalValue)
		val chaptersValue = view.findViewById<TextView>(R.id.chaptersValue)
		collect(viewModel.todayDuration) { millis ->
			if (millis != null) {
				todayValue.text = formatDuration(millis)
			}
		}
		collect(viewModel.weekDurations) { days ->
			if (days.isNotEmpty()) {
				weekValue.text = formatDuration(days.sum())
				weekCard.visibility = if (days.any { it > 0L }) View.VISIBLE else View.GONE
				val today = LocalDate.now()
				weekChart.setData(
					days.mapIndexed { index, duration ->
						val date = today.minusDays((days.size - 1 - index).toLong())
						BarChartView.Bar(
							value = TimeUnit.MILLISECONDS.toMinutes(duration).toInt(),
							label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
						)
					},
				)
			}
		}
		collect(viewModel.totalDuration) { millis ->
			if (millis != null) {
				totalValue.text = formatDuration(millis)
			}
		}
		collect(viewModel.chaptersRead) { count ->
			if (count != null) {
				chaptersValue.text = count.toString()
			}
		}
	}


	/** Manga Epic: the cloud account row — sign-in entry point or the signed-in e-mail + sign-out. */
	private fun bindAccount(root: View? = view) {
		val status = root?.findViewById<TextView>(R.id.accountStatus) ?: return
		val button = root.findViewById<Button>(R.id.accountButton) ?: return
		if (supabase.isSignedIn) {
			status.text = getString(R.string.account_signed_in_as, supabase.userEmail)
			button.setText(R.string.account_sign_out)
			button.setOnClickListener {
				MaterialAlertDialogBuilder(requireContext())
					.setMessage(R.string.account_sign_out_confirm)
					.setPositiveButton(R.string.account_sign_out) { _, _ ->
						lifecycleScope.launch {
							supabase.signOut()
							bindAccount()
						}
					}
					.setNegativeButton(android.R.string.cancel, null)
					.show()
			}
		} else {
			status.setText(R.string.account_signed_out_hint)
			button.setText(R.string.account_sign_in)
			button.setOnClickListener {
				accountLauncher.launch(Intent(requireContext(), AccountActivity::class.java))
			}
		}
	}

	override fun onResume() {
		super.onResume()
		viewModel.load()
		view?.let { bindProfile(it) }
	}

	private fun <T> collect(flow: Flow<T>, action: (T) -> Unit) {
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				flow.collect(action)
			}
		}
	}

	private fun formatDuration(millis: Long): String {
		return resources.formatDurationShort(millis) ?: getString(R.string.minutes_short, 0)
	}

	private fun bindProfile(view: View) {
		val name = settings.profileName
		view.findViewById<TextView>(R.id.nameView).text = name.ifBlank {
			getString(R.string.profile_default_name)
		}
		val avatarView = view.findViewById<ImageView>(R.id.avatarView)
		val file = avatarFile()
		if (file.exists()) {
			avatarView.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
		} else {
			avatarView.setImageResource(R.drawable.ic_profile_checked)
		}
	}

	private fun showNameDialog() {
		val context = requireContext()
		val padding = (24 * resources.displayMetrics.density).toInt()
		val input = EditText(context).apply {
			setText(settings.profileName)
			hint = getString(R.string.profile_name_hint)
			setSelection(text.length)
		}
		val container = FrameLayout(context).apply {
			setPadding(padding, padding / 2, padding, 0)
			addView(input)
		}
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.profile)
			.setView(container)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				settings.profileName = input.text.toString().trim()
				view?.let { bindProfile(it) }
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun avatarFile(): File = File(requireContext().filesDir, "profile/avatar.jpg")

	private fun saveAvatar(uri: Uri) {
		val context = requireContext()
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
			runCatching {
				val source = context.contentResolver.openInputStream(uri)?.use { input ->
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
				val file = avatarFile()
				file.parentFile?.mkdirs()
				file.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 90, out) }
			}
			withContext(Dispatchers.Main) {
				view?.let { bindProfile(it) }
			}
		}
	}

	private companion object {
		const val AVATAR_SIZE = 512
	}
}
