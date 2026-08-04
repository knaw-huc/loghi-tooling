package nl.knaw.huc.di.images.minions;

import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.layoutds.models.Page.TextRegion;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.opencv.core.Core;

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
import java.util.Optional;

public class ReadingOrderBenchmark {
    private static final class Config {
        Path imageDir = Paths.get("/data/ijsberg/full/train");
        Path xmlDir = Paths.get("/data/ijsberg/full/train/page");
        Path csv = Paths.get("/tmp/reading-order-benchmark.csv");
        String label = "benchmark";
        int limit = 20;
        int warmup = 1;
        int runs = 3;
        boolean cleanBorders = false;
        int borderMargin = 200;
        boolean asSingleRegion = false;
        double interlineClusteringMultiplier = 1.5;
        double dubiousSizeWidthMultiplier = 0.05;
        boolean ignoreXmlErrors = false;
        boolean suppressStderr = true;
    }

    private static final class Pair {
        final String stem;
        final Path xml;

        Pair(String stem, Path xml) {
            this.stem = stem;
            this.xml = xml;
        }
    }

    private static final class Measurement {
        final String stem;
        final int run;
        final long elapsedNanos;
        final int regionsBefore;
        final int regionsAfter;
        final int lines;

        Measurement(String stem, int run, long elapsedNanos, int regionsBefore, int regionsAfter, int lines) {
            this.stem = stem;
            this.run = run;
            this.elapsedNanos = elapsedNanos;
            this.regionsBefore = regionsBefore;
            this.regionsAfter = regionsAfter;
            this.lines = lines;
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Config config = parseArgs(args);
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

        List<Pair> pairs = selectPairs(findPairs(config), config.limit);
        if (pairs.isEmpty()) {
            throw new IllegalArgumentException("No xml pairs found in " + config.xmlDir);
        }

        if (config.csv.getParent() != null) {
            Files.createDirectories(config.csv.getParent());
        }

        List<Measurement> measurements = new ArrayList<>();
        int failures = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(config.csv, StandardCharsets.UTF_8)) {
            writer.write("label,stem,run,elapsed_ms,regions_before,regions_after,lines,status,error");
            writer.newLine();

            for (int i = 0; i < config.warmup; i++) {
                failures += executeRun(config, pairs, -1, writer, null);
                System.gc();
            }

            for (int run = 1; run <= config.runs; run++) {
                failures += executeRun(config, pairs, run, writer, measurements);
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
    }

    private static int executeRun(Config config, List<Pair> pairs, int run, BufferedWriter writer,
                                  List<Measurement> measurements) throws IOException {
        int failures = 0;
        for (Pair pair : pairs) {
            try {
                String xml = Files.readString(pair.xml);
                PcGts page = PageUtils.readPageFromString(xml, config.ignoreXmlErrors);
                if (page == null) {
                    throw new IllegalStateException("PAGE parse returned null");
                }
                int regionsBefore = page.getPage().getTextRegions().size();
                int lines = countLines(page);

                long elapsed = measureReadingOrder(config, pair, page);

                int regionsAfter = page.getPage().getTextRegions().size();

                if (run > 0) {
                    Measurement measurement = new Measurement(pair.stem, run, elapsed, regionsBefore, regionsAfter, lines);
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
            }
        }
        return failures;
    }

    private static long measureReadingOrder(Config config, Pair pair, PcGts page) {
        PrintStream originalErr = System.err;
        PrintStream mutedErr = null;
        if (config.suppressStderr) {
            mutedErr = new PrintStream(OutputStream.nullOutputStream());
            System.setErr(mutedErr);
        }

        MinionRecalculateReadingOrderNew minion = new MinionRecalculateReadingOrderNew(
                pair.stem,
                page,
                p -> {},
                config.cleanBorders,
                config.borderMargin,
                config.asSingleRegion,
                config.interlineClusteringMultiplier,
                config.dubiousSizeWidthMultiplier,
                null,
                null,
                Optional.empty());

        List<String> readingOrderList = new ArrayList<>();
        readingOrderList.add(null);

        long start = System.nanoTime();
        try {
            minion.runPage(pair.stem, page, config.cleanBorders, config.borderMargin, config.asSingleRegion, readingOrderList);
            return System.nanoTime() - start;
        } finally {
            try {
                minion.close();
            } catch (Exception ignored) {
            }
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
        writer.write(Integer.toString(measurement.regionsBefore));
        writer.write(",");
        writer.write(Integer.toString(measurement.regionsAfter));
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
                            pairs.add(new Pair(stem, xml));
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
                case "--clean-borders":
                    config.cleanBorders = Boolean.parseBoolean(value(args, ++i, arg));
                    break;
                case "--border-margin":
                    config.borderMargin = Integer.parseInt(value(args, ++i, arg));
                    break;
                case "--as-single-region":
                    config.asSingleRegion = Boolean.parseBoolean(value(args, ++i, arg));
                    break;
                case "--interline-clustering-multiplier":
                    config.interlineClusteringMultiplier = Double.parseDouble(value(args, ++i, arg));
                    break;
                case "--dubious-size-width-multiplier":
                    config.dubiousSizeWidthMultiplier = Double.parseDouble(value(args, ++i, arg));
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
