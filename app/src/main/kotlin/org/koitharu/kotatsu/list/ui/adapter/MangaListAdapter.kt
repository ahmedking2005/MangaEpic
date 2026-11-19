package org.koitharu.kotatsu.list.ui.adapter

import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.ads.NativeAdController
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.NativeAdListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

open class MangaListAdapter(
	listener: MangaListListener,
	sizeResolver: ItemSizeResolver,
	private val showNativeAds: Boolean = false,
) : BaseListAdapter<ListModel>() {

	private val nativeAdController = NativeAdController()

	init {
		addDelegate(ListItemType.MANGA_LIST, mangaListItemAD(listener))
		addDelegate(ListItemType.MANGA_LIST_DETAILED, mangaListDetailedItemAD(listener))
		addDelegate(ListItemType.MANGA_GRID, mangaGridItemAD(sizeResolver, listener))
		if (showNativeAds) {
			addDelegate(ListItemType.NATIVE_AD, nativeAdListItemAD(nativeAdController))
		}
		addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		addDelegate(ListItemType.STATE_ERROR, errorStateListAD(listener))
		addDelegate(ListItemType.FOOTER_ERROR, errorFooterAD(listener))
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(listener))
		addDelegate(ListItemType.HINT_EMPTY, emptyHintAD(listener))
		addDelegate(ListItemType.HEADER, listHeaderAD(listener))
		addDelegate(ListItemType.QUICK_FILTER, quickFilterAD(listener))
		addDelegate(ListItemType.TIP, tipAD(listener))
		addDelegate(ListItemType.INFO, infoAD())
		addDelegate(ListItemType.FOOTER_BUTTON, buttonFooterAD(listener))
	}

	override suspend fun emit(value: List<ListModel>?) {
		super.emit(if (showNativeAds && org.koitharu.kotatsu.ads.AdMobManager.isEnabled()) value?.withNativeAdSlots() else value)
	}

	override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
		nativeAdController.destroy()
		super.onDetachedFromRecyclerView(recyclerView)
	}

	private fun List<ListModel>.withNativeAdSlots(): List<ListModel> {
		if (none { it is MangaListModel }) return this
		val result = ArrayList<ListModel>(size + size / NATIVE_AD_INTERVAL)
		var mangaCount = 0
		for (model in this) {
			result += model
			if (model is MangaListModel) {
				mangaCount++
				if (mangaCount % NATIVE_AD_INTERVAL == 0) {
					result += NativeAdListModel(slot = mangaCount / NATIVE_AD_INTERVAL)
				}
			}
		}
		return result
	}

	private companion object {
		// One full-width card after every sixteen manga items keeps placement predictable.
		const val NATIVE_AD_INTERVAL = 16
	}
}
