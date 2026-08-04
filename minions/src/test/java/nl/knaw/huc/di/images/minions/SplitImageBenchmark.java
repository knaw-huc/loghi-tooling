package nl.knaw.huc.di.images.minions;

import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.layoutds.models.Page.TextRegion;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

public class SplitImageBenchmark {
    private static final class Config {
        Path imageDir = Paths.get("/data/ijsberg/full/train");
        Path xmlDir = Paths.get("/data/ijsberg/full/train/page");
        Path outputBase = null;
        Path csv = Paths.get("/tmp/split-image-benchmark.csv");
        String label = "benchmark";
        int limit = 20;
        int warmup = 1;
        int runs = 3;
        int minWidth = 5;
        int minHeight = 5;
        int minWidthToHeight = 0;
        String outputType = "png";
        int channels = 4;
        boolean recalculateTextLineContoursFromBaselines = true;
        int minimumInterlineDistance = 35;
        int pngCompressionLevel = 1;
        int minimumBaselineThickness = 1;
        boolean ignoreXmlErrors = false;
        boolean suppressStderr = true;
    }

    private static final class Pair {
        final String stem;
        final Path image;
        final Path xml;

        Pair(String stem, Path image, Path xml) {
            this.stem = stem;
            this.image = image;
            this.xml = xml;
        }
    }

    private static final class Measurement {
        final String stem;
        final int run;
        final long elapsedNanos;
        final int width;
        final int height;
        final int lines;

        Measurement(String stem, int run, long elapsedNanos, int width, int height, int lines) {
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

        Path outputBase = config.outputBase != null
                ? config.outputBase
                : Files.createTempDirectory("split-image-bench-");
        try {
            List<Pair> pairs = selectPairs(findPairs(config), config.limit);
            if (pairs.isEmpty()) {
                throw new IllegalArgumentException("No image/xml pairs found in " + config.imageDir);
            }

            if (config.csv.getParent() != null) {
                Files.createDirectories(config.csv.getParent());
            }

            List<Measurement> measurements = new ArrayList<>();
            int failures = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(config.csv, StandardCharsets.UTF_8)) {
                writer.write("label,stem,run,elapsed_ms,width,height,lines,status,error");
                writer.newLine();

                for (int i = 0; i < config.warmup; i++) {
                    failures += executeRun(config, pairs, -1, writer, null, outputBase);
                    System.gc();
                }

                for (int run = 1; run <= config.runs; run++) {
                    failures += executeRun(config, pairs, run, writer, measurements, outputBase);
                    System.gc();
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
        } finally {
            if (config.outputBase == null) {
                deleteRecursively(outputBase);
            }
        }
    }

    private static int executeRun(Config config, List<Pair> pairs, int run, BufferedWriter writer,
                                  List<Measurement> measurements, Path outputBase) throws IOException {
        int failures = 0;
        for (Pair pair : pairs) {
            Path runOutput = outputBase.resolve(pair.stem + "-r" + Math.max(run, 0));
            Mat probeImage = null;
            try {
                probeImage = OpenCVWrapper.imread(pair.image.toString(), Imgcodecs.IMREAD_COLOR);
                if (probeImage.empty()) {
                    throw new IllegalStateException("Image could not be read");
                }
                int width = probeImage.width();
                int height = probeImage.height();

                String xml = Files.readString(pair.xml);
                PcGts probePage = PageUtils.readPageFromString(xml, config.ignoreXmlErrors);
                if (probePage == null) {
                    throw new IllegalStateException("PAGE parse returned null");
                }
                int lines = countLines(probePage);

                Files.createDirectories(runOutput);

                long elapsed = measureSplit(config, pair, runOutput);

                if (run > 0) {
                    Measurement measurement = new Measurement(pair.stem, run, elapsed, width, height, lines);
                    measurements.add(measurement);
                    writeCsv(writer, config.label, measurement, "ok", "");
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
                if (probeImage != null && probeImage.dataAddr() != 0) {
                    probeImage = OpenCVWrapper.release(probeImage);
                }
                if (config.outputBase == null) {
                    deleteRecursively(runOutput);
                }
            }
        }
        return failures;
    }

    private static long measureSplit(Config config, Pair pair, Path runOutput) {
        PrintStream originalErr = System.err;
        PrintStream mutedErr = null;
        if (config.suppressStderr) {
            mutedErr = new PrintStream(OutputStream.nullOutputStream());
            System.setErr(mutedErr);
        }

        Supplier<Mat> imageSupplier = () -> OpenCVWrapper.imread(pair.image.toString(), Imgcodecs.IMREAD_COLOR);
        Supplier<PcGts> pageSupplier = () -> {
            try {
                String xml = Files.readString(pair.xml);
                return PageUtils.readPageFromString(xml, config.ignoreXmlErrors);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        MinionCutFromImageBasedOnPageXMLNew minion = new MinionCutFromImageBasedOnPageXMLNew(
                pair.stem,
                imageSupplier,
                pageSupplier,
                runOutput.toString(),
                pair.image.getFileName().toString(),
                /* overwriteExistingPage */ false,
                config.minWidth,
                config.minHeight,
                config.minWidthToHeight,
                config.outputType,
                config.channels,
                /* writeTextContents */ false,
                /* rescaleHeight */ null,
                /* outputConfidenceFile */ false,
                /* outputBoxFile */ true,
                /* outputTxtFile */ true,
                config.recalculateTextLineContoursFromBaselines,
                /* fixedXHeight */ null,
                LayoutProc.MINIMUM_XHEIGHT,
                /* useDiforNames */ false,
                /* writeDoneFiles */ false,
                /* ignoreDoneFiles */ true,
                /* errorLog */ error -> {},
                /* includeTextStyles */ false,
                /* useTags */ false,
                /* skipUnclear */ false,
                /* minimumConfidence */ null,
                /* maximumConfidence */ null,
                config.minimumInterlineDistance,
                config.pngCompressionLevel,
                Optional.empty(),
                /* tmpdir */ null,
                config.minimumBaselineThickness,
                /* coffeeStains */ 0);

        long start = System.nanoTime();
        try {
            minion.run();
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

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
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
                case "--output-base":
                    config.outputBase = Paths.get(value(args, ++i, arg));
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
                case "--min-width":
                    config.minWidth = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--min-height":
                    config.minHeight = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--min-width-to-height":
                    config.minWidthToHeight = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--output-type":
                    config.outputType = value(args, ++i, arg);
                    break;
                case "--channels":
                    config.channels = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--recalculate-contours":
                    config.recalculateTextLineContoursFromBaselines = Boolean.parseBoolean(value(args, ++i, arg));
                    break;
                case "--minimum-interline-distance":
                    config.minimumInterlineDistance = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--png-compression-level":
                    config.pngCompressionLevel = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--minimum-baseline-thickness":
                    config.minimumBaselineThickness = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--ignore-xml-errors":
                    config.ignoreXmlErrors = Boolean.parseBoolean(value(args, ++i, arg));
                    break;
                case "--suppress-stderr":
                    config.suppressStderr = Boolean.parseBoolean(value(args, ++i, arg));
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
