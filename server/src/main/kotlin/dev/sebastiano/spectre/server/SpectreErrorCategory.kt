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
    /** Explicit cancel of an in-flight op (#200); agent wire name `cancelled`. */
    Cancelled("cancelled"),
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
                // Non-standard but widely understood "client closed request" for cancelled work.
                Cancelled -> CLIENT_CLOSED_REQUEST
                InputRejected -> io.ktor.http.HttpStatusCode.Conflict
                InternalError -> io.ktor.http.HttpStatusCode.InternalServerError
            }

        public fun fromWire(value: String?): SpectreErrorCategory =
            entries.firstOrNull { it.wireName == value } ?: InternalError

        /** nginx-style status for cancelled / client-aborted work (not in the IANA registry). */
        private val CLIENT_CLOSED_REQUEST: io.ktor.http.HttpStatusCode =
            io.ktor.http.HttpStatusCode(CLIENT_CLOSED_REQUEST_CODE, "Client Closed Request")

        private const val CLIENT_CLOSED_REQUEST_CODE: Int = 499
    }
}
