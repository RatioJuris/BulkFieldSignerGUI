// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Stamp designs and signature images in {@code config\stamps}. Blocking: call from workers only. */
public final class StampStore {
    public static final int MAX_DESIGNS = 50;
    public static final long MAX_IMPORT_BYTES = 10L * 1024 * 1024;
    private static final int MAX_SOURCE_PIXELS = 40_000_000;
    private static final int MAX_STORED_EDGE = 1200;
    private StampStore() { }

    public static List<StampDesign> list() throws IOException {
        List<StampDesign> designs = new ArrayList<>();
        try (var files = Files.list(AppPaths.requireLocal(AppPaths.STAMPS))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".properties")).sorted().limit(MAX_DESIGNS).toList()) {
                try { designs.add(read(file)); }
                catch (IOException | IllegalArgumentException ex) {
                    System.getLogger(StampStore.class.getName()).log(System.Logger.Level.WARNING, "Skipping invalid stamp design " + file, ex);
                }
            }
        }
        for (StampDesign builtIn : List.of(StampDesign.standard(), StampDesign.classic())) {
            if (designs.stream().noneMatch(d -> d.id().equals(builtIn.id()))) designs.add(builtIn);
        }
        designs.sort(Comparator.comparing((StampDesign d) -> !d.id().equals(StampDesign.DEFAULT_ID))
                .thenComparing(d -> !d.id().equals(StampDesign.CLASSIC_ID))
                .thenComparing(StampDesign::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(designs);
    }

    public static StampDesign find(String id) throws IOException {
        return list().stream().filter(d -> d.id().equals(id)).findFirst().orElse(StampDesign.standard());
    }

    public static String newId() { return "d-" + UUID.randomUUID().toString().substring(0, 13); }

    /** Saves the design and, when {@code image} is non-null, replaces its PNG. */
    public static StampDesign save(StampDesign design, BufferedImage image, boolean removeImage) throws IOException {
        boolean exists = Files.exists(propertiesPath(design.id()));
        if (!exists && countStored() >= MAX_DESIGNS) throw new IOException("At most " + MAX_DESIGNS + " stamp designs can be saved.");
        boolean hasImage = design.hasImage();
        if (image != null) {
            LocalFiles.atomicWrite(imagePath(design.id()), png(image));
            hasImage = true;
        } else if (removeImage) {
            Files.deleteIfExists(imagePath(design.id()));
            hasImage = false;
        }
        StampDesign saved = new StampDesign(design.id(), design.name(), design.template(), design.layout(), design.ink(), design.border(),
                hasImage, design.dateStyle());
        Properties values = new Properties();
        values.setProperty("name", saved.name());
        values.setProperty("template", saved.template());
        values.setProperty("layout", saved.layout().name());
        values.setProperty("ink", saved.ink().name());
        values.setProperty("border", Boolean.toString(saved.border()));
        values.setProperty("image", Boolean.toString(saved.hasImage()));
        values.setProperty("date", saved.dateStyle().name());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, "Ratio Juris stamp design");
        LocalFiles.atomicWrite(propertiesPath(saved.id()), bytes.toByteArray());
        return saved;
    }

    public static void delete(String id) throws IOException {
        Files.deleteIfExists(propertiesPath(id));
        Files.deleteIfExists(imagePath(id));
    }

    /** The validated PNG bytes of a design's image, or null. */
    public static byte[] imageBytes(StampDesign design) throws IOException {
        if (!design.hasImage()) return null;
        Path path = imagePath(design.id());
        if (!Files.exists(path)) return null;
        byte[] bytes = LocalFiles.readBounded(path, 8 * 1024 * 1024);
        decode(bytes); // Reject damaged or oversized files before they reach a PDF.
        return bytes;
    }

    public static BufferedImage image(StampDesign design) throws IOException {
        byte[] bytes = imageBytes(design);
        return bytes == null ? null : decode(bytes);
    }

    /** Reads an external PNG/JPEG/GIF/BMP (read access only) and normalizes it for storage. */
    public static BufferedImage importImage(Path external) throws IOException {
        if (!Files.isRegularFile(external) || !Files.isReadable(external)) throw new IOException("Select a readable image file.");
        if (Files.size(external) > MAX_IMPORT_BYTES) throw new IOException("Images are limited to 10 MiB.");
        byte[] bytes;
        try (var input = Files.newInputStream(external)) { bytes = input.readNBytes((int) MAX_IMPORT_BYTES + 1); }
        if (bytes.length > MAX_IMPORT_BYTES) throw new IOException("Images are limited to 10 MiB.");
        BufferedImage image = decode(bytes);
        double scale = Math.min(1, MAX_STORED_EDGE / (double) Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = normalized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally { g.dispose(); }
        return normalized;
    }

    static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image. Use PNG, JPEG, GIF or BMP.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_SOURCE_PIXELS) throw new IOException("Image dimensions are too large.");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG encoding is unavailable.");
        return bytes.toByteArray();
    }

    private static StampDesign read(Path file) throws IOException {
        Properties values = new Properties();
        values.load(new ByteArrayInputStream(LocalFiles.readBounded(file, 16_384)));
        String id = file.getFileName().toString().replaceFirst("\\.properties$", "");
        return new StampDesign(id, values.getProperty("name"), values.getProperty("template", StampDesign.DEFAULT_TEMPLATE),
                parse(StampDesign.Layout.class, values.getProperty("layout"), StampDesign.Layout.TEXT_ONLY),
                parse(StampDesign.Ink.class, values.getProperty("ink"), StampDesign.Ink.BLUE),
                Boolean.parseBoolean(values.getProperty("border", "true")),
                Boolean.parseBoolean(values.getProperty("image", "false")),
                parse(StampDesign.DateStyle.class, values.getProperty("date"), StampDesign.DateStyle.INDIAN));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        try { return value == null ? fallback : Enum.valueOf(type, value); }
        catch (IllegalArgumentException ex) { return fallback; }
    }

    private static long countStored() throws IOException {
        try (var files = Files.list(AppPaths.requireLocal(AppPaths.STAMPS))) {
            return files.filter(f -> f.getFileName().toString().endsWith(".properties")).count();
        }
    }

    private static Path propertiesPath(String id) throws IOException { return AppPaths.requireLocal(AppPaths.STAMPS.resolve(id + ".properties")); }
    private static Path imagePath(String id) throws IOException { return AppPaths.requireLocal(AppPaths.STAMPS.resolve(id + ".png")); }
}
