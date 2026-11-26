package dev.sebastiano.spectre.cli.daemon

import java.io.OutputStream
import java.util.HexFormat

/** Bit-exact Hello emitted by the published protocol 1.15 client at 104ba237. */
internal val published15Hello: ByteArray =
    HexFormat.of()
        .parseHex(
            "9f78356465762e73656261737469616e6f2e737065637472652e636c692e6461656d6f6e" +
                "2e4461656d6f6e526571756573742e48656c6c6fbf6d636c69656e7456657273696f6e" +
                "bf656d616a6f7201656d696e6f720fff6d636f6e66696775726174696f6ebfffffff"
        )

internal fun writePublished15Hello(output: OutputStream) {
    DaemonFraming.writeFrame(output, published15Hello)
}
