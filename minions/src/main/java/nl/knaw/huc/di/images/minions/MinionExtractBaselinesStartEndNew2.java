package nl.knaw.huc.di.images.minions;

import com.google.common.collect.Lists;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.Page.Baseline;
import nl.knaw.huc.di.images.layoutds.models.Page.Coords;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.*;
import org.apache.commons.io.FilenameUtils;
import org.opencv.core.*;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/*
This takes pageXML and an png containing baselines
 and an png containing baseline start and ending
 and extracts info about the baselines
 and add baseline/textline information to the regions in the pagexml
 */
public class MinionExtractBaselinesStartEndNew2 implements Runnable, AutoCloseable {

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    private final String imageFile;
    private final String imageFileStart;
    private final String imageFileEnd;
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


    private String xmlFile;
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
    private int margin;
    private int dilationUsed;
    private final int minimumHeight;

    private int shortest_edge;

    private int max_size;

    private static final Logger LOG = LoggerFactory.getLogger(MinionExtractBaselinesStartEndNew2.class);


    public MinionExtractBaselinesStartEndNew2(String xmlFile,
                                              String outputFile, boolean asSingleRegion,
                                              boolean removeEmptyRegions,
                                              String imageFile,
                                              String imageFileStart,
                                              String imageFileEnd,
                                              int margin,
                                              int dilationUsed,
                                              int minimumHeight,
                                              int shortest_edge,
                                              int max_size,
                                              String namespace
    ) {
        this.xmlFile = xmlFile;
        this.outputFile = outputFile;
        this.asSingleRegion = asSingleRegion;
        this.removeEmptyRegions = removeEmptyRegions;
        this.imageFile = imageFile;
        this.imageFileStart = imageFileStart;
        this.imageFileEnd = imageFileEnd;
        this.margin = margin;
        this.dilationUsed = dilationUsed;
        this.minimumHeight = minimumHeight;
        this.shortest_edge = shortest_edge;
        this.max_size = max_size;
        this.namespace = namespace;
    }

    private void extractAndMergeBaseLinesNew(
            String xmlPath, String outputFile, int margin, String namespace
    ) throws IOException, TransformerException {
        String transkribusPageXml = StringTools.readFile(xmlPath);
        PcGts page = PageUtils.readPageFromString(transkribusPageXml);
        int pointDistance = 1;
        List<List<Point>> baselines = new ArrayList<>();
        // Start -> follow -> end".

        // Loop all start labels
        for (int labelNumber = 1; labelNumber < numLabelsStart; labelNumber++) {

            // Get starting point of the baseline
            Point startPoint = getStartPoint(labelNumber, dilationUsed);
            Rect rect = null;

            // Loop from startPoint to startPoint +50 or end of array
            for (int startCounter = (int) startPoint.x; startCounter < (int) startPoint.x + 50 && startCounter < labeled.width() - 1; startCounter++) {

                // If non zero value is found get the connected components from the found baseline, if not don't add baseline
                int pixelValueTarget = (int) labeled.get((int) startPoint.y, startCounter)[0];
                if (pixelValueTarget != 0) {
                    // Get bbox of connected component of baseline and end
                    rect = LayoutProc.getRectFromStats(stats, pixelValueTarget);;
                    Mat submat = labeled.submat(rect);
                    Mat submatEnd = labeledEnd.submat(rect);
                    Point offset = new Point(rect.x, rect.y);
                    List<Point> baseline = new ArrayList<>();
                    Point point = null;
                    int pixelCounter = -1;
                    boolean endPixelsFound = false;
                    boolean mergedLineDetected = false;

                    // Loop bbox from starting point in x direction
                    for (int i = startCounter - rect.x; i < submat.width(); i++) {
                        boolean mergedLineDetectedStep1 = false;
                        double sum = 0;
                        int counter = 0;
                        // Loop bbox in y direction
                        for (int j = 0; j < submat.height(); j++) {
                            // Count if match with baseline, sum height
                            int pixelValue = (int) submat.get(j, i)[0];
                            if (pixelValue == pixelValueTarget) {
                                sum += j;
                                counter++;
                                if (mergedLineDetectedStep1) {
                                    mergedLineDetected = true;
                                }
                                // If pixel was part of the selected baseline set to zero
                                zeroMat.put(rect.y + j, rect.x + i, 0);
                            } else {
                                // Check if height of connected component is higher than 0
                                if (counter > 0) {
                                    mergedLineDetectedStep1 = true;
                                }
                            }
                        }

                        // If thickness higher than 1
                        if (counter > 1) {
                            // Average height
                            sum /= counter;

                            // Check if end pixels found at least 50 pixels away
                            if (pixelCounter > 50 && (int) submatEnd.get((int) sum, i)[0] != 0) {
//                                System.out.println("reached end of textline");
                                endPixelsFound = true;
                            } else if (endPixelsFound) {
                                break;
                            }

                        }

                        // If thickness not over minimum height
                        if (counter < minimumHeight) {
                            continue;
                        }

                        // Count part of baseline that is above minimum height
                        pixelCounter++;

                        // Put a baseline at the middle (sum) of the y height
                        point = new Point(i + offset.x, sum + offset.y);
                        if (pixelCounter % pointDistance == 0) {
                            baseline.add(point);
                        }
                    }

                    // Add final baseline part
                    if (pixelCounter % pointDistance != 0) {
                        baseline.add(point);
                    }

                    if (mergedLineDetected) {
                        LOG.info("mergedLineDetected: " + xmlFile);
                    }
                    submat.release();
                    submatEnd.release();

                    // More than two points found -> add baseline to list
                    if (baseline.size() > 2) {
                        // Douglas-Peucker Simplification
                        baseline = StringConverter.simplifyPolygon(baseline, 3);
                    }

                    // Break out of loop finding the baseline after successfully adding baseline
                    baselines.add(baseline);
                    break;
                }
            }
        }

        // Add textlines to the page
        List<TextLine> newTextLines = new ArrayList<TextLine>();
        for (List<Point> baselinePoints : baselines) {
            if (baselinePoints.size() < 2) {
                continue;
            }
            // First add textline
            TextLine textLine = new TextLine();
            Coords coords = new Coords();
            // These values are always overwritten
            List<Point> coordPoints = new ArrayList<>();
            coordPoints.add(new Point(0, 0));
            coordPoints.add(new Point(1, 0));
            coordPoints.add(new Point(1, 1));
            coordPoints.add(new Point(0, 1));
            coords.setPoints(StringConverter.pointToString(coordPoints));
            textLine.setCoords(coords);

            // Add baseline to textline
            Baseline baseline = new Baseline();
            baseline.setPoints(StringConverter.pointToString(baselinePoints));
            textLine.setBaseline(baseline);
            textLine.setId(UUID.randomUUID().toString());
            newTextLines.add(textLine);
        }

        // Threshold all non zeros in the zero mat to 255 (Just for debugging?)
        Imgproc.threshold(zeroMat, zeroMatThresholded, 0, 255, Imgproc.THRESH_BINARY);

        // Copy the remaining connected component baselines, with mask of the already used baselines (zeroMat)
        thresHoldedBaselines.copyTo(remainingMat, zeroMatThresholded);
        zeroMatThresholded.release();

        // Rerun connected components to see if any new baseline are found
        int numLabelsRemaining = OpenCVWrapper.connectedComponentsWithStats(remainingMat, labeledRemaining, statsRemaining, centroidsRemaining, 8, CvType.CV_32S);
        baselines.clear();
// Search from right to left

        // Loop all end labels
        for (int labelNumber = 1; labelNumber < numLabelsEnd; labelNumber++) {

            // Get starting point of the baseline
            Point endPoint = getEndPoint(labelNumber);
            Rect rect = null;

            // Loop from endPoint to endPoint -50 or start of array
            for (int endCounter = (int) endPoint.x; endCounter > (int) endPoint.x - 50 && endCounter > 0; endCounter--) {
                int pixelValueTarget = (int) labeledRemaining.get((int) endPoint.y, endCounter)[0];
                if (pixelValueTarget != 0) {
                    // Get bbox of connected component of baseline and end
                    rect = new Rect((int) statsRemaining.get(pixelValueTarget, Imgproc.CC_STAT_LEFT)[0],
                            (int) statsRemaining.get(pixelValueTarget, Imgproc.CC_STAT_TOP)[0],
                            (int) statsRemaining.get(pixelValueTarget, Imgproc.CC_STAT_WIDTH)[0],
                            (int) statsRemaining.get(pixelValueTarget, Imgproc.CC_STAT_HEIGHT)[0]);
                    Mat submat = labeledRemaining.submat(rect);
//                    Mat submatRemaining = labeledRemaining.submat(rect);
                    Point offset = new Point(rect.x, rect.y);
                    List<Point> baseline = new ArrayList<>();

                    Point point = null;
                    int pixelCounter = -1;

                    // Loop bbox from end point in negative x direction
                    for (int i = endCounter - rect.x; i > 0; i--) {
                        double sum = 0;
                        int counter = 0;
                        // Loop bbox in y direction
                        for (int j = 0; j < submat.height(); j++) {
                            // Count if match with baseline, sum height
                            int pixelValue = (int) submat.get(j, i)[0];
                            if (pixelValue == pixelValueTarget) {
                                sum += j;
                                counter++;

                                // If pixel was part of the selected baseline set to zero
                                labeledRemaining.put(rect.y + j, rect.x + i, 0);
                                zeroMat.put(rect.y + j, rect.x + i, 0);
                            }
                        }

                        // If thickness higher than 1
                        if (counter > 1) {
                            // Average height
                            sum /= counter;
                        }

                        // If thickness not over minimum height
                        if (counter < minimumHeight) {
                            continue;
                        }

                        // Count part of baseline that is above minimum height
                        pixelCounter++;

                        // Put a baseline at the middle (sum) of the y height
                        point = new Point(i + offset.x, sum + offset.y);
                        if (pixelCounter % pointDistance == 0) {
                            baseline.add(point);
                        }
                    }

                    // Add final baseline part
                    if (pixelCounter % pointDistance != 0) {
                        baseline.add(point);
                    }

                    submat.release();
//                    submatRemaining.release();

                    // Reverse because it started from the end
                    baseline = Lists.reverse(baseline);

                    // More than two points found -> add baseline to list
                    if (baseline.size() > 2) {
                        baseline = StringConverter.simplifyPolygon(baseline, 2);
                    }

                    // Break out of loop finding the baseline after successfully adding baseline
                    baselines.add(baseline);
                    break;
                }
            }
        }

        // Add textlines to the page
        for (List<Point> baselinePoints : baselines) {
            if (baselinePoints.size() < 2) {
                continue;
            }
            // First add textline
            TextLine textLine = new TextLine();
            Coords coords = new Coords();
            // These values are always overwritten
            List<Point> coordPoints = new ArrayList<>();
            coordPoints.add(new Point(0, 0));
            coordPoints.add(new Point(1, 0));
            coordPoints.add(new Point(1, 1));
            coordPoints.add(new Point(0, 1));
            coords.setPoints(StringConverter.pointToString(coordPoints));
            textLine.setCoords(coords);

            // Add baseline to textline
            Baseline baseline = new Baseline();
            baseline.setPoints(StringConverter.pointToString(baselinePoints));
            textLine.setBaseline(baseline);
            textLine.setId(UUID.randomUUID().toString());
            newTextLines.add(textLine);
        }

/// new code

        // Threshold all non zeros in the zero mat to 255 (Just for debugging?)
        Imgproc.threshold(zeroMat, zeroMatThresholded, 0, 1, Imgproc.THRESH_BINARY);
        remainingMat.release();

        // Copy the remaining connected component baselines, with mask of the already used baselines (zeroMat)
        this.remainingMat = Mat.zeros(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
        thresHoldedBaselines.copyTo(remainingMat, zeroMatThresholded);
//        Imgcodecs.imwrite("/tmp/thresHoldedBaselines.png", thresHoldedBaselines);
//        Imgcodecs.imwrite("/tmp/zeroMat.png", zeroMat);
//        Imgcodecs.imwrite("/tmp/zeroMatThresholded.png", zeroMatThresholded);
//        Imgcodecs.imwrite("/tmp/remain.png", remainingMat);
        zeroMatThresholded.release();

        baselines.clear();
// remaining without valid start or end

        // Rerun connected components to see if any new baseline are found
        numLabelsRemaining = OpenCVWrapper.connectedComponentsWithStats(remainingMat, labeledRemaining, statsRemaining, centroidsRemaining, 8, CvType.CV_32S);

        // Loop all baseline labels
        for (int labelNumber = 1; labelNumber < numLabelsRemaining; labelNumber++) {
            // Get bbox of connected component of baseline and end
            Rect rect = null;
            rect = new Rect((int) statsRemaining.get(labelNumber, Imgproc.CC_STAT_LEFT)[0],
                    (int) statsRemaining.get(labelNumber, Imgproc.CC_STAT_TOP)[0],
                    (int) statsRemaining.get(labelNumber, Imgproc.CC_STAT_WIDTH)[0],
                    (int) statsRemaining.get(labelNumber, Imgproc.CC_STAT_HEIGHT)[0]);
            Mat submat = labeledRemaining.submat(rect);
            Point offset = new Point(rect.x, rect.y);
            List<Point> baseline = new ArrayList<>();

            Point point = null;
            int pixelCounter = -1;

            // Loop bbox from starting point in x direction
            for (int i = 0; i < rect.width; i++) {
                double sum = 0;
                int counter = 0;

                // Loop bbox in y direction
                for (int j = 0; j < submat.height(); j++) {
                    // Count if match with baseline and not with the start or end, sum height
                    int pixelValue = (int) submat.get(j, i)[0];
                    if (pixelValue == labelNumber
                            && (int) labeledEnd.get(rect.y + j, rect.x + i)[0] == 0
                            && (int) labeledStart.get(rect.y + j, rect.x + i)[0] == 0) {
                        sum += j;
                        counter++;
                        // If pixel was part of the selected baseline set to zero
                        labeledRemaining.put(rect.y + j, rect.x + i, 0);
//                        zeroMat.put(rect.y + j, rect.x + i, 0);
                    }
                }

                // If thickness higher than 1
                if (counter > 1) {
                    // Average height
                    sum /= counter;
                }

                // If thickness not over minimum height
                if (counter < minimumHeight) {
                    continue;
                }

                // Count part of baseline that is above minimum height
                pixelCounter++;

                // Put a baseline at the middle (sum) of the y height
                point = new Point(i + offset.x, sum + offset.y);
                if (pixelCounter % pointDistance == 0) {
                    baseline.add(point);
                }
            }

            // Add final baseline part
            if (pixelCounter % pointDistance != 0) {
                baseline.add(point);
            }

            submat.release();

            // Don't add if less than length 50
            if (baseline.size() < 50) {
                continue;
            }

            // More than two points found -> add baseline to list
            if (baseline.size() > 2) {
                baseline = StringConverter.simplifyPolygon(baseline, 2);
            }
            baselines.add(baseline);
        }

        // Add textlines to the page
        for (List<Point> baselinePoints : baselines) {
            if (baselinePoints.size() < 2) {
                continue;
            }
            // First add textline
            TextLine textLine = new TextLine();
            Coords coords = new Coords();
            // These values are always overwritten
            List<Point> coordPoints = new ArrayList<>();
            coordPoints.add(new Point(0, 0));
            coordPoints.add(new Point(1, 0));
            coordPoints.add(new Point(1, 1));
            coordPoints.add(new Point(0, 1));
            coords.setPoints(StringConverter.pointToString(coordPoints));
            textLine.setCoords(coords);

            // Add baseline to textline
            Baseline baseline = new Baseline();
            baseline.setPoints(StringConverter.pointToString(baselinePoints));
            textLine.setBaseline(baseline);
            textLine.setId(UUID.randomUUID().toString());
            newTextLines.add(textLine);
        }
/// end new code

        // find duplicate lines
        List<TextLine> linesToRemove = new ArrayList<>();
        for (int i = 0; i < newTextLines.size(); i++) {
            for (int j = i + 1; j < newTextLines.size(); j++) {
                TextLine firstLine = newTextLines.get(i);
                TextLine secondLine = newTextLines.get(j);
                ArrayList<Point> firstPoints = StringConverter.stringToPoint(firstLine.getBaseline().getPoints());
                ArrayList<Point> secondPoints = StringConverter.stringToPoint(secondLine.getBaseline().getPoints());

                Point firstLastPoints = firstPoints.get(firstPoints.size() - 1);
                Point secondLastPoints = secondPoints.get(secondPoints.size() - 1);

                // Duplicate if last points match
                if (firstLastPoints.x == secondLastPoints.x && firstLastPoints.y == secondLastPoints.y) {
                    if (firstPoints.get(0).x > secondPoints.get(0).x) {
                        linesToRemove.add(firstLine);
                    } else {
                        linesToRemove.add(secondLine);
                    }
                    continue;
                }
                Point firstPoint = firstPoints.get(0);
                Point secondPoint = secondPoints.get(0);

                // Duplicates if last points match
                if (firstPoint.x == secondPoint.x && firstPoint.y == secondPoint.y) {
                    linesToRemove.add(secondLine);
                    continue;
                }
            }
        }
        newTextLines.removeAll(linesToRemove);
        labeledRemaining.release();
        statsRemaining.release();
        centroidsRemaining.release();

        // Add lines to the regions they were found in
        page = MinionExtractBaselines.mergeTextLines(page, newTextLines, asSingleRegion, xmlPath,
                removeEmptyRegions, margin, true);

        // This is what fixes the found textlines polygon, otherwise they are just 0 and 1
        LayoutProc.recalculateTextLinesFromBaselines(page);
        PageUtils.writePageToFile(page, namespace, Paths.get(outputFile));
    }

//    def get_output_shape(old_height: int, old_width: int, short_edge_length: int, max_size: int) -> tuple[int, int]:
//            """
//        Compute the output size given input size and target short edge length.
//
//        Args:
//            old_height (int): original height of image
//            old_width (int): original width of image
//            short_edge_length (int): desired shortest edge length
//            max_size (int): max length of other edge
//
//        Returns:
//            tuple[int, int]: new height and width
//        """
//        scale = float(short_edge_length) / min(old_height, old_width)
//            if old_height < old_width:
//        height, width = short_edge_length, scale * old_width
//            else:
//        height, width = scale * old_height, short_edge_length
//            if max(height, width) > max_size:
//        scale = max_size * 1.0 / max(height, width)
//        height = height * scale
//                width = width * scale
//
//        height = int(height + 0.5)
//        width = int(width + 0.5)
//        return (height, width)

    private Size getOutputShape() {
        float scale = (float) shortest_edge / (float) Math.min(baseLineMat.height(), baseLineMat.width());

        float height;
        float width;
        if (baseLineMat.height() < baseLineMat.width()) {
            height = shortest_edge;
            width = scale * baseLineMat.width();
        } else {
            height = scale * baseLineMat.height();
            width = shortest_edge;
        }
        if (Math.max(height, width) > max_size){
            scale = (float) max_size / Math.max(height, width);
        }
        height = scale * height;
        width = scale * width;

        int height_int = (int) (height + 0.5);
        int width_int = (int) (width + 0.5);

        return new Size(width_int, height_int);
    }



    private Point getStartPoint(int labelNumber, int dilationUsed) {
        int pixelCounter = 0;
        int totalPixelsOn = 0;
        // Get connected component bounding box
        Rect rect = new Rect((int) statsStart.get(labelNumber, Imgproc.CC_STAT_LEFT)[0],
                (int) statsStart.get(labelNumber, Imgproc.CC_STAT_TOP)[0],
                (int) statsStart.get(labelNumber, Imgproc.CC_STAT_WIDTH)[0],
                (int) statsStart.get(labelNumber, Imgproc.CC_STAT_HEIGHT)[0]);
        Mat submat = labeledStart.submat(rect);

        // Get the average offset of the y value
        try {
            for (int counter = 0; counter < rect.height; counter++) {
                int pixelValue = (int) submat.get(counter, submat.width() - 1)[0];
                if (pixelValue == labelNumber) {
                    pixelCounter += counter;
                    totalPixelsOn++;
                }
            }
        } finally {
            submat.release();
        }

        // Start point selected based on the dilation and found offset (Seem like it would only work in one orientation)
        Point startPoint = new Point(rect.x + dilationUsed, rect.y + (totalPixelsOn == 0 ? rect.height / 2 : (pixelCounter / totalPixelsOn)));
        return startPoint;
    }

    private Point getEndPoint(int labelNumber) {
        int pixelCounter = 0;
        int totalPixelsOn = 0;
        // Get connected component bounding box
        Rect rect = new Rect((int) statsEnd.get(labelNumber, Imgproc.CC_STAT_LEFT)[0],
                (int) statsEnd.get(labelNumber, Imgproc.CC_STAT_TOP)[0],
                (int) statsEnd.get(labelNumber, Imgproc.CC_STAT_WIDTH)[0],
                (int) statsEnd.get(labelNumber, Imgproc.CC_STAT_HEIGHT)[0]);
        Mat submat = labeledEnd.submat(rect);

        // Get the average offset of the y value
        try {
            for (int counter = 0; counter < rect.height; counter++) {
                int pixelValue = (int) submat.get(counter, 0)[0];
                if (pixelValue == labelNumber) {
                    pixelCounter += counter;
                    totalPixelsOn++;
                }
            }
        } finally {
            submat.release();
        }

        // Start point selected based on the bounding box size and found offset
        Point endPoint = new Point(rect.x + (rect.width / 2), rect.y + (totalPixelsOn == 0 ? rect.height / 2 : (pixelCounter / totalPixelsOn)));
        return endPoint;
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
        options.addOption("as_single_region", false, "All text lines should be included in one text region. (default: false)");
        options.addOption("remove_empty_regions", false, "Remove empty regions from page. (default: false)");
        options.addOption("margin", true, "The amount of pixels the baseline can be outside of its TextRegion to be included (default: 50)");
        options.addOption("dilation", true, "Compensate for the dilation used in pixels (default: 5)");
        options.addOption("minimum_height", true, "Minimum height of a text line in pixels (default: 5)");
        options.addOption("shortest_edge", true, "The shortest edge resize parameter (default: -1)");
        options.addOption("max_size", true, "The max size resize parameter");
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");

        return options;
    }

    public static void printHelp(Options options, String callName) {
        final HelpFormatter helpFormatter = new HelpFormatter();

        helpFormatter.printHelp(callName, options, true);
    }


    //example parameters
    // -input_path_png /scratch/randomprint/results/prod/page/ -input_path_png_start /scratch/randomprint-start/results/prod/page/ -input_path_png_end /scratch/randomprint-end/results/prod/page/ -input_path_pagexml /scratch/randomprint/results/prod/page/ -output_path_pagexml /scratch/randomprint/results/prod/page/
    public static void main(String[] args) throws Exception {
        int numthreads = (Runtime.getRuntime().availableProcessors() / 2) + 1;
        int maxCount = -1;

        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;
        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException ex) {
            printHelp(options, "java " + MinionExtractBaselinesStartEndNew2.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java " + MinionExtractBaselinesStartEndNew2.class.getName());
            return;
        }

        String inputPathPng = "/scratch/randomprint/results/prod/page/";
        String inputPathPngStart = "/scratch/randomprint-start/results/prod/page/";
        String inputPathPngEnd = "/scratch/randomprint-end/results/prod/page/";
        String inputPathPageXml = "/scratch/randomprint/results/prod/page/";
        String outputPathPageXml = "/scratch/randomprint/results/prod/page/";
        boolean asSingleRegion = false;
        boolean removeEmptyRegions = false;
        int margin = 50;
        int dilationUsed = 5;
        int minimumHeight = 5;
        int shortest_edge = -1;
        int max_size = -1;
        if (commandLine.hasOption("input_path_png")) {
            inputPathPng = commandLine.getOptionValue("input_path_png");
            LOG.info("input_path_png: {}", inputPathPng);
        }
        if (commandLine.hasOption("input_path_pagexml")) {
            inputPathPageXml = commandLine.getOptionValue("input_path_pagexml");
            LOG.info("input_path_pagexml: {}", inputPathPageXml);
        }
        if (commandLine.hasOption("output_path_pagexml")) {
            outputPathPageXml = commandLine.getOptionValue("output_path_pagexml");
            LOG.info("output_path_pagexml: {}", outputPathPageXml);
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
        if (commandLine.hasOption("dilation")) {
            dilationUsed = Integer.parseInt(commandLine.getOptionValue("dilation"));
        }
        if (commandLine.hasOption("minimum_height")) {
            minimumHeight = Integer.parseInt(commandLine.getOptionValue("minimum_height"));
        }
        if (commandLine.hasOption("shortest_edge")) {
            shortest_edge = Integer.parseInt(commandLine.getOptionValue("shortest_edge"));
        }
        if (commandLine.hasOption("max_size")) {
            max_size = Integer.parseInt(commandLine.getOptionValue("max_size"));
        }
        LOG.info("as_single_region: {}", asSingleRegion);
        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

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
                    String xmlFile = new File(inputPathPageXml, baseFilename + ".xml").getAbsolutePath();
                    String imageFile = new File(inputPathPng, baseFilename + ".png").getAbsolutePath();
                    String imageFileStart = new File(inputPathPngStart, baseFilename + ".png").getAbsolutePath();
                    String imageFileEnd = new File(inputPathPngEnd, baseFilename + ".png").getAbsolutePath();
                    String outputFile = new File(outputPathPageXml, baseFilename + ".xml").getAbsolutePath();

                    if (Files.exists(Paths.get(xmlFile))
                            && Files.exists(Paths.get(imageFile))
                            && Files.exists(Paths.get(imageFileStart))
                            && Files.exists(Paths.get(imageFileEnd))
                    ) {
//                        System.out.println(xmlFile);

                        Runnable worker = new MinionExtractBaselinesStartEndNew2(
                                xmlFile,
                                outputFile, asSingleRegion,
                                removeEmptyRegions,
                                imageFile, imageFileStart, imageFileEnd,
                                margin, dilationUsed, minimumHeight, shortest_edge, max_size, namespace
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
        try {
//            extractAndMergeBaseLines(xmlFile, outputFile);
//            System.out.println("starting: "+this.imageFile);
            baseLineMat = OpenCVWrapper.imread(this.imageFile, Imgcodecs.IMREAD_GRAYSCALE);
            baseLineMatStart = OpenCVWrapper.imread(imageFileStart, Imgcodecs.IMREAD_GRAYSCALE);
            baseLineMatEnd = OpenCVWrapper.imread(imageFileEnd, Imgcodecs.IMREAD_GRAYSCALE);
            thresHoldedBaselines = new Mat();
            thresHoldedBaselinesStart = new Mat();
            thresHoldedBaselinesEnd = new Mat();

            // Threshold all non-zero to 255
            Imgproc.threshold(baseLineMat, thresHoldedBaselines, 0, 255, Imgproc.THRESH_BINARY);
            Imgproc.threshold(baseLineMatStart, thresHoldedBaselinesStart, 0, 255, Imgproc.THRESH_BINARY);
            Imgproc.threshold(baseLineMatEnd, thresHoldedBaselinesEnd, 0, 255, Imgproc.THRESH_BINARY);

            stats = new Mat();
            centroids = new Mat();
            labeled = new Mat();

            statsStart = new Mat();
            centroidsStart = new Mat();
            labeledStart = new Mat();

            statsEnd = new Mat();
            centroidsEnd = new Mat();
            labeledEnd = new Mat();

//                        Imgcodecs.imwrite ("/tmp/thresHoldedBaselines.png",thresHoldedBaselines );
//                        Imgcodecs.imwrite ("/tmp/thresHoldedBaselinesStart.png",thresHoldedBaselinesStart );
//                        Imgcodecs.imwrite ("/tmp/thresHoldedBaselinesEnd.png",thresHoldedBaselinesEnd );

            // Get connected components for baseline start and end
            numLabels = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselines, labeled, stats, centroids);
            numLabelsStart = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselinesStart, labeledStart, statsStart, centroidsStart);
            numLabelsEnd = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselinesEnd, labeledEnd, statsEnd, centroidsEnd);
            this.zeroMat = Mat.ones(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.remainingMat = Mat.zeros(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.zeroMatThresholded = Mat.ones(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.labeledRemaining = new Mat();
            this.statsRemaining = new Mat();
            this.centroidsRemaining = new Mat();

            extractAndMergeBaseLinesNew(this.xmlFile, this.outputFile, this.margin, this.namespace);
        } catch (IOException | TransformerException e) {
            LOG.error("Unexpected error", e);
        } finally {
            try {
                this.close();
            } catch (Exception e) {
                LOG.error("Unexpected error", e);
            }
        }
    }

    @Override
    public void close() throws Exception {
        baseLineMat.release();
        baseLineMatStart.release();
        baseLineMatEnd.release();
        thresHoldedBaselines.release();
        thresHoldedBaselinesStart.release();
        thresHoldedBaselinesEnd.release();
        stats.release();
        statsStart.release();
        statsEnd.release();
        centroids.release();
        centroidsStart.release();
        centroidsEnd.release();
        labeled.release();
        labeledStart.release();
        labeledEnd.release();
        zeroMat.release();
        remainingMat.release();
        zeroMatThresholded.release();
        labeledRemaining.release();
        statsRemaining.release();
        centroidsRemaining.release();
    }
}