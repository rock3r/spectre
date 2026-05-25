package dev.sebastiano.spectre.agent

@ExperimentalSpectreAgentApi
internal object AgentPlatformPreflight {
    fun requireSupported(osName: String = System.getProperty("os.name").orEmpty()) {
        if (osName.startsWith("Windows", ignoreCase = true)) {
            throw AttachPlatformUnsupportedException(osName)
        }
    }
}
