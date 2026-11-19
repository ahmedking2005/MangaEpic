package org.koitharu.kotatsu.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import org.koitharu.kotatsu.core.util.ext.toBitmapOrNull
import java.io.File

/**
 * Manga Epic: shared access to the user's profile picture — the locally chosen
 * photo, or the signed-in account's photo (e.g. Google) — for the Profile page
 * and for the top-bar profile action, which wears the picture itself.
 */
object ProfileAvatar {

	fun localFile(context: Context): File = File(context.filesDir, "profile/avatar.jpg")

	fun localBitmap(context: Context): Bitmap? {
		val file = localFile(context)
		return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
	}

	suspend fun remoteBitmap(context: Context, loader: ImageLoader, url: String): Bitmap? {
		val request = ImageRequest.Builder(context)
			.data(url)
			.allowHardware(false)
			.build()
		return loader.execute(request).toBitmapOrNull()
	}

	/** Center-crops, circle-crops and scales a bitmap for use as a round toolbar icon. */
	fun roundDrawable(context: Context, src: Bitmap, sizeDp: Int = 28): Drawable {
		val sizePx = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
		val side = minOf(src.width, src.height)
		val cropped = Bitmap.createBitmap(
			src,
			(src.width - side) / 2,
			(src.height - side) / 2,
			side,
			side,
		)
		val scaled = Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)
		val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG)
		paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
		Canvas(out).drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
		return BitmapDrawable(context.resources, out)
	}
}
