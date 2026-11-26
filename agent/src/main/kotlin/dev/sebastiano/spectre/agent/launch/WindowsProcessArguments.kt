package dev.sebastiano.spectre.agent.launch

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi

internal fun processArguments(handle: ProcessHandle): Array<String> {
    // Linux JDKs can stop reading cmdline at a page boundary. macOS obtains argv via
    // KERN_PROCARGS2 and argc; keep its argument boundaries (never tokenize ps output).
    if (System.getProperty("os.name") == "Linux") return LinuxProcessArguments.read(handle)
    val arguments = handle.info().arguments().orElse(emptyArray())
    return if (arguments.isEmpty() && java.io.File.separatorChar == '\\') {
        WindowsProcessArguments.read(handle.pid())
    } else arguments
}

@OptIn(ExperimentalSpectreAgentApi::class)
internal fun hasLaunchMarker(pid: Long, marker: String): Boolean {
    val handle = ProcessHandle.of(pid).orElse(null) ?: return false
    return "-D${LaunchAndAttach.LAUNCH_MARKER_PROPERTY}=$marker" in processArguments(handle)
}

/**
 * Windows ProcessHandle reports the executable but omits argv. Read the kernel's snapshot without
 * attaching to the JVM or waiting for any cooperation from the target process.
 */
internal object WindowsProcessArguments {
    private const val PROCESS_COMMAND_LINE_INFORMATION = 60
    // A Windows command line is limited to 32,767 UTF-16 code units including the terminator.
    private const val BUFFER_BYTES = 65536L + 16

    private interface NtDll : StdCallLibrary {
        @Suppress("FunctionNaming") // JNA resolves the exported NT function by this exact name.
        fun NtQueryInformationProcess(
            process: WinNT.HANDLE,
            informationClass: Int,
            buffer: Pointer,
            capacity: Int,
            returnedLength: Pointer?,
        ): Int
    }

    private val ntDll by lazy { Native.load("ntdll", NtDll::class.java) }

    fun read(pid: Long): Array<String> {
        val kernel = Kernel32.INSTANCE
        val process =
            kernel.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt())
                ?: return emptyArray()
        try {
            return Memory(BUFFER_BYTES).use { buffer ->
                val status =
                    ntDll.NtQueryInformationProcess(
                        process,
                        PROCESS_COMMAND_LINE_INFORMATION,
                        buffer,
                        BUFFER_BYTES.toInt(),
                        null,
                    )
                if (status != 0) return emptyArray()
                // UNICODE_STRING begins with a USHORT byte length and an aligned PWSTR into buffer.
                val bytes = buffer.getShort(0).toUShort().toInt()
                if (bytes == 0) return emptyArray()
                val data = buffer.getPointer(Native.POINTER_SIZE.toLong())
                val offset = Pointer.nativeValue(data) - Pointer.nativeValue(buffer)
                if (offset !in 0..(BUFFER_BYTES - bytes)) return emptyArray()
                val command = String(buffer.getByteArray(offset, bytes), Charsets.UTF_16LE)
                Shell32Util.CommandLineToArgv(command).drop(1).toTypedArray()
            }
        } finally {
            kernel.CloseHandle(process)
        }
    }
}
