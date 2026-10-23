package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.Droidspaces
import com.usbarsenal.app.backend.readDroidspaces
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType

// A setting typed in: its input on the right, and under it what is wrong with it, if anything
@Composable
private fun TypedRow(label: String, value: String, placeholder: String, problem: String?, onChange: (String) -> Unit) {
    Kv(label) { Input(value, onChange, Modifier.width(200.dp).padding(vertical = 4.dp), placeholder = placeholder) }
    if (problem != null) Warn(problem)
}

/*
 * Tether: what the next start brings up, every option `tether start` takes, around the live
 * chain and its counters. It only builds the setup Home's switch starts; a change while tether
 * runs is applied, or discarded, on the way back. The gateway is offered only with Droidspaces installed, and
 * only its running containers can be chosen.
 */
@Composable
fun TetherScreen(back: () -> Unit, openLog: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TetherSetup.load(context) }
    val plan = TetherSetup.plan
    fun set(next: TetherPlan) = TetherSetup.set(context, next)

    // Null until read. Without Droidspaces there is nothing to hand the link to, so a saved
    // gateway goes at once; until then the gateway rows stay as saved, so nothing jumps
    var ds by remember { mutableStateOf<Droidspaces?>(null) }
    LaunchedEffect(Unit) {
        val read = readDroidspaces()
        ds = read
        if (!read.installed && TetherSetup.plan.gateway) set(TetherSetup.plan.copy(gateway = false))
    }
    val containers = ds?.running
    // Leaving with no running container chosen turns the gateway off; a list that could not be
    // read is not taken to mean none
    val leaving by rememberUpdatedState {
        if (plan.gateway && containers != null && plan.container !in containers) set(plan.copy(gateway = false))
    }
    DisposableEffect(Unit) { onDispose { leaving() } }
    BackHandler(onBack = back)
    PollStatus()

    val caps = Gadget.caps
    val profile = Gadget.profile
    val live = Gadget.status?.tether
    val on = live != null
    val function = live?.function ?: tetherFunction(plan, profile, caps)
    val shake = rememberShake()
    val fault = Gadget.fault?.takeIf { it.where == Fn.TETHER }
    LaunchedEffect(fault) { if (fault != null) shake.fire() }
    var choosing by rememberSaveable { mutableStateOf(false) }

    // The chain shows what runs; the rows show what the next start brings up
    val gatewayLive = live?.gateway ?: plan.gateway
    val address = plan.address.ifBlank { profile["tether_address"] ?: DEFAULT_ADDRESS }
    Box(Modifier.fillMaxSize()) {
        Screen {
            Top { BackKey() }
            Title("Tether")
            fault?.let { Fault(it.message, onLog = openLog, modifier = Modifier.shake(shake)) }
            Column(verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
                Segs(
                    listOf("NCM", "RNDIS"),
                    if (tetherFunction(plan, profile, caps) == "rndis") 1 else 0,
                    { set(plan.copy(function = if (it == 1) "rndis" else "ncm")) },
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
                        val name = live?.gatewayContainer?.ifEmpty { null } ?: plan.container.ifEmpty { "container" }
                        Node(name, if (live?.gatewayWaiting == true) "waiting" else live?.gatewayIface)
                    } else {
                        Node("Upstream", live?.upstream?.ifEmpty { null })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
                    ValueBox("Tx", live?.txBytes?.takeIf { it >= 0 }?.let(::sizeText) ?: "-", Modifier.weight(1f))
                    ValueBox("Rx", live?.rxBytes?.takeIf { it >= 0 }?.let(::sizeText) ?: "-", Modifier.weight(1f))
                }
                // What runs above, what the next start brings up below
                Rule()
                Column {
                    Group("Setup")
                    // Without Droidspaces there is no container to hand the link to
                    if (ds?.installed != false) Kv("Gateway") { Dip(plan.gateway, { set(plan.copy(gateway = it)) }, "Gateway mode") }
                    if (plan.gateway) {
                        LinkRow("Container", plan.container.takeIf { containers == null || it in containers }?.ifEmpty { null } ?: "none", { choosing = true })
                        TypedRow("Its iface", plan.iface, "usb0", "an interface name, up to 15 characters".takeUnless { validIface(plan.iface) }) {
                            set(plan.copy(iface = it))
                        }
                        Kv("Host IPv4") { Text("by ${plan.container.ifEmpty { "the container" }}", LcdType.data) }
                    } else {
                        Kv("DHCP and NAT") { Dip(plan.net, { set(plan.copy(net = it)) }, "DHCP and NAT") }
                        if (plan.net) {
                            TypedRow(
                                "Address", plan.address, profile["tether_address"] ?: DEFAULT_ADDRESS,
                                "a host address with a /8 to /30 prefix".takeUnless { plan.address.isBlank() || validAddress(plan.address) },
                            ) { set(plan.copy(address = it)) }
                            Kv("Host IPv4") { Text(hostOf(address.trim()) ?: "-", LcdType.data) }
                            Kv("Host IPv6") { Text(live?.ipv6?.ifEmpty { null } ?: "-", LcdType.data) }
                            TypedRow(
                                "DNS", plan.dns, profile["tether_dns"]?.replace(",", ", ") ?: "1.1.1.1, 8.8.8.8",
                                "IPv4 addresses, comma-separated".takeUnless { plan.dns.isBlank() || validDns(plan.dns) },
                            ) { set(plan.copy(dns = it)) }
                            TypedRow(
                                "Upstream", plan.upstream, profile["tether_upstream"] ?: "auto",
                                "an interface name, up to 15 characters".takeUnless { plan.upstream.isBlank() || validIface(plan.upstream) },
                            ) { set(plan.copy(upstream = it)) }
                        }
                    }
                }
            }
        }
        UnappliedSheet(Fn.TETHER, back)
        ChoiceSheet(
            choosing, "Container", containers.orEmpty(), plan.container,
            if (containers == null && ds != null) "Cannot read Droidspaces' containers" else "No containers are running",
            onPick = {
                set(plan.copy(container = it))
                choosing = false
            },
            onDismiss = { choosing = false },
        )
    }
}
