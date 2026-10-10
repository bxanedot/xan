package com.music.innertube.pages

import com.music.innertube.models.YTItem
import com.music.innertube.models.getItems
import com.music.innertube.models.getContinuation
import com.music.innertube.models.response.BrowseResponse

data class ArtistItemsContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
) {
    companion object {
        fun fromResponse(response: BrowseResponse): ArtistItemsContinuationPage {
        return when {
            response.continuationContents?.gridContinuation != null -> {
                val gridContinuation = response.continuationContents.gridContinuation
                val items = gridContinuation.items.mapNotNull {
                    it.musicTwoRowItemRenderer?.let { renderer ->
                        ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                    }
                }
                ArtistItemsContinuationPage(
                    items = items,
                    continuation = gridContinuation.continuations?.getContinuation()
                )
            }

            response.continuationContents?.musicShelfContinuation != null -> {
                val shelf = response.continuationContents.musicShelfContinuation
                val items = shelf.contents?.getItems()?.mapNotNull {
                    ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                }.orEmpty()
                ArtistItemsContinuationPage(
                    items = items,
                    continuation = shelf.continuations?.getContinuation()
                        ?: shelf.contents?.getContinuation()
                )
            }

            response.continuationContents?.musicPlaylistShelfContinuation != null -> {
                val musicPlaylistShelfContinuation = response.continuationContents.musicPlaylistShelfContinuation
                val items = musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                    ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                }
                ArtistItemsContinuationPage(
                    items = items,
                    continuation = musicPlaylistShelfContinuation.continuations?.getContinuation()
                        ?: musicPlaylistShelfContinuation.contents.getContinuation()
                )
            }

            else -> {
                val continuationItems = response.onResponseReceivedActions?.firstNotNullOfOrNull { it.appendContinuationItemsAction?.continuationItems }
                val items = continuationItems?.getItems()?.mapNotNull {
                    ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                } ?: emptyList()
                ArtistItemsContinuationPage(
                    items = items,
                    continuation = if (items.isEmpty()) null else continuationItems?.getContinuation()
                )
            }
        }
        }
    }
}
