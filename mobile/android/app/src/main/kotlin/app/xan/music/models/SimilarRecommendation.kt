/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.models

import androidx.compose.runtime.Immutable
import com.music.innertube.models.YTItem
import app.xan.music.db.entities.LocalItem

@Immutable
data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)
