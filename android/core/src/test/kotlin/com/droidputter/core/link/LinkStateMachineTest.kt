package com.droidputter.core.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkStateMachineTest {
    @Test
    fun `happy path from detached to linked`() {
        val machine = LinkStateMachine()

        assertEquals(listOf(LinkAction.REQUEST_PERMISSION), machine.handle(LinkEvent.DeviceAttached))
        assertEquals(LinkState.PERMISSION_PENDING, machine.state)

        assertEquals(listOf(LinkAction.OPEN), machine.handle(LinkEvent.PermissionGranted))
        assertEquals(LinkState.OPENING, machine.state)

        assertTrue(machine.handle(LinkEvent.Opened).isEmpty())
        assertEquals(LinkState.OPENING, machine.state)

        assertEquals(listOf(LinkAction.SEND_HELLO_ACK), machine.handle(LinkEvent.HelloReceived))
        assertEquals(LinkState.LINKED, machine.state)
        assertEquals(0, machine.missedPings)
    }

    @Test
    fun `permission denied moves to error`() {
        val machine = LinkStateMachine()
        machine.handle(LinkEvent.DeviceAttached)

        val actions = machine.handle(LinkEvent.PermissionDenied)

        assertEquals(LinkState.ERROR, machine.state)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `detach while linked moves to reconnecting and closes`() {
        val machine = linkedMachine()

        val actions = machine.handle(LinkEvent.Detached)

        assertEquals(LinkState.RECONNECTING, machine.state)
        assertEquals(listOf(LinkAction.CLOSE), actions)
    }

    @Test
    fun `detach while not linked returns to detached and closes`() {
        val machine = LinkStateMachine()
        machine.handle(LinkEvent.DeviceAttached)
        assertEquals(LinkState.PERMISSION_PENDING, machine.state)

        val actions = machine.handle(LinkEvent.Detached)

        assertEquals(LinkState.DETACHED, machine.state)
        assertEquals(listOf(LinkAction.CLOSE), actions)
    }

    @Test
    fun `three missed pings drop the link to reconnecting`() {
        val machine = linkedMachine()

        val first = machine.handle(LinkEvent.PingTimeout)
        assertEquals(LinkState.LINKED, machine.state)
        assertEquals(listOf(LinkAction.SEND_PING), first)
        assertEquals(1, machine.missedPings)

        val second = machine.handle(LinkEvent.PingTimeout)
        assertEquals(LinkState.LINKED, machine.state)
        assertEquals(listOf(LinkAction.SEND_PING), second)
        assertEquals(2, machine.missedPings)

        val third = machine.handle(LinkEvent.PingTimeout)
        assertEquals(LinkState.RECONNECTING, machine.state)
        assertEquals(listOf(LinkAction.CLOSE), third)
        assertEquals(3, machine.missedPings)
    }

    @Test
    fun `a HELLO while linked resets the missed-ping count`() {
        val machine = linkedMachine()
        machine.handle(LinkEvent.PingTimeout)
        machine.handle(LinkEvent.PingTimeout)
        assertEquals(2, machine.missedPings)

        machine.handle(LinkEvent.HelloReceived)

        assertEquals(0, machine.missedPings)
        assertEquals(LinkState.LINKED, machine.state)
    }

    @Test
    fun `events invalid for the current state are ignored`() {
        val machine = LinkStateMachine()

        assertTrue(machine.handle(LinkEvent.PermissionGranted).isEmpty())
        assertEquals(LinkState.DETACHED, machine.state)

        assertTrue(machine.handle(LinkEvent.PingTimeout).isEmpty())
        assertEquals(LinkState.DETACHED, machine.state)
    }

    @Test
    fun `device re-attached after an error retries permission`() {
        val machine = LinkStateMachine()
        machine.handle(LinkEvent.DeviceAttached)
        machine.handle(LinkEvent.PermissionDenied)
        assertEquals(LinkState.ERROR, machine.state)

        val actions = machine.handle(LinkEvent.DeviceAttached)

        assertEquals(LinkState.PERMISSION_PENDING, machine.state)
        assertEquals(listOf(LinkAction.REQUEST_PERMISSION), actions)
    }

    private fun linkedMachine(): LinkStateMachine {
        val machine = LinkStateMachine()
        machine.handle(LinkEvent.DeviceAttached)
        machine.handle(LinkEvent.PermissionGranted)
        machine.handle(LinkEvent.Opened)
        machine.handle(LinkEvent.HelloReceived)
        assertEquals(LinkState.LINKED, machine.state)
        return machine
    }
}
