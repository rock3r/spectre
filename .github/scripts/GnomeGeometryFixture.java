import java.awt.*;
import javax.swing.*;

public final class GnomeGeometryFixture {
  private static String rect(Rectangle r) { return String.format("{\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d}", r.x,r.y,r.width,r.height); }
  private static String point(Point p) { return String.format("{\"x\":%d,\"y\":%d}", p.x,p.y); }
  public static void main(String[] args) throws Exception {
    if (args.length != 7) throw new IllegalArgumentException(
      "usage: CASE DECORATED X Y WIDTH HEIGHT TITLE");
    String caseName = args[0]; boolean decorated = Boolean.parseBoolean(args[1]);
    int x = Integer.parseInt(args[2]), y = Integer.parseInt(args[3]);
    int width = Integer.parseInt(args[4]), height = Integer.parseInt(args[5]);
    String title = args[6];
    SwingUtilities.invokeAndWait(() -> {
      JFrame f = new JFrame(title); f.setUndecorated(!decorated);
      JPanel root = new JPanel(new BorderLayout()); root.setBackground(new Color(25,25,25));
      JPanel stripe = new JPanel(); stripe.setBackground(new Color(3, 231, 97)); stripe.setPreferredSize(new Dimension(480, 13));
      JComponent center = new JComponent() { protected void paintComponent(Graphics g) { super.paintComponent(g); g.setColor(new Color(237,31,211)); g.fillRect(getWidth()/2-9,getHeight()/2-9,19,19); } };
      center.setOpaque(true); center.setBackground(new Color(20, 45, 210)); root.add(stripe, BorderLayout.NORTH); root.add(center, BorderLayout.CENTER);
      f.setContentPane(root); f.setBounds(x,y,width,height); f.setVisible(true);
      Toolkit.getDefaultToolkit().sync();
      Insets i=f.getInsets(); GraphicsConfiguration gc=f.getGraphicsConfiguration();
      System.out.printf("{\"schemaVersion\":2,\"units\":\"AWT user-space pixels\",\"caseName\":\"%s\",\"decorated\":%s,\"requestedTopLevelBounds\":%s,\"pid\":%d,\"title\":\"%s\",\"topLevelBounds\":%s,\"topLevelLocationOnScreen\":%s,\"rootBounds\":%s,\"rootLocationOnScreen\":%s,\"contentBounds\":%s,\"contentLocationOnScreen\":%s,\"insets\":{\"top\":%d,\"left\":%d,\"bottom\":%d,\"right\":%d},\"defaultTransform\":{\"scaleX\":%.6f,\"scaleY\":%.6f,\"translateX\":%.6f,\"translateY\":%.6f},\"deviceBounds\":%s}%n",
        caseName,decorated,rect(new Rectangle(x,y,width,height)),ProcessHandle.current().pid(),f.getTitle(),rect(f.getBounds()),point(f.getLocationOnScreen()),rect(root.getBounds()),point(root.getLocationOnScreen()),rect(f.getContentPane().getBounds()),point(f.getContentPane().getLocationOnScreen()),i.top,i.left,i.bottom,i.right,gc.getDefaultTransform().getScaleX(),gc.getDefaultTransform().getScaleY(),gc.getDefaultTransform().getTranslateX(),gc.getDefaultTransform().getTranslateY(),rect(gc.getBounds()));
      System.out.flush();
    });
    Thread.sleep(120000);
  }
}
