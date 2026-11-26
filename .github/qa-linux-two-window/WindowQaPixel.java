import java.io.File;
import javax.imageio.ImageIO;
class WindowQaPixel {
    public static void main(String[] args) throws Exception {
        var image = ImageIO.read(new File(args[0]));
        int expected = Integer.parseInt(args[1],16);
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        if (mac) {
            expected = switch (expected) { case 0x174ea6 -> 0x274da0; case 0xe8710a -> 0xd9782f; default -> throw new AssertionError(); };
        }
        int tolerance = mac ? 2 : 0;
        int matches = 0;
        for (int x = 1; x <= 4; x++) {
            for (int y = 1; y <= 4; y++) {
                int rgb = image.getRGB(image.getWidth()*x/5,image.getHeight()*y/5) & 0xffffff;
                boolean close = true;
                for (int shift : new int[] {0, 8, 16}) {
                    close &= Math.abs(((rgb >> shift) & 255) - ((expected >> shift) & 255)) <= tolerance;
                }
                if (close) matches++;
            }
        }
        System.out.printf("%dx%d %d/16 match #%06x%n",image.getWidth(),image.getHeight(),matches,expected);
        if (matches < 8) throw new AssertionError("PNG does not contain the selected fixture window");
    }
}
