package org.orca.advisory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.orca.advisory.ui.LocalPalette

/**
 * Settings — one flat list, no sub-pages.
 *
 * <p>Flat on purpose. Every review of app design for older and
 * low-literacy users says the same thing: flatten the hierarchy, prefer
 * linear navigation, keep the path short. A settings screen with
 * categories that open further screens is where a crew gives up.
 *
 * <p>Each row says what it CHANGES, not what it is called. "Every minute
 * — normal" beats "GPS interval", because the second one requires already
 * knowing what a GPS interval costs you.
 */
@Composable
fun SettingsScreen(
    advisory: OrcaRepository.Advisory?,
    values: Settings.Values,
    onChange: (Settings.Values) -> Unit,
) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    val context = LocalContext.current

    // The number being typed, before ADD commits it to the list.
    var draft by remember { mutableStateOf("") }

    fun set(v: Settings.Values) {
        Settings.save(context, v)
        onChange(v)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // --- home harbour -------------------------------------------------
        Section(bi("என் துறைமுகம் / MY HARBOUR", lang)) {
            Text(
                "ORCA opens on this harbour's verdict. Without it, every crew on the " +
                    "coast is shown Chennai.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            val zones = advisory?.zones?.map { it.zone } ?: emptyList()
            if (zones.isEmpty()) {
                Text(
                    "No advisory stored yet, so there are no harbours to choose from.",
                    color = p.caution, fontSize = 13.sp,
                )
            } else {
                zones.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { z ->
                            Chip(z, values.homeZone == z, Modifier.weight(1f)) {
                                set(values.copy(homeZone = if (values.homeZone == z) null else z))
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // --- who to call ----------------------------------------------------
        Section(bi("யாருக்கு அழைப்பு / WHO TO ALERT", lang)) {
            Text(
                "Everyone here gets the SOS on ONE press. Add the boat owner, someone " +
                    "ashore, and the harbour — whoever has signal is the one who answers.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))

            values.contacts.forEachIndexed { i, number ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}.", color = p.muted, fontSize = 15.sp,
                        modifier = Modifier.width(28.dp),
                    )
                    Text(
                        number, color = p.ink, fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    // Removal is a plain button, not a swipe. A swipe is a
                    // gesture people discover by accident and then cannot
                    // find again, and this list is safety equipment.
                    Text(
                        bi("நீக்கு · Remove", lang), color = p.deny, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                set(values.copy(contacts = values.contacts.filterIndexed { j, _ -> j != i }))
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                HorizontalDivider(color = p.line)
            }

            if (values.contacts.isEmpty()) {
                Text(
                    bi("எண் எதுவும் இல்லை · No numbers yet — the SOS has nowhere to go.", lang),
                    color = p.deny, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.filter { c -> c.isDigit() || c == '+' }.take(15) },
                    label = { Text("Add a phone number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = p.ink, unfocusedTextColor = p.ink,
                        focusedBorderColor = p.accent, unfocusedBorderColor = p.line,
                        focusedLabelColor = p.accent, unfocusedLabelColor = p.muted,
                        cursorColor = p.accent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                val ready = draft.length >= 3 && draft !in values.contacts
                Text(
                    bi("சேர் · ADD", lang),
                    color = if (ready) p.onAccent else p.muted,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (ready) p.accent else p.line)
                        .clickable(enabled = ready) {
                            set(values.copy(contacts = values.contacts + draft))
                            draft = ""
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = values.boatId,
                onValueChange = { set(values.copy(boatId = it.take(24))) },
                label = { Text("Boat registration (optional)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = p.ink, unfocusedTextColor = p.ink,
                    focusedBorderColor = p.accent, unfocusedBorderColor = p.line,
                    focusedLabelColor = p.accent, unfocusedLabelColor = p.muted,
                    cursorColor = p.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The SOS sends the moment you press it — one press, no confirmation — " +
                    "carrying your GPS position. If there is no fix it says POSITION " +
                    "UNKNOWN rather than guessing one.",
                color = p.muted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }

        // --- reading ---------------------------------------------------------
        Section(bi("எழுத்து அளவு / TEXT SIZE", lang)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.TextSize.entries.forEach { t ->
                    Chip(t.label, values.textSize == t, Modifier.weight(1f)) {
                        set(values.copy(textSize = t))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Changes every screen at once. Wet hands, glare and fifty-year-old eyes " +
                    "are the normal case here, not the exception.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }

        // --- audio -------------------------------------------------------------
        Section(bi("குரலில் படி / READ ALOUD", lang)) {
            ToggleRow(
                Icons.Filled.VolumeUp,
                "Speak the verdict aloud",
                "Says the answer instead of only printing it. Written text is the part " +
                    "that fails first for a crew who did not finish school.",
                values.speakAloud,
            ) { set(values.copy(speakAloud = it)) }
        }

        // --- battery -------------------------------------------------------------
        Section(bi("பேட்டரி / BATTERY", lang)) {
            Text(
                "GPS is the expensive part of a five-day trip. This is how often the " +
                    "background watch checks where you are.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            Settings.GpsRate.entries.forEach { r ->
                Chip(r.label, values.gpsRate == r, Modifier.fillMaxWidth()) {
                    set(values.copy(gpsRate = r))
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "At 6 knots a boat covers nearly a nautical mile in five minutes, so the " +
                    "saver setting is for when the boundary is far away.",
                color = p.caution, fontSize = 12.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                Icons.Filled.ScreenLockPortrait,
                "Keep the screen on while ORCA is open",
                "Useful mounted in a wheelhouse. Costs battery.",
                values.keepScreenOn,
            ) { set(values.copy(keepScreenOn = it)) }
        }

        // --- hull ------------------------------------------------------------------
        Section(bi("படகு வகை / BOAT TYPE", lang)) {
            Text(
                "Used only by the drift calculation. A hull that catches more wind drifts " +
                    "further, so this changes where the search box goes.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            Settings.Hull.entries.forEach { h ->
                Chip(h.label, values.hull == h, Modifier.fillMaxWidth()) {
                    set(values.copy(hull = h))
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "These are the categories Allen & Plourde actually measured (USCG " +
                    "CG-D-08-99). ORCA does not offer \"vallam\" or \"FRP boat\" because " +
                    "nobody has measured their leeway, and a made-up coefficient would " +
                    "make up the position it produces.",
                color = p.muted, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }

        Section(bi("பற்றி / ABOUT", lang)) {
            Text(
                "Language and day/dusk/night are the two chips at the top of every " +
                    "screen — they are needed too often to live in here.",
                color = p.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, m: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    Box(
        m.clip(RoundedCornerShape(12.dp))
            .background(if (on) p.accent else p.panel)
            .border(1.dp, if (on) p.accent else p.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (on) p.onAccent else p.ink,
            fontSize = 14.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    detail: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val p = LocalPalette.current
    val lang = LocalLang.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = p.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = p.muted, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = p.onAccent, checkedTrackColor = p.accent,
            ),
        )
    }
}
