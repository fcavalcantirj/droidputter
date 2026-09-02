package com.droidputter.core.link

/** Where a droidputter USB link is in its lifecycle. */
enum class LinkState {
    DETACHED,
    ATTACHED,
    PERMISSION_PENDING,
    OPENING,
    LINKED,
    ERROR,
    RECONNECTING,
}

/** Inputs the caller feeds into [LinkStateMachine.handle]. */
sealed class LinkEvent {
    object DeviceAttached : LinkEvent()
    object PermissionGranted : LinkEvent()
    object PermissionDenied : LinkEvent()
    object Opened : LinkEvent()
    object HelloReceived : LinkEvent()
    object PingTimeout : LinkEvent()
    object Detached : LinkEvent()
}

/** Side effects the caller must perform in response to a [LinkEvent]. */
enum class LinkAction {
    REQUEST_PERMISSION,
    OPEN,
    SEND_HELLO_ACK,
    SEND_PING,
    CLOSE,
}

/**
 * Pure Kotlin USB link lifecycle: Detached -> [Attached ->] PermissionPending -> Opening -> Linked,
 * with Error and Reconnecting as recovery states. No I/O and no timers -- the caller owns the
 * clock (e.g. a 2s PING timer feeding [LinkEvent.PingTimeout] while Linked) and executes the
 * returned [LinkAction]s. Attaching and requesting permission happen as one hop (there is no
 * separate "now request permission" event); ATTACHED remains available as an explicit initial
 * state for callers that observe the OS attach intent before this machine is constructed.
 *
 * Missed-ping tracking: every PingTimeout while Linked counts as one missed reply; a HELLO
 * seen while Linked (the ESP is still alive) resets the count. Three consecutive misses with
 * no HELLO in between drop the link to Reconnecting.
 */
class LinkStateMachine(initialState: LinkState = LinkState.DETACHED) {
    var state: LinkState = initialState
        private set

    var missedPings: Int = 0
        private set

    fun handle(event: LinkEvent): List<LinkAction> {
        val actions = mutableListOf<LinkAction>()
        when (event) {
            is LinkEvent.DeviceAttached -> when (state) {
                LinkState.DETACHED, LinkState.ATTACHED, LinkState.ERROR, LinkState.RECONNECTING -> {
                    state = LinkState.PERMISSION_PENDING
                    actions += LinkAction.REQUEST_PERMISSION
                }
                else -> {}
            }

            is LinkEvent.PermissionGranted -> if (state == LinkState.PERMISSION_PENDING) {
                state = LinkState.OPENING
                actions += LinkAction.OPEN
            }

            is LinkEvent.PermissionDenied -> if (state == LinkState.PERMISSION_PENDING) {
                state = LinkState.ERROR
            }

            is LinkEvent.Opened -> {
                // Transport confirmed open; stay in OPENING until the ESP's HELLO arrives.
            }

            is LinkEvent.HelloReceived -> if (state == LinkState.OPENING || state == LinkState.LINKED) {
                state = LinkState.LINKED
                missedPings = 0
                actions += LinkAction.SEND_HELLO_ACK
            }

            is LinkEvent.PingTimeout -> if (state == LinkState.LINKED) {
                missedPings++
                if (missedPings >= MAX_MISSED_PINGS) {
                    state = LinkState.RECONNECTING
                    actions += LinkAction.CLOSE
                } else {
                    actions += LinkAction.SEND_PING
                }
            }

            is LinkEvent.Detached -> when (state) {
                LinkState.DETACHED -> {}
                LinkState.LINKED -> {
                    state = LinkState.RECONNECTING
                    missedPings = 0
                    actions += LinkAction.CLOSE
                }
                else -> {
                    state = LinkState.DETACHED
                    actions += LinkAction.CLOSE
                }
            }
        }
        return actions
    }

    companion object {
        const val MAX_MISSED_PINGS = 3
    }
}
