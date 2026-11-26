package verification;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** A genuinely separate JVM with the same title as the governed red target. */
public final class BlueDecoy {
    private BlueDecoy() {}

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Spectre governed same-title target");
            JPanel panel = new JPanel();
            panel.setBackground(Color.BLUE);
            frame.setContentPane(panel);
            frame.setBounds(300, 240, 480, 320);
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
        });
        Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        Thread.currentThread().join();
    }
}
