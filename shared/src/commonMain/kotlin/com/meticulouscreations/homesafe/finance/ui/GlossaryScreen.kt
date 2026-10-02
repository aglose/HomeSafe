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
import com.meticulouscreations.homesafe.finance.domain.Explainer
import com.meticulouscreations.homesafe.finance.domain.Explainers
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.explain_click_label
import homesafe.shared.generated.resources.glossary_no_matches
import homesafe.shared.generated.resources.glossary_search_description
import homesafe.shared.generated.resources.glossary_search_hint
import homesafe.shared.generated.resources.glossary_subtitle
import homesafe.shared.generated.resources.glossary_title
import org.jetbrains.compose.resources.stringResource

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
    // The words are resources, so they're read in the reader's language first; search and the
    // alphabetical order within each topic both work on what the reader sees.
    val terms = Explainers.glossary
        .map { e -> GlossaryTerm(e, stringResource(e.title), e.technical?.let { stringResource(it) }, stringResource(e.oneLiner)) }
        .sortedWith(compareBy({ it.explainer.topic.ordinal }, { it.title }))
    val matches = terms.filter { t ->
        q.isEmpty() || t.title.lowercase().contains(q) || t.technical.orEmpty().lowercase().contains(q) || t.oneLiner.lowercase().contains(q)
    }
    LazyColumn(contentPadding = contentPadding) {
        item {
            Column(Modifier.padding(horizontal = PageGutter, vertical = 8.dp)) {
                Text(stringResource(Res.string.glossary_title), style = type.title, color = colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.glossary_subtitle), style = type.body, color = colors.textSecondary)
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
                        if (query.isEmpty()) Text(stringResource(Res.string.glossary_search_hint), style = type.body, color = colors.textTertiary)
                        val searchDescription = stringResource(Res.string.glossary_search_description)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = type.body.copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = searchDescription },
                        )
                    }
                }
            }
        }
        var lastTopic: Any? = null
        matches.forEach { term ->
            val e = term.explainer
            if (e.topic != lastTopic) {
                lastTopic = e.topic
                item(key = "topic-${e.topic.name}") {
                    Row(Modifier.padding(horizontal = PageGutter).padding(top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(topicColor(e.topic, colors)))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(e.topic.title).uppercase(), style = type.micro, color = colors.textSecondary)
                    }
                }
            }
            item(key = "term-${e.id}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = stringResource(Res.string.explain_click_label, term.title)) { open(e.id) }
                        .padding(horizontal = PageGutter, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(topicIcon(e.topic), contentDescription = null, tint = topicColor(e.topic, colors), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(term.title, style = type.bodyStrong, color = colors.textPrimary)
                            if (term.technical != null && term.technical != term.title) {
                                Spacer(Modifier.width(6.dp))
                                Text(term.technical, style = type.micro, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(term.oneLiner, style = type.label, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            item { FinePrint(stringResource(Res.string.glossary_no_matches, query)) }
        }
    }
}

/** An explainer's glossary words, read in the reader's language so they can be searched and sorted. */
private data class GlossaryTerm(val explainer: Explainer, val title: String, val technical: String?, val oneLiner: String)
