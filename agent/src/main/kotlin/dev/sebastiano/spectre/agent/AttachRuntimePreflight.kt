package dev.sebastiano.spectre.agent

/** Runtime prerequisites shared by all JDK Attach API entrypoints. */
@ExperimentalSpectreAgentApi
internal object AttachRuntimePreflight {
    fun requireSupported(javaFeature: Int = Runtime.version().feature()) {
        if (javaFeature < MINIMUM_JAVA_FEATURE) {
            throw JavaVersionUnsupportedException(javaFeature)
        }
    }

    private const val MINIMUM_JAVA_FEATURE: Int = 21
}
