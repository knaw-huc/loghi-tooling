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
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/*
This takes pageXML and an png containing baselines
 and an png containing baseline start and ending
 and extracts info about the baselines
 and add baseline/textline information to the regions in the pagexml
 */
public class MinionExtractBaselinesStartEndNew implements Runnable, AutoCloseable {

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

    private static final Logger LOG = LoggerFactory.getLogger(MinionExtractBaselinesStartEndNew.class);
    private List<String> regionOrderList = new ArrayList<>();


    public MinionExtractBaselinesStartEndNew(String xmlFile,
                                             String outputFile, boolean asSingleRegion,
                                             boolean removeEmptyRegions,
                                             String imageFile,
                                             String imageFileStart,
                                             String imageFileEnd,
                                             int margin,
                                             int dilationUsed,
                                             int minimumHeight,
                                             List<String> regionOrder,
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
        this.regionOrderList = regionOrder;
        this.namespace = namespace;
    }

    private void extractAndMergeBaseLinesNew(
            String xmlPath, String outputFile, int margin, String namespace
    ) throws IOException, TransformerException {
        String pageXml = StringTools.readFile(xmlPath);
        PcGts page = PageUtils.readPageFromString(pageXml);
        int pointDistance = 1;
        List<List<Point>> baselines = new ArrayList<>();
        // Start -> follow -> end".
        for (int labelNumber = 1; labelNumber < numLabelsStart; labelNumber++) {
            Point startPoint = getStartPoint(labelNumber, dilationUsed);
            Rect rect = null;
            for (int startCounter = (int) startPoint.x; startCounter < (int) startPoint.x + 50 && startCounter < labeled.width() - 1; startCounter++) {
                int pixelValueTarget = (int) labeled.get((int) startPoint.y, startCounter)[0];
                if (pixelValueTarget != 0) {
                    rect = LayoutProc.getRectFromStats(stats, pixelValueTarget);
                    Mat submat = labeled.submat(rect);
                    Mat submatEnd = labeledEnd.submat(rect);
                    Point offset = new Point(rect.x, rect.y);
                    List<Point> baseline = new ArrayList<>();
                    Point point = null;
                    int pixelCounter = -1;
                    boolean endPixelsFound = false;
                    boolean mergedLineDetected = false;
                    for (int i = startCounter - rect.x; i < submat.width(); i++) {
                        boolean mergedLineDetectedStep1 = false;
                        double sum = 0;
                        int counter = 0;
                        for (int j = 0; j < submat.height(); j++) {
                            int pixelValue = (int) submat.get(j, i)[0];
                            if (pixelValue == pixelValueTarget) {
                                sum += j;
                                counter++;
                                if (mergedLineDetectedStep1) {
                                    mergedLineDetected = true;
                                }
                                zeroMat.put(rect.y + j, rect.x + i, 0);
                            } else {
                                if (counter > 0) {
                                    mergedLineDetectedStep1 = true;
                                }
                            }
                        }
                        if (counter > 1) {
                            sum /= counter;
                            if (pixelCounter > 50 && (int) submatEnd.get((int) sum, i)[0] != 0) {
//                                System.out.println("reached end of textline");
                                endPixelsFound = true;
                            } else if (endPixelsFound) {
                                break;
                            }

                        }
                        if (counter < minimumHeight) {
                            continue;
                        }

                        pixelCounter++;

                        point = new Point(i + offset.x, sum + offset.y);
                        if (pixelCounter % pointDistance == 0) {
                            baseline.add(point);
                        }
                    }
                    if (pixelCounter % pointDistance != 0) {
                        baseline.add(point);
                    }
                    if (mergedLineDetected) {
                        LOG.info("mergedLineDetected: " + xmlFile);
                    }
                    submat = OpenCVWrapper.release(submat);
                    submatEnd = OpenCVWrapper.release(submatEnd);
                    if (baseline.size() > 2) {
                        baseline = StringConverter.simplifyPolygon(baseline, 3);
                    }

                    baselines.add(baseline);
                    break;
                }
            }
        }


        List<TextLine> newTextLines = new ArrayList<TextLine>();
        for (List<Point> baselinePoints : baselines) {
            if (baselinePoints.size() < 2) {
                continue;
            }
            TextLine textLine = new TextLine();
            Coords coords = new Coords();
            List<Point> coordPoints = new ArrayList<>();
            coordPoints.add(new Point(0, 0));
            coordPoints.add(new Point(1, 0));
            coordPoints.add(new Point(1, 1));
            coordPoints.add(new Point(0, 1));
            coords.setPoints(StringConverter.pointToString(coordPoints), false);
            textLine.setCoords(coords);
            Baseline baseline = new Baseline();
            baseline.setPoints(StringConverter.pointToString(baselinePoints));
            textLine.setBaseline(baseline);
            textLine.setId(UUID.randomUUID().toString());
            newTextLines.add(textLine);
        }

        Imgproc.threshold(zeroMat, zeroMatThresholded, 0, 255, Imgproc.THRESH_BINARY);
        thresHoldedBaselines.copyTo(remainingMat, zeroMatThresholded);
        zeroMatThresholded = OpenCVWrapper.release(zeroMatThresholded);

        int numLabelsRemaining = OpenCVWrapper.connectedComponentsWithStats(remainingMat, labeledRemaining, statsRemaining, centroidsRemaining, 8, CvType.CV_32S);
        baselines.clear();
// Search from right to left
        for (int labelNumber = 1; labelNumber < numLabelsEnd; labelNumber++) {
            Point endPoint = getEndPoint(labelNumber);
            Rect rect = null;
            for (int endCounter = (int) endPoint.x; endCounter > (int) endPoint.x - 50 && endCounter > 0; endCounter--) {
                int pixelValueTarget = (int) labeledRemaining.get((int) endPoint.y, endCounter)[0];
                if (pixelValueTarget != 0) {
                    rect = LayoutProc.getRectFromStats(statsRemaining, pixelValueTarget);
                    Mat submat = labeledRemaining.submat(rect);
//                    Mat submatRemaining = labeledRemaining.submat(rect);
                    Point offset = new Point(rect.x, rect.y);
                    List<Point> baseline = new ArrayList<>();

                    Point point = null;
                    int pixelCounter = -1;

                    for (int i = endCounter - rect.x; i > 0; i--) {
                        double sum = 0;
                        int counter = 0;

                        for (int j = 0; j < submat.height(); j++) {
                            int pixelValue = (int) submat.get(j, i)[0];
                            if (pixelValue == pixelValueTarget) {
                                sum += j;
                                counter++;
                                labeledRemaining.put(rect.y + j, rect.x + i, 0);
                                zeroMat.put(rect.y + j, rect.x + i, 0);
                            }
                        }
                        if (counter > 1) {
                            sum /= counter;
                        }

                        if (counter < minimumHeight) {
                            continue;
                        }

                        pixelCounter++;

                        point = new Point(i + offset.x, sum + offset.y);
                        if (pixelCounter % pointDistance == 0) {
                            baseline.add(point);
                        }
                    }
                    if (pixelCounter % pointDistance != 0) {
                        baseline.add(point);
                    }

                    submat = OpenCVWrapper.release(submat);
//                    submatRemaining.release();
                    baseline = Lists.reverse(baseline);
                    if (baseline.size() > 2) {
                        baseline = StringConverter.simplifyPolygon(baseline, 2);
                    }
                    baselines.add(baseline);
                    break;
                }
            }
        }

        for (List<Point> baselinePoints : baselines) {
            if (baselinePoints.size() < 2) {
                continue;
            }
            TextLine textLine = new TextLine();
            Coords coords = new Coords();
            List<Point> coordPoints = new ArrayList<>();
            coordPoints.add(new Point(0, 0));
            coordPoints.add(new Point(1, 0));
            coordPoints.add(new Point(1, 1));
            coordPoints.add(new Point(0, 1));
            coords.setPoints(StringConverter.pointToString(coordPoints));
            textLine.setCoords(coords);
            Baseline baseline = new Baseline();
            baseline.setPoints(StringConverter.pointToString(baselinePoints));
            textLine.setBaseline(baseline);
            textLine.setId(UUID.randomUUID().toString());
            newTextLines.add(textLine);
        }

/// new code

        Imgproc.threshold(zeroMat, zeroMatThresholded, 0, 1, Imgproc.THRESH_BINARY);
        remainingMat = OpenCVWrapper.release(remainingMat);
        this.remainingMat = Mat.zeros(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
        thresHoldedBaselines.copyTo(remainingMat, zeroMatThresholded);
//        Imgcodecs.imwrite("/tmp/thresHoldedBaselines.png", thresHoldedBaselines);
//        Imgcodecs.imwrite("/tmp/zeroMat.png", zeroMat);
//        Imgcodecs.imwrite("/tmp/zeroMatThresholded.png", zeroMatThresholded);
//        Imgcodecs.imwrite("/tmp/remain.png", remainingMat);
        zeroMatThresholded = OpenCVWrapper.release(zeroMatThresholded);

        baselines.clear();
// remaining without valid start or end
        numLabelsRemaining = OpenCVWrapper.connectedComponentsWithStats(remainingMat, labeledRemaining, statsRemaining, centroidsRemaining, 8, CvType.CV_32S);
        for (int labelNumber = 1; labelNumber < numLabelsRemaining; labelNumber++) {
            Rect rect = null;
            rect = LayoutProc.getRectFromStats(statsRemaining, labelNumber);
            Mat submat = labeledRemaining.submat(rect);
            Point offset = new Point(rect.x, rect.y);
            List<Point> baseline = new ArrayList<>();

            Point point = null;
            int pixelCounter = -1;

            for (int i = 0; i < rect.width; i++) {
                double sum = 0;
                int counter = 0;

                for (int j = 0; j < submat.height(); j++) {
                    int pixelValue = (int) submat.get(j, i)[0];
                    if (pixelValue == labelNumber
                            && (int) labeledEnd.get(rect.y + j, rect.x + i)[0] == 0
                            && (int) labeledStart.get(rect.y + j, rect.x + i)[0] == 0) {
                        sum += j;
                        counter++;
                        labeledRemaining.put(rect.y + j, rect.x + i, 0);
//                        zeroMat.put(rect.y + j, rect.x + i, 0);
                    }
                }
                if (counter > 1) {
                    sum /= counter;
                }

                if (counter < minimumHeight) {
                    continue;
                }

                pixelCounter++;

                point = new Point(i + offset.x, sum + offset.y);
                if (pixelCounter % pointDistance == 0) {
                    baseline.add(point);
                }
            }
            if (pixelCounter % pointDistance != 0) {
                baseline.add(point);
            }

            submat = OpenCVWrapper.release(submat);
            if (baseline.size() < 50) {
                continue;
            }
            if (baseline.size() > 2) {
                baseline = StringConverter.simplifyPolygon(baseline, 2);
            }
            baselines.add(baseline);
        }

        for (List<Point> baselinePoints : baselines) {
            if (baselinePoints.size() < 2) {
                continue;
            }
            TextLine textLine = new TextLine();
            Coords coords = new Coords();
            List<Point> coordPoints = new ArrayList<>();
            coordPoints.add(new Point(0, 0));
            coordPoints.add(new Point(1, 0));
            coordPoints.add(new Point(1, 1));
            coordPoints.add(new Point(0, 1));
            coords.setPoints(StringConverter.pointToString(coordPoints));
            textLine.setCoords(coords);
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

                if (firstPoint.x == secondPoint.x && firstPoint.y == secondPoint.y) {
                    linesToRemove.add(secondLine);
                    continue;
                }
            }
        }
        newTextLines.removeAll(linesToRemove);
        labeledRemaining = OpenCVWrapper.release(labeledRemaining);
        statsRemaining = OpenCVWrapper.release(statsRemaining);
        centroidsRemaining = OpenCVWrapper.release(centroidsRemaining);
        MinionExtractBaselines.mergeTextLines(page, newTextLines, asSingleRegion, xmlPath,
                removeEmptyRegions, margin, true);
        LayoutProc.reorderRegions(page, this.regionOrderList);
//        PageUtils.writePageToFile(page, namespace, Paths.get(outputFile));

        LayoutProc.recalculateTextLinesFromBaselines(page);
        PageUtils.writePageToFile(page, namespace, Paths.get(outputFile));
    }

    private Point getStartPoint(int labelNumber, int dilationUsed) {
        int pixelCounter = 0;
        int totalPixelsOn = 0;
        Rect rect = LayoutProc.getRectFromStats(statsStart, labelNumber);
        Mat submat = labeledStart.submat(rect);
        try {
            for (int counter = 0; counter < rect.height; counter++) {
                int pixelValue = (int) submat.get(counter, submat.width() - 1)[0];
                if (pixelValue == labelNumber) {
                    pixelCounter += counter;
                    totalPixelsOn++;
                }
            }
        } finally {
            submat = OpenCVWrapper.release(submat);
        }
        Point startPoint = new Point(rect.x + dilationUsed, rect.y + (totalPixelsOn == 0 ? rect.height / 2 : (pixelCounter / totalPixelsOn)));
        return startPoint;
    }

    private Point getEndPoint(int labelNumber) {
        int pixelCounter = 0;
        int totalPixelsOn = 0;
        Rect rect = LayoutProc.getRectFromStats(statsEnd, labelNumber);
        Mat submat = labeledEnd.submat(rect);
        try {
            for (int counter = 0; counter < rect.height; counter++) {
                int pixelValue = (int) submat.get(counter, 0)[0];
                if (pixelValue == labelNumber) {
                    pixelCounter += counter;
                    totalPixelsOn++;
                }
            }
        } finally {
            submat = OpenCVWrapper.release(submat);
        }
        Point endPoint = new Point(rect.x + (rect.width / 2), rect.y + (totalPixelsOn == 0 ? rect.height / 2 : (pixelCounter / totalPixelsOn)));
        return endPoint;
    }

    private static Options getOptions() {
        Options options = new Options();

        options.addOption(Option.builder("input_path_png").required(true).hasArg(true)
                .desc("Folder that contains the images with the baselines and their start and end.").build()
        );
        options.addOption(Option.builder("input_path_png_start").required(true).hasArg(true)
                .desc("Folder with the images that contain the start points of the baselines").build()
        );
        options.addOption(Option.builder("input_path_png_end").required(true).hasArg(true)
                .desc("Folder with the images that contain the end points of the baselines").build()
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

        options.addOption("region_order", true, "comma separated list of regions");
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
            printHelp(options, "java " + MinionExtractBaselinesStartEndNew.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java " + MinionExtractBaselinesStartEndNew.class.getName());
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
        List<String> regionOrder = new ArrayList<>();
        if (commandLine.hasOption("input_path_png")) {
            inputPathPng = commandLine.getOptionValue("input_path_png");
            LOG.info("input_path_png: {}", inputPathPng);
        }
        if (commandLine.hasOption("input_path_png_start")) {
            inputPathPngStart = commandLine.getOptionValue("input_path_png_start");
            LOG.info("input_path_png_start: {}", inputPathPngStart);
        }
        if (commandLine.hasOption("input_path_png_end")) {
            inputPathPngEnd = commandLine.getOptionValue("input_path_png_end");
            LOG.info("input_path_png_end: {}", inputPathPngEnd);
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

        if (commandLine.hasOption("region_order")) {
            regionOrder.addAll(Arrays.asList(commandLine.getOptionValue("region_order").split(",")));
        }
        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

        LOG.info("as_single_region: {}", asSingleRegion);
        DirectoryStream<Path> fileStream = Files.newDirectoryStream(Paths.get(inputPathPng));
        List<Path> files = new ArrayList<>();
        fileStream.forEach(files::add);
        files.sort(Comparator.comparing(Path::toString));


        ExecutorService executor = Executors.newFixedThreadPool(numthreads);
        for (Path file : files) {
            if (file.getFileName().toString().endsWith(".png")) {
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

                        Runnable worker = new MinionExtractBaselinesStartEndNew(
                                xmlFile,
                                outputFile, asSingleRegion,
                                removeEmptyRegions,
                                imageFile, imageFileStart, imageFileEnd,
                                margin, dilationUsed, minimumHeight, regionOrder, namespace
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


            numLabels = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselines, labeled, stats, centroids);
            numLabelsStart = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselinesStart, labeledStart, statsStart, centroidsStart);
            numLabelsEnd = OpenCVWrapper.connectedComponentsWithStats(thresHoldedBaselinesEnd, labeledEnd, statsEnd, centroidsEnd);
            this.zeroMat = Mat.ones(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.remainingMat = Mat.zeros(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.zeroMatThresholded = Mat.ones(this.thresHoldedBaselines.size(), thresHoldedBaselines.type());
            this.labeledRemaining = new Mat();
            this.statsRemaining = new Mat();
            this.centroidsRemaining = new Mat();

            extractAndMergeBaseLinesNew(xmlFile, outputFile, margin, this.namespace);
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
        baseLineMat = OpenCVWrapper.release(baseLineMat);
        baseLineMatStart = OpenCVWrapper.release(baseLineMatStart);
        baseLineMatEnd = OpenCVWrapper.release(baseLineMatEnd);
        thresHoldedBaselines = OpenCVWrapper.release(thresHoldedBaselines);
        thresHoldedBaselinesStart = OpenCVWrapper.release(thresHoldedBaselinesStart);
        thresHoldedBaselinesEnd = OpenCVWrapper.release(thresHoldedBaselinesEnd);
        stats= OpenCVWrapper.release(stats);
        statsStart = OpenCVWrapper.release(statsStart);
        statsEnd = OpenCVWrapper.release(statsEnd);
        centroids = OpenCVWrapper.release(centroids);
        centroidsStart  = OpenCVWrapper.release(centroidsStart);
        centroidsEnd= OpenCVWrapper.release(centroidsEnd);
        labeled = OpenCVWrapper.release(labeled);
        labeledStart = OpenCVWrapper.release(labeledStart);
        labeledEnd = OpenCVWrapper.release(labeledEnd);
        zeroMat = OpenCVWrapper.release(zeroMat);
        remainingMat = OpenCVWrapper.release(remainingMat);
        zeroMatThresholded = OpenCVWrapper.release(zeroMatThresholded);
        labeledRemaining = OpenCVWrapper.release(labeledRemaining);
        statsRemaining = OpenCVWrapper.release(statsRemaining);
        centroidsRemaining = OpenCVWrapper.release(centroidsRemaining);
    }
}