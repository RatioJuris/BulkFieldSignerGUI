// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Versioned primitive-only format; legacy Java serialized files are never opened. */
public final class PlaceholderStore {
    public static final int MAX_COUNT = 10_000;
    private PlaceholderStore() { }

    public static void save(Path pdf, List<SignaturePlaceholder> placeholders) throws IOException {
        if (placeholders.size() > MAX_COUNT) throw new IOException("At most " + MAX_COUNT + " stamp areas are supported.");
        StringBuilder text = new StringBuilder("RJ-PLACEHOLDERS-1\n");
        for (SignaturePlaceholder p : placeholders) {
            text.append(p.page()).append(' ').append(p.x()).append(' ').append(p.y()).append(' ')
                    .append(p.width()).append(' ').append(p.height()).append('\n');
        }
        LocalFiles.atomicWrite(storagePath(pdf), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static List<SignaturePlaceholder> load(Path pdf) throws IOException {
        Path stored = storagePath(pdf);
        if (!Files.exists(stored)) return List.of();
        String[] lines = new String(LocalFiles.readBounded(stored, 1_048_576), StandardCharsets.UTF_8).split("\\R");
        if (lines.length == 0 || !"RJ-PLACEHOLDERS-1".equals(lines[0]) || lines.length > MAX_COUNT + 1) {
            throw new IOException("Invalid saved signature layout.");
        }
        List<SignaturePlaceholder> result = new ArrayList<>();
        try {
            for (int i = 1; i < lines.length; i++) {
                String[] values = lines[i].split(" ");
                if (values.length != 5) throw new IllegalArgumentException("Expected five values.");
                result.add(new SignaturePlaceholder(Integer.parseInt(values[0]), Float.parseFloat(values[1]),
                        Float.parseFloat(values[2]), Float.parseFloat(values[3]), Float.parseFloat(values[4])));
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid saved signature layout.", ex);
        }
        return List.copyOf(result);
    }

    private static Path storagePath(Path pdf) throws IOException {
        return AppPaths.requireLocal(AppPaths.PLACEHOLDERS.resolve(LocalFiles.fingerprint(pdf) + ".txt"));
    }
}
