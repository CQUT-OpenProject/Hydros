package dev.uednd.hydros

import dev.uednd.hydros.data.update.UpdatePhase
import dev.uednd.hydros.data.update.UpdateTask
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UpdateTaskTest {
    @Test fun inFlightTaskRoundTripsThroughPersistedJson() {
        val task = UpdateTask(
            phase = UpdatePhase.INSTALLING, taskId = "task", version = "1.2.0",
            url = "https://github.com/CQUT-OpenProject/Hydros/releases/download/v1.2.0/app.apk", size = 100,
            sha256 = "a".repeat(64), downloadId = 7, sessionId = 3, versionCode = 10200, committed = true,
            committedAt = 1_600_000_000_000,
        )
        val restored = Json.decodeFromString(UpdateTask.serializer(), Json.encodeToString(UpdateTask.serializer(), task))
        assertEquals(task, restored)
        assertTrue(restored.active)
        assertFalse(restored.cancellable)
    }

    @Test fun terminalTasksAreInactiveAndOnlyUncommittedTasksCancel() {
        assertFalse(UpdateTask(phase = UpdatePhase.FAILED, error = "失败").active)
        assertFalse(UpdateTask().active)
        assertFalse(UpdateTask().cancellable)
        assertTrue(UpdateTask(phase = UpdatePhase.DOWNLOADING).cancellable)
        assertTrue(UpdateTask(phase = UpdatePhase.AWAITING_CONFIRMATION).cancellable)
        assertFalse(UpdateTask(phase = UpdatePhase.INSTALLING, committed = true).cancellable)
    }
}
