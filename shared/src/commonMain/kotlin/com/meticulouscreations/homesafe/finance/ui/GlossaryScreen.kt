package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.Explainers

/**
 * The jargon buster: every term the finance app uses, in plain words, grouped by topic and
 * searchable. A row opens that term's full explainer.
 */
@Composable
internal fun GlossaryScreen(contentPadding: PaddingValues) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val open = LocalExplainer.current
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val matches = Explainers.glossary.filter { e ->
        q.isEmpty() || e.title.lowercase().contains(q) || e.technical.lowercase().contains(q) || e.oneLiner.lowercase().contains(q)
    }
    LazyColumn(contentPadding = contentPadding) {
        item {
            Column(Modifier.padding(horizontal = PageGutter, vertical = 8.dp)) {
                Text("Jargon buster", style = type.title, color = colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text("Every term in this app, in plain English. Tap one for the full story.", style = type.body, color = colors.textSecondary)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Search, e.g. \"yield curve\"", style = type.body, color = colors.textTertiary)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = type.body.copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search terms" },
                        )
                    }
                }
            }
        }
        var lastTopic: Any? = null
        matches.forEach { e ->
            if (e.topic != lastTopic) {
                lastTopic = e.topic
                item(key = "topic-${e.topic.name}") {
                    Row(Modifier.padding(horizontal = PageGutter).padding(top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(topicColor(e.topic, colors)))
                        Spacer(Modifier.width(8.dp))
                        Text(e.topic.title.uppercase(), style = type.micro, color = colors.textSecondary)
                    }
                }
            }
            item(key = "term-${e.id}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Explain ${e.title}") { open(e.id) }
                        .padding(horizontal = PageGutter, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(topicIcon(e.topic), contentDescription = null, tint = topicColor(e.topic, colors), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(e.title, style = type.bodyStrong, color = colors.textPrimary)
                            if (e.technical.isNotEmpty() && e.technical != e.title) {
                                Spacer(Modifier.width(6.dp))
                                Text(e.technical, style = type.micro, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(e.oneLiner, style = type.label, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            item { FinePrint("Nothing matches \"$query\".") }
        }
    }
}
