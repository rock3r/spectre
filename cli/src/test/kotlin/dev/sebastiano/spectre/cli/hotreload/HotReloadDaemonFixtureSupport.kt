package dev.sebastiano.spectre.cli.hotreload

internal fun composeFixtureDiedAfterReadyMessage(exitValue: Int?, outputTail: String): String {
    val exit = exitValue?.let { "exit=$it" } ?: "exit=unknown"
    val tail = outputTail.trim().ifEmpty { "(empty)" }
    return "Compose fixture exited after READY ($exit). Last output:\n$tail"
}
