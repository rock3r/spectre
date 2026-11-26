package dev.sebastiano.spectre.build

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliRuntimeArchitectureContractTest {
    @Test
    fun `real little-endian thin Mach headers accept both released CPUs`() {
        assertValid("macosX64", macEntries(thinMach(0x01000007)))
        assertValid("macosArm64", macEntries(thinMach(0x0100000c)))
    }

    @Test
    fun `fat32 and fat64 require real correctly typed slices`() {
        assertValid("macosArm64", macEntries(fatMach(false, 0x01000007, 0x0100000c)))
        assertValid("macosX64", macEntries(fatMach(true, 0x01000007, 0x0100000c)))
        val corrupt = fatMach(false, 0x01000007).also { thinMach(0x0100000c).copyInto(it, 64) }
        assertTrue(CliRuntimeArchitectureContract.validate("macosX64", macEntries(corrupt)).isNotEmpty())
    }

    @Test
    fun `same-CPU binaries disguised as another format are rejected`() {
        val windows = windowsEntries().toMutableMap()
        windows["bundle/runtime/bin/server/jvm.dll"] = elf(0x3e)
        assertTrue(CliRuntimeArchitectureContract.validate("windowsX64", windows).any { "requires PE" in it })
        val linux = linuxEntries(0x3e).toMutableMap()
        linux["bundle/runtime/lib/server/libjvm.so"] = pe(0x8664)
        assertTrue(CliRuntimeArchitectureContract.validate("linuxX64", linux).any { "requires ELF64" in it })
    }

    @Test
    fun `ELF class byte order and PE signature are validated`() {
        assertValid("linuxArm64", linuxEntries(0xb7))
        assertValid("windowsX64", windowsEntries())
        val elf32 = linuxEntries(0x3e).toMutableMap()
        elf32["bundle/runtime/bin/java"] = elf(0x3e).also { it[4] = 1 }
        assertTrue(CliRuntimeArchitectureContract.validate("linuxX64", elf32).isNotEmpty())
        val fakePe = windowsEntries().toMutableMap()
        fakePe["bundle/runtime/bin/java.exe"] = pe(0x8664).also { it[64] = 0 }
        assertTrue(CliRuntimeArchitectureContract.validate("windowsX64", fakePe).isNotEmpty())
    }

    private fun assertValid(target: String, entries: Map<String, ByteArray>) {
        val errors = CliRuntimeArchitectureContract.validate(target, entries)
        assertTrue(errors.isEmpty(), errors.joinToString())
    }

    private fun linuxEntries(cpu: Int) = mapOf(
        "bundle/spectre" to elf(cpu),
        "bundle/runtime/bin/java" to elf(cpu),
        "bundle/runtime/lib/server/libjvm.so" to elf(cpu),
        "bundle/runtime/lib/libjli.so" to elf(cpu),
        "bundle/runtime/lib/libverify.so" to elf(cpu),
        "bundle/runtime/lib/jspawnhelper" to elf(cpu),
    )

    private fun windowsEntries() = mapOf(
        "bundle/spectre.exe" to pe(0x8664),
        "bundle/runtime/bin/java.exe" to pe(0x8664),
        "bundle/runtime/bin/server/jvm.dll" to pe(0x8664),
        "bundle/runtime/bin/jli.dll" to pe(0x8664),
    )

    private fun macEntries(binary: ByteArray) = mapOf(
        "bundle/Spectre.app/Contents/MacOS/spectre" to binary,
        "bundle/Spectre.app/Contents/Resources/runtime/bin/java" to binary,
        "bundle/Spectre.app/Contents/Resources/runtime/lib/server/libjvm.dylib" to binary,
        "bundle/Spectre.app/Contents/Resources/runtime/lib/libjli.dylib" to binary,
    )

    private fun elf(machine: Int) = ByteArray(64).also {
        it[0] = 0x7f; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = 2; it[5] = 1
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(18, machine.toShort())
    }

    private fun pe(machine: Int) = ByteArray(128).also {
        it[0] = 'M'.code.toByte(); it[1] = 'Z'.code.toByte()
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3c, 64)
        it[64] = 'P'.code.toByte(); it[65] = 'E'.code.toByte()
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(68, machine.toShort())
    }

    /** First 32 bytes match genuine little-endian 64-bit Mach-O headers. */
    private fun thinMach(cpu: Int) = ByteArray(32).also {
        val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0xfeedfacf.toInt()).putInt(cpu).putInt(0).putInt(6).putInt(0).putInt(0).putInt(0).putInt(0)
    }

    private fun fatMach(fat64: Boolean, vararg cpus: Int): ByteArray {
        val recordSize = if (fat64) 32 else 20
        val bytes = ByteArray(64 + cpus.size * 64)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.putInt(if (fat64) 0xcafebabf.toInt() else 0xcafebabe.toInt()).putInt(cpus.size)
        cpus.forEachIndexed { index, cpu ->
            b.putInt(8 + index * recordSize, cpu)
            if (fat64) {
                b.putLong(16 + index * recordSize, (64 + index * 64).toLong())
                b.putLong(24 + index * recordSize, 32)
            } else {
                b.putInt(16 + index * recordSize, 64 + index * 64)
                b.putInt(20 + index * recordSize, 32)
            }
            thinMach(cpu).copyInto(bytes, 64 + index * 64)
        }
        return bytes
    }
}
