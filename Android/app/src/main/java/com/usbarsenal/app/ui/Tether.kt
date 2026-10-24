package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.Cache
import com.usbarsenal.app.backend.readDroidspaces
import com.usbarsenal.app.backend.readUplinks
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType

/*
 * A setting typed in: its input on the right, and under it [problem] while what is typed is not
 * [valid]. [filter] drops what the value cannot hold, so it never shows. The text stays here
 * until it is valid, or empty (the default), and only then goes to the setup: one left invalid
 * is dropped on the way out and never applied. A change to the setup (Discard) resets it.
 */
@Composable
private fun TypedRow(
    label: String,
    value: String,
    placeholder: String,
    problem: String,
    valid: (String) -> Boolean,
    keyboard: KeyboardOptions,
    filter: (String) -> String,
    onChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val ok = text.isBlank() || valid(text)
    // Set when the field is left, not on every keystroke: a name typed in steps was set in
    // steps, one `setup set` per pause. Leaving is a tap elsewhere, the keyboard going (Back
    // closes it first, so the next Back finds the value set and can ask about it), or the screen
    val commit by rememberUpdatedState({ if (ok && text != value) onChange(text) })
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(focused, keyboardUp) { if (focused && !keyboardUp) commit() }
    DisposableEffect(Unit) { onDispose { commit() } }
    Kv(label) {
        Input(
            text,
            { text = filter(it) },
            Modifier.width(200.dp).padding(vertical = 4.dp).onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) commit()
            },
            placeholder = placeholder,
            keyboard = keyboard,
        )
    }
    if (!ok) Warn(problem)
}

// The URI keyboard has the digits, '.' and '/' an address needs
private val ADDRESS_KEYS = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
private val NAME_KEYS = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

/*
 * Tether: what the next start brings up, every option `tether start` takes, around the live
 * chain and its counters. It only builds the setup Home's switch starts; a change while tether
 * runs is applied, or discarded, on the way back. The gateway is offered only with Droidspaces installed, and
 * only its running containers can be chosen.
 */
@Composable
fun TetherScreen(back: () -> Unit, openLog: () -> Unit) {
    val plan = tetherPlan()
    fun set(next: TetherPlan) = setPlan(next)

    // Droidspaces as last read, re-read on each visit: its containers change
    val ds = Gadget.droidspaces
    LaunchedEffect(Unit) { readDroidspaces().let { Gadget.droidspaces = it; Cache.droidspaces = it } }
    val containers = ds?.running
    BackHandler(onBack = back)

    val caps = Gadget.caps
    val profile = Gadget.profile
    val live = Gadget.status?.tether
    val on = live != null
    val function = live?.function ?: tetherFunction(plan, profile, caps)
    val shake = rememberShake()
    val fault = Gadget.fault?.takeIf { it.where == Fn.TETHER }
    LaunchedEffect(fault) { if (fault != null) shake.fire() }
    var choosing by rememberSaveable { mutableStateOf(false) }
    // The uplinks as last listed, re-read each time the sheet opens: they come and go with Wi-Fi and data
    var pickingUp by rememberSaveable { mutableStateOf(false) }
    var uplinks by remember { mutableStateOf(Cache.uplinks.orEmpty()) }
    LaunchedEffect(pickingUp) { if (pickingUp) readUplinks()?.let { uplinks = it; Cache.uplinks = it } }
    // An empty upstream is the profile's, or the backend's own pick
    val auto = profile["tether_upstream"] ?: "auto"

    // The chain shows what runs; the rows show what the next start brings up
    val gatewayLive = live?.gateway ?: plan.gateway
    val address = live?.address?.ifEmpty { null } ?: plan.address.ifBlank { profile["tether_address"] ?: DEFAULT_ADDRESS }
    Box(Modifier.fillMaxSize()) {
        Screen {
            Top { BackKey() }
            Title("Tether")
            fault?.let { Fault(it.message, onLog = openLog, modifier = Modifier.shake(shake).opener("tether/fault")) }
            Column(verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
                Segs(
                    listOf("NCM", "RNDIS"),
                    if (tetherFunction(plan, profile, caps) == "rndis") 1 else 0,
                    { set(plan.copy(function = if (it == 1) "rndis" else "ncm")) },
                    Modifier.focusRequester(focusOn()),
                    enabled = { if (it == 0) caps.ncmOk() else caps.rndisOk() },
                )
                Column {
                    Node("Host")
                    Wire(function, moving = on)
                    Node("Phone", live?.iface ?: "off", inverted = true)
                    Wire(
                        when {
                            gatewayLive -> "virtual cable"
                            live != null && !live.running -> "no network side"
                            else -> "masquerade"
                        },
                        up = true,
                        moving = on,
                    )
                    // Values are live only: off, the rows below already say what a start would use
                    if (gatewayLive) {
                        val name = live?.gatewayContainer?.ifEmpty { null } ?: plan.container.ifEmpty { "Container" }
                        Node(name, if (live?.gatewayWaiting == true) "waiting" else live?.gatewayIface)
                    } else {
                        Node("Upstream", live?.upstream?.ifEmpty { null })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
                    ValueBox("Tx", live?.txBytes?.takeIf { it >= 0 }?.let(::sizeText) ?: "-", Modifier.weight(1f))
                    ValueBox("Rx", live?.rxBytes?.takeIf { it >= 0 }?.let(::sizeText) ?: "-", Modifier.weight(1f))
                }
                // What the next start brings up
                Column {
                    Group("Configure")
                    // A gateway is a container: switching it on picks one, off forgets it. Without
                    // Droidspaces there is no container to hand the link to, so the switch is held
                    Kv("Gateway") {
                        if (ds?.installed == false) Text("no droidspaces", LcdType.data)
                        Dip(plan.gateway, { if (it) choosing = true else set(plan.copy(container = "")) }, "Gateway mode", enabled = ds?.installed != false)
                    }
                    if (plan.gateway) {
                        val stopped = containers != null && plan.container !in containers
                        LinkRow("Container", if (stopped) "${plan.container} · not running" else plan.container, { choosing = true }, Modifier.opener("tether/container"))
                        TypedRow("Its iface", plan.iface, "usb0", "an interface name, up to 15 characters", ::validIface, NAME_KEYS, ::ifaceInput) {
                            set(plan.copy(iface = it))
                        }
                        Kv("Host IPv4") { Text("by the container", LcdType.data) }
                    } else {
                        // Without NAT in the kernel only --no-net can start
                        Kv("DHCP and NAT") {
                            if (!caps.natOk()) Text("no nat in this kernel", LcdType.data)
                            Dip(plan.net && caps.natOk(), { set(plan.copy(net = it)) }, "DHCP and NAT", enabled = caps.natOk())
                        }
                        if (plan.net) {
                            TypedRow(
                                "Address", plan.address, profile["tether_address"] ?: DEFAULT_ADDRESS,
                                "a host address with a /8 to /30 prefix", ::validAddress, ADDRESS_KEYS,
                                { it.filter { c -> c.isDigit() || c in "./" }.take(18) },
                            ) { set(plan.copy(address = it)) }
                            Kv("Host IPv4") { Text(hostOf(address.trim()) ?: "-", LcdType.data) }
                            Kv("Host IPv6") { Text(live?.ipv6?.ifEmpty { null } ?: "-", LcdType.data) }
                            TypedRow(
                                "DNS", plan.dns, profile["tether_dns"]?.replace(",", ", ") ?: "1.1.1.1, 8.8.8.8",
                                "IPv4 addresses, comma-separated, 127 characters at most", ::validDns, ADDRESS_KEYS,
                                { it.filter { c -> c.isDigit() || c in ".," }.take(127) },
                            ) { set(plan.copy(dns = it)) }
                            LinkRow("Upstream", plan.upstream.ifBlank { auto }, { pickingUp = true }, Modifier.opener("tether/upstream"))
                        }
                    }
                }
            }
        }
        UnappliedSheet(Fn.TETHER, back)
        ChoiceSheet(
            choosing, "Container", containers.orEmpty(), plan.container,
            if (ds == null) "Reading Droidspaces' containers" else if (containers == null) "Cannot read Droidspaces' containers" else "No containers are running",
            onPick = {
                set(plan.copy(container = it))
                choosing = false
            },
            onDismiss = { choosing = false },
        )
        // What is set stays listed while it is down, or when the command line set it
        ChoiceSheet(
            pickingUp, "Upstream", (listOf(auto) + listOfNotNull(plan.upstream.ifBlank { null }) + uplinks).distinct(), plan.upstream.ifBlank { auto }, "",
            onPick = {
                set(plan.copy(upstream = if (it == auto) "" else it))
                pickingUp = false
            },
            onDismiss = { pickingUp = false },
        )
    }
}
