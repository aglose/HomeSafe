package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.packages.domain.CameraVerdict
import com.meticulouscreations.homesafe.packages.domain.Carrier
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelStage
import com.meticulouscreations.homesafe.packages.domain.ParcelZone
import com.meticulouscreations.homesafe.packages.domain.PorchState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_open_parcel
import homesafe.shared.generated.resources.packages_signature_badge
import homesafe.shared.generated.resources.packages_surprise_badge
import org.jetbrains.compose.resources.stringResource

/** The one a list is made of: what it is, who it's for, where it has got to and when it comes. A surprise wears its wrapping. */
@Composable
internal fun ParcelCard(parcel: Parcel, today: Int, zone: ParcelZone, running: Boolean, onOpen: (String) -> Unit, modifier: Modifier = Modifier, index: Int = 0, showRoute: Boolean = !parcel.delivered) {
    val colors = PackagesTheme.colors
    val tint = parcel.tint()
    val shape = RoundedCornerShape(20.dp)
    val name = parcelName(parcel)
    val status = parcelStatus(parcel, today, zone)
    val open = stringResource(Res.string.packages_open_parcel)
    Box(
        modifier
            .fillMaxWidth()
            .riseIn(index)
            .pressable({ onOpen(parcel.id) }, open)
            .semantics { contentDescription = "$name, $status" }
            .testTag("parcel_${parcel.id}")
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, if (parcel.needsAttention) colors.warn.copy(alpha = 0.55f) else colors.hairline, shape),
    ) {
        if (parcel.masked) GiftWrap(Modifier.matchParentSize(), seed = (parcel.id.hashCode() and 255) / 255f, running = running)
        if (parcel.masked) Box(Modifier.matchParentSize().background(Color(0x66140A24)))
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = if (showRoute) 7.dp else 13.dp).clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ParcelGlyph(parcel, tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = PackagesTheme.type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (parcel.pinned) Icon(Icons.Filled.PushPin, contentDescription = null, tint = colors.tape, modifier = Modifier.padding(start = 5.dp).size(13.dp))
                    }
                    Text(status, style = PackagesTheme.type.label, color = if (parcel.needsAttention) colors.warn else colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (parcel.delivered && parcel.verdict != null && !parcel.masked) VerdictDot(parcel.verdict)
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                carrierName(parcel)?.let { PackagesPill(it, colors.carrier(parcel.carrier)) }
                if (parcel.owner.isNotBlank()) PackagesPill(PackagesFormat.person(parcel.owner), colors.textMuted)
                if (parcel.surprise && !parcel.masked) PackagesPill(stringResource(Res.string.packages_surprise_badge), colors.gift, icon = Icons.Filled.CardGiftcard)
                if (parcel.signature && !parcel.delivered) PackagesPill(stringResource(Res.string.packages_signature_badge), colors.warn, icon = Icons.Filled.Draw)
            }
            if (showRoute) {
                Spacer(Modifier.height(5.dp))
                RouteStrip(parcel.progress(today), tint, running = running, margin = 4.dp)
            }
        }
    }
}

/** The name a carrier goes by, for its badge. */
internal fun carrierName(parcel: Parcel): String? = parcel.carrier.label.ifEmpty { parcel.carrierName?.replaceFirstChar { it.uppercase() } }

/** A tinted disc with what it most needs you to know. */
@Composable
internal fun ParcelGlyph(parcel: Parcel, tint: Color, size: Int = 42) {
    val icon: ImageVector = when {
        parcel.masked -> Icons.Filled.CardGiftcard
        parcel.needsAttention -> Icons.Filled.ErrorOutline
        parcel.stage == ParcelStage.DELIVERED && parcel.porch == PorchState.ON_PORCH -> Icons.Filled.Inventory2
        parcel.stage == ParcelStage.DELIVERED -> Icons.Filled.CheckCircle
        parcel.stage == ParcelStage.OUT_FOR_DELIVERY -> Icons.Filled.LocalShipping
        parcel.carrier == Carrier.OTHER && parcel.stage == ParcelStage.ORDERED -> Icons.Outlined.Inventory2
        else -> Icons.Filled.LocalShipping
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** The camera's word, as a small mark in a card's corner. */
@Composable
internal fun VerdictDot(verdict: CameraVerdict) {
    val colors = PackagesTheme.colors
    val tint = colors.verdict(verdict)
    Box(Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(
            if (verdict == CameraVerdict.NOT_SEEN) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
            contentDescription = stringResource(verdictShort(verdict)),
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A list's heading: small capitals, how many, and a line of colour. */
@Composable
internal fun SectionHead(text: String, count: Int, tint: Color, modifier: Modifier = Modifier) {
    val colors = PackagesTheme.colors
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = PackagesTheme.type.micro, color = colors.textMuted, modifier = Modifier.semantics { })
        Spacer(Modifier.width(7.dp))
        Text(count.toString(), style = PackagesTheme.type.micro, color = colors.textFaint)
    }
}
