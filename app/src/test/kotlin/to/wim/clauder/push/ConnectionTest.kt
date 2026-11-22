package to.wim.clauder.push

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTest {
    @Test
    fun `worker answers map to what the settings screen says`() {
        assertEquals(ConnectOutcome.Connected(true), Connection.outcome(WorkerClient.Result.Ok(true)))
        assertEquals(ConnectOutcome.Connected(false), Connection.outcome(WorkerClient.Result.Ok(false)))
        assertEquals(ConnectOutcome.WrongToken, Connection.outcome(WorkerClient.Result.Unauthorized))
        assertEquals(ConnectOutcome.Unreachable("HTTP 500"), Connection.outcome(WorkerClient.Result.Failed("HTTP 500")))
    }
}
