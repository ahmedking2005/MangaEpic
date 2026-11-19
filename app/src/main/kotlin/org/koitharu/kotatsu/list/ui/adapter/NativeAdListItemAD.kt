package org.koitharu.kotatsu.list.ui.adapter

import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.ads.NativeAdController
import org.koitharu.kotatsu.ads.bindNativeAd
import org.koitharu.kotatsu.databinding.ItemNativeAdBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.NativeAdListModel

fun nativeAdListItemAD(
	controller: NativeAdController,
) = adapterDelegateViewBinding<NativeAdListModel, ListModel, ItemNativeAdBinding>(
	{ inflater, parent -> ItemNativeAdBinding.inflate(inflater, parent, false) },
) {

	bind {
		val slot = item.slot
		itemView.tag = slot
		// ADS fix: reserve no space until an ad actually fills the slot. A
		// loading or failed (no-fill) slot collapses completely instead of
		// leaving a full-height empty gap in the list (a GONE item view keeps
		// its measured height in a RecyclerView).
		setAdSlotCollapsed(itemView, collapsed = true)
		controller.getOrLoad(context, slot) { nativeAd ->
			if (itemView.tag != slot || nativeAd == null) return@getOrLoad
			binding.bindNativeAd(nativeAd)
			setAdSlotCollapsed(itemView, collapsed = false)
		}
	}
}

private fun setAdSlotCollapsed(itemView: View, collapsed: Boolean) {
	val margin = itemView.resources.getDimensionPixelSize(R.dimen.margin_small)
	val params = (itemView.layoutParams as? ViewGroup.MarginLayoutParams)
		?: ViewGroup.MarginLayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		)
	if (collapsed) {
		params.height = 0
		params.topMargin = 0
		params.bottomMargin = 0
	} else {
		params.height = ViewGroup.LayoutParams.WRAP_CONTENT
		params.topMargin = margin
		params.bottomMargin = margin
	}
	itemView.layoutParams = params
	itemView.isVisible = !collapsed
}
