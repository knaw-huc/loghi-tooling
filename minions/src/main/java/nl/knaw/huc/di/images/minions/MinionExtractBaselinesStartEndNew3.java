package nl.knaw.huc.di.images.minions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.imageanalysiscommon.UnicodeToAsciiTranslitirator;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.BaselineExtractionType;
import nl.knaw.huc.di.images.layoutds.models.Page.*;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.*;
import org.apache.commons.io.FilenameUtils;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opencv.core.CvType.CV_64F;

/*
This takes pageXML and an png containing baselines
 and an png containing baseline start and ending
 and extracts info about the baselines
 and add baseline/textline information to the regions in the pagexml
 This version add the ability to correctly detect rotated lines
 */
public class MinionExtractBaselinesStartEndNew3 implements Runnable, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MinionExtractBaselinesStartEndNew3.class);
    /**
     * Shared, thread-safe {@link XmlMapper}. Creating a new {@code XmlMapper}
     * is expensive (reflective initialisation); reusing one instance avoids
     * that cost per processed file (SMELL-06).
     */
    private static final XmlMapper XML_MAPPER = new XmlMapper();

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    private final String imageFile;
    private final String imageFilename;
    private final UnicodeToAsciiTranslitirator unicodeToAsciiTranslitirator;
    private final String namespace;
    private int numLabelsStart;
    private int numLabelsEnd;
    private Mat baseLineMatStart;
    private Mat baseLineMatEnd;
    private Mat thresHoldedBaselinesStart;
    private Mat thresHoldedBaselinesEnd;
    private Mat statsStart;
    private Mat statsEnd;
    private Mat centroidsStart;
    private Mat centroidsEnd;
    private Mat labeledStart;
    private Mat labeledEnd;
    private Mat zeroMat;
    private Mat remainingMat;
    private Mat zeroMatThresholded;
    private final boolean removeEmptyRegions;


    private final String xmlFile;
    private final String outputFile;
    private boolean asSingleRegion;
    private int numLabels;
    private Mat baseLineMat;
    private Mat thresHoldedBaselines;
    private Mat stats;
    private Mat centroids;
    private Mat labeled;
    private Mat labeledRemaining;
    private Mat statsRemaining;
    private Mat centroidsRemaining;
    private final int margin;
    private final int thicknessUsed;
    private final int thicknessStartEndUsed;
    private final int minimumHeight;

    public MinionExtractBaselinesStartEndNew3(String xmlFile,
                                              String outputFile, boolean asSingleRegion,
                                              boolean removeEmptyRegions,
                                              String imageFile,
                                              int margin,
                                              int thicknessUsed,
                                              int thicknessStartEndUsed,
                                              int minimumHeight,
                                              String imageFilename,
                                              String namespace
    ) {
        this.xmlFile = xmlFile;
        this.outputFile = outputFile;
        this.asSingleRegion = asSingleRegion;
        this.removeEmptyRegions = removeEmptyRegions;
        this.imageFile = imageFile;
        this.margin = margin;
        this.thicknessUsed = thicknessUsed;
        this.thicknessStartEndUsed = thicknessStartEndUsed;
        this.minimumHeight = minimumHeight;
        this.imageFilename = imageFilename;
        this.unicodeToAsciiTranslitirator = new UnicodeToAsciiTranslitirator();
        this.namespace = namespace;
    }

    private Point rotateBack(Point point, Point oldCenter, Point newCenter, double rotation) {
        Point result = new Point(point.x - newCenter.x, point.y - newCenter.y);
        result = new Point(result.x * Math.cos(rotation) - result.y * Math.sin(rotation), result.x * Math.sin(rotation) + result.y * Math.cos(rotation));
        result = new Point(result.x + oldCenter.x, result.y + oldCenter.y);
        return result;
    }

    private void extractAndMergeBaseLinesNew(
            String xmlPath, String outputFile, int margin, boolean clearExistingLines,
            BaselineExtractionType baselineExtractionType, boolean mergeTextLinesWithoutEndToTextLinesWithoutStart,
            int minimumLengthTextLine
    ) throws IOException, TransformerException {
        PcGts page;
        if (Files.exists(Paths.get(xmlPath))) {
            String transkribusPageXml = StringTools.readFile(xmlPath);
            page = PageUtils.readPageFromString(transkribusPageXml);
        } else {
            page = PageUtils.createFromImage(baseLineMat.height(), baseLineMat.width(), imageFilename);
        }
        boolean addLinesWithoutRegion = true;

        List<TextLine> newTextLines = new ArrayList<>();
        List<TextLine> newTextLinesWithoutStart = new ArrayList<>();
        List<TextLine> newTextLinesWithoutEnd = new ArrayList<>();
        List<TextLine> newTextLinesWithMultipleEnd = new ArrayList<>();
        List<TextLine> newTextLinesWithoutStartAndEnd = new ArrayList<>();


        List<TextLine> newMergedTextLines = new ArrayList<>();


        /*
         * for each baseline find start and end points
         * */
        int linesWithoutStartAndEnd = 0;
        int smallLines = 0;
        int linesWithMultipleStart = 0;
        int linesWithMultipleEnd = 0;
        int mergedBaselines = 0;
        for (int labelNumber = 1; labelNumber < numLabels; labelNumber++) {
            Rect rect = LayoutProc.getRectFromStats(stats, labelNumber);
            if (rect.width * rect.height < 3) {
                continue;
            }
            List<Integer> startLabels = new ArrayList<>();
            List<Integer> endLabels = new ArrayList<>();
            int[] numStartLabelsCount = new int[numLabelsStart];
            int[] numEndLabelsCount = new int[numLabelsEnd];
            double[] numStartLabelsEnergy = new double[numLabelsStart];
            double[] numEndLabelsEnergy = new double[numLabelsEnd];
            for (int i = 0; i < numLabelsStart; i++) {
                numStartLabelsCount[i] = 0;
                numStartLabelsEnergy[i] = 0;
            }
            for (int i = 0; i < numLabelsEnd; i++) {
                numEndLabelsCount[i] = 0;
                numEndLabelsEnergy[i] = 0;
            }
            for (int i = rect.y; i < rect.y + rect.height; i++) {
                for (int j = rect.x; j < rect.x + rect.width; j++) {
                    if (labeled.get(i, j)[0] != labelNumber) {
                        continue;
                    }
                    int startLabel = (int) labeledStart.get(i, j)[0];
                    if (startLabel > 0) {
                        numStartLabelsCount[startLabel]++;
                        numStartLabelsEnergy[startLabel] += baseLineMatStart.get(i, j)[0];
                        if (!startLabels.contains(startLabel)) {
                            startLabels.add(startLabel);
                        }

                    }
                    int endLabel = (int) labeledEnd.get(i, j)[0];
                    if (endLabel > 0) {
                        numEndLabelsCount[endLabel]++;
                        numEndLabelsEnergy[endLabel] += baseLineMatEnd.get(i, j)[0];
                        if (!endLabels.contains(endLabel)) {
                            endLabels.add(endLabel);
                        }
                    }
                }
            }

            boolean doGoodLines = true;
            boolean doLinesWithMultipleStart = true;
            boolean doLinesWithMultipleEnd = true;
            // these lines look good, only one start and one end, lets extract them
            if (doGoodLines && startLabels.size() == 1 && endLabels.size() == 1) {
                // standard baseline with start and end
                //find baselinepixels that overlap with start
                List<Point> overlappingPointsStart = getOverLappingPixels(labelNumber, startLabels.get(0), statsStart, labeledStart);
                Point centerStart = getCenter(overlappingPointsStart);
                List<Point> overlappingPointsEnd = getOverLappingPixels(labelNumber, endLabels.get(0), statsEnd, labeledEnd);
                Point centerEnd = getCenter(overlappingPointsEnd);

                TextLine textLine = extractTextLine(centerStart, centerEnd, rect, labelNumber, baselineExtractionType);
                TextEquiv textEquiv = new TextEquiv(null, unicodeToAsciiTranslitirator.toAscii("normal"), "normal");
                textLine.setTextEquiv(textEquiv);
                newTextLines.add(textLine);
            } else if (doLinesWithMultipleStart && startLabels.size() >= 1) {
                linesWithMultipleStart++;
                int bestStartLabel = getBestLabel(startLabels, numStartLabelsEnergy);
                List<Point> overlappingPointsStart = getOverLappingPixels(labelNumber, bestStartLabel, statsStart, labeledStart);
                Point centerStart = getCenter(overlappingPointsStart);
                if (endLabels.size() >= 1) {
                    int bestEndLabel = getBestLabel(endLabels, numEndLabelsEnergy);
                    List<Point> overlappingPointsEnd = getOverLappingPixels(labelNumber, bestEndLabel, statsEnd, labeledEnd);
                    Point centerEnd = getCenter(overlappingPointsEnd);

                    TextLine textLine = extractTextLine(centerStart, centerEnd, rect, labelNumber, baselineExtractionType);
                    TextEquiv textEquiv = new TextEquiv(null, "linesWithMultipleEnd " + centerStart.x + " " + centerEnd.x);

                    textLine.setTextEquiv(textEquiv);
                    newTextLinesWithMultipleEnd.add(textLine);
                } else {
                    // only a start, but no end
                    Point centerBaseline = new Point(rect.x + (rect.width / 2), rect.y + (rect.height / 2));
                    TextLine textLine = extractTextLine(centerStart, centerBaseline, rect, labelNumber, baselineExtractionType);
                    final String text = "only a start, but no end";
                    TextEquiv textEquiv = new TextEquiv(0d, unicodeToAsciiTranslitirator.toAscii(text), text);
                    textLine.setTextEquiv(textEquiv);
                    newTextLinesWithoutEnd.add(textLine);
                }
            } else if (doLinesWithMultipleEnd && endLabels.size() >= 1) {
                // no start, just an end
                linesWithMultipleEnd++;
                int bestEndLabel = getBestLabel(endLabels, numEndLabelsEnergy);
                List<Point> overlappingPointsEnd = getOverLappingPixels(labelNumber, bestEndLabel, statsEnd, labeledEnd);
                Point centerEnd = getCenter(overlappingPointsEnd);
                Point centerBaseline = new Point(rect.x + (rect.width / 2), rect.y + (rect.height / 2));
                TextLine textLine = extractTextLine(centerBaseline, centerEnd, rect, labelNumber, baselineExtractionType);
                final String text = "no start, just an end";
                TextEquiv textEquiv = new TextEquiv(0d, unicodeToAsciiTranslitirator.toAscii(text), text);
                textLine.setTextEquiv(textEquiv);
                newTextLinesWithoutStart.add(textLine);
            } else {
                // without start and ending
                TextLine textLine = extractTextLine(new Point(rect.x, rect.y + rect.y / 2), new Point(rect.x + rect.width, rect.y + rect.y / 2), rect, labelNumber, baselineExtractionType);
                final String text = "without start and ending";
                TextEquiv textEquiv = new TextEquiv(0d, unicodeToAsciiTranslitirator.toAscii(text), text);
                textLine.setTextEquiv(textEquiv);
                newTextLinesWithoutStartAndEnd.add(textLine);
                if (Math.sqrt(rect.width * rect.height) > 50) {
                    linesWithoutStartAndEnd++;
                } else {
                    smallLines++;
                }
            }
        }

        ArrayList<TextLine> newTextLinesWithoutStartToRemove = new ArrayList<>();
        ArrayList<TextLine> newTextLinesWithoutEndToRemove = new ArrayList<>();
        ArrayList<TextLine> newTextLinesWithoutStartAndEndToRemove = new ArrayList<>();

        boolean foundLineToConnect = true;
        while (foundLineToConnect && mergeTextLinesWithoutEndToTextLinesWithoutStart) {
            foundLineToConnect = false;
            for (TextLine textlineWithoutEnd : newTextLinesWithoutEnd) {
                ArrayList<Point> points = StringConverter.stringToPoint(textlineWithoutEnd.getBaseline().getPoints());
                Point last = points.get(points.size() - 1);

                for (TextLine textlineWithoutStart : newTextLinesWithoutStart) {
                    if (newTextLinesWithoutStartToRemove.contains(textlineWithoutStart)) {
                        continue;
                    }
                    points = StringConverter.stringToPoint(textlineWithoutStart.getBaseline().getPoints());
                    Point first = points.get(0);
                    if (LayoutProc.getDistance(last, first) < 25) {
                        mergeBaselines(textlineWithoutEnd, textlineWithoutStart);
                        newTextLinesWithoutStartToRemove.add(textlineWithoutStart);
                        final String text = "mergeTextLinesWithoutEndToTextLinesWithoutStart";
                        TextEquiv textEquiv = new TextEquiv(0d, unicodeToAsciiTranslitirator.toAscii(text), text);
                        textlineWithoutEnd.setTextEquiv(textEquiv);
                        mergedBaselines++;
                        foundLineToConnect = true;
                    }
                }
            }
            for (TextLine textlineWithoutEnd : newTextLinesWithoutEnd) {
                ArrayList<Point> points = StringConverter.stringToPoint(textlineWithoutEnd.getBaseline().getPoints());
                Point last = points.get(points.size() - 1);

                for (TextLine textlineWithoutStartAndEnd : newTextLinesWithoutStartAndEnd) {
                    if (newTextLinesWithoutStartAndEndToRemove.contains(textlineWithoutStartAndEnd)) {
                        continue;
                    }
                    points = StringConverter.stringToPoint(textlineWithoutStartAndEnd.getBaseline().getPoints());
                    Point first = points.get(0);
                    if (LayoutProc.getDistance(last, first) < 50) {
                        mergeBaselines(textlineWithoutEnd, textlineWithoutStartAndEnd);
                        newTextLinesWithoutStartAndEndToRemove.add(textlineWithoutStartAndEnd);
//                        newTextLinesWithoutEndToRemove.add(textlineWithoutEnd);
                        final String text = "mergeTextLinesWithoutEndToTextLinesWithoutStart with lineswithoutstartandend";
                        TextEquiv textEquiv = new TextEquiv(0d, unicodeToAsciiTranslitirator.toAscii(text), text);
                        textlineWithoutEnd.setTextEquiv(textEquiv);
//                        newMergedTextLines.add(textlineWithoutEnd);
                        mergedBaselines++;
                        foundLineToConnect = true;
                        points = StringConverter.stringToPoint(textlineWithoutEnd.getBaseline().getPoints());
                        last = points.get(points.size() - 1);
                    }
                }
            }
        }
        newTextLinesWithoutStart.removeAll(newTextLinesWithoutStartToRemove);
        newTextLinesWithoutEnd.removeAll(newTextLinesWithoutEndToRemove);
        newTextLinesWithoutStartAndEnd.removeAll(newTextLinesWithoutStartAndEndToRemove);
//        newTextLines.clear();
        newTextLines.addAll(newTextLinesWithMultipleEnd);
        newTextLines.addAll(newTextLinesWithoutEnd);
        newTextLines.addAll(newTextLinesWithoutStart);
        newTextLines.addAll(newMergedTextLines);
        newTextLines.addAll(newTextLinesWithoutStartAndEnd);

        LOG.info("{} possible baselines: {}", imageFilename, numLabels);
        LOG.info("{} found baselines: {}", imageFilename, newTextLines.size());
        LOG.info("{} merged baselines: {}", imageFilename, mergedBaselines);
        LOG.info("{} total explained baselines: {}", imageFilename, (newTextLines.size() + mergedBaselines));
        LOG.info("{} total not explained baselines: {}", imageFilename, (numLabels - (newTextLines.size() + mergedBaselines)));
        LOG.info("{} linesWithoutStartAndEnd: {}", imageFilename, linesWithoutStartAndEnd);
        LOG.info("{} smallLines: {}", imageFilename, smallLines);
        LOG.info("{} linesWithMultipleStart: {}", imageFilename, linesWithMultipleStart);
        LOG.info("{} linesWithMultipleEnd: {}", imageFilename, linesWithMultipleEnd);
        labeledRemaining = OpenCVWrapper.release(labeledRemaining);
        statsRemaining  = OpenCVWrapper.release(statsRemaining);
        centroidsRemaining  = OpenCVWrapper.release(centroidsRemaining);

//        newTextLines = removeSmallLines(newTextLines, minimumLengthTextLine);
//TODO asSingleRegion parameter
        String newPageXml = mergeTextLines(page, newTextLines, addLinesWithoutRegion, true, xmlPath,
                removeEmptyRegions, margin, clearExistingLines);
        page = PageUtils.readPageFromString(newPageXml);
        LayoutProc.recalculateTextLinesFromBaselines(page);
        PageUtils.writePageToFile(page, namespace, Paths.get(outputFile));
    }

    private List<TextLine> removeSmallLines(List<TextLine> newTextLines, int minimumLength) {
        ArrayList<TextLine> textLines = new ArrayList<>();
        for (TextLine textLine : newTextLines) {
            if (LayoutProc.getLength(StringConverter.stringToPoint(textLine.getBaseline().getPoints())) < minimumLength) {
                continue;
            }
            textLines.add(textLine);
        }
        return textLines;
    }

    private void mergeBaselines(TextLine baseTextLine, TextLine textLineToAppend) {
        List<Point> baseline = StringConverter.stringToPoint(baseTextLine.getBaseline().getPoints());
        List<Point> pointsToAdd = StringConverter.stringToPoint(textLineToAppend.getBaseline().getPoints());
        baseline.addAll(pointsToAdd);
        if (baseline.size() > 2) {
            baseline = StringConverter.simplifyPolygon(baseline, 5);
        }
        baseTextLine.setBaseline(new Baseline());
        baseTextLine.getBaseline().setPoints(StringConverter.pointToString(baseline));
    }

    private int getBestLabel(List<Integer> labels, double[] labelsEnergy) {
        double bestEnergy = 0;
        int bestLabel = 0;
        for (int label : labels) {
            double energy = labelsEnergy[label];
            if (energy > bestEnergy) {
                bestLabel = label;
                bestEnergy = energy;
            }
        }
        return bestLabel;
    }

    private TextLine extractTextLine(Point centerStart, Point centerEnd, Rect rect, int labelNumber, BaselineExtractionType baselineExtractionType) {
        double orientation = getAngle(centerStart, centerEnd);

        Mat baseLineSubmat = this.baseLineMat.submat(rect);
        Mat labeledSubmat = this.labeled.submat(rect);
        Mat result = null;
        Mat labelOnly = null;
        Mat rotatedMaskedBaselineMat = null;
        try {
            result = LayoutProc.rotate(baseLineSubmat, orientation);
            labelOnly = Mat.zeros(rect.height, rect.width, CvType.CV_8UC1);
            for (int i = 0; i < rect.height; i++) {
                for (int j = 0; j < rect.width; j++) {
                    if (labeledSubmat.get(i, j)[0] == labelNumber) {
                        labelOnly.put(i, j, baseLineSubmat.get(i, j)[0]);
                        // clear baselinemat
                        baseLineSubmat.put(i, j, 0);
                    }
                }
            }
            rotatedMaskedBaselineMat = LayoutProc.rotate(labelOnly, orientation);

            List<Point> baselinePoints = getBaselinePoints(baselineExtractionType, rotatedMaskedBaselineMat);

            for (Point point : baselinePoints) {
                Point oldCenter = new Point(rect.x + (rect.width / 2), rect.y + (rect.height / 2));
                Point newCenter = new Point(result.width() / 2, result.height() / 2);
                Point newPoint = rotateBack(point, oldCenter, newCenter, Math.toRadians(orientation));
                point.x = newPoint.x;
                point.y = newPoint.y;
            }

            if (baselinePoints.size() > 2) {
                baselinePoints = StringConverter.simplifyPolygon(baselinePoints, 2);
            }

            // go through image following orientation.
            // Use seam carving to find best baselinePath
            TextLine textLine = new TextLine();
            Baseline baseline = new Baseline();
            baseline.setPoints(StringConverter.pointToString(baselinePoints));
            textLine.setBaseline(baseline);
            textLine.setId(UUID.randomUUID().toString());
            return textLine;
        } finally {
            if (rotatedMaskedBaselineMat != null) {
                rotatedMaskedBaselineMat = OpenCVWrapper.release(rotatedMaskedBaselineMat);
            }
            if (result != null) {
                result = OpenCVWrapper.release(result);
            }
            if (labelOnly != null) {
                labelOnly = OpenCVWrapper.release(labelOnly);
            }
            if (baseLineSubmat != null) {
                baseLineSubmat = OpenCVWrapper.release(baseLineSubmat);
            }
            if (labeledSubmat != null) {
                labeledSubmat = OpenCVWrapper.release(labeledSubmat);
            }
        }
    }

    private List<Point> getBaselinePoints(BaselineExtractionType baselineExtractionType, Mat rotatedMaskedBaselineMat) {
        switch (baselineExtractionType) {
            case Seamcarve:
                // Seam based extraction
                return getBaselineBySeamCarve(rotatedMaskedBaselineMat);

            case PixelWeight:
                // extraction taking into account pixel weight
                return getBaselineByWeightedPixels(rotatedMaskedBaselineMat);

            case BinaryWeight:
                // classic extraction via binary
                return getGetBaselineByBinaryPixels(rotatedMaskedBaselineMat);

            default:
                return getGetBaselineByBinaryPixels(rotatedMaskedBaselineMat);
        }
    }

    public static String mergeTextLines(PcGts page, List<TextLine> newTextLines, boolean addLinesWithoutRegion,
                                        boolean asSingleRegion, String xmlFile, boolean removeEmptyRegions,
                                        int margin, boolean clearExistingLines) throws JsonProcessingException {
        final List<TextLine> oldTextLines = page.getPage().getTextRegions().stream().flatMap(region -> region.getTextLines().stream()).collect(Collectors.toList());
        final Map<String, String> newLinesToOldLines = BaselinesMapper.mapNewLinesToOldLines(newTextLines, oldTextLines, new Size(page.getPage().getImageWidth(), page.getPage().getImageHeight()));

        for (TextLine newTextLine : newTextLines) {
            if (newLinesToOldLines.containsKey(newTextLine.getId())) {
                final String oldTextLineId = newLinesToOldLines.get(newTextLine.getId());
                final Optional<TextLine> oldTextLine = oldTextLines.stream().filter(oldLine -> oldLine.getId().equals(oldTextLineId)).findAny();
                if (oldTextLine.isPresent()) {
                    newTextLine.setId(oldTextLineId);
                }
            }
        }
        if (!asSingleRegion && page.getPage().getTextRegions().size() > 0) {
            for (TextRegion textRegion : page.getPage().getTextRegions()) {
                if (clearExistingLines) {
                    textRegion.setTextLines(new ArrayList<>());
                }
                newTextLines = PageUtils.attachTextLines(textRegion, newTextLines, 0.51f, 0);
            }
            for (TextRegion textRegion : page.getPage().getTextRegions()) {
                newTextLines = PageUtils.attachTextLines(textRegion, newTextLines, 0.01f, margin);
            }
        } else {
            page.getPage().setTextRegions(new ArrayList<>());

            if (newTextLines.size() > 0) {
                if (addLinesWithoutRegion) {
                    TextRegion newRegion = new TextRegion();
                    newRegion.setId(UUID.randomUUID().toString());
                    Coords coords = new Coords();
                    List<Point> coordPoints = new ArrayList<>();
                    coordPoints.add(new Point(0, 0));
                    coordPoints.add(new Point(page.getPage().getImageWidth() - 1, 0));
                    coordPoints.add(new Point(page.getPage().getImageWidth() - 1, page.getPage().getImageHeight() - 1));
                    coordPoints.add(new Point(0, page.getPage().getImageHeight() - 1));
                    coords.setPoints(StringConverter.pointToString(coordPoints));
                    newRegion.setCoords(coords);
                    newRegion.setTextLines(newTextLines);
                    page.getPage().getTextRegions().add(newRegion);
                }
            }
        }
        if (newTextLines.size() > 0) {
            LOG.debug("textlines remaining: " + newTextLines.size() + " " + xmlFile);
        }

        List<TextRegion> goodRegions = new ArrayList<>();
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
//            if (textRegion.getTextLines().size() > 0 || textRegion.getCustom().contains(":Photo") || textRegion.getCustom().contains(":Drawing")) {
            if (!removeEmptyRegions || textRegion.getTextLines().size() > 0 || textRegion.getCustom().contains(":Photo") || textRegion.getCustom().contains(":Drawing") || textRegion.getCustom().contains(":separator")) {
                goodRegions.add(textRegion);
            }
        }
        page.getPage().setTextRegions(goodRegions);

        return XML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(page);
    }

    private List<Point> getBaselineBySeamCarve(Mat rotatedMaskedBaselineMat) {
        List<Point> baselinePoints;

        Size newSize = rotatedMaskedBaselineMat.size();
        Mat seamImage = new Mat(rotatedMaskedBaselineMat.size(), CV_64F);

        LayoutProc.calcSeamImage(rotatedMaskedBaselineMat, newSize, seamImage);
        baselinePoints = LayoutProc.findSeam(seamImage);
        seamImage = OpenCVWrapper.release(seamImage);
        return baselinePoints;
    }

    private List<Point> getGetBaselineByBinaryPixels(Mat rotatedMaskedBaselineMat) {
        List<Point> baselinePoints;
        baselinePoints = new ArrayList<>();
        for (int column = 0; column < rotatedMaskedBaselineMat.width(); column++) {
            double sum = 0;
            int counter = 0;
            for (int row = 0; row < rotatedMaskedBaselineMat.height(); row++) {
                double pixelValue = rotatedMaskedBaselineMat.get(row, column)[0];
                if (pixelValue > 0) {
                    sum += row;
                    counter++;
                }
            }
            if (counter > 0) {
                sum /= counter;
                baselinePoints.add(new Point(column, sum));
            }
        }
        return baselinePoints;
    }

    private List<Point> getBaselineByWeightedPixels(Mat rotatedMaskedBaselineMat) {
        List<Point> baselinePoints;
        baselinePoints = new ArrayList<>();
        for (int column = 0; column < rotatedMaskedBaselineMat.width(); column++) {
            double sum = 0;
            int counter = 0;
            double pixelValueTotal = 0;
            for (int row = 0; row < rotatedMaskedBaselineMat.height(); row++) {
                double pixelValue = rotatedMaskedBaselineMat.get(row, column)[0];
                sum += (row * pixelValue);
                if (pixelValue > 0) {
                    counter++;
                }
                pixelValueTotal += pixelValue;
            }
            if (counter > 0) {
                sum /= pixelValueTotal;
                baselinePoints.add(new Point(column, sum));
            }
        }
        return baselinePoints;
    }

    public double getAngle(Point start, Point end) {
        double angle = Math.toDegrees(Math.atan2(end.y - start.y, end.x - start.x));

        return angle;
    }


    private Point getCenter(List<Point> overlappingPoints) {
        int x = 0;
        int y = 0;
        for (Point point : overlappingPoints) {
            x += point.x;
            y += point.y;
        }
        return new Point(x / overlappingPoints.size(), y / overlappingPoints.size());
    }

    private List<Point> getOverLappingPixels(int labelNumber, int targetLabel, Mat targetStats, Mat targetLabels) {
        List<Point> points = new ArrayList<>();
        Rect rect = LayoutProc.getRectFromStats(stats, labelNumber);
        Rect rectTarget = LayoutProc.getRectFromStats(targetStats, targetLabel);
        int yStart = rect.y;
        if (rectTarget.y >= yStart) {
            yStart = rectTarget.y;
        }
        for (int i = yStart; i < rect.y + rect.height && i < rectTarget.y + rectTarget.height; i++) {
            for (int j = rect.x; j < rect.x + rect.width; j++) {
                if (targetLabel == (int) targetLabels.get(i, j)[0] &&
                        labelNumber == (int) labeled.get(i, j)[0]) {
                    points.add(new Point(j, i));
                }
            }
        }
        return points;
    }

    private static Options getOptions() {
        Options options = new Options();

        options.addOption(Option.builder("input_path_png").required(true).hasArg(true)
                .desc("Folder that contains the images with the baselines and their start and end.").build()
        );
        options.addOption(Option.builder("input_path_pagexml").required(true).hasArg(true)
                .desc("Folder that contains the PAGE xml that has to be updated").build()
        );
        options.addOption(Option.builder("output_path_pagexml").required(true).hasArg(true)
                .desc("The folder where the updated page has to be saved.").build()
        );
        options.addOption("as_single_region", false, "as_single_region");
        options.addOption("remove_empty_regions", false, "remove empty regions from page");
        options.addOption("margin", true, "the amount of pixels the baseline can be outside of its TextRegion to be included");
        options.addOption("thickness", true, "thickness of the base lines");
        options.addOption("thickness_start_end", true, "thickness of the start and end points");
        options.addOption("minimum_height", true, "minimum text line height");
        options.addOption("threads", true, "threads to use");
        options.addOption("help", false, "prints this help dialog");
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");


        return options;
    }

    public static void printHelp(Options options, String callName) {
        final HelpFormatter helpFormatter = new HelpFormatter();

        helpFormatter.printHelp(callName, options, true);
    }

    //example parameters
    // -input_path_png /scratch/randomprint/results/prod/page/ -input_path_pagexml /scratch/randomprint/results/prod/page/ -output_path_pagexml /scratch/randomprint/results/prod/page/
    
    public static void main(String[] args) throws Exception {
        int numthreads = (Runtime.getRuntime().availableProcessors() / 2) + 1;
//        numthreads=1;

        int maxCount = -1;

        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;
        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException ex) {
            printHelp(options, "java " + MinionExtractBaselinesStartEndNew3.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java " + MinionExtractBaselinesStartEndNew3.class.getName());
            return;
        }
        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

        String inputPathPng = "/scratch/randomprint/results/prod/page/";
        String inputPathPageXml = null;
        String outputPathPageXml = "/scratch/randomprint/results/prod/page/";
        boolean asSingleRegion = false;
        boolean removeEmptyRegions = false;
        int margin = 50;
        int thicknessUsed = 15;
        int thicknessStartEndUsed = 25;
        int minimumHeight = 5;
        if (commandLine.hasOption("input_path_png")) {
            inputPathPng = commandLine.getOptionValue("input_path_png");
            LOG.info("input_path_png: " + inputPathPng);
        }
        if (commandLine.hasOption("input_path_pagexml")) {
            inputPathPageXml = commandLine.getOptionValue("input_path_pagexml");
            LOG.info("input_path_pagexml: " + inputPathPageXml);
        }
        if (commandLine.hasOption("output_path_pagexml")) {
            outputPathPageXml = commandLine.getOptionValue("output_path_pagexml");
            LOG.info("output_path_pagexml: " + outputPathPageXml);
        }
        if (commandLine.hasOption("as_single_region")) {
            asSingleRegion = true;
        }
//        asSingleRegion = true;
        if (commandLine.hasOption("remove_empty_regions")) {
            removeEmptyRegions = true;
        }
        if (commandLine.hasOption("margin")) {
            margin = Integer.parseInt(commandLine.getOptionValue("margin"));
        }
        if (commandLine.hasOption("thickness")) {
            thicknessUsed = Integer.parseInt(commandLine.getOptionValue("thickness"));
        }
        if (commandLine.hasOption("thickness_start_end")) {
            thicknessStartEndUsed = Integer.parseInt(commandLine.getOptionValue("thickness_start_end"));
        }
        if (commandLine.hasOption("minimum_height")) {
            minimumHeight = Integer.parseInt(commandLine.getOptionValue("minimum_height"));
        }
        if (commandLine.hasOption("threads")) {
            numthreads = Integer.parseInt(commandLine.getOptionValue("threads"));
        }

        LOG.info("as_single_region: " + asSingleRegion);

        DirectoryStream<Path> fileStream = Files.newDirectoryStream(Paths.get(inputPathPng));
        List<Path> files = new ArrayList<>();
        fileStream.forEach(files::add);
        files.sort(Comparator.comparing(Path::toString));


        ExecutorService executor = Executors.newFixedThreadPool(numthreads);
        for (Path file : files) {
            if (file.getFileName().toString().endsWith(".png")) {// && file.getFileName().toString().endsWith("DDD_010927620_001.png")) {
                if (maxCount != 0) {
                    maxCount--;
                    String baseFilename = FilenameUtils.removeExtension(file.getFileName().toString());
                    String xmlFile = inputPathPageXml + baseFilename + ".xml";
                    String imageFile = inputPathPng + baseFilename + ".png";
                    String outputFile = outputPathPageXml + baseFilename + ".xml";
                    // TODO: true original filename should be included
                    String imageFilename = baseFilename + ".jpg";
                    if (!Files.exists(Paths.get(outputPathPageXml))) {
                        new File(outputPathPageXml).mkdir();
                    }
                    if (Files.exists(Paths.get(imageFile))) {

                        Runnable worker = new MinionExtractBaselinesStartEndNew3(
                                xmlFile,
                                outputFile,
                                asSingleRegion,
                                removeEmptyRegions,
                                imageFile,
                                margin,
                                thicknessUsed,
                                thicknessStartEndUsed,
                                minimumHeight,
                                imageFilename, namespace
                        );
                        executor.execute(worker);//calling execute method of ExecutorService
                    }
                }
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                LOG.warn("Timed out waiting for baseline extraction workers to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while waiting for baseline extraction workers", e);
        }
        LOG.info("Finished all threads");
    }


    @Override
    public void run() {
        Mat combinedMat = null;
        try {
            combinedMat = OpenCVWrapper.imread(this.imageFile, Imgcodecs.IMREAD_COLOR);
            List<Mat> bgr = new ArrayList<>(3);
            Core.split(combinedMat, bgr);
            this.baseLineMatEnd = bgr.get(0);
            this.baseLineMatStart = bgr.get(1);
            this.baseLineMat = bgr.get(2);
            this.thresHoldedBaselines = new Mat();
            this.thresHoldedBaselinesStart = new Mat();
            this.thresHoldedBaselinesEnd = new Mat();
            boolean clearExistingLines = true;
            int threshold = 50;
//            for (int threshold : new int[]{10, 50, 100, 150}) {
            Imgproc.threshold(this.baseLineMat, this.thresHoldedBaselines, threshold, 255, Imgproc.THRESH_BINARY);
            Imgproc.threshold(this.baseLineMatStart, this.thresHoldedBaselinesStart, threshold, 255, Imgproc.THRESH_BINARY);
            Imgproc.threshold(this.baseLineMatEnd, this.thresHoldedBaselinesEnd, threshold, 255, Imgproc.THRESH_BINARY);

            // Imgcodecs.imwrite("/tmp/output/" + this.imageFilename + ".png",  this.thresHoldedBaselines);
            // Imgcodecs.imwrite("/tmp/output/" + this.imageFilename + "start.png",  this.thresHoldedBaselinesStart);
            // Imgcodecs.imwrite("/tmp/output/" + this.imageFilename + "end.png",  this.thresHoldedBaselinesEnd);

            this.stats = new Mat();
            this.centroids = new Mat();
            this.labeled = new Mat();

            this.statsStart = new Mat();
            this.centroidsStart = new Mat();
            this.labeledStart = new Mat();

            this.statsEnd = new Mat();
            this.centroidsEnd = new Mat();
            this.labeledEnd = new Mat();

            this.numLabels = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselines, labeled, stats, centroids);
            this.numLabelsStart = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselinesStart, labeledStart, statsStart, centroidsStart);
            this.numLabelsEnd = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselinesEnd, labeledEnd, statsEnd, centroidsEnd);
            this.zeroMat = Mat.ones(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.remainingMat = Mat.zeros(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.zeroMatThresholded = Mat.ones(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.labeledRemaining = new Mat();
            this.statsRemaining = new Mat();
            this.centroidsRemaining = new Mat();
            boolean mergeTextLinesWithoutEndToTextLinesWithoutStart = true;
            int minimumLengthTextLine = 10;
            extractAndMergeBaseLinesNew(xmlFile, outputFile, margin, clearExistingLines,
                    BaselineExtractionType.PixelWeight, mergeTextLinesWithoutEndToTextLinesWithoutStart,
                    minimumLengthTextLine);
            clearExistingLines = false;
//            }
        } catch (IOException | TransformerException e) {
            LOG.error("Error while processing {}", xmlFile, e);
        } finally {
            if (combinedMat != null) {
                combinedMat = OpenCVWrapper.release(combinedMat);
            }
            try {
                this.close();
            } catch (Exception e) {
                LOG.error("Error while releasing Mats for {}", xmlFile, e);
            }
        }
    }

    @Override
    public void close() throws Exception {
        baseLineMat = safeRelease(baseLineMat);
        baseLineMatStart = safeRelease(baseLineMatStart);
        baseLineMatEnd = safeRelease(baseLineMatEnd);
        thresHoldedBaselines = safeRelease(thresHoldedBaselines);
        thresHoldedBaselinesStart = safeRelease(thresHoldedBaselinesStart);
        thresHoldedBaselinesEnd = safeRelease(thresHoldedBaselinesEnd);
        stats = safeRelease(stats);
        statsStart = safeRelease(statsStart);
        statsEnd = safeRelease(statsEnd);
        centroids = safeRelease(centroids);
        centroidsStart = safeRelease(centroidsStart);
        centroidsEnd = safeRelease(centroidsEnd);
        labeled = safeRelease(labeled);
        labeledStart = safeRelease(labeledStart);
        labeledEnd = safeRelease(labeledEnd);
        zeroMat = safeRelease(zeroMat);
        remainingMat = safeRelease(remainingMat);
        zeroMatThresholded = safeRelease(zeroMatThresholded);
        labeledRemaining = safeRelease(labeledRemaining);
        statsRemaining = safeRelease(statsRemaining);
        centroidsRemaining = safeRelease(centroidsRemaining);
    }

    private Mat safeRelease(Mat mat) {
        if (mat == null || mat.dataAddr() == 0) {
            return null;
        }
        return OpenCVWrapper.release(mat);
    }
}