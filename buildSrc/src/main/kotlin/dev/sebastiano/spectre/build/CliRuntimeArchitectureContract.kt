package dev.sebastiano.spectre.build

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fail-closed native-binary contract for the five published Roast archives. */
object CliRuntimeArchitectureContract {
    fun validate(targetName: String, entries: Map<String, ByteArray>): List<String> {
        val target = Target.fromName(targetName)
        val binaries = entries.mapValues { (_, bytes) -> NativeBinary.parse(bytes) }
        val errors = mutableListOf<String>()

        fun requireOne(label: String, predicate: (String) -> Boolean) {
            val matches = entries.keys.filter(predicate)
            if (matches.size != 1) errors += "$targetName must contain exactly one $label; found ${matches.sorted()}"
        }
        requireOne("outer launcher") { name -> name.endsWith(target.launcherSuffix) }
        requireOne("runtime java launcher") { name -> name.endsWith(target.javaSuffix) }
        requireOne("main JVM") { name -> name.endsWith(target.jvmSuffix) }
        requireOne("runtime libjli") { name -> name.endsWith(target.jliSuffix) }

        entries.forEach { (name, _) ->
            val binary = binaries.getValue(name)
            if (binary == null) {
                errors += "$targetName $name is not a recognised native binary"
            } else {
                if (binary.format != target.format) {
                    errors += "$targetName $name requires ${target.format.label}, found ${binary.format.label}"
                }
                if (target.architecture !in binary.architectures) {
                    errors +=
                        "$targetName $name requires ${target.architecture.label}, found " +
                            binary.architectures.joinToString { it.label }.ifEmpty { "unknown architecture" }
                }
            }
        }
        return errors
    }

    fun isNativePayload(name: String, bytes: ByteArray): Boolean =
        name.contains("/runtime/") &&
            !name.endsWith("/") &&
            !name.endsWith(".jar") &&
            (NativeBinary.parse(bytes) != null || NATIVE_NAME.matches(name.substringAfterLast('/')))

    private data class NativeBinary(val format: Format, val architectures: Set<Architecture>) {
        companion object {
            fun parse(bytes: ByteArray): NativeBinary? = parseAt(bytes, 0, bytes.size)

            private fun parseAt(bytes: ByteArray, start: Int, available: Int): NativeBinary? {
                if (available < 4 || start < 0 || start > bytes.size - available) return null
                if (available >= 20 && bytes.matches(start, ELF_MAGIC)) {
                    if (bytes[start + 4].toInt() != 2) return null
                    val order =
                        when (bytes[start + 5].toInt()) {
                            1 -> ByteOrder.LITTLE_ENDIAN
                            2 -> ByteOrder.BIG_ENDIAN
                            else -> return null
                        }
                    return NativeBinary(Format.ELF, setOfNotNull(Architecture.fromElf(bytes.u16(start + 18, order))))
                }
                if (available >= 64 && bytes[start] == 'M'.code.toByte() && bytes[start + 1] == 'Z'.code.toByte()) {
                    val peOffset = bytes.u32(start + 0x3c, ByteOrder.LITTLE_ENDIAN).toLong() and UINT_MASK
                    if (peOffset > available - 6) return null
                    val pe = start + peOffset.toInt()
                    if (!bytes.matches(pe, PE_MAGIC)) return null
                    return NativeBinary(Format.PE, setOfNotNull(Architecture.fromPe(bytes.u16(pe + 4, ByteOrder.LITTLE_ENDIAN))))
                }
                return parseMach(bytes, start, available)
            }

            private fun parseMach(bytes: ByteArray, start: Int, available: Int): NativeBinary? {
                val magic = bytes.u32(start, ByteOrder.BIG_ENDIAN)
                val thinOrder =
                    when (magic) {
                        MACH_64_BE -> ByteOrder.BIG_ENDIAN
                        MACH_64_LE -> ByteOrder.LITTLE_ENDIAN
                        else -> null
                    }
                if (thinOrder != null && available >= MACH_HEADER_SIZE) {
                    return NativeBinary(
                        Format.MACH_O,
                        setOfNotNull(Architecture.fromMach(bytes.u32(start + 4, thinOrder))),
                    )
                }
                val fat64 = magic == FAT_64_BE || magic == FAT_64_LE
                val order =
                    when (magic) {
                        FAT_32_BE, FAT_64_BE -> ByteOrder.BIG_ENDIAN
                        FAT_32_LE, FAT_64_LE -> ByteOrder.LITTLE_ENDIAN
                        else -> return null
                    }
                val count = bytes.u32(start + 4, order)
                val recordSize = if (fat64) 32 else 20
                if (count !in 1..MAX_FAT_ARCHITECTURES || available < 8 + count * recordSize) return null
                val architectures = mutableSetOf<Architecture>()
                repeat(count) { index ->
                    val record = start + 8 + index * recordSize
                    val declared = Architecture.fromMach(bytes.u32(record, order)) ?: return null
                    val offset = bytes.unsigned(record + 8, order, fat64)
                    val size = bytes.unsigned(record + if (fat64) 16 else 12, order, fat64)
                    if (offset > available || size < MACH_HEADER_SIZE || size > available - offset) return null
                    val member = parseAt(bytes, start + offset.toInt(), size.toInt()) ?: return null
                    if (member.format != Format.MACH_O || member.architectures != setOf(declared)) return null
                    architectures += declared
                }
                return NativeBinary(Format.MACH_O, architectures)
            }
        }
    }

    private enum class Format(val label: String) { ELF("ELF64"), PE("PE"), MACH_O("Mach-O") }

    private enum class Architecture(val label: String) {
        X86_64("x86_64"), AARCH64("aarch64");
        companion object {
            fun fromElf(value: Int) = if (value == 0x3e) X86_64 else if (value == 0xb7) AARCH64 else null
            fun fromPe(value: Int) = if (value == 0x8664) X86_64 else if (value == 0xaa64) AARCH64 else null
            fun fromMach(value: Int) = if (value == 0x01000007) X86_64 else if (value == 0x0100000c) AARCH64 else null
        }
    }

    private enum class Target(
        val architecture: Architecture,
        val format: Format,
        val launcherSuffix: String,
        val javaSuffix: String,
        val jvmSuffix: String,
        val jliSuffix: String,
    ) {
        LINUX_X64(Architecture.X86_64, Format.ELF, "/spectre", "/runtime/bin/java", "/runtime/lib/server/libjvm.so", "/runtime/lib/libjli.so"),
        LINUX_ARM64(Architecture.AARCH64, Format.ELF, "/spectre", "/runtime/bin/java", "/runtime/lib/server/libjvm.so", "/runtime/lib/libjli.so"),
        MACOS_X64(Architecture.X86_64, Format.MACH_O, "/Contents/MacOS/spectre", "/runtime/bin/java", "/runtime/lib/server/libjvm.dylib", "/runtime/lib/libjli.dylib"),
        MACOS_ARM64(Architecture.AARCH64, Format.MACH_O, "/Contents/MacOS/spectre", "/runtime/bin/java", "/runtime/lib/server/libjvm.dylib", "/runtime/lib/libjli.dylib"),
        WINDOWS_X64(Architecture.X86_64, Format.PE, "/spectre.exe", "/runtime/bin/java.exe", "/runtime/bin/server/jvm.dll", "/runtime/bin/jli.dll");
        companion object {
            fun fromName(name: String) = entries.firstOrNull { it.name.replace("_", "").equals(name, true) }
                ?: error("Unsupported Roast target: $name")
        }
    }

    private fun ByteArray.matches(offset: Int, expected: ByteArray) =
        offset >= 0 && offset <= size - expected.size && expected.indices.all { this[offset + it] == expected[it] }
    private fun ByteArray.u16(offset: Int, order: ByteOrder) = ByteBuffer.wrap(this, offset, 2).order(order).short.toInt() and 0xffff
    private fun ByteArray.u32(offset: Int, order: ByteOrder) = ByteBuffer.wrap(this, offset, 4).order(order).int
    private fun ByteArray.unsigned(offset: Int, order: ByteOrder, wide: Boolean): Long =
        if (wide) ByteBuffer.wrap(this, offset, 8).order(order).long.takeIf { it >= 0 } ?: Long.MAX_VALUE
        else u32(offset, order).toLong() and UINT_MASK

    private val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    private val PE_MAGIC = byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 0, 0)
    private val NATIVE_NAME = Regex("(?:java(?:w)?\\.exe|jspawnhelper|.*\\.(?:dll|dylib|so)(?:\\..*)?)", RegexOption.IGNORE_CASE)
    private const val UINT_MASK = 0xffffffffL
    private const val MACH_HEADER_SIZE = 32L
    private const val MACH_64_BE = 0xfeedfacf.toInt()
    private const val MACH_64_LE = 0xcffaedfe.toInt()
    private const val FAT_32_BE = 0xcafebabe.toInt()
    private const val FAT_32_LE = 0xbebafeca.toInt()
    private const val FAT_64_BE = 0xcafebabf.toInt()
    private const val FAT_64_LE = 0xbfbafeca.toInt()
    private const val MAX_FAT_ARCHITECTURES = 16
}
