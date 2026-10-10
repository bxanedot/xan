package app.xan.music.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.xan.music.R
import app.xan.music.constants.AppLogo
import app.xan.music.ui.utils.xanLauncherMarkPainter

@Composable
fun appLogoLabel(logo: AppLogo): String = stringResource(
    when (logo) {
        AppLogo.WHITE -> R.string.app_logo_white
        AppLogo.GOLD -> R.string.app_logo_gold
        AppLogo.HORIZON -> R.string.app_logo_horizon
        AppLogo.AURORA -> R.string.app_logo_aurora
        AppLogo.SUNSET -> R.string.app_logo_sunset
        AppLogo.GREEN -> R.string.app_logo_green
        AppLogo.PRISM -> R.string.app_logo_prism
        AppLogo.LILAC -> R.string.app_logo_lilac
        AppLogo.BLUE -> R.string.app_logo_blue
    },
)

@Composable
fun AppLogoPicker(current: AppLogo, onSelect: (AppLogo) -> Unit, onDismiss: () -> Unit) {
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.app_logo_picker)) },
        modifier = Modifier.selectableGroup(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            AppLogo.entries.forEach { logo ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = logo == current, role = Role.RadioButton) { onSelect(logo) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RadioButton(selected = logo == current, onClick = null)
                    Box(
                        Modifier.size(44.dp).background(
                            Color(0xFF121212),
                            RoundedCornerShape(12.dp),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            xanLauncherMarkPainter(logo),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    Text(appLogoLabel(logo))
                }
            }
        }
    }
}
