package com.example.queststats

/** Latest text/JSON produced by the service, read by the settings screen and the HTTP server. */
object SharedState {
    @Volatile var statsText: String = "Service not running."
    @Volatile var predictionText: String = ""
    @Volatile var json: String = "{}"
    @Volatile var running: Boolean = false
}
