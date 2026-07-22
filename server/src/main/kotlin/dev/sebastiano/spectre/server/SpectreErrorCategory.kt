package dev.sebastiano.spectre.server

/**
 * Shared error taxonomy wire names (#199), aligned with the agent transport's `AgentErrorCategory`.
 *
 * HTTP maps these onto status codes via [httpStatus]; response bodies carry the category string so
 * clients can branch without scraping free text.
 */
@ExperimentalSpectreHttpApi
public enum class SpectreErrorCategory(public val wireName: String) {
    UnsupportedOperation("unsupportedOperation"),
    ProtocolMismatch("protocolMismatch"),
    InvalidSelector("invalidSelector"),
    NodeNotFound("nodeNotFound"),
    Timeout("timeout"),
    InputRejected("inputRejected"),
    InternalError("internalError");

    public companion object {
        /** HTTP status used when this category is the primary failure signal. */
        public fun httpStatus(category: SpectreErrorCategory): io.ktor.http.HttpStatusCode =
            when (category) {
                UnsupportedOperation -> io.ktor.http.HttpStatusCode.NotImplemented
                ProtocolMismatch,
                InvalidSelector -> io.ktor.http.HttpStatusCode.BadRequest
                NodeNotFound -> io.ktor.http.HttpStatusCode.NotFound
                Timeout -> io.ktor.http.HttpStatusCode.GatewayTimeout
                InputRejected -> io.ktor.http.HttpStatusCode.Conflict
                InternalError -> io.ktor.http.HttpStatusCode.InternalServerError
            }

        public fun fromWire(value: String?): SpectreErrorCategory =
            entries.firstOrNull { it.wireName == value } ?: InternalError
    }
}
