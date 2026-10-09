package com.dedhapp3n.pokeldn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dedhapp3n.pokeldn.ui.theme.BevelShadow
import com.dedhapp3n.pokeldn.ui.theme.CreamPanel
import com.dedhapp3n.pokeldn.ui.theme.CreamPanelDark
import com.dedhapp3n.pokeldn.ui.theme.DeviceAmber
import com.dedhapp3n.pokeldn.ui.theme.DeviceBezel
import com.dedhapp3n.pokeldn.ui.theme.DeviceInk
import com.dedhapp3n.pokeldn.ui.theme.IndicatorCyan
import com.dedhapp3n.pokeldn.ui.theme.IndicatorGreen
import com.dedhapp3n.pokeldn.ui.theme.IndicatorRed
import com.dedhapp3n.pokeldn.ui.theme.ScreenBlack
import com.dedhapp3n.pokeldn.ui.theme.ScreenMuted
import com.dedhapp3n.pokeldn.ui.theme.ScreenText
import com.dedhapp3n.pokeldn.ui.theme.ShellRed
import com.dedhapp3n.pokeldn.ui.theme.ShellRedDark

internal enum class StatusTone { NEUTRAL, POSITIVE, WARNING, ERROR }
internal enum class DeviceButtonStyle { PRIMARY, SECONDARY, DISPLAY }

@Composable
internal fun CenteredDeviceList(modifier: Modifier, content: LazyListScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
internal fun DeviceScreenTitle(code: String?, title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        code?.let {
            Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = CreamPanelDark)
        }
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = CreamPanel)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = CreamPanelDark) }
    }
}

@Composable
internal fun BezelDisplay(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DeviceBezel,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(3.dp, BevelShadow),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IndicatorLight(IndicatorRed, 8.dp)
                Text(
                    label.uppercase(),
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CreamPanelDark,
                )
                repeat(4) {
                    Surface(
                        color = CreamPanelDark.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(start = 4.dp).size(width = 18.dp, height = 3.dp),
                    ) {}
                }
            }
            Surface(
                color = ScreenBlack,
                contentColor = ScreenText,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(2.dp, CreamPanelDark.copy(alpha = 0.65f)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun ShellPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CreamPanel,
        contentColor = DeviceInk,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(3.dp, DeviceBezel),
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Surface(color = ShellRed, shape = RoundedCornerShape(5.dp), modifier = Modifier.size(10.dp)) {}
                Text(
                    title.uppercase(),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = ShellRedDark,
                )
            }
            content()
        }
    }
}

@Composable
internal fun DiagnosticSection(
    number: String,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = CreamPanelDark,
        contentColor = DeviceInk,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(2.dp, DeviceBezel),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = ShellRedDark, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        number,
                        color = CreamPanel,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    title.uppercase(),
                    modifier = Modifier.padding(start = 9.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                )
            }
            content()
        }
    }
}

@Composable
internal fun StatusBadge(text: String, tone: StatusTone) {
    val colors = when (tone) {
        StatusTone.NEUTRAL -> CreamPanelDark to DeviceInk
        StatusTone.POSITIVE -> IndicatorGreen.copy(alpha = 0.24f) to DeviceInk
        StatusTone.WARNING -> DeviceAmber.copy(alpha = 0.42f) to DeviceInk
        StatusTone.ERROR -> IndicatorRed.copy(alpha = 0.22f) to ShellRedDark
    }
    Surface(color = colors.first, contentColor = colors.second, shape = RoundedCornerShape(7.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IndicatorLight(
                when (tone) {
                    StatusTone.NEUTRAL -> DeviceInk.copy(alpha = 0.55f)
                    StatusTone.POSITIVE -> IndicatorGreen
                    StatusTone.WARNING -> DeviceAmber
                    StatusTone.ERROR -> IndicatorRed
                },
                7.dp,
            )
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
internal fun DisplayStatus(text: String, tone: StatusTone) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IndicatorLight(
            when (tone) {
                StatusTone.NEUTRAL -> ScreenMuted
                StatusTone.POSITIVE -> IndicatorGreen
                StatusTone.WARNING -> DeviceAmber
                StatusTone.ERROR -> IndicatorRed
            },
            10.dp,
        )
        Text(text.uppercase(), color = ScreenText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
    }
}

@Composable
internal fun DeviceButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: DeviceButtonStyle = DeviceButtonStyle.PRIMARY,
) {
    val container = when (style) {
        DeviceButtonStyle.PRIMARY -> ShellRedDark
        DeviceButtonStyle.SECONDARY -> DeviceAmber
        DeviceButtonStyle.DISPLAY -> IndicatorCyan
    }
    val content = when (style) {
        DeviceButtonStyle.PRIMARY -> CreamPanel
        DeviceButtonStyle.SECONDARY, DeviceButtonStyle.DISPLAY -> DeviceInk
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, DeviceBezel),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 5.dp, pressedElevation = 1.dp),
    ) {
        Text(label.uppercase(), fontWeight = FontWeight.Black)
    }
}

@Composable
internal fun DetailLine(label: String, value: String, dark: Boolean = false) {
    val labelColor = if (dark) ScreenMuted else DeviceInk.copy(alpha = 0.68f)
    val valueColor = if (dark) ScreenText else DeviceInk
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label.uppercase(), color = labelColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.42f))
        Text(value, color = valueColor, modifier = Modifier.weight(0.58f))
    }
}

@Composable
internal fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun IndicatorLight(color: Color, size: Dp) {
    Surface(color = color, shape = CircleShape, border = BorderStroke(1.dp, DeviceBezel), modifier = Modifier.size(size)) {}
}
