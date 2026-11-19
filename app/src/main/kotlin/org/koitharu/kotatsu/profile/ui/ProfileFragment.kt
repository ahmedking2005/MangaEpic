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
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.cloud.LibrarySync
import org.koitharu.kotatsu.cloud.SupabaseClient
import org.koitharu.kotatsu.cloud.ui.AccountActivity
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.formatDurationShort
import org.koitharu.kotatsu.stats.ui.views.BarChartView
import java.text.DateFormat
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
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

	@Inject
	lateinit var librarySync: LibrarySync

	@Inject
	lateinit var coil: ImageLoader

	@Inject
	lateinit var syncScheduler: org.koitharu.kotatsu.cloud.LibrarySyncWorker.Scheduler

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Manga Epic: opened from the top-bar icon, the page reveals itself with an
		// expanding circle from that icon instead of the default tab fade.
		if ((activity as? org.koitharu.kotatsu.main.ui.MainActivity)?.hasProfileRevealCenter() == true) {
			enterTransition = null
		}
	}

	private val viewModel by viewModels<ProfileViewModel>()

	private val accountLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		bindAccount()
		view?.let { bindProfile(it) }
		if (supabase.isSignedIn) {
			syncScheduler.schedule()
			runSync()
		}
	}

	private val avatarPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		if (uri != null) {
			saveAvatar(uri)
		}
	}

	private val coverPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		if (uri != null) {
			saveCover(uri)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// Manga Epic: immersive page — the search bar and the bottom navigation
		// hide while the profile is open, exactly like entering Settings.
		val mainActivity = activity as? org.koitharu.kotatsu.main.ui.MainActivity
		mainActivity?.setProfileChromeHidden(true)
		val revealCenter = mainActivity?.consumeProfileRevealCenter()
		if (revealCenter != null) {
			view.visibility = View.INVISIBLE
			view.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
				override fun onPreDraw(): Boolean {
					view.viewTreeObserver.removeOnPreDrawListener(this)
					val loc = IntArray(2)
					view.getLocationOnScreen(loc)
					val radius = kotlin.math.hypot(view.width.toDouble(), view.height.toDouble()).toFloat()
					view.visibility = View.VISIBLE
					android.view.ViewAnimationUtils.createCircularReveal(
						view,
						(revealCenter.x - loc[0]).toInt(),
						(revealCenter.y - loc[1]).toInt(),
						0f,
						radius,
					).setDuration(450).start()
					return true
				}
			})
		}
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
		// Manga Epic: an explicit way out of the immersive page — the bottom bar is
		// hidden here, so the page carries its own close button over the banner.
		view.findViewById<View>(R.id.closeButton).setOnClickListener {
			requireActivity().onBackPressedDispatcher.onBackPressed()
		}
		// Manga Epic: the banner is the cover-photo area. Tapping it picks a photo;
		// with a cover already set it offers replacing or removing it. The cover
		// image is clipped to the banner's rounded bottom corners, like the gradient.
		val coverView = view.findViewById<ImageView>(R.id.coverView)
		coverView.outlineProvider = object : ViewOutlineProvider() {
			override fun getOutline(view: View, outline: Outline) {
				outline.setRoundRect(0, -corner.toInt(), view.width, view.height, corner)
			}
		}
		coverView.clipToOutline = true
		view.findViewById<View>(R.id.bannerView).setOnClickListener {
			if (coverFile().exists()) {
				showCoverDialog()
			} else {
				coverPicker.launch("image/*")
			}
		}
		bindCover(view)
		val avatarView = view.findViewById<ImageView>(R.id.avatarView)
		avatarView.outlineProvider = object : ViewOutlineProvider() {
			override fun getOutline(view: View, outline: Outline) {
				outline.setOval(0, 0, view.width, view.height)
			}
		}
		avatarView.clipToOutline = true
		avatarView.setOnClickListener {
			if (!supabase.isSignedIn) {
				avatarPicker.launch("image/*")
			}
		}
		view.findViewById<TextView>(R.id.nameView).setOnClickListener {
			if (!supabase.isSignedIn) {
				showNameDialog()
			}
		}
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


	/** Manga Epic: runs one library sync cycle and reflects the state on the account card. */
	private fun runSync() {
		val status = view?.findViewById<TextView>(R.id.syncStatus) ?: return
		status.setText(R.string.account_syncing)
		lifecycleScope.launch {
			val result = librarySync.syncAll()
			bindSyncStatus()
			if (result.isFailure) {
				view?.findViewById<TextView>(R.id.syncStatus)
					?.setText(R.string.account_sync_failed)
			}
		}
	}

	private fun bindSyncStatus(root: View? = view) {
		val status = root?.findViewById<TextView>(R.id.syncStatus) ?: return
		if (!supabase.isSignedIn) {
			status.text = ""
			status.setOnClickListener(null)
			return
		}
		val last = settings.lastSyncAt
		status.text = if (last > 0L) {
			getString(
				R.string.account_last_sync,
				DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last)),
			)
		} else {
			getString(R.string.account_sync_never)
		}
		status.setOnClickListener { runSync() }
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
							view?.let { bindProfile(it) }
						}
					}
					.setNegativeButton(android.R.string.cancel, null)
					.show()
			}
			bindSyncStatus(root)
		} else {
			status.setText(R.string.account_signed_out_hint)
			button.setText(R.string.account_sign_in)
			button.setOnClickListener {
				accountLauncher.launch(Intent(requireContext(), AccountActivity::class.java))
			}
			bindSyncStatus(root)
		}
	}

	override fun onDestroyView() {
		// Manga Epic: leaving the profile brings the bars back.
		(activity as? org.koitharu.kotatsu.main.ui.MainActivity)?.setProfileChromeHidden(false)
		super.onDestroyView()
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
		val nameView = view.findViewById<TextView>(R.id.nameView)
		val avatarView = view.findViewById<ImageView>(R.id.avatarView)
		val identityView = view.findViewById<TextView>(R.id.identityView)
		val hintView = view.findViewById<TextView>(R.id.editHintView)
		if (supabase.isSignedIn) {
			// Manga Epic: while signed in, the header IS the account — the name, the
			// @handle, the e-mail and the photo come from the provider (e.g. Google)
			// and disappear again on sign-out, when the local profile returns.
			nameView.text = supabase.userName
				.ifBlank { supabase.userEmail.substringBefore('@') }
				.ifBlank { getString(R.string.profile_default_name) }
			identityView.text = "@${supabase.userHandle}\n${supabase.userEmail}"
			identityView.visibility = View.VISIBLE
			hintView.visibility = View.GONE
			val avatarUrl = supabase.userAvatarUrl
			if (avatarUrl.isNotBlank()) {
				ImageRequest.Builder(requireContext())
					.data(avatarUrl)
					.target(avatarView)
					.placeholder(R.drawable.ic_profile_checked)
					.error(R.drawable.ic_profile_checked)
					.enqueueWith(coil)
			} else {
				bindLocalAvatar(avatarView)
			}
		} else {
			identityView.visibility = View.GONE
			hintView.visibility = View.VISIBLE
			nameView.text = settings.profileName.ifBlank {
				getString(R.string.profile_default_name)
			}
			bindLocalAvatar(avatarView)
		}
	}

	private fun bindLocalAvatar(avatarView: ImageView) {
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

	private fun coverFile(): File = File(requireContext().filesDir, "profile/cover.jpg")

	/** Manga Epic: the banner shows the cover photo when one is set; otherwise the
	 *  theme gradient on the banner shows through. Local only — never synced. */
	private fun bindCover(root: View) {
		val coverView = root.findViewById<ImageView>(R.id.coverView)
		val file = coverFile()
		if (file.exists()) {
			coverView.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
			coverView.visibility = View.VISIBLE
		} else {
			coverView.setImageDrawable(null)
			coverView.visibility = View.GONE
		}
	}

	private fun showCoverDialog() {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.profile_cover)
			.setItems(
				arrayOf<CharSequence>(
					getString(R.string.profile_cover_change),
					getString(R.string.profile_cover_remove),
				),
			) { _, which ->
				when (which) {
					0 -> coverPicker.launch("image/*")
					1 -> {
						coverFile().delete()
						view?.let { bindCover(it) }
					}
				}
			}
			.show()
	}

	private fun saveCover(uri: Uri) {
		val context = requireContext()
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
			runCatching {
				val source = context.contentResolver.openInputStream(uri)?.use { input ->
					BitmapFactory.decodeStream(input)
				} ?: return@runCatching
				val maxDim = maxOf(source.width, source.height)
				val scaled = if (maxDim > COVER_MAX_SIZE) {
					val ratio = COVER_MAX_SIZE.toFloat() / maxDim
					Bitmap.createScaledBitmap(
						source,
						(source.width * ratio).toInt().coerceAtLeast(1),
						(source.height * ratio).toInt().coerceAtLeast(1),
						true,
					)
				} else {
					source
				}
				val file = coverFile()
				file.parentFile?.mkdirs()
				file.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 90, out) }
			}
			withContext(Dispatchers.Main) {
				view?.let { bindCover(it) }
			}
		}
	}

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
		const val COVER_MAX_SIZE = 1440
	}
}
