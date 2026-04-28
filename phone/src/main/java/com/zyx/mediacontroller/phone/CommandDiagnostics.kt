package se.kvrgic.mediawristremote.phone

object CommandDiagnostics {
    @Volatile
    var lastRoute: String = "No commands sent yet."
        private set

    fun record(route: String) {
        lastRoute = route
    }
}
