/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.BitmapLoader
import app.xan.music.R
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import timber.log.Timber

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun createFallbackBitmap(): Bitmap =
        runCatching {
            ContextCompat.getDrawable(context, R.drawable.xan_default_album_art)
                ?.toBitmap(512, 512, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private fun Bitmap.copyIfNeeded(): Bitmap {
        return if (isRecycled) {
            createFallbackBitmap()
        } else {
            try {
                copy(Bitmap.Config.ARGB_8888, false) ?: createFallbackBitmap()
            } catch (e: Exception) {
                createFallbackBitmap()
            }
        }
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                bitmap?.copyIfNeeded() ?: createFallbackBitmap()
            } catch (e: Exception) {
                Timber.tag("CoilBitmapLoader").w(e, "Failed to decode bitmap data")
                createFallbackBitmap()
            }
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            repeat(3) { attempt ->
                try {
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .size(512, 512)
                        .allowHardware(false)
                        .memoryCachePolicy(if (attempt == 0) CachePolicy.ENABLED else CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build()

                    when (val result = context.imageLoader.execute(request)) {
                        is ErrorResult -> {
                            Timber.tag("CoilBitmapLoader").w(
                                result.throwable,
                                "Artwork load failed (attempt ${attempt + 1}/3): $uri",
                            )
                        }
                        is SuccessResult -> {
                            try {
                                return@future result.image.toBitmap().copyIfNeeded()
                            } catch (e: Exception) {
                                Timber.tag("CoilBitmapLoader").w(e, "Failed to convert artwork bitmap")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("CoilBitmapLoader").w(e, "Artwork load failed (attempt ${attempt + 1}/3): $uri")
                }

                if (attempt < 2) delay(350L * (attempt + 1))
            }

            createFallbackBitmap()
        }
}
