package app.xan.shared

import app.xan.music.listentogether.ControlModes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosNativeRoomControlTest {
    @Test
    fun nativeCommandsStayLocalWhenThereIsNoConnectedRoom() {
        assertFalse(shouldRouteIosPlayerCommandToRoom(IosRoomUiState()))
        assertFalse(shouldRouteIosPlayerCommandToRoom(IosRoomUiState(status = "Connecting…", roomCode = "ABCD")))
        assertFalse(shouldRouteIosPlayerCommandToRoom(IosRoomUiState(status = "Disconnected", roomCode = "ABCD")))
    }

    @Test
    fun connectedRoomRoutesNativeCommandsAndHonorsRoomPermissions() {
        val host = IosRoomUiState(status = "In room", roomCode = "ABCD", isHost = true)
        val everyoneModeGuest = IosRoomUiState(status = "In room", roomCode = "ABCD", controlMode = ControlModes.EVERYONE)
        val ownerModeGuest = IosRoomUiState(status = "In room", roomCode = "ABCD", controlMode = ControlModes.OWNER)

        assertTrue(shouldRouteIosPlayerCommandToRoom(host))
        assertTrue(canControlIosRoomPlayback(host))
        assertTrue(shouldRouteIosPlayerCommandToRoom(everyoneModeGuest))
        assertTrue(canControlIosRoomPlayback(everyoneModeGuest))
        assertTrue(shouldRouteIosPlayerCommandToRoom(ownerModeGuest))
        assertFalse(canControlIosRoomPlayback(ownerModeGuest))
    }
}
