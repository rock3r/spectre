package verification;

import java.awt.Color;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** A genuinely separate JVM with the same title as the governed red target. */
public final class BlueDecoy {
    private BlueDecoy() {}

    public static void main(String[] args) throws Exception {
        Path reveal = Path.of(args[1]);
        JFrame[] holder = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Spectre governed same-title target");
            JPanel panel = new JPanel();
            panel.setBackground(Color.BLUE);
            frame.setContentPane(panel);
            frame.setBounds(300, 240, 480, 320);
            frame.setType(Window.Type.NORMAL);
            frame.setAlwaysOnTop(true);
            holder[0] = frame;
        });
        Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (!Files.exists(reveal) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        if (!Files.exists(reveal)) {
            throw new IllegalStateException("target readiness was not signalled within 60 seconds");
        }
        SwingUtilities.invokeAndWait(() -> {
            holder[0].setVisible(true);
            holder[0].toFront();
            holder[0].requestFocus();
        });
        Thread.currentThread().join();
    }
}
