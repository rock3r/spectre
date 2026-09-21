package dev.sebastiano.spectre.server

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest

/**
 * Deployment-scoped security configuration for the Spectre HTTP transport.
 *
 * Create this configuration from a secret supplied by the deployment (for example, an environment
 * variable or secret manager), and construct matching configurations in the server and client
 * processes. The bearer is intentionally not exposed as a property and this class does not include
 * it in [toString].
 *
 * [allowedOrigins] is an exact allowlist. An empty set disables browser cross-origin access.
 * [allowInsecureLoopback] exists for local tests only; HTTPS remains mandatory for every
 * non-loopback connection.
 */
@ExperimentalSpectreHttpApi
public class SpectreHttpSecurity(
    bearerToken: String,
    allowedOrigins: Set<String> = emptySet(),
    public val allowInsecureLoopback: Boolean = false,
) {
    public val allowedOrigins: Set<String> = java.util.Set.copyOf(allowedOrigins)

    init {
        require(bearerToken.length >= MINIMUM_BEARER_LENGTH) {
            "bearerToken must contain at least $MINIMUM_BEARER_LENGTH characters"
        }
        require(BEARER_TOKEN_PATTERN.matches(bearerToken)) {
            "bearerToken must be an RFC 6750 bearer value"
        }
        require(this.allowedOrigins.none { it == "*" }) {
            "allowedOrigins must contain exact origins; wildcard origins are not supported"
        }
        require(this.allowedOrigins.all(::isValidOrigin)) {
            "allowedOrigins entries must be absolute HTTP(S) origins without paths"
        }
    }

    private val tokenBytes: ByteArray = bearerToken.toByteArray(Charsets.UTF_8)

    internal val authorizationHeader: String = "Bearer $bearerToken"

    internal fun acceptsAuthorization(value: String?): Boolean {
        if (value == null || !value.startsWith(BEARER_PREFIX, ignoreCase = true)) return false
        val candidate = value.substring(BEARER_PREFIX.length).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(tokenBytes, candidate)
    }

    internal fun allowsOrigin(origin: String): Boolean = origin in allowedOrigins

    private companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val MINIMUM_BEARER_LENGTH = 32
        private const val MAX_PORT = 65_535
        private val BEARER_TOKEN_PATTERN = Regex("[A-Za-z0-9\\-._~+/]+=*")

        private fun isValidOrigin(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return (uri.scheme.equals("https", ignoreCase = true) ||
                uri.scheme.equals("http", ignoreCase = true)) &&
                uri.rawAuthority != null &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.port <= MAX_PORT &&
                (uri.rawPath.isNullOrEmpty()) &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        }
    }
}

/**
 * Token the plaintext loopback hatch trusts.
 *
 * [remoteAddress] is the TCP peer (`RequestConnectionPoint.remoteAddress`). [remoteHost] is Ktor's
 * reverse-DNS name of that peer. On Windows, 127.0.0.1 reverse-resolves to the computer name, so
 * trusting [remoteHost] rejects a real loopback client with HTTP 426. A blank address fails closed:
 * the host name is never a substitute, including when it is `localhost`.
 */
internal fun loopbackCheckInput(remoteAddress: String, remoteHost: String): String {
    if (remoteHost.isEmpty() && remoteAddress.isEmpty()) return ""
    return remoteAddress
}

private fun isNumericAddressLiteral(literal: String): Boolean {
    val ipv6 = literal.contains(':')
    return literal.all { character ->
        character.isDigit() ||
            character == '.' ||
            (ipv6 && (character == ':' || character.lowercaseChar() in 'a'..'f'))
    }
}

internal fun isLoopbackHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true)) return true
    val literal = host.removePrefix("[").removeSuffix("]").substringBefore('%')
    if (literal.isEmpty() || !isNumericAddressLiteral(literal)) return false
    return runCatching { InetAddress.getByName(literal).isLoopbackAddress }.getOrDefault(false)
}
