package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType

/*
 * Tether: the host, the phone and what the phone hands the link to, as a chain, with the
 * counters and the addresses under it. The title's switch is Home's: off, the function and the
 * gateway only build the setup a start brings up; on, changing either restarts tether (a
 * replug), and the container fields lock.
 */
@Composable
fun TetherScreen(back: () -> Unit, openLog: () -> Unit) {
    BackHandler(onBack = back)
    PollStatus()
    val context = LocalContext.current
    LaunchedEffect(Unit) { TetherSetup.load(context) }
    val plan = TetherSetup.plan
    val caps = Gadget.caps
    val profile = Gadget.profile
    val live = Gadget.status?.tether
    val on = live != null
    // On, a change starts from what runs, which may have been started elsewhere, not from the
    // saved setup: only the one thing changed differs after the restart
    val base = live?.let {
        plan.copy(
            function = it.function,
            gateway = it.gateway,
            container = it.gatewayContainer.ifEmpty { plan.container },
            iface = it.gatewayIface.ifEmpty { plan.iface },
        )
    } ?: plan
    val function = live?.function ?: tetherFunction(plan, profile, caps)
    val shake = rememberShake()
    val fault = Gadget.fault?.takeIf { it.where == Fn.TETHER }
    LaunchedEffect(fault) { if (fault != null) shake.fire() }
    // Where the last command came from: the title's switch, or a setting under it
    var fromTitle by remember { mutableStateOf(false) }

    // Off, a change is only kept. On, tether comes back with it: stop, then the start it now
    // makes. While a command runs nothing changes, as Gadget.run would refuse the restart
    fun change(next: TetherPlan) {
        fromTitle = false
        if (Gadget.busy != null) return
        if (on && next.gateway && next.container.isBlank()) {
            // The fields are locked while on: stopping now would leave a start that cannot run
            Gadget.fault = Fault(Fn.TETHER, "[-] Name the container first: switch tether off")
            return
        }
        TetherSetup.set(context, next)
        if (on) Gadget.run(Fn.TETHER, "tether", "stop") { if (it) flip(Fn.TETHER, true) }
    }

    // A gateway hands addressing to the container; the far node is the container's end of the cable
    val gateway = live?.gateway ?: plan.gateway
    val container = live?.gatewayContainer?.ifEmpty { null } ?: plan.container
    val by = "by $container"
    Screen {
        Top { BackKey() }
        Title("Tether") { FnSwitch(Fn.TETHER, if (fromTitle) Modifier.shake(shake) else Modifier) { fromTitle = true } }
        if (fromTitle) fault?.let { Fault(it.message, onLog = openLog) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segs(
                    listOf("NCM", "RNDIS"),
                    if (function == "rndis") 1 else 0,
                    { change(base.copy(function = if (it == 1) "rndis" else "ncm")) },
                    if (fromTitle) Modifier else Modifier.shake(shake),
                    enabled = { if (it == 0) caps.ncmOk() else caps.rndisOk() },
                )
                if (!fromTitle) fault?.let { Fault(it.message, onLog = openLog) }
            }
            Column {
                Node("Host")
                Wire(function, moving = on)
                Node("Phone", live?.iface ?: "off", inverted = true)
                Wire(if (gateway) "virtual cable" else "masquerade", up = true, moving = on)
                when {
                    gateway -> Node(container, if (live?.gatewayWaiting == true) "waiting" else live?.gatewayIface ?: plan.iface)
                    else -> Node("Upstream", live?.upstream?.ifEmpty { null } ?: "auto")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
                ValueBox("Tx", live?.txBytes?.takeIf { it >= 0 }?.let(::sizeText) ?: "-", Modifier.weight(1f))
                ValueBox("Rx", live?.rxBytes?.takeIf { it >= 0 }?.let(::sizeText) ?: "-", Modifier.weight(1f))
            }
            Column {
                Kv("Gateway") { Dip(gateway, { change(base.copy(gateway = it)) }, "Gateway mode") }
                Kv("IPv4") { Text(if (gateway) by else hostOf(live?.address ?: profile["tether_address"] ?: DEFAULT_ADDRESS) ?: "-", LcdType.data) }
                Kv("IPv6") { Text(if (gateway) by else live?.ipv6?.ifEmpty { null } ?: "-", LcdType.data) }
                if (gateway) {
                    // Read at the next start, so they lock while tether runs
                    Kv("Container") { Input(plan.container, { TetherSetup.set(context, plan.copy(container = it)) }, Modifier.width(180.dp).padding(vertical = 4.dp), placeholder = "OpenWrt", enabled = !on) }
                    Kv("Its iface") { Input(plan.iface, { TetherSetup.set(context, plan.copy(iface = it)) }, Modifier.width(180.dp).padding(vertical = 4.dp), placeholder = "usb0", enabled = !on) }
                } else {
                    Kv("Dns") { Text(profile["tether_dns"]?.replace(",", ", ") ?: "1.1.1.1, 8.8.8.8", LcdType.data) }
                }
            }
        }
    }
}
