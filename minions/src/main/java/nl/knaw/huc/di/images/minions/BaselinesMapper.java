package nl.knaw.huc.di.images.minions;

import com.google.common.base.Stopwatch;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.Page.Baseline;
import nl.knaw.huc.di.images.layoutds.models.Page.Coords;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class BaselinesMapper {

    private static final Logger LOG = LoggerFactory.getLogger(BaselinesMapper.class);

    public static final float SCALE = 0.25f;
    public static final double MIN_LIMIT_ACCEPT = 0.50;

    static {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    public static Map<String, String> mapNewLinesToOldLines(List<TextLine> newTextLines, List<TextLine> oldTextLines, Size size) {
        final Stopwatch started = Stopwatch.createStarted();

        final Map<String, List<String>> possibleNewOldMappings = new HashMap<>();
        final Map<String, List<String>> possibleOldNewMappings = new HashMap<>();
        final double scaledWidth = size.width * SCALE;
        final double scaledHeight = size.height * SCALE;
        final Size scaledSize = new Size(scaledWidth, scaledHeight);

        // Precompute the images for old text lines
        Map<String, Mat> oldLineImages = new HashMap<>();
        for (TextLine oldTextLine : oldTextLines) {
            Mat oldLineImage = Mat.zeros(scaledSize, CvType.CV_8UC1);
            writeBaseLineToMat(oldLineImage, oldTextLine.getBaseline(), SCALE);
            oldLineImages.put(oldTextLine.getId(), oldLineImage);
        }

        for (TextLine newTextLine : newTextLines) {
            Mat newLineImage = Mat.zeros(scaledSize, CvType.CV_8UC1);
            writeBaseLineToMat(newLineImage, newTextLine.getBaseline(), SCALE);

            for (Map.Entry<String, Mat> entry : oldLineImages.entrySet()) {
                Rect boundingBoxNew = LayoutProc.getBoundingBoxBaseLine(newTextLine);
                Rect boundingBoxOld = LayoutProc.getBoundingBoxBaseLine(oldTextLines.stream().filter(textLine -> textLine.getId().equals(entry.getKey())).findFirst().get());
                // if no overlap continue:
                if (boundingBoxNew.x > boundingBoxOld.x + boundingBoxOld.width
                        || boundingBoxOld.x > boundingBoxNew.x + boundingBoxNew.width
                        || boundingBoxNew.y > boundingBoxOld.y + boundingBoxOld.height
                        || boundingBoxOld.y > boundingBoxNew.y + boundingBoxNew.height) {
                    continue;
                }

                String oldTextLineId = entry.getKey();
                Mat oldLineImage = entry.getValue();

//                Stopwatch intersectOverUnionTimer = Stopwatch.createStarted();
                final double intersectOverUnion = LayoutProc.intersectOverUnion(newLineImage, oldLineImage);
//                LOG.info("Intersect over union took: " + intersectOverUnionTimer.stop());

                if (intersectOverUnion > MIN_LIMIT_ACCEPT) {
                    final String newTextLineId = newTextLine.getId();
                    possibleNewOldMappings.computeIfAbsent(newTextLineId, k -> new ArrayList<>()).add(oldTextLineId);
                    possibleOldNewMappings.computeIfAbsent(oldTextLineId, k -> new ArrayList<>()).add(newTextLineId);
                }
            }
            newLineImage = OpenCVWrapper.release(newLineImage);
        }

        // Release old line images
        for (Mat oldLineImage : oldLineImages.values()) {
            oldLineImage = OpenCVWrapper.release(oldLineImage);
        }

        final Map<String, String> idMapping = possibleNewOldMappings.entrySet().stream()
                .filter(entry -> entry.getValue().size() == 1)
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().get(0)))
                .filter(entry -> possibleOldNewMappings.get(entry.getValue()).size() == 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        LOG.info("Mapping lines took: " + started.stop());

        return idMapping;
    }


    private static void writeBaseLineToMat(Mat image, Baseline baseline, float scale) {
        Point beginPoint = null;
        Point endPoint = null;
        Scalar color = new Scalar(255);
        int thickness = Math.max((int) (10 * scale), 1);
        for (Point point : StringConverter.stringToPoint(baseline.getPoints())) {
            endPoint = new Point(point.x * scale, point.y * scale);
            if (beginPoint != null) {
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
        for (int i = 1; i < numLabels; i++) {
            Rect rect = LayoutProc.getRectFromStats(stats, i);
            Mat roi = labeled.submat(rect);
            Mat submat = roi.clone();
            roi = OpenCVWrapper.release(roi);
            List<Point> baselinePoints = extractBaseline(submat, i, new Point(rect.x, rect.y), minimumHeight, identifier);
            submat = OpenCVWrapper.release(submat);
            if (baselinePoints.size() < 2) {
                continue;
            }
            TextLine textLine = new TextLine();
            Coords coords = new Coords();
            List<Point> coordPoints = new ArrayList<>();
            coordPoints.add(new Point(rect.x, rect.y));
            coordPoints.add(new Point(rect.x + rect.width-1, rect.y));
            coordPoints.add(new Point(rect.x + rect.width-1, rect.y + rect.height-1));
            coordPoints.add(new Point(rect.x, rect.y + rect.height-1));
            coords.setPoints(StringConverter.pointToString(coordPoints));
            textLine.setCoords(coords);
            Baseline baseline = new Baseline();
            baseline.setPoints(StringConverter.pointToString(baselinePoints));
            textLine.setBaseline(baseline);
            textLine.setId(UUID.randomUUID().toString());
            textLines.add(textLine);
        }
        return textLines;
    }

    private static List<Point> extractBaseline(Mat baselineMat, int label, Point offset, int minimumHeight, String imageFile) {
        List<Point> baseline = new ArrayList<>();
        Point point = null;
        int pixelCounter = -1;
        boolean mergedLineDetected = false;
        for (int j = 0; j < baselineMat.width(); j++) {
            boolean mergedLineDetectedStep1 = false;
            double sum = 0;
            int counter = 0;
            for (int i = 0; i < baselineMat.height(); i++) {
                int pixelValue = LayoutProc.getSafeInt(baselineMat,i, j);
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