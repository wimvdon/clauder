package to.wim.clauder.push

/** What happened when the phone tried to connect to the Worker. */
sealed interface ConnectOutcome {
    /** Registered. [pushReady] is false when the Worker has no FCM service account yet. */
    data class Connected(val pushReady: Boolean) : ConnectOutcome
    data object InvalidAddress : ConnectOutcome
    data object MissingToken : ConnectOutcome
    data object WrongToken : ConnectOutcome
    data class Unreachable(val detail: String) : ConnectOutcome
    /** This build of the app has no Firebase key, so it can't receive pushes. */
    data object PushUnavailable : ConnectOutcome
}

object Connection {
    fun outcome(result: WorkerClient.Result<Boolean>): ConnectOutcome = when (result) {
        is WorkerClient.Result.Ok -> ConnectOutcome.Connected(result.value)
        WorkerClient.Result.Unauthorized -> ConnectOutcome.WrongToken
        is WorkerClient.Result.Failed -> ConnectOutcome.Unreachable(result.message)
    }
}
