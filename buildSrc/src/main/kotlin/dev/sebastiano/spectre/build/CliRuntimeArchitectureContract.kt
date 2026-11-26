package dev.sebastiano.spectre.build

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Fail-closed native-binary contract for the five published Roast archives. */
object CliRuntimeArchitectureContract {
    class Payload(val length: Long, val open: () -> InputStream) {
        constructor(bytes: ByteArray) : this(bytes.size.toLong(), { ByteArrayInputStream(bytes) })

        fun read(offset: Long, count: Int): ByteArray? {
            if (offset < 0 || count < 0 || offset > length || count.toLong() > length - offset) return null
            return open().use { input ->
                input.skipNBytes(offset)
                input.readNBytes(count).takeIf { it.size == count }
            }
        }
    }

    fun validate(targetName: String, entries: Map<String, ByteArray>): List<String> =
        validatePayloads(targetName, entries.mapValues { Payload(it.value) })

    fun validatePayloads(targetName: String, entries: Map<String, Payload>): List<String> {
        val target = Target.fromName(targetName)
        val binaries = entries.mapValues { (_, payload) -> NativeBinary.parse(payload) }
        val errors = mutableListOf<String>()

        fun requireOne(label: String, predicate: (String) -> Boolean) {
            val matches = entries.keys.filter(predicate)
            if (matches.size != 1) errors += "$targetName must contain exactly one $label; found ${matches.sorted()}"
        }
        requireOne("outer launcher") { name -> name.endsWith(target.launcherSuffix) }
        requireOne("runtime java launcher") { name -> name.endsWith(target.javaSuffix) }
        requireOne("main JVM") { name -> name.endsWith(target.jvmSuffix) }
        requireOne("runtime libjli") { name -> name.endsWith(target.jliSuffix) }
        if (target.format != Format.PE) {
            requireOne("runtime lib/jspawnhelper") { name -> name.endsWith("/runtime/lib/jspawnhelper") }
        }

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

    fun isNativePayloadName(name: String): Boolean =
        name.endsWith("/spectre") ||
            name.endsWith("/spectre.exe") ||
            (name.contains("/runtime/") && NATIVE_NAME.matches(name.substringAfterLast('/')))

    private data class NativeBinary(val format: Format, val architectures: Set<Architecture>) {
        companion object {
            fun parse(payload: Payload): NativeBinary? {
                val prefix = payload.read(0, minOf(64L, payload.length).toInt()) ?: return null
                if (prefix.size >= 20 && prefix.matches(0, ELF_MAGIC)) {
                    if (prefix[4].toInt() != 2) return null
                    val order = when (prefix[5].toInt()) {
                        1 -> ByteOrder.LITTLE_ENDIAN
                        2 -> ByteOrder.BIG_ENDIAN
                        else -> return null
                    }
                    return NativeBinary(Format.ELF, setOfNotNull(Architecture.fromElf(prefix.u16(18, order))))
                }
                if (prefix.size >= 64 && prefix[0] == 'M'.code.toByte() && prefix[1] == 'Z'.code.toByte()) {
                    val peOffset = prefix.u32(0x3c, ByteOrder.LITTLE_ENDIAN).toLong() and UINT_MASK
                    val pe = payload.read(peOffset, 6) ?: return null
                    if (!pe.matches(0, PE_MAGIC)) return null
                    return NativeBinary(Format.PE, setOfNotNull(Architecture.fromPe(pe.u16(4, ByteOrder.LITTLE_ENDIAN))))
                }
                return parseMach(payload, prefix)
            }

            private fun parseMach(payload: Payload, prefix: ByteArray): NativeBinary? {
                if (prefix.size < 4) return null
                val magic = prefix.u32(0, ByteOrder.BIG_ENDIAN)
                val thinOrder = when (magic) {
                    MACH_64_BE -> ByteOrder.BIG_ENDIAN
                    MACH_64_LE -> ByteOrder.LITTLE_ENDIAN
                    else -> null
                }
                if (thinOrder != null) return parseThinMach(payload, 0, payload.length, null, thinOrder)
                val fat64 = magic == FAT_64_BE || magic == FAT_64_LE
                val order = when (magic) {
                    FAT_32_BE, FAT_64_BE -> ByteOrder.BIG_ENDIAN
                    FAT_32_LE, FAT_64_LE -> ByteOrder.LITTLE_ENDIAN
                    else -> return null
                }
                val fatHeader = payload.read(0, 8) ?: return null
                val count = fatHeader.u32(4, order)
                val recordSize = if (fat64) 32 else 20
                if (count !in 1..MAX_FAT_ARCHITECTURES) return null
                val records = payload.read(8, count * recordSize) ?: return null
                val architectures = mutableSetOf<Architecture>()
                repeat(count) { index ->
                    val record = index * recordSize
                    val declared = Architecture.fromMach(records.u32(record, order)) ?: return null
                    val offset = records.unsigned(record + 8, order, fat64)
                    val size = records.unsigned(record + if (fat64) 16 else 12, order, fat64)
                    if (offset > payload.length || size < MACH_HEADER_SIZE || size > payload.length - offset) return null
                    val header = payload.read(offset, MACH_HEADER_SIZE.toInt()) ?: return null
                    val memberOrder = when (header.u32(0, ByteOrder.BIG_ENDIAN)) {
                        MACH_64_BE -> ByteOrder.BIG_ENDIAN
                        MACH_64_LE -> ByteOrder.LITTLE_ENDIAN
                        else -> return null // Nested fat members are invalid rather than recursive.
                    }
                    parseThinMach(payload, offset, size, declared, memberOrder) ?: return null
                    architectures += declared
                }
                return NativeBinary(Format.MACH_O, architectures)
            }

            private fun parseThinMach(
                payload: Payload,
                offset: Long,
                available: Long,
                declared: Architecture?,
                order: ByteOrder,
            ): NativeBinary? {
                if (available < MACH_HEADER_SIZE) return null
                val header = payload.read(offset, MACH_HEADER_SIZE.toInt()) ?: return null
                val actual = Architecture.fromMach(header.u32(4, order)) ?: return null
                if (declared != null && actual != declared) return null
                return NativeBinary(Format.MACH_O, setOf(actual))
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
    private val NATIVE_NAME = Regex("(?:java(?:w)?(?:\\.exe)?|jspawnhelper|.*\\.(?:dll|dylib|so)(?:\\..*)?)", RegexOption.IGNORE_CASE)
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
