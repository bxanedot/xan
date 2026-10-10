/**
 * xan Project (C) 2026
 * Licensed under GPL-3.0-only | See LICENCE for details
 */

package app.xan.music.ui.utils

import androidx.compose.runtime.mutableStateOf

class ItemWrapper<T>(
    val item: T,
) {
    private val _isSelected = mutableStateOf(true)

    var isSelected: Boolean
        get() = _isSelected.value
        set(value) {
            _isSelected.value = value
        }
}
