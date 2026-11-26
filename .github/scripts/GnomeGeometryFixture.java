import java.awt.*;
import javax.swing.*;

public final class GnomeGeometryFixture {
  private static String rect(Rectangle r) { return String.format("{\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d}", r.x,r.y,r.width,r.height); }
  private static String point(Point p) { return String.format("{\"x\":%d,\"y\":%d}", p.x,p.y); }
  public static void main(String[] args) throws Exception {
    boolean decorated = args[0].equals("decorated"); int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
    SwingUtilities.invokeAndWait(() -> {
      JFrame f = new JFrame("Spectre geometry " + args[0]); f.setUndecorated(!decorated);
      JPanel root = new JPanel(new BorderLayout()); root.setBackground(new Color(25,25,25));
      JPanel stripe = new JPanel(); stripe.setBackground(new Color(3, 231, 97)); stripe.setPreferredSize(new Dimension(480, 13));
      JComponent center = new JComponent() { protected void paintComponent(Graphics g) { super.paintComponent(g); g.setColor(new Color(237,31,211)); g.fillRect(getWidth()/2-9,getHeight()/2-9,19,19); } };
      center.setOpaque(true); center.setBackground(new Color(20, 45, 210)); root.add(stripe, BorderLayout.NORTH); root.add(center, BorderLayout.CENTER);
      f.setContentPane(root); f.setBounds(x,y,480,320); f.setVisible(true);
      Toolkit.getDefaultToolkit().sync();
      Insets i=f.getInsets(); GraphicsConfiguration gc=f.getGraphicsConfiguration();
      System.out.printf("{\"schemaVersion\":1,\"units\":\"AWT user-space pixels\",\"kind\":\"%s\",\"pid\":%d,\"title\":\"%s\",\"topLevelBounds\":%s,\"topLevelLocationOnScreen\":%s,\"rootBounds\":%s,\"rootLocationOnScreen\":%s,\"contentBounds\":%s,\"contentLocationOnScreen\":%s,\"insets\":{\"top\":%d,\"left\":%d,\"bottom\":%d,\"right\":%d},\"defaultTransform\":{\"scaleX\":%.6f,\"scaleY\":%.6f,\"translateX\":%.6f,\"translateY\":%.6f},\"deviceBounds\":%s}%n",
        args[0],ProcessHandle.current().pid(),f.getTitle(),rect(f.getBounds()),point(f.getLocationOnScreen()),rect(root.getBounds()),point(root.getLocationOnScreen()),rect(f.getContentPane().getBounds()),point(f.getContentPane().getLocationOnScreen()),i.top,i.left,i.bottom,i.right,gc.getDefaultTransform().getScaleX(),gc.getDefaultTransform().getScaleY(),gc.getDefaultTransform().getTranslateX(),gc.getDefaultTransform().getTranslateY(),rect(gc.getBounds()));
      System.out.flush();
    });
    Thread.sleep(120000);
  }
}
