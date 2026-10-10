/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.models

import androidx.compose.runtime.Stable
import com.music.innertube.models.YTItem

@Stable
data class ItemsPage(
    val items: List<YTItem>,
    val continuation: String?,
)
