package com.saha.videodownloader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saha.videodownloader.model.TabState
import com.saha.videodownloader.viewmodel.TabListReducer

/**
 * Compact horizontal tab strip, sized to sit under the URL row inside the
 * existing top-chrome Surface.
 */
@Composable
fun TabStrip(
    tabs: List<TabState>,
    activeTabId: Long?,
    detectedCountFor: (Long) -> Int,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs, key = { it.id }) { tab ->
            TabChip(
                tab = tab,
                active = tab.id == activeTabId,
                detectedCount = detectedCountFor(tab.id),
                onSelect = { onSelect(tab.id) },
                onClose = { onClose(tab.id) }
            )
        }
        item(key = "new-tab") {
            NewTabChip(
                enabled = tabs.size < TabListReducer.MAX_TABS,
                onClick = onNewTab
            )
        }
    }
}

@Composable
private fun TabChip(
    tab: TabState,
    active: Boolean,
    detectedCount: Int,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = if (active) 3.dp else 0.dp,
        modifier = Modifier
            .height(28.dp)
            .widthIn(min = 76.dp, max = 148.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when {
                tab.isCrashed -> Text("⚠", fontSize = 11.sp)
                tab.isLoading -> Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Text(
                text = tab.label,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f, fill = false)
            )
            if (detectedCount > 0) {
                Text(
                    text = "$detectedCount",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            // Only the active tab gets a close target — a 28.dp chip is too
            // small for two reliable tap areas. Use "จัดการแท็บ" for the rest.
            if (active) {
                Text(
                    text = "✕",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onClose)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun NewTabChip(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "＋",
                fontSize = 14.sp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        }
    }
}

/** Full tab list — same shape as the existing history dialog. */
@Composable
fun TabsDialog(
    tabs: List<TabState>,
    activeTabId: Long?,
    detectedCountFor: (Long) -> Int,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("แท็บ (${tabs.size}/${TabListReducer.MAX_TABS})") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(tab.id) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (tab.id == activeTabId) "●" else "○",
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = tab.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (tab.id == activeTabId) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                            modifier = Modifier.weight(1f)
                        )
                        val count = detectedCountFor(tab.id)
                        if (count > 0) {
                            Text(
                                text = "วิดีโอ $count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                        TextButton(onClick = { onClose(tab.id) }) {
                            Text("✕", fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("ปิด") }
        },
        dismissButton = {
            TextButton(
                onClick = onNewTab,
                enabled = tabs.size < TabListReducer.MAX_TABS
            ) {
                Text("＋ แท็บใหม่")
            }
        }
    )
}
