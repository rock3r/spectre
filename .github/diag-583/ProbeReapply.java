import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;

/** Shows a JFrame at a fixed spot, then compares AWT's location with what Robot sees. */
public class ProbeReapply {
    public static void main(String[] args) throws Exception {
        int x = 600, y = 400;
        AtomicReference<JFrame> ref = new AtomicReference<>();
        final int[] clicks = {0};
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame("probe");
            JPanel p = new JPanel();
            p.setBackground(new Color(0x33, 0x66, 0xCC));
            p.setPreferredSize(new Dimension(540, 320));
            p.addMouseListener(new MouseAdapter() { public void mousePressed(MouseEvent e) { clicks[0]++; } });
            f.setContentPane(p);
            f.pack();
            f.setLocation(x, y);
            f.setAlwaysOnTop(true);
            f.setVisible(true);
            ref.set(f);
        });
        Thread.sleep(Long.parseLong(System.getProperty("probe.wait", "1500")));
        JFrame f = ref.get();
        SwingUtilities.invokeAndWait(() -> f.setLocation(x, y));
        Thread.sleep(300);
        Point[] loc = new Point[1];
        SwingUtilities.invokeAndWait(() -> loc[0] = f.getContentPane().getLocationOnScreen());
        Robot r = new Robot();
        Color atAwt = r.getPixelColor(loc[0].x + 270, loc[0].y + 160);
        Color atReq = r.getPixelColor(x + 270, y + 160);
        r.mouseMove(loc[0].x + 270, loc[0].y + 160);
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK); r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        r.waitForIdle(); Thread.sleep(300);
        boolean ok = loc[0].x == x && loc[0].y == y && clicks[0] == 1;
        System.out.printf("RESULT %s frame=%s content=%s pixelAtAwt=%06X pixelAtRequested=%06X clicks=%d%n",
            ok ? "OK" : "BAD", f.getBounds(), loc[0], atAwt.getRGB() & 0xFFFFFF, atReq.getRGB() & 0xFFFFFF, clicks[0]);
        System.exit(ok ? 0 : 1);
    }
}
