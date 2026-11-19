package org.koitharu.kotatsu.reader.ui.config

import android.view.View
import android.widget.CompoundButton
import androidx.fragment.app.activityViewModels
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.reader.ui.ScreenOrientationHelper
import javax.inject.Inject

abstract class BaseReaderConfigSheet<B : ViewBinding> :
	BaseAdaptiveSheet<B>(),
	CompoundButton.OnCheckedChangeListener {

	@Inject
	lateinit var orientationHelper: ScreenOrientationHelper

	protected val viewModel by activityViewModels<ReaderViewModel>()

	protected abstract fun getScreenRotateButton(): View?

	protected abstract fun getLockRotationSwitch(): CompoundButton?

	protected fun observeScreenOrientation() {
		orientationHelper.observeAutoOrientation()
			.onEach {
				getScreenRotateButton()?.let { btn ->
					btn.visibility = if (it) View.GONE else View.VISIBLE
				}
				getLockRotationSwitch()?.let { sw ->
					sw.visibility = if (it) View.VISIBLE else View.GONE
				}
				updateOrientationLockSwitch()
			}.launchIn(viewLifecycleScope)
	}

	protected fun updateOrientationLockSwitch() {
		val switch = getLockRotationSwitch() ?: return
		switch.setOnCheckedChangeListener(null)
		switch.isChecked = orientationHelper.isLocked
		switch.setOnCheckedChangeListener(this)
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		when (buttonView.id) {
			org.koitharu.kotatsu.R.id.switch_screen_lock_rotation -> {
				orientationHelper.isLocked = isChecked
			}
		}
	}
}
