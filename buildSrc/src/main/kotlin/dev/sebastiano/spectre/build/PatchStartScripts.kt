package dev.sebastiano.spectre.build

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/** Adds Spectre's JDK preflight to the generated application launchers. */
abstract class PatchStartScripts : DefaultTask() {
    @get:InputFile abstract val unixScript: RegularFileProperty

    @get:InputFile abstract val windowsScript: RegularFileProperty

    @TaskAction
    fun patch() {
        patchUnix(unixScript.get().asFile)
        patchWindows(windowsScript.get().asFile)
    }

    private fun patchUnix(script: File) {
        script.writeText(
            script
                .readText()
                .replace("# Determine the Java command to use to start the JVM.", UNIX_JAVA_SEARCH)
                .replace(UNIX_DEFAULT_JVM_OPTIONS_COMMENT, UNIX_JDK_PREFLIGHT),
        )
    }

    private fun patchWindows(script: File) {
        val originalScript = script.readText()
        val lineSeparator = if ("\r\n" in originalScript) "\r\n" else "\n"
        val patchedScript =
            originalScript
                .replace("\r\n", "\n")
                .replace("@rem Find java.exe", WINDOWS_JAVA_SEARCH)
                .replace(
                    WINDOWS_PATH_PROBE,
                    "$WINDOWS_PATH_PROBE\n\n$WINDOWS_JDK_FALLBACK",
                )
                .replace(
                    ":findJavaFromJavaHome",
                    "$WINDOWS_JDK_CANDIDATE_PROBE\n\n:findJavaFromJavaHome",
                )
                .replace(":execute\n@rem Setup the command line", WINDOWS_JDK_PREFLIGHT)
        script.writeText(patchedScript.replace("\n", lineSeparator))
    }

    private companion object {
        private val UNIX_JAVA_SEARCH =
            """
            # Prefer a bundled runtime only when it matches this host.
            if [ -x "${'$'}APP_HOME/runtime/bin/java" ] && [ -r "${'$'}APP_HOME/runtime/spectre-runtime.properties" ]; then
                spectre_runtime_os=${'$'}(sed -n 's/^spectre\.runtime\.os=//p' "${'$'}APP_HOME/runtime/spectre-runtime.properties")
                spectre_runtime_arch=${'$'}(sed -n 's/^spectre\.runtime\.arch=//p' "${'$'}APP_HOME/runtime/spectre-runtime.properties")
                case "${'$'}(uname -s)" in Darwin) spectre_host_os='Mac OS X' ;; Linux) spectre_host_os=Linux ;; *) spectre_host_os= ;; esac
                case "${'$'}(uname -m)" in arm64|aarch64) spectre_host_arch=aarch64 ;; x86_64|amd64) spectre_host_arch=x86_64 ;; *) spectre_host_arch= ;; esac
                if [ "${'$'}spectre_runtime_os" = "${'$'}spectre_host_os" ] && [ "${'$'}spectre_runtime_arch" = "${'$'}spectre_host_arch" ]; then
                    JAVA_HOME=${'$'}APP_HOME/runtime
                fi
            fi

            # Find a locally installed JDK when JAVA_HOME and PATH are unset.
            if [ -z "${'$'}{JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
                spectre_java_fallback_home=
                if [ -x /usr/libexec/java_home ]; then
                    JAVA_HOME=${'$'}(/usr/libexec/java_home -v 21+ 2>/dev/null || true)
                fi
                if [ -n "${'$'}{JAVA_HOME:-}" ] && "${'$'}JAVA_HOME/bin/java" --list-modules 2>/dev/null | grep -q '^jdk.attach@'; then
                    : # Preserve the OS-selected JDK.
                else
                    if [ -n "${'$'}{JAVA_HOME:-}" ]; then
                        spectre_java_fallback_home=${'$'}JAVA_HOME
                        JAVA_HOME=
                    fi
                    for spectre_java_home in "${'$'}{HOME:-}/.sdkman/candidates/java/current" /usr/lib/jvm/* /Library/Java/JavaVirtualMachines/*/Contents/Home; do
                        if [ -x "${'$'}spectre_java_home/bin/java" ]; then
                            spectre_java_version=${'$'}("${'$'}spectre_java_home/bin/java" -version 2>&1 | sed -n '1s/.*version "\([^" ]*\)".*/\1/p')
                            spectre_java_feature=${'$'}{spectre_java_version%%.*}
                            case "${'$'}spectre_java_feature" in
                                '' | *[!0-9]*) continue ;;
                            esac
                            if [ "${'$'}spectre_java_feature" -ge 21 ] && "${'$'}spectre_java_home/bin/java" --list-modules 2>/dev/null | grep -q '^jdk.attach@'; then
                                JAVA_HOME=${'$'}spectre_java_home
                                break
                            elif [ -z "${'$'}spectre_java_fallback_home" ] && [ "${'$'}spectre_java_feature" -ge 21 ]; then
                                spectre_java_fallback_home=${'$'}spectre_java_home
                            fi
                        fi
                    done
                    if [ -z "${'$'}{JAVA_HOME:-}" ]; then
                        JAVA_HOME=${'$'}spectre_java_fallback_home
                    fi
                fi
            fi

            # Determine the Java command to use to start the JVM.
            """
                .trimIndent()

        private val UNIX_JDK_PREFLIGHT =
            """
            # Spectre requires Java 21 or later. Attach operations validate jdk.attach themselves.
            spectre_java_version=${'$'}("${'$'}JAVACMD" -version 2>&1 | sed -n '1s/.*version "\([^" ]*\)".*/\1/p')
            spectre_java_feature=${'$'}{spectre_java_version%%.*}
            case "${'$'}spectre_java_feature" in
                '' | *[!0-9]*) die "ERROR: Could not determine the Java version from ${'$'}JAVACMD." ;;
            esac
            if [ "${'$'}spectre_java_feature" -lt 21 ]; then
                die "ERROR: Spectre requires JDK 21 or later; found Java ${'$'}spectre_java_version at ${'$'}JAVACMD."
            fi
            $UNIX_DEFAULT_JVM_OPTIONS_COMMENT
            """
                .trimIndent()

        private const val UNIX_DEFAULT_JVM_OPTIONS_COMMENT =
            "# Add default JVM options here. You can also use JAVA_OPTS and SPECTRE_OPTS to pass JVM options to this script."

        private val WINDOWS_JAVA_SEARCH =
            """
            @rem Find java.exe
            if exist "%APP_HOME%\runtime\bin\java.exe" if exist "%APP_HOME%\runtime\spectre-runtime.properties" (
                call :useBundledSpectreRuntime
                if defined JAVA_HOME goto findJavaFromJavaHome
            )
            """
                .trimIndent()

        private const val WINDOWS_PATH_PROBE =
            """if %ERRORLEVEL% equ 0 goto execute"""

        private val WINDOWS_JDK_FALLBACK =
            """
            @rem PATH did not provide Java; now try common local JDK installations.
            set SPECTRE_JRE_HOME=
            for %%d in ("%ProgramFiles%\\Java\\*" "%ProgramFiles%\\Eclipse Adoptium\\*" "%ProgramFiles%\\Microsoft\\jdk-*") do call :findCompatibleSpectreJdk "%%~fd"
            if not defined JAVA_HOME if defined SPECTRE_JRE_HOME set JAVA_HOME=%SPECTRE_JRE_HOME%
            if defined JAVA_HOME goto findJavaFromJavaHome
            """
                .trimIndent()

        private val WINDOWS_JDK_CANDIDATE_PROBE =
            """
            :useBundledSpectreRuntime
            set SPECTRE_RUNTIME_OS=
            set SPECTRE_RUNTIME_ARCH=
            for /f "tokens=1,* delims==" %%a in ('findstr /b "spectre.runtime.os=" "%APP_HOME%\runtime\spectre-runtime.properties"') do set SPECTRE_RUNTIME_OS=%%b
            for /f "tokens=1,* delims==" %%a in ('findstr /b "spectre.runtime.arch=" "%APP_HOME%\runtime\spectre-runtime.properties"') do set SPECTRE_RUNTIME_ARCH=%%b
            set SPECTRE_HOST_ARCH=%PROCESSOR_ARCHITECTURE%
            if /I "%SPECTRE_HOST_ARCH%"=="AMD64" set SPECTRE_HOST_ARCH=x86_64
            if /I "%SPECTRE_HOST_ARCH%"=="ARM64" set SPECTRE_HOST_ARCH=aarch64
            if /I "%SPECTRE_RUNTIME_OS%"=="Windows" if /I "%SPECTRE_RUNTIME_ARCH%"=="%SPECTRE_HOST_ARCH%" set JAVA_HOME=%APP_HOME%\runtime
            goto :eof

            :findCompatibleSpectreJdk
            if defined JAVA_HOME goto :eof
            if not exist "%~1\\bin\\java.exe" goto :eof
            set SPECTRE_JAVA_VERSION=
            set SPECTRE_JAVA_FEATURE=
            for /f "tokens=3" %%v in ('"%~1\\bin\\java.exe" -version 2^>^&1 ^| findstr /c:"version"') do set SPECTRE_JAVA_VERSION=%%v
            set SPECTRE_JAVA_VERSION=%SPECTRE_JAVA_VERSION:"=%
            for /f "tokens=1 delims=." %%v in ("%SPECTRE_JAVA_VERSION%") do set SPECTRE_JAVA_FEATURE=%%v
            if "%SPECTRE_JAVA_FEATURE%"=="" goto :eof
            if %SPECTRE_JAVA_FEATURE% LSS 21 goto :eof
            "%~1\\bin\\java.exe" --list-modules 2^>NUL | findstr /r /c:"^jdk.attach@" >NUL
            if %ERRORLEVEL% equ 0 set JAVA_HOME=%~1
            if not defined JAVA_HOME if not defined SPECTRE_JRE_HOME set SPECTRE_JRE_HOME=%~1
            goto :eof
            """
                .trimIndent()

        private val WINDOWS_JDK_PREFLIGHT =
            """
            :execute
            @rem Spectre requires Java 21 or later. Attach operations validate jdk.attach themselves.
            set SPECTRE_JAVA_VERSION=
            set SPECTRE_JAVA_FEATURE=
            for /f "tokens=3" %%v in ('"%JAVA_EXE%" -version 2^>^&1 ^| findstr /c:"version"') do set SPECTRE_JAVA_VERSION=%%v
            set SPECTRE_JAVA_VERSION=%SPECTRE_JAVA_VERSION:"=%
            for /f "tokens=1 delims=." %%v in ("%SPECTRE_JAVA_VERSION%") do set SPECTRE_JAVA_FEATURE=%%v
            if "%SPECTRE_JAVA_FEATURE%"=="" goto invalidJavaVersion
            if %SPECTRE_JAVA_FEATURE% LSS 21 goto oldJavaVersion
            goto setupCommandLine

            :invalidJavaVersion
            @rem Use ">&2 echo" rather than trailing "1>&2" — cmd can treat the latter as a
            @rem syntax error when JAVA_EXE expansion leaves a path-like parse failure.
            >&2 echo ERROR: Could not determine the Java version from %JAVA_EXE%.
            goto fail

            :oldJavaVersion
            >&2 echo ERROR: Spectre requires JDK 21 or later; found Java %SPECTRE_JAVA_VERSION% at %JAVA_EXE%.
            goto fail

            :setupCommandLine
            @rem Setup the command line
            """
                .trimIndent()
    }
}
