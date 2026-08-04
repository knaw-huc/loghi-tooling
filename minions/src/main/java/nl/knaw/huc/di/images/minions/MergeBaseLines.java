package nl.knaw.huc.di.images.minions;

import com.google.common.base.Stopwatch;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.Page.Baseline;
import nl.knaw.huc.di.images.layoutds.models.Page.Coords;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class MergeBaseLines {
    private static final Logger LOG = LoggerFactory.getLogger(MergeBaseLines.class);

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    public static void main(String[] args) throws IOException {
        final Stopwatch started = Stopwatch.createStarted();

        Map<String, String> idMapping = new HashMap<>();
        float scale = 0.25f;
        try {
            // Oude page omzetten naar image met ingetekende lijnen
            // p2pala output omzetten naar page en dan één voor één de lijnen intekenen
            final String p2palaOutput = "/home/stefan/Downloads/NL-0400410000_26_009006_000312.png";
            Mat baseLineMat = OpenCVWrapper.imread(p2palaOutput, Imgcodecs.IMREAD_GRAYSCALE);
            if (baseLineMat.empty()) {
                baseLineMat = OpenCVWrapper.release(baseLineMat);
                throw new IOException("Could not read baseline image: " + p2palaOutput);
            }
            Mat thresHoldedBaselines = OpenCVWrapper.newMat(baseLineMat.size(), CvType.CV_8UC1);
            Imgproc.threshold(baseLineMat, thresHoldedBaselines, 0, 255, Imgproc.THRESH_BINARY);
            Mat stats = new Mat();
            Mat centroids = new Mat();
            Mat labeled = new Mat();
            int numLabels = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselines, labeled, stats, centroids, 8, CvType.CV_32S);

            boolean cleanup = true;
            int minimumWidth = 15;
            int minimumHeight = 3;
            List<TextLine> newTextLines = extractBaselines(cleanup, minimumHeight, minimumWidth, numLabels, stats, labeled, p2palaOutput);

            final PcGts oldPage = PageUtils.readPageFromFile(Paths.get("/home/stefan/Downloads/NL-0400410000_26_009006_000312.xml"));
            final List<TextLine> oldLines = oldPage.getPage().getTextRegions().stream().flatMap(region -> region.getTextLines().stream()).collect(Collectors.toList());

            final int scaledWidth = (int) (baseLineMat.size().width * scale);
            final int scaledHeight = (int) (baseLineMat.size().height * scale);
            final Size scaledSize = new Size(scaledWidth, scaledHeight);

            int i = 0;
            for (TextLine newTextLine : newTextLines) {
                Mat newLineImage = OpenCVWrapper.zeros(baseLineMat.size(), CvType.CV_8UC1);

                writeBaseLineToMat(newLineImage, newTextLine.getBaseline());
                Imgcodecs.imwrite("/tmp/output/" + i + "_" + newTextLine.getId() + ".png", newLineImage);
                i++;

                for (TextLine oldLine : oldLines) {
                    Mat oldLineImage = OpenCVWrapper.newMat(baseLineMat.size(), CvType.CV_8UC1);
                    try {
                        final double intersectOverUnion = LayoutProc.intersectOverUnion(newLineImage, oldLineImage);


                        if (intersectOverUnion > 0.50) {
                            idMapping.put(newTextLine.getId(), oldLine.getId());
                        }
                    } finally {
                        oldLineImage = OpenCVWrapper.release(oldLineImage);
                    }
                }
                newLineImage = OpenCVWrapper.release(newLineImage);
            }
            baseLineMat = OpenCVWrapper.release(baseLineMat);
            thresHoldedBaselines = OpenCVWrapper.release(thresHoldedBaselines);
            stats = OpenCVWrapper.release(stats);
            centroids= OpenCVWrapper.release(centroids);
            labeled =OpenCVWrapper.release(labeled);


            LOG.info("{}", idMapping);
            LOG.info("{}", idMapping.values());
            LOG.info("{}", idMapping.values().size());
            LOG.info("{}", started.stop());
//            System.out.println(idMapping.keySet().size());

        } catch (Exception e) {
            LOG.error("Error while merging baselines", e);
        }
    }

    private static long getCountBaseLinePixels(Mat union) {
        final int[] pixelValues = new int[(int) union.total() * union.channels()];
        int count = 0;
        union.get(0, 0, pixelValues);

        for (int unionPixelValue : pixelValues) {
            if (unionPixelValue > 0) {
                count++;
            }
        }

        return count;
    }

    private static void writeBaseLineToMat(Mat image, Baseline baseline) {
        Point beginPoint = null;
        Point endPoint = null;
        Scalar color = new Scalar(255);
        int thickness = 10;
        for (Point point : StringConverter.stringToPoint(baseline.getPoints())) {
            endPoint = point;
            if (beginPoint != null && endPoint != null) {
                Imgproc.line(image, beginPoint, endPoint, color, thickness);
            }

            beginPoint = endPoint;

        }

    }

    private static List<TextLine> extractBaselines(boolean cleanup, int minimumHeight, int minimumWidth, int numLabels, Mat stats, Mat labeled, String identifier) {
        List<TextLine> allTextLines = extractBaselines(numLabels, stats, labeled, identifier, minimumHeight);
        if (!cleanup) {
            return allTextLines;
        }
        List<TextLine> textLines = new ArrayList<>();
        for (TextLine textLine : allTextLines) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            for (Point point : StringConverter.stringToPoint(textLine.getBaseline().getPoints())) {
                if (point.x < minX) {
                    minX = (int) point.x;
                }
                if (point.x > maxX) {
                    maxX = (int) point.x;
                }
            }
            int width = 0;
            if (maxX > 0) {
                width = maxX - minX;
            }
            if (width >= minimumWidth) {
                textLines.add(textLine);
            }
        }
        return textLines;
    }

    private static List<TextLine> extractBaselines(int numLabels, Mat stats, Mat labeled, String identifier, int minimumHeight) {
        List<TextLine> textLines = new ArrayList<>();
        for (int label = 1; label < numLabels; label++) {
            Rect rect = LayoutProc.getRectFromStats(stats, label);
            Mat submat = labeled.submat(rect);
            try {
                List<Point> baselinePoints = extractBaseline(submat, label, new Point(rect.x, rect.y), minimumHeight, identifier);
                if (baselinePoints.size() < 2) {
                    continue;
                }
                TextLine textLine = new TextLine();
                Coords coords = new Coords();
                List<Point> coordPoints = new ArrayList<>();
                coordPoints.add(new Point(rect.x, rect.y));
                coordPoints.add(new Point(rect.x + rect.width - 1, rect.y));
                coordPoints.add(new Point(rect.x + rect.width - 1, rect.y + rect.height - 1));
                coordPoints.add(new Point(rect.x, rect.y + rect.height - 1));
                coords.setPoints(StringConverter.pointToString(coordPoints));
                textLine.setCoords(coords);
                Baseline baseline = new Baseline();
                baseline.setPoints(StringConverter.pointToString(baselinePoints));
                textLine.setBaseline(baseline);
                textLine.setId(UUID.randomUUID().toString());
                textLines.add(textLine);
            } finally {
                submat = OpenCVWrapper.release(submat);
            }
        }
        return textLines;
    }

    private static List<Point> extractBaseline(Mat baselineMat, int label, Point offset, int minimumHeight, String imageFile) {
        List<Point> baseline = new ArrayList<>();
        int j;
        Point point = null;
        int pixelCounter = -1;
        boolean mergedLineDetected = false;
        for (j = 0; j < baselineMat.width(); j++) {
            boolean mergedLineDetectedStep1 = false;
            double sum = 0;
            int counter = 0;
            for (int i = 0; i < baselineMat.height(); i++) {
                int pixelValue = LayoutProc.getSafeInt(baselineMat, i, j);
                if (pixelValue == label) {
                    sum += i;
                    counter++;
                    if (mergedLineDetectedStep1) {
                        mergedLineDetected = true;
                    }
                } else {
                    if (counter > 0) {
                        mergedLineDetectedStep1 = true;
                    }
                }
            }
            if (counter < minimumHeight) {
                continue;
            }
            pixelCounter++;
            if (counter > 1) {
                sum /= counter;
            }

            point = new Point(j + offset.x, sum + offset.y);
            if (pixelCounter % 50 == 0) {
                baseline.add(point);
            }
        }
        if (pixelCounter % 50 != 0) {
            baseline.add(point);
        }
        if (mergedLineDetected) {
            LOG.info("lines detected for: {}", imageFile);
        }
        return baseline;
    }
}