package nl.knaw.huc.di.images.minions;

import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Verifies that skipping contour recalculation in split-image produces bit-for-bit
 * identical line-strip PNGs when the PAGE XML already contains fresh seam-traced contours
 * (i.e. extract-baselines already ran with recalculate=true, as the web service does).
 *
 * Gated on -Dbenchmark.enabled=true so it does not run in normal CI builds.
 * Run via: mvn -pl minions -am -DargLine=-Djava.library.path=/usr/local/share/java/opencv4
 *              -Dtest=SplitImageRecalculateCorrectnessTest -Dbenchmark.enabled=true
 *              -DfailIfNoTests=false test
 */
public class SplitImageRecalculateCorrectnessTest {

    private static final String IMAGE_DIR = System.getProperty("correctness.imageDir",
            "/data/ijsberg/full/train");
    private static final String XML_DIR = System.getProperty("correctness.xmlDir",
            "/data/ijsberg/full/train/page");
    private static final int LIMIT = Integer.getInteger("correctness.limit", 3);

    @Test
    public void recalculateSkipProducesIdenticalOutputs() throws Exception {
        Assume.assumeTrue("Set -Dbenchmark.enabled=true to run", Boolean.getBoolean("benchmark.enabled"));
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

        List<String> stems = selectStems(LIMIT);
        Assert.assertFalse("No paired image/xml found in " + IMAGE_DIR, stems.isEmpty());

        int totalCompared = 0;
        int totalDifferent = 0;
        List<String> differingFiles = new ArrayList<>();

        for (String stem : stems) {
            Path imagePath = Paths.get(IMAGE_DIR, stem + ".jpg");
            Path xmlPath = Paths.get(XML_DIR, stem + ".xml");

            String originalXml = Files.readString(xmlPath);
            String namespace = PageUtils.NAMESPACE2013;

            // Step 1: recalculate contours (simulates what extract-baselines does in the web service)
            Mat image = OpenCVWrapper.imread(imagePath.toString(), Imgcodecs.IMREAD_COLOR);
            try {
                PcGts page = PageUtils.readPageFromString(originalXml);
                LayoutProc.recalculateTextLineContoursFromBaselines(
                        stem, image, page,
                        MinionCutFromImageBasedOnPageXMLNew.SHRINK_FACTOR,
                        MinionCutFromImageBasedOnPageXMLNew.DEFAULT_MINIMUM_INTERLINE_DISTANCE,
                        /* thickness */ 10,
                        /* minimumBaselineThickness */ 1,
                        /* ignoreBroken */ true);
                String freshXml = PageUtils.convertPcGtsToString(page, namespace);

                // Step 2a: run split-image with recalculate=true (recomputes from scratch)
                Path outTrue = Files.createTempDirectory("split-true-");
                runSplit(stem, imagePath, freshXml, true, outTrue);

                // Step 2b: run split-image with recalculate=true AGAIN — determinism check
                Path outTrue2 = Files.createTempDirectory("split-true2-");
                runSplit(stem, imagePath, freshXml, true, outTrue2);

                Map<String, Path> trueFiles2a = collectPngs(outTrue);
                Map<String, Path> trueFiles2b = collectPngs(outTrue2);
                int deterministicDiff = 0;
                for (Map.Entry<String, Path> e : trueFiles2a.entrySet()) {
                    Path other = trueFiles2b.get(e.getKey());
                    if (other != null && !Arrays.equals(Files.readAllBytes(e.getValue()), Files.readAllBytes(other))) {
                        deterministicDiff++;
                    }
                }
                System.out.printf("  %s: determinism check (recalc=true twice): %d/%d differ%n",
                        stem, deterministicDiff, trueFiles2a.size());
                deleteRecursively(outTrue2);

                // Step 3: run split-image with recalculate=false (uses the already-fresh contours)
                Path outFalse = Files.createTempDirectory("split-false-");
                runSplit(stem, imagePath, freshXml, false, outFalse);

                // Step 4: compare outputs
                Map<String, Path> trueFiles = collectPngs(outTrue);
                Map<String, Path> falseFiles = collectPngs(outFalse);

                for (Map.Entry<String, Path> entry : trueFiles.entrySet()) {
                    String name = entry.getKey();
                    totalCompared++;
                    if (!falseFiles.containsKey(name)) {
                        totalDifferent++;
                        differingFiles.add(stem + "/" + name + " (missing in no-recalc)");
                        continue;
                    }
                    byte[] trueBytes = Files.readAllBytes(entry.getValue());
                    byte[] falseBytes = Files.readAllBytes(falseFiles.get(name));
                    if (!Arrays.equals(trueBytes, falseBytes)) {
                        totalDifferent++;
                        Mat tImg = OpenCVWrapper.imread(entry.getValue().toString(), Imgcodecs.IMREAD_UNCHANGED);
                        Mat fImg = OpenCVWrapper.imread(falseFiles.get(name).toString(), Imgcodecs.IMREAD_UNCHANGED);
                        String sizeNote = tImg.width() + "x" + tImg.height() + " vs " + fImg.width() + "x" + fImg.height();
                        OpenCVWrapper.release(tImg);
                        OpenCVWrapper.release(fImg);
                        differingFiles.add(stem + "/" + name + " [" + sizeNote + "]");
                    }
                }
                for (String name : falseFiles.keySet()) {
                    if (!trueFiles.containsKey(name)) {
                        totalCompared++;
                        totalDifferent++;
                        differingFiles.add(stem + "/" + name + " (only in no-recalc)");
                    }
                }

                deleteRecursively(outTrue);
                deleteRecursively(outFalse);
            } finally {
                OpenCVWrapper.release(image);
            }
        }

        System.out.printf("Correctness check: %d pages, %d PNGs compared, %d different%n",
                stems.size(), totalCompared, totalDifferent);
        if (!differingFiles.isEmpty()) {
            System.out.println("Differing files (first 20):");
            differingFiles.stream().limit(20).forEach(f -> System.out.println("  " + f));
        }

        Assert.assertEquals(
                totalDifferent + " of " + totalCompared + " PNGs differ between recalculate=true and recalculate=false on fresh XML",
                0, totalDifferent);
    }

    private static void runSplit(String stem, Path imagePath, String xmlString,
                                  boolean recalculate, Path outputDir) throws IOException {
        MinionCutFromImageBasedOnPageXMLNew minion = new MinionCutFromImageBasedOnPageXMLNew(
                stem,
                () -> OpenCVWrapper.imread(imagePath.toString(), Imgcodecs.IMREAD_COLOR),
                () -> PageUtils.readPageFromString(xmlString),
                outputDir.toString(),
                imagePath.getFileName().toString(),
                /* overwriteExistingPage */ false,
                /* minWidth */ 5,
                /* minHeight */ 5,
                /* minWidthToHeight */ 0,
                /* outputType */ "png",
                /* channels */ 4,
                /* writeTextContents */ false,
                /* rescaleHeight */ null,
                /* outputConfidenceFile */ false,
                /* outputBoxFile */ false,
                /* outputTxtFile */ false,
                recalculate,
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
                MinionCutFromImageBasedOnPageXMLNew.DEFAULT_MINIMUM_INTERLINE_DISTANCE,
                /* pngCompressionLevel */ 1,
                Optional.empty(),
                /* tmpdir */ null,
                /* minimumBaselineThickness */ 1,
                /* coffeeStains */ 0);
        minion.run();
    }

    private static Map<String, Path> collectPngs(Path dir) throws IOException {
        Map<String, Path> result = new TreeMap<>();
        if (!Files.exists(dir)) return result;
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".png"))
                  .forEach(p -> result.put(dir.relativize(p).toString(), p));
        }
        return result;
    }

    private static List<String> selectStems(int limit) throws IOException {
        Path imageDir = Paths.get(IMAGE_DIR);
        Path xmlDir = Paths.get(XML_DIR);
        List<String> paired = new ArrayList<>();
        try (var stream = Files.list(imageDir)) {
            stream.filter(p -> p.toString().endsWith(".jpg"))
                  .map(p -> p.getFileName().toString())
                  .map(n -> n.substring(0, n.length() - 4))
                  .filter(stem -> Files.exists(xmlDir.resolve(stem + ".xml")))
                  .sorted()
                  .forEach(paired::add);
        }
        if (paired.isEmpty() || limit >= paired.size()) return paired;
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            selected.add(paired.get((int) Math.round((double) i * (paired.size() - 1) / (limit - 1))));
        }
        return selected;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d); return FileVisitResult.CONTINUE;
            }
        });
    }
}
