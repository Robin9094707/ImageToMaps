package dev.simplex.itm.maps;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Decodes animated GIFs into fully composed frames (respects offsets, disposal methods and frame delays). */
public final class GifDecoder {

    public record Result(List<BufferedImage> frames, List<Integer> delaysMs) {}

    private GifDecoder() {}

    public static Result decode(Path file, int maxFrames) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) throw new IOException("No GIF reader available");
            ImageReader reader = readers.next();
            reader.setInput(in, false);

            int width = -1, height = -1;
            IIOMetadata streamMeta = reader.getStreamMetadata();
            if (streamMeta != null) {
                Node root = streamMeta.getAsTree(streamMeta.getNativeMetadataFormatName());
                Node lsd = child(root, "LogicalScreenDescriptor");
                if (lsd != null) {
                    width = intAttr(lsd, "logicalScreenWidth", -1);
                    height = intAttr(lsd, "logicalScreenHeight", -1);
                }
            }

            List<BufferedImage> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            BufferedImage canvas = null;
            BufferedImage previous = null;

            int count;
            try {
                count = reader.getNumImages(true);
            } catch (Exception e) {
                count = Integer.MAX_VALUE;
            }
            for (int i = 0; i < count && frames.size() < maxFrames; i++) {
                BufferedImage raw;
                try {
                    raw = reader.read(i);
                } catch (IndexOutOfBoundsException e) {
                    break;
                }
                IIOMetadata meta = reader.getImageMetadata(i);
                Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
                Node desc = child(root, "ImageDescriptor");
                Node gce = child(root, "GraphicControlExtension");
                int x = desc != null ? intAttr(desc, "imageLeftPosition", 0) : 0;
                int y = desc != null ? intAttr(desc, "imageTopPosition", 0) : 0;
                int delay = gce != null ? intAttr(gce, "delayTime", 10) * 10 : 100;
                String disposal = gce != null ? strAttr(gce, "disposalMethod", "none") : "none";
                if (delay < 20) delay = 100; // browsers treat 0/1 as 100 ms

                if (canvas == null) {
                    if (width <= 0) width = raw.getWidth() + x;
                    if (height <= 0) height = raw.getHeight() + y;
                    canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                }
                if ("restoreToPrevious".equals(disposal)) previous = copy(canvas);

                Graphics2D g = canvas.createGraphics();
                g.drawImage(raw, x, y, null);
                g.dispose();

                frames.add(copy(canvas));
                delays.add(delay);

                switch (disposal) {
                    case "restoreToBackgroundColor" -> {
                        Graphics2D c = canvas.createGraphics();
                        c.setComposite(AlphaComposite.Clear);
                        c.fillRect(x, y, raw.getWidth(), raw.getHeight());
                        c.dispose();
                    }
                    case "restoreToPrevious" -> {
                        if (previous != null) canvas = previous;
                    }
                    default -> {
                    }
                }
            }
            reader.dispose();
            if (frames.isEmpty()) throw new IOException("GIF contains no frames");
            return new Result(frames, delays);
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage b = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return b;
    }

    private static Node child(Node root, String name) {
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeName().equals(name)) return n;
        }
        return null;
    }

    private static int intAttr(Node node, String name, int def) {
        String s = strAttr(node, name, null);
        try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static String strAttr(Node node, String name, String def) {
        NamedNodeMap attrs = node.getAttributes();
        if (attrs == null) return def;
        Node n = attrs.getNamedItem(name);
        return n == null ? def : n.getNodeValue();
    }
}
