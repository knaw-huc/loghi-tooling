import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import nl.knaw.huc.di.images.layoutds.models.Page.TextRegion;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class RecalculateContoursBenchmark {
    private static final class Config {
        private Path imageDir = Paths.get("/data/ijsberg/full/train");
        private Path xmlDir = Paths.get("/data/ijsberg/full/train/page");
        private Path csv = Paths.get("/tmp/recalculate-contours-benchmark.csv");
        private String label = "benchmark";
        private int limit = 20;
        private int warmup = 1;
        private int runs = 3;
        private double scaleDownFactor = 4;
        private int minimumInterlineDistance = 35;
        private int thickness = 10;
        private int minimumBaselineThickness = 1;
        private boolean ignoreBroken = true;
        private boolean ignoreXmlErrors = false;
        private boolean suppressStderr = true;
        private Path dumpContours = null;
    }

    private static final class Pair {
        private final String stem;
        private final Path image;
        private final Path xml;

        private Pair(String stem, Path image, Path xml) {
            this.stem = stem;
            this.image = image;
            this.xml = xml;
        }
    }

    private static final class Measurement {
        private final String stem;
        private final int run;
        private final long elapsedNanos;
        private final int width;
        private final int height;
        private final int lines;

        private Measurement(String stem, int run, long elapsedNanos, int width, int height, int lines) {
            this.stem = stem;
            this.run = run;
            this.elapsedNanos = elapsedNanos;
            this.width = width;
            this.height = height;
            this.lines = lines;
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Config config = parseArgs(args);
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

        List<Pair> pairs = selectPairs(findPairs(config), config.limit);
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("No image/xml pairs found in " + config.imageDir + " and " + config.xmlDir);
        }

        if (config.csv.getParent() != null) {
            Files.createDirectories(config.csv.getParent());
        }

        List<Measurement> measurements = new ArrayList<>();
        int failures = 0;
        BufferedWriter dumpWriter = null;
        if (config.dumpContours != null) {
            if (config.dumpContours.getParent() != null) {
                Files.createDirectories(config.dumpContours.getParent());
            }
            dumpWriter = Files.newBufferedWriter(config.dumpContours, StandardCharsets.UTF_8);
            dumpWriter.write("stem\tlineId\tcontour");
            dumpWriter.newLine();
        }
        try (BufferedWriter writer = Files.newBufferedWriter(config.csv, StandardCharsets.UTF_8)) {
            writer.write("label,stem,run,elapsed_ms,width,height,lines,status,error");
            writer.newLine();

            for (int i = 0; i < config.warmup; i++) {
                failures += executeRun(config, pairs, -1, writer, null, null);
                System.gc();
            }

            for (int run = 1; run <= config.runs; run++) {
                // Only dump contours during run 1 to keep the dump deterministic
                // and avoid quadrupling the file size.
                BufferedWriter dumpForRun = run == 1 ? dumpWriter : null;
                failures += executeRun(config, pairs, run, writer, measurements, dumpForRun);
                System.gc();
            }
        } finally {
            if (dumpWriter != null) {
                dumpWriter.close();
            }
        }

        List<Double> elapsedMillis = new ArrayList<>();
        for (Measurement measurement : measurements) {
            elapsedMillis.add(measurement.elapsedNanos / 1_000_000d);
        }
        Collections.sort(elapsedMillis);

        double totalMillis = 0;
        for (double elapsed : elapsedMillis) {
            totalMillis += elapsed;
        }

        System.out.printf(Locale.ROOT,
                "BENCHMARK_SUMMARY label=%s pairs=%d runs=%d measurements=%d failures=%d total_ms=%.3f median_ms=%.3f p95_ms=%.3f peak_rss_kb=%d csv=%s%n",
                config.label,
                pairs.size(),
                config.runs,
                measurements.size(),
                failures,
                totalMillis,
                percentile(elapsedMillis, 0.50),
                percentile(elapsedMillis, 0.95),
                peakRssKb(),
                config.csv);
    }

    private static int executeRun(Config config, List<Pair> pairs, int run, BufferedWriter writer,
                                  List<Measurement> measurements, BufferedWriter dumpWriter) throws IOException {
        int failures = 0;
        for (Pair pair : pairs) {
            Mat image = null;
            try {
                String xml = Files.readString(pair.xml);
                PcGts page = PageUtils.readPageFromString(xml, config.ignoreXmlErrors);
                if (page == null) {
                    throw new IllegalStateException("PAGE parse returned null");
                }

                image = OpenCVWrapper.imread(pair.image.toString(), Imgcodecs.IMREAD_COLOR);
                if (image.empty()) {
                    throw new IllegalStateException("Image could not be read");
                }
                int lines = countLines(page);

                long elapsed = measureRecalculate(config, pair, image, page);

                if (run > 0) {
                    Measurement measurement = new Measurement(pair.stem, run, elapsed, image.width(), image.height(), lines);
                    measurements.add(measurement);
                    writeCsv(writer, config.label, measurement, "ok", "");
                    if (dumpWriter != null) {
                        dumpContours(dumpWriter, pair.stem, page);
                    }
                }
            } catch (Exception exception) {
                failures++;
                if (run > 0) {
                    writer.write(csv(config.label));
                    writer.write(",");
                    writer.write(csv(pair.stem));
                    writer.write(",");
                    writer.write(Integer.toString(run));
                    writer.write(",,,,,error,");
                    writer.write(csv(exception.getClass().getSimpleName() + ": " + exception.getMessage()));
                    writer.newLine();
                }
            } finally {
                if (image != null && image.dataAddr() != 0) {
                    image = OpenCVWrapper.release(image);
                }
            }
        }
        return failures;
    }

    private static long measureRecalculate(Config config, Pair pair, Mat image, PcGts page) {
        PrintStream originalErr = System.err;
        PrintStream mutedErr = null;
        if (config.suppressStderr) {
            mutedErr = new PrintStream(OutputStream.nullOutputStream());
            System.setErr(mutedErr);
        }

        long start = System.nanoTime();
        try {
            LayoutProc.recalculateTextLineContoursFromBaselines(
                    pair.stem,
                    image,
                    page,
                    config.scaleDownFactor,
                    config.minimumInterlineDistance,
                    config.thickness,
                    config.minimumBaselineThickness,
                    config.ignoreBroken);
            return System.nanoTime() - start;
        } finally {
            if (mutedErr != null) {
                System.setErr(originalErr);
                mutedErr.close();
            }
        }
    }

    private static void writeCsv(BufferedWriter writer, String label, Measurement measurement,
                                 String status, String error) throws IOException {
        writer.write(csv(label));
        writer.write(",");
        writer.write(csv(measurement.stem));
        writer.write(",");
        writer.write(Integer.toString(measurement.run));
        writer.write(",");
        writer.write(String.format(Locale.ROOT, "%.3f", measurement.elapsedNanos / 1_000_000d));
        writer.write(",");
        writer.write(Integer.toString(measurement.width));
        writer.write(",");
        writer.write(Integer.toString(measurement.height));
        writer.write(",");
        writer.write(Integer.toString(measurement.lines));
        writer.write(",");
        writer.write(csv(status));
        writer.write(",");
        writer.write(csv(error));
        writer.newLine();
    }

    private static List<Pair> findPairs(Config config) throws IOException {
        List<Pair> pairs = new ArrayList<>();
        try (var images = Files.list(config.imageDir)) {
            images.filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(image -> {
                        String stem = stem(image.getFileName().toString());
                        Path xml = config.xmlDir.resolve(stem + ".xml");
                        if (Files.isRegularFile(xml)) {
                            pairs.add(new Pair(stem, image, xml));
                        }
                    });
        }
        return pairs;
    }

    private static List<Pair> selectPairs(List<Pair> pairs, int limit) {
        if (limit <= 0 || limit >= pairs.size()) {
            return pairs;
        }

        List<Pair> selected = new ArrayList<>(limit);
        double step = (double) pairs.size() / (double) limit;
        for (int i = 0; i < limit; i++) {
            selected.add(pairs.get((int) Math.floor(i * step)));
        }
        return selected;
    }

    private static void dumpContours(BufferedWriter writer, String stem, PcGts page) throws IOException {
        // Stable, sortable per-line dump for equivalence checking across optimization
        // runs. Format: <stem>\t<lineId>\t<x1,y1 x2,y2 ...>. Lines emitted in
        // (region order, line order) — same as the in-memory page traversal.
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            for (TextLine textLine : textRegion.getTextLines()) {
                String coords = "";
                if (textLine.getCoords() != null && textLine.getCoords().getPoints() != null) {
                    coords = textLine.getCoords().getPoints();
                }
                writer.write(stem);
                writer.write('\t');
                writer.write(textLine.getId() == null ? "" : textLine.getId());
                writer.write('\t');
                writer.write(coords);
                writer.newLine();
            }
        }
    }

    private static int countLines(PcGts page) {
        int lines = 0;
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            lines += textRegion.getTextLines().size();
        }
        return lines;
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        index = Math.max(0, Math.min(index, values.size() - 1));
        return values.get(index);
    }

    private static long peakRssKb() {
        Path status = Paths.get("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return -1;
        }
        try {
            for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                if (line.startsWith("VmHWM:")) {
                    String value = line.substring("VmHWM:".length()).trim().split("\\s+")[0];
                    return Long.parseLong(value);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            return -1;
        }
        return -1;
    }

    private static String stem(String filename) {
        int index = filename.lastIndexOf('.');
        return index >= 0 ? filename.substring(0, index) : filename;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static Config parseArgs(String[] args) {
        Config config = new Config();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--image-dir":
                    config.imageDir = Paths.get(value(args, ++i, arg));
                    break;
                case "--xml-dir":
                    config.xmlDir = Paths.get(value(args, ++i, arg));
                    break;
                case "--csv":
                    config.csv = Paths.get(value(args, ++i, arg));
                    break;
                case "--label":
                    config.label = value(args, ++i, arg);
                    break;
                case "--limit":
                    config.limit = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--warmup":
                    config.warmup = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--runs":
                    config.runs = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--scale":
                    config.scaleDownFactor = Double.parseDouble(value(args, ++i, arg));
                    break;
                case "--minimum-interline-distance":
                    config.minimumInterlineDistance = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--thickness":
                    config.thickness = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--minimum-baseline-thickness":
                    config.minimumBaselineThickness = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--ignore-broken":
                    config.ignoreBroken = Boolean.parseBoolean(value(args, ++i, arg));
                    break;
                case "--ignore-xml-errors":
                    config.ignoreXmlErrors = Boolean.parseBoolean(value(args, ++i, arg));
                    break;
                case "--suppress-stderr":
                    config.suppressStderr = Boolean.parseBoolean(value(args, ++i, arg));
                    break;
                case "--dump-contours":
                    config.dumpContours = Paths.get(value(args, ++i, arg));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return config;
    }

    private static String value(String[] args, int index, String arg) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + arg);
        }
        return args[index];
    }
}
