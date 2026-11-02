package com.dualcast.live.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage
import com.dualcast.live.BuildConfig
import com.dualcast.live.data.model.LiveStatus
import com.dualcast.live.data.model.RecentRoom
import com.dualcast.live.data.model.RoomCombo
import com.dualcast.live.data.model.RoomInfo
import com.dualcast.live.ui.HomeUiState
import com.dualcast.live.ui.Slot
import com.dualcast.live.ui.SourceState
import com.dualcast.live.ui.components.GradientBackground
import com.dualcast.live.ui.components.PillTag
import com.dualcast.live.ui.components.SlotBadge
import com.dualcast.live.ui.components.pressable
import com.dualcast.live.ui.components.safeBottomPadding
import com.dualcast.live.ui.components.safeTopPadding

@Composable
fun HomeScreen(
    state: HomeUiState,
    onInputChange: (Slot, String) -> Unit,
    onResolve: (Slot) -> Unit,
    onClear: (Slot) -> Unit,
    onSwap: () -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onUseRecent: (RecentRoom) -> Unit,
    onStartCombo: (RoomCombo) -> Unit,
    onDeleteCombo: (RoomCombo) -> Unit,
    modifier: Modifier = Modifier,
) {
    GradientBackground(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val scheme = MaterialTheme.colorScheme
            // 主流手机横屏约 700~760dp 宽，阈值取 600 才能稳定触发双列；
            // 竖屏一般 400dp 左右，不会误判。
            val wide = maxWidth >= 600.dp
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 900.dp)
                    .fillMaxSize()
                    .safeTopPadding(),
                // 底部要给常驻的开始按钮留位置，滚动到底也不会被盖住
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { HomeHeader(compact = wide, onOpenSettings = onOpenSettings) }
                if (state.combos.isNotEmpty()) {
                    item { ComboSection(state.combos, onStartCombo, onDeleteCombo) }
                }
                if (wide) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.weight(1f)) {
                                SourceCard(
                                    slot = Slot.A,
                                    state = state.a,
                                    onInputChange = onInputChange,
                                    onResolve = onResolve,
                                    onClear = onClear,
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                SourceCard(
                                    slot = Slot.B,
                                    state = state.b,
                                    onInputChange = onInputChange,
                                    onResolve = onResolve,
                                    onClear = onClear,
                                )
                            }
                        }
                    }
                    item { SwapRow(onSwap) }
                } else {
                    item {
                        SourceCard(
                            slot = Slot.A,
                            state = state.a,
                            onInputChange = onInputChange,
                            onResolve = onResolve,
                            onClear = onClear,
                        )
                    }
                    item { SwapRow(onSwap) }
                    item {
                        SourceCard(
                            slot = Slot.B,
                            state = state.b,
                            onInputChange = onInputChange,
                            onResolve = onResolve,
                            onClear = onClear,
                        )
                    }
                }
                if (state.recent.isNotEmpty()) {
                    item { RecentSection(state.recent, onUseRecent) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // 开始叠播常驻底部：填完房间号不用再往下找按钮
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to scheme.surfaceContainer.copy(alpha = 0.94f),
                            1f to scheme.surfaceContainer,
                        )
                    )
                    .safeBottomPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    StartSection(
                        ready = state.a.ready && state.b.ready,
                        playable = state.a.room?.liveStatus?.isPlayable == true &&
                            state.b.room?.liveStatus?.isPlayable == true,
                        starting = state.starting,
                        error = state.startError,
                        onStart = onStart,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(compact: Boolean, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "叠播",
                style = if (compact) {
                    MaterialTheme.typography.headlineLarge
                } else {
                    MaterialTheme.typography.displaySmall
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "A 的弹幕，盖到 B 的画面上",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pressable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceCard(
    slot: Slot,
    state: SourceState,
    onInputChange: (Slot, String) -> Unit,
    onResolve: (Slot) -> Unit,
    onClear: (Slot) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (slot == Slot.A) scheme.secondaryContainer else scheme.primaryContainer
    val onContainer = if (slot == Slot.A) scheme.onSecondaryContainer else scheme.onPrimaryContainer
    val badge = if (slot == Slot.A) scheme.secondary else scheme.primary
    val badgeText = if (slot == Slot.A) scheme.onSecondary else scheme.onPrimary
    val role = if (slot == Slot.A) "弹幕 + 声音" else "画面 + 声音"
    val hint = if (slot == Slot.A) "A 房间负责弹幕和它的声音" else "B 房间负责画面，声音也保留"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SlotBadge(
                    label = if (slot == Slot.A) "A" else "B",
                    container = badge,
                    contentColor = badgeText,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = role,
                        style = MaterialTheme.typography.titleLarge,
                        color = onContainer,
                    )
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.86f),
                    )
                }
            }

            RoomInput(
                slot = slot,
                state = state,
                onInputChange = onInputChange,
                onResolve = onResolve,
                accent = onContainer,
                fieldContainer = scheme.surface.copy(alpha = 0.55f),
            )

            AnimatedVisibility(
                visible = state.room != null,
                enter = fadeIn() + androidx.compose.animation.expandVertically(),
                exit = fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                state.room?.let { room ->
                    RoomPreview(room) { onClear(slot) }
                }
            }

            if (state.error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "重试",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onErrorContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onResolve(slot) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomInput(
    slot: Slot,
    state: SourceState,
    onInputChange: (Slot, String) -> Unit,
    onResolve: (Slot) -> Unit,
    accent: Color,
    fieldContainer: Color,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.input,
        onValueChange = { onInputChange(slot, it) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        placeholder = {
            Text(
                text = "直播间号或 live.bilibili.com 链接",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        trailingIcon = {
            when {
                state.resolving -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
                state.input.isNotBlank() -> IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        onResolve(slot)
                    }
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "解析", tint = accent)
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
            onResolve(slot)
        }),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = fieldContainer,
            unfocusedContainerColor = fieldContainer,
            focusedBorderColor = accent.copy(alpha = 0.6f),
            unfocusedBorderColor = accent.copy(alpha = 0.2f),
        ),
    )
}

@Composable
private fun RoomPreview(room: RoomInfo, onClear: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.surface.copy(alpha = 0.62f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = room.coverUrl.ifBlank { room.keyframeUrl },
            contentDescription = null,
            modifier = Modifier
                .width(76.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceContainerHighest),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = room.title,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 主播名占用剩余宽度并单行省略。之前三个都按内容撑开，
                // 窄卡片下会被一起压到一字一行，看起来像竖排。
                Text(
                    text = room.anchor,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PillTag(
                    text = room.liveStatus.label(),
                    container = if (room.liveStatus.isPlayable) scheme.errorContainer else scheme.surfaceContainerHighest,
                    contentColor = if (room.liveStatus.isPlayable) scheme.onErrorContainer else scheme.onSurfaceVariant,
                )
                if (room.onlineText.isNotBlank()) {
                    Text(
                        room.onlineText,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "清除",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun LiveStatus.label(): String = when (this) {
    LiveStatus.LIVE -> "直播中"
    LiveStatus.ROUND_PLAY -> "轮播"
    LiveStatus.OFFLINE -> "未开播"
    LiveStatus.UNKNOWN -> "未知"
}

@Composable
private fun SwapRow(onSwap: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        )
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pressable(onClick = onSwap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.SwapVert,
                contentDescription = "交换 A / B",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        )
    }
}

@Composable
private fun StartSection(
    ready: Boolean,
    playable: Boolean,
    starting: Boolean,
    error: String?,
    onStart: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (ready) 1f else 0.98f, label = "startScale")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (error != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(scheme.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onErrorContainer,
                )
            }
        }
        Button(
            onClick = onStart,
            enabled = ready && playable && !starting,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(horizontal = 24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
                disabledContainerColor = scheme.surfaceContainerHighest,
                disabledContentColor = scheme.onSurfaceVariant,
            ),
        ) {
            if (starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = scheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("正在解析两路直播…", style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始叠播", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (ready && !playable) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = scheme.error,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "有一个房间当前未开播，开播后即可叠播",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.error,
                )
            }
        }
    }
}

@Composable
private fun ComboSection(
    combos: List<RoomCombo>,
    onStart: (RoomCombo) -> Unit,
    onDelete: (RoomCombo) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "我的组合",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "点一下直接开播",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(combos, key = { "${it.aRoomId}-${it.bRoomId}" }) { combo ->
                Card(
                    modifier = Modifier
                        .width(228.dp)
                        .pressable { onStart(combo) },
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        Modifier.padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ComboLine(
                                badge = "A",
                                badgeColor = scheme.secondary,
                                badgeText = scheme.onSecondary,
                                title = combo.aTitle,
                                subtitle = combo.aAnchor,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .pressable { onDelete(combo) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "删除组合",
                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        ComboLine(
                            badge = "B",
                            badgeColor = scheme.primary,
                            badgeText = scheme.onPrimary,
                            title = combo.bTitle,
                            subtitle = combo.bAnchor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComboLine(
    badge: String,
    badgeColor: Color,
    badgeText: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(badgeColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = badgeText,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "未命名直播间" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentSection(recent: List<RecentRoom>, onUseRecent: (RecentRoom) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "最近看过",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent, key = { it.input }) { room ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .pressable { onUseRecent(room) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = room.title.ifBlank { room.input },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(140.dp),
                    )
                }
            }
        }
    }
}
