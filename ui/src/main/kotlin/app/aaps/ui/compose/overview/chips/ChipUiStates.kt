package app.aaps.ui.compose.overview.chips

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.interfaces.overview.graph.TbrState
import app.aaps.core.ui.compose.icons.IcArrowFlat
import app.aaps.core.ui.compose.icons.IcArrowSimpleDown
import app.aaps.core.ui.compose.icons.IcArrowSimpleUp

@Immutable
data class IobUiState(
    val text: String = "",
    val iobTotal: Double = 0.0
)

@Immutable
data class CobUiState(
    val text: String = "",
    val carbsReq: Int = 0,
    val cobValue: Double = 0.0
)

@Immutable
data class SensitivityUiState(
    val asText: String = "",
    val isfFrom: String = "",
    val isfTo: String = "",
    val dialogText: String = "",
    val ratio: Double = 1.0,
    val isEnabled: Boolean = true,
    val hasData: Boolean = false
)

@Stable
@Immutable
data class TbrUiState(
    val rate: Double = 0.0,
    val profileBasal: Double = 0.0,
    val isAbsolute: Boolean = true,
    val arrow: TbrArrow = TbrArrow.FLAT
) {
    fun toTbrState(): TbrState = when (arrow) {
        TbrArrow.UP   -> TbrState.HIGH
        TbrArrow.DOWN -> TbrState.LOW
        TbrArrow.FLAT -> TbrState.NONE
    }
}

data class SmbUiState(
    val text: String = "",
    val hasData: Boolean = false
)

enum class TbrArrow(val icon: ImageVector) {
    UP(IcArrowSimpleUp),
    DOWN(IcArrowSimpleDown),
    FLAT(IcArrowFlat)
}
