package nl.knaw.huc.di.images.minions;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.layoutanalyzer.Tuple;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.LaypaConfig;
import nl.knaw.huc.di.images.layoutds.models.P2PaLAConfig;
import nl.knaw.huc.di.images.layoutds.models.Page.*;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
import org.apache.commons.cli.*;
import org.apache.commons.io.FilenameUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import javax.xml.transform.TransformerException;
import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/*
This takes pageXML and an png containing baselines
 and extracts info about the baselines
 and add baseline/textline information to the regions in the pagexml
 */
public class MinionExtractBaselines implements Runnable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MinionExtractBaselines.class);

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    private final String outputFile;
    private final P2PaLAConfig p2palaconfig;
    private final LaypaConfig laypaConfig;
    private final String identifier;
    private final Supplier<PcGts> pageSupplier;
    private final Supplier<Mat> imageSupplier;
    private final Supplier<Mat> baselineImageSupplier;
    private final String namespace;
    private final boolean recalculateTextLineContoursFromBaselines;
    private final Optional<ErrorFileWriter> errorFileWriter;
    private boolean asSingleRegion;
    private int margin;
    private boolean invertImage;
    private final Consumer<String> errorLog;
    private int threshold;
    private List<String> reorderRegionsList;

    private final boolean splitBaselines;
    private final double triggerLineThicknessMultiplier;
    private final double maxLineThicknessMultiplier;
    private final int minimumBaselineThickness;


    public MinionExtractBaselines(String identifier, Supplier<PcGts> pageSupplier, Supplier<Mat> imageSupplier, String outputFile,
                                  boolean asSingleRegion, P2PaLAConfig p2palaconfig, LaypaConfig laypaConfig,
                                  Supplier<Mat> baselineImageSupplier, int margin, boolean invertImage, int threshold,
                                  List<String> reorderRegionsList, String namespace,
                                  boolean recalculateTextLineContoursFromBaselines,
                                  Optional<ErrorFileWriter> errorFileWriter, boolean splitBaselines,
                                  double triggerLineThicknessMultiplier, double maxLineThicknessMultiplier, int minimumBaselineThickness) {
        this(identifier, pageSupplier, imageSupplier, outputFile, asSingleRegion, p2palaconfig, laypaConfig, baselineImageSupplier,
                margin, invertImage, error -> {
                }, threshold, reorderRegionsList, namespace, recalculateTextLineContoursFromBaselines, errorFileWriter,
                splitBaselines, triggerLineThicknessMultiplier, maxLineThicknessMultiplier, minimumBaselineThickness);
    }

    public MinionExtractBaselines(String identifier, Supplier<PcGts> pageSupplier, Supplier<Mat> imageSupplier,
                                  String outputFile, boolean asSingleRegion, P2PaLAConfig p2palaconfig,
                                  LaypaConfig laypaConfig, Supplier<Mat> baselineImageSupplier, int margin,
                                  boolean invertImage, Consumer<String> errorLog,
                                  int threshold, List<String> reorderRegionsList, String namespace,
                                  boolean recalculateTextLineContoursFromBaselines,
                                  Optional<ErrorFileWriter> errorFileWriter, boolean splitBaselines,
                                  double triggerLineThicknessMultiplier, double maxLineThicknessMultiplier,
                                  int minimumBaselineThickness) {
        this.identifier = identifier;
        this.pageSupplier = pageSupplier;
        this.imageSupplier = imageSupplier;
        this.baselineImageSupplier = baselineImageSupplier;
        this.outputFile = outputFile;
        this.asSingleRegion = asSingleRegion;
        this.margin = margin;
        this.invertImage = invertImage;
        this.p2palaconfig = p2palaconfig;
        this.laypaConfig = laypaConfig;
        this.errorLog = errorLog;
        this.threshold = threshold;
        this.reorderRegionsList = reorderRegionsList;
        this.namespace = namespace;
        this.recalculateTextLineContoursFromBaselines = recalculateTextLineContoursFromBaselines;
        this.errorFileWriter = errorFileWriter;
        this.splitBaselines = splitBaselines;
        this.triggerLineThicknessMultiplier = triggerLineThicknessMultiplier;
        this.maxLineThicknessMultiplier = maxLineThicknessMultiplier;
        this.minimumBaselineThickness = minimumBaselineThickness;
    }

    private static List<Point> extractBaseline(Mat baselineMat, int label, Point offset, int minimumHeight,
                                               String identifier) {
        List<Point> baseline = new ArrayList<>();
        Point point = null;
        int pixelCounter = -1;
        boolean mergedLineDetected = false;
        for (int j = 0; j < baselineMat.width(); j++) {
            boolean mergedLineDetectedStep1 = false;
            double sum = 0;
            int counter = 0;
            for (int i = 0; i < baselineMat.height(); i++) {
                int pixelValue = LayoutProc.getSafeInt(baselineMat, i, j);
                if (pixelValue < 0) {
                    LOG.error("pixelValue < 0: " + pixelValue + " " + identifier);
                    throw new RuntimeException("pixelValue < 0: " + pixelValue + " " + identifier);
                }
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
        if (pixelCounter % 50 != 0 && point != null) {
            baseline.add(point);
        }
        if (mergedLineDetected) {
            LOG.info("mergedLineDetected: " + identifier);
        }
        return baseline;
    }

    public static List<TextLine> extractBaselines(boolean cleanup, int minimumHeight, int minimumWidth, int numLabels,
                                                  Mat stats, Mat labeled, String identifier, boolean splitBaselines,
                                                  double triggerLineThicknessMultiplier, double maxLineThicknessMultiplier) {
        List<TextLine> allTextLines = extractBaselines(numLabels, stats, labeled, identifier, minimumHeight,
                splitBaselines, triggerLineThicknessMultiplier, maxLineThicknessMultiplier);

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
            if (minX < 0) {
                LOG.info("minX < 0: " + minX + " " + identifier);
                minX = 0;
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


    public static PcGts mergeTextLines(PcGts page, List<TextLine> newTextLines,
                                       boolean asSingleRegion, String identifier, boolean removeEmptyRegions,
                                       int margin, boolean clearExistingLines) {
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


        LOG.info("textlines to match: " + newTextLines.size() + " " + identifier);
        if (!asSingleRegion) {
            if (clearExistingLines) {
                for (TextRegion textRegion : page.getPage().getTextRegions()) {
                    textRegion.setTextLines(new ArrayList<>());
                }
            }

            for (float percentage = 0.51f; percentage >= 0.01f; percentage -= 0.05) {
                if (newTextLines.isEmpty()) {
                    break;
                }
                for (TextRegion textRegion : page.getPage().getTextRegions()) {
                    newTextLines = PageUtils.attachTextLines(textRegion, newTextLines, percentage, 0);
                }
            }
        } else {
            page.getPage().setTextRegions(new ArrayList<>());
            if (newTextLines.size() > 0) {
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
                newRegion.getTextLines().addAll(newTextLines);
                newTextLines.clear();
                page.getPage().getTextRegions().add(newRegion);
            }
        }
        if (newTextLines.size() > 0) {
            LOG.info("textlines remaining: " + newTextLines.size() + " " + identifier);
            for (TextLine textLine : newTextLines) {
                final TextRegion newRegion = new TextRegion();
                newRegion.setId(UUID.randomUUID().toString());
                newRegion.setCoords(textLine.getCoords());
                newRegion.setTextLines(new ArrayList<>());
                newRegion.getTextLines().add(textLine);
                page.getPage().getTextRegions().add(newRegion);
            }
        }

        List<TextRegion> goodRegions = new ArrayList<>();
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
//            if (textRegion.getTextLines().size() > 0 || textRegion.getCustom().contains(":Photo") || textRegion.getCustom().contains(":Drawing")) {
            if (!removeEmptyRegions || textRegion.getTextLines().size() > 0 || textRegion.getCustom().contains(":Photo") || textRegion.getCustom().contains(":Drawing") || textRegion.getCustom().contains(":separator")) {
                goodRegions.add(textRegion);
            }
        }
        page.getPage().setTextRegions(goodRegions);

        return page;
    }

    private static List<TextLine> extractBaselines(int numLabels, Mat stats, Mat labeled, String identifier,
                                                   int minimumHeight, boolean splitBaselines,
                                                   double triggerLineThicknessMultiplier,
                                                   double maxLineThicknessMultiplier) {
        List<TextLine> textLines = new ArrayList<>();
        for (int labelNumber = 1; labelNumber < numLabels; labelNumber++) {
            Rect rect = LayoutProc.getRectFromStats(stats, labelNumber);
            Mat connectedBaseLineRoi = labeled.submat(rect);
            Mat connectedBaseLineSubmat = connectedBaseLineRoi.clone();
            connectedBaseLineRoi = OpenCVWrapper.release(connectedBaseLineRoi);
            Point offsetPointConnectedBaseLine = new Point(rect.x, rect.y);
            if (splitBaselines) {
                extractSplittedBaselines(identifier, minimumHeight, textLines, labelNumber, connectedBaseLineSubmat,
                        offsetPointConnectedBaseLine, triggerLineThicknessMultiplier, maxLineThicknessMultiplier);
            } else {
                TextLine textLine = extractTextLine(connectedBaseLineSubmat, labelNumber, offsetPointConnectedBaseLine, minimumHeight, identifier);
                if (textLine != null) {
                    textLines.add(textLine);
                }
            }
            connectedBaseLineSubmat = OpenCVWrapper.release(connectedBaseLineSubmat);
        }
        return textLines;
    }

    private static void extractSplittedBaselines(String identifier, int minimumHeight, List<TextLine> textLines,
                                                 int labelNumber, Mat connectedBaseLineSubmat,
                                                 Point offsetPointConnectedBaseLine,
                                                 double triggerLineThicknessMultiplier,
                                                 double maxLineThicknessMultiplier) {
        List<Tuple<Mat, Point>> tuples = LayoutProc.splitBaselines(connectedBaseLineSubmat, labelNumber,
                offsetPointConnectedBaseLine, triggerLineThicknessMultiplier, maxLineThicknessMultiplier);

        for (Tuple<Mat, Point> tuple : tuples) {
            if (tuple == null) {
                continue;
            }
            Mat submat = tuple.getX();
            Point offsetPoint = tuple.getY();
            TextLine textLine = extractTextLine(submat, labelNumber, offsetPoint, minimumHeight, identifier);
            OpenCVWrapper.release(submat);
            if (textLine != null) {
                textLines.add(textLine);
            }
        }
    }

    private static TextLine extractTextLine(Mat connectedBaseLineSubmat, int labelNumber, Point offsetPoint, int minimumHeight, String identifier) {
        List<Point> baselinePoints = extractBaseline(connectedBaseLineSubmat, labelNumber, offsetPoint, minimumHeight,
                identifier);
        if (baselinePoints.size() < 2) {
            return null;
        }
        TextLine textLine = new TextLine(); // placeholder
        Coords coords = new Coords();
        List<Point> coordPoints = new ArrayList<>();
        coordPoints.add(offsetPoint);
        coordPoints.add(new Point(offsetPoint.x + connectedBaseLineSubmat.width() - 1, offsetPoint.y));
        coordPoints.add(new Point(offsetPoint.x + connectedBaseLineSubmat.width() - 1, offsetPoint.y + connectedBaseLineSubmat.height() - 1));
        coordPoints.add(new Point(offsetPoint.x, offsetPoint.y + connectedBaseLineSubmat.height() - 1));
        coords.setPoints(StringConverter.pointToString(coordPoints));
        textLine.setCoords(coords);
        Baseline baseline = new Baseline();
        baseline.setPoints(StringConverter.pointToString(StringConverter.simplifyPolygon(baselinePoints, 1)));
        textLine.setBaseline(baseline);
        textLine.setId(UUID.randomUUID().toString());
        return textLine;
    }

    public static Options getOptions() {
        final Options options = new Options();

        options.addOption(Option.builder("input_path_image").hasArg(true)
                .desc("original image input path").build()
        );

        options.addOption(Option.builder("input_path_png").required(true).hasArg(true)
                .desc("P2PaLA baseline detection output").build()
        );

        options.addOption(Option.builder("input_path_page").required(true).hasArg(true)
                .desc("Folder of the page files, that need to be updated").build()
        );

        options.addOption(Option.builder("output_path_page").required(true).hasArg(true)
                .desc("Folder to write the updated page to").build()
        );

        options.addOption(Option.builder("as_single_region").required(false).hasArg(false)
                .desc("Are all baselines in the same region? default is false)").build()
        );
        options.addOption(Option.builder("p2palaconfig").required(false).hasArg(true)
                .desc("Path to P2PaLAConfig used").build()
        );
        options.addOption(Option.builder("laypaconfig").required(false).hasArg(true)
                .desc("Path to laypaconfig used").build()
        );
        options.addOption(Option.builder("margin").required(false).hasArg(true)
                .desc("the margin in pxels used when determining if a text line is part of a text region, default 50")
                .build()
        );
        options.addOption("threads", true, "number of threads to use, default 4");

        options.addOption("help", false, "prints this help dialog");
        options.addOption("invert_image", false, "inverts pixelmap image");
        options.addOption("threshold", true, "threshold to use for binarization of baselinemaps, default 32");
        options.addOption("region_order_list", true, "region_order_list");
        final Option whiteListOption = Option.builder("config_white_list").hasArgs()
                .desc("a list with properties that should be added to the PageXML")
                .build();
        options.addOption(whiteListOption);
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");
        options.addOption("recalculate_textline_contours_from_baselines", "recalculate textline contours from baselines (default false)");
        options.addOption("minimum_interlinedistance", true, "minimum interline distance (default 35)");
        options.addOption("split_baselines", "experimental: split horizontal baselines that are connected vertically(default false)");
        options.addOption("trigger_line_thickness_multiplier", true, "trigger line thickness multiplier (default 1.2)");
        options.addOption("max_line_thickness_multiplier", true, "max line thickness multiplier (default 1.5)");
        options.addOption("minimum_baseline_thickness", true, "minimum baseline thickness (default 1)");
        return options;
    }

    public static void printHelp(Options options, String callName) {
        final HelpFormatter helpFormatter = new HelpFormatter();

        helpFormatter.printHelp(callName, options, true);
    }

    public static void main(String[] args) throws Exception {
        Core.setNumThreads(1);
        int numthreads = 4;
        int maxCount = -1;
        int margin = 50;
        List<String> regionOrderList = new ArrayList<>();
        String inputPathImage = null;
        String inputPathPng = "/scratch/output/";
        String inputPathPageXml = "/data/prizepapersall/page/";
        String outputPathPageXml = "/data/prizepapersall/page/";
        boolean asSingleRegion = false;
        String p2palaConfig = null;
        String laypaConfig = null;
        int threshold = 32;

        final Options options = getOptions();
        CommandLineParser commandLineParser = new DefaultParser();
        final CommandLine commandLine;
        try {
            commandLine = commandLineParser.parse(options, args);
        } catch (ParseException ex) {
            printHelp(options, "java " + MinionExtractBaselines.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java " + MinionExtractBaselines.class.getName());
            return;
        }

        if (commandLine.hasOption("input_path_image")) {
            inputPathImage = commandLine.getOptionValue("input_path_image");
        }

        inputPathPng = commandLine.getOptionValue("input_path_png");
        inputPathPageXml = commandLine.getOptionValue("input_path_page");
        outputPathPageXml = commandLine.getOptionValue("output_path_page");
        if (commandLine.hasOption("threads")) {
            numthreads = Integer.parseInt(commandLine.getOptionValue("threads"));
        }
        if (commandLine.hasOption("threshold")) {
            threshold = Integer.parseInt(commandLine.getOptionValue("threshold"));
        }
        if (commandLine.hasOption("p2palaconfig")) {
            p2palaConfig = commandLine.getOptionValue("p2palaconfig");
        }
        if (commandLine.hasOption("laypaconfig")) {
            laypaConfig = commandLine.getOptionValue("laypaconfig");
        }

        if (commandLine.hasOption("as_single_region")) {
            asSingleRegion = true;
        }
        if (commandLine.hasOption("margin")) {
            margin = Integer.parseInt(commandLine.getOptionValue("margin"));
        }
        boolean invertImage = commandLine.hasOption("invert_image");

        if (commandLine.hasOption("margin")) {
            margin = Integer.parseInt(commandLine.getOptionValue("margin"));
        }

        if (commandLine.hasOption("region_order_list")) {
            regionOrderList.addAll(Arrays.asList(commandLine.getOptionValue("region_order_list").trim().split(",")));
            regionOrderList.add(null);// default add all regions without type
        }

        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013 : PageUtils.NAMESPACE2019;
        boolean recalculateTextLineContoursFromBaselines = commandLine.hasOption("recalculate_textline_contours_from_baselines");

        if (recalculateTextLineContoursFromBaselines && Strings.isNullOrEmpty(inputPathImage)) {
            throw new IllegalArgumentException("input_path_image is required when recalculate_textline_contours_from_baselines is set");
        }

        int minimumInterlineDistance = MinionCutFromImageBasedOnPageXMLNew.DEFAULT_MINIMUM_INTERLINE_DISTANCE;
        if (commandLine.hasOption("minimum_interlinedistance")) {
            minimumInterlineDistance = Integer.parseInt(commandLine.getOptionValue("minimum_interlinedistance"));
        }

        boolean splitBaselines = commandLine.hasOption("split_baselines");


        double triggerLineThicknessMultiplier = 1.2;
        double maxLineThicknessMultiplier = 1.5;
        if (commandLine.hasOption("trigger_line_thickness_multiplier")) {
            triggerLineThicknessMultiplier = Double.parseDouble(commandLine.getOptionValue("trigger_line_thickness_multiplier"));
        }
        if (commandLine.hasOption("max_line_thickness_multiplier")) {
            maxLineThicknessMultiplier = Double.parseDouble(commandLine.getOptionValue("max_line_thickness_multiplier"));
        }
        int minimumBaselineThickness = 1;
        if (commandLine.hasOption("minimum_baseline_thickness")) {
            minimumBaselineThickness = Integer.parseInt(commandLine.getOptionValue("minimum_baseline_thickness"));
        }

        DirectoryStream<Path> fileStream = Files.newDirectoryStream(Paths.get(inputPathPng));
        List<Path> files = new ArrayList<>();
        fileStream.forEach(files::add);
        files.sort(Comparator.comparing(Path::toString));

        final List<String> whiteList;
        if (commandLine.hasOption("config_white_list")) {
            whiteList = Arrays.asList(commandLine.getOptionValues("config_white_list"));
        } else {
            whiteList = Lists.newArrayList();
        }
        final P2PaLAConfig p2PaLAConfigContents = p2palaConfig != null ? readP2PaLAConfigFile(p2palaConfig, whiteList) : null;
        final LaypaConfig laypaConfigContents = laypaConfig != null ? readLaypaConfigFile(laypaConfig, whiteList) : null;


        ExecutorService executor = Executors.newFixedThreadPool(numthreads);
        for (Path file : files) {
            if (file.getFileName().toString().endsWith(".png")) {
                if (maxCount != 0) {
                    maxCount--;
                    final String baseFilename = FilenameUtils.removeExtension(file.getFileName().toString());
                    final String xmlFile = Path.of(inputPathPageXml, baseFilename + ".xml").toFile().getAbsolutePath();
                    final String baselineImageFile = Path.of(inputPathPng, baseFilename + ".png").toFile().getAbsolutePath();
                    final String outputFile = Path.of(outputPathPageXml, baseFilename + ".xml").toFile().getAbsolutePath();
                    final Supplier<PcGts> pageSupplier = () -> {
                        try {
                            return PageUtils.readPageFromFile(Path.of(xmlFile));
                        } catch (Exception e) {
                            LOG.error("Cannot read page: " + e);
                            return null;
                        }
                    };

                    // Default try just .jpg
                    String imageFile = Path.of(inputPathImage, baseFilename + ".jpg").toFile().getAbsolutePath();
                    PcGts pcGts = pageSupplier.get();
                    if (pcGts == null) {
                        LOG.error("Could not read page XML for file: " + xmlFile);
                        continue; // or handle the error appropriately
                    }
//                    read the actual image filename from the page xml
                    final String imageFilenameFromPage = pcGts.getPage().getImageFilename();
                    final String inputPathImageFile = Path.of(inputPathImage, imageFilenameFromPage).toFile().getAbsolutePath();
//                    and check if that file exists in the expected location
                    if (Files.exists(Paths.get(inputPathImageFile))) {
                        imageFile = Path.of(inputPathImageFile).toFile().getAbsolutePath();
                    }
                    String finalImageFile = imageFile;

                    // Ownership contract: caller must release Mats returned by these suppliers.
                    final Supplier<Mat> baselineImageSupplier = () -> OpenCVWrapper.imread(baselineImageFile, Imgcodecs.IMREAD_GRAYSCALE);
                    // Ownership contract: caller must release Mats returned by these suppliers.
                    final Supplier<Mat> imageSupplier = () -> OpenCVWrapper.imread(finalImageFile, Imgcodecs.IMREAD_UNCHANGED);
                    final Runnable worker = new MinionExtractBaselines(baselineImageFile, pageSupplier, imageSupplier,
                            outputFile, asSingleRegion, p2PaLAConfigContents, laypaConfigContents,
                            baselineImageSupplier, margin, invertImage, threshold, regionOrderList, namespace,
                            recalculateTextLineContoursFromBaselines, Optional.empty(), splitBaselines,
                            triggerLineThicknessMultiplier, maxLineThicknessMultiplier, minimumBaselineThickness);

                    executor.execute(worker);//calling execute method of ExecutorService
                }
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                LOG.warn("Timed out waiting for MinionExtractBaselines workers to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while waiting for MinionExtractBaselines workers", e);
        }


        LOG.info("Finished all threads");
    }

    private void extractAndMergeBaseLines(Supplier<PcGts> pageSupplier, Supplier<Mat> imageSupplier,
                                          Supplier<Mat> baselineImageSupplier, String outputFile,
                                          int margin, P2PaLAConfig p2PaLAConfig, LaypaConfig laypaConfig, int threshold,
                                          String namespace, boolean recalculateTextLineContoursFromBaselines,
                                          boolean splitBaselines, int thickness, double triggerLineThicknessMultiplier,
                                          double maxLineThicknessMultiplier, int minimumBaselineThickness)
            throws IOException, org.json.simple.parser.ParseException, TransformerException {

        boolean cleanup = true;
        int minimumWidth = 15;
        int minimumHeight = 3;
        Mat baselineMat = baselineImageSupplier.get();
        if (baselineMat == null || baselineMat.empty()) {
            LOG.error("Baseline image empty/null");
            return;
        }
        if (baselineMat.type() != CvType.CV_8U) {
            LOG.error("Baseline image is not of type CV_8U");
            return;
        }

        OpenCVWrapper.threshold(baselineMat, baselineMat, threshold, invertImage);

        Mat imageMat = imageSupplier.get();
        if (imageMat.channels() == 1) {
//            convert to 3 channels
            Imgproc.cvtColor(imageMat, imageMat, Imgproc.COLOR_GRAY2BGR);
        }

        if (imageMat == null || imageMat.empty()) {
            LOG.error("Image empty/null");
            return;
        }
        Mat stats = OpenCVWrapper.newMat();
        Mat centroids = OpenCVWrapper.newMat();
        Mat labeled = OpenCVWrapper.newMat(baselineMat.size(), CvType.CV_32S);
        int numLabels = OpenCVWrapper.connectedComponentsWithStats(baselineMat, labeled, stats, centroids, 8, CvType.CV_32S);
        baselineMat = OpenCVWrapper.release(baselineMat);
        centroids = OpenCVWrapper.release(centroids);
        LOG.info("FOUND LABELS:" + numLabels);
        PcGts page = pageSupplier.get();
        if (page == null) {
            page = PageUtils.createFromImage(imageMat.height(), imageMat.width(), identifier);
        }
        List<TextLine> textLines = extractBaselines(cleanup, minimumHeight, minimumWidth, numLabels, stats, labeled,
                this.identifier, splitBaselines, triggerLineThicknessMultiplier, maxLineThicknessMultiplier);
        labeled = OpenCVWrapper.release(labeled);
        stats = OpenCVWrapper.release(stats);

        LOG.info("Extracted " + textLines.size() + " textlines");
        mergeTextLines(page, textLines, this.asSingleRegion, this.identifier,
                false, margin, true);


        if (!this.reorderRegionsList.isEmpty()) {
            LayoutProc.reorderRegions(page, this.reorderRegionsList);
        }

        addP2PaLAInfo(page, p2PaLAConfig);
        addLaypaInfo(page, laypaConfig);

        if (recalculateTextLineContoursFromBaselines) {
            LayoutProc.recalculateTextLineContoursFromBaselines(identifier, imageMat, page,
                    MinionCutFromImageBasedOnPageXMLNew.SHRINK_FACTOR,
                    MinionCutFromImageBasedOnPageXMLNew.DEFAULT_MINIMUM_INTERLINE_DISTANCE,
                    thickness, minimumBaselineThickness, true);
        }
        imageMat = OpenCVWrapper.release(imageMat);

        try {
            final Path outputFilePath = Paths.get(outputFile);
            final Path parent = outputFilePath.getParent();
            if (!Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            PageUtils.writePageToFileAtomic(page, namespace, outputFilePath);
        } catch (IOException ex) {
            errorLog.accept("Could not write '" + outputFile + "'");
            throw ex;
        } catch (TransformerException ex) {
            errorLog.accept("Could not transform page to 2013 version: " + ex.getMessage());
            throw ex;
        }
    }

    public static P2PaLAConfig readP2PaLAConfigFile(String configFile, List<String> whiteList) throws IOException, org.json.simple.parser.ParseException {
        if (Strings.isNullOrEmpty(configFile) || !Files.exists(Paths.get(configFile))) {
            return null;
        }
        JSONObject jsonObject = (JSONObject) new JSONParser().parse(new FileReader(configFile));

        return processP2PaLAConfig(jsonObject, whiteList);
    }

    public static P2PaLAConfig readP2PaLAConfigFile(InputStream configFile, List<String> whiteList) throws IOException, org.json.simple.parser.ParseException {
        if (configFile == null) {
            return null;
        }
        JSONObject jsonObject = (JSONObject) new JSONParser().parse(new InputStreamReader(configFile));

        return processP2PaLAConfig(jsonObject, whiteList);
    }

    private static P2PaLAConfig processP2PaLAConfig(JSONObject jsonObject, List<String> whiteList) {
        final P2PaLAConfig p2PaLAConfig = new P2PaLAConfig();
        Map<String, Object> values = new HashMap<>();

        p2PaLAConfig.setModel(jsonObject.get("gen_model").toString());
        p2PaLAConfig.setGitHash(jsonObject.get("git_hash").toString());
        if (jsonObject.containsKey("uuid")) {
            p2PaLAConfig.setUuid(UUID.fromString(jsonObject.get("uuid").toString()));
        }

        JSONObject args = (JSONObject) jsonObject.get("args");
        for (Object key : args.keySet()) {
            LOG.debug(String.valueOf(key));
            LOG.debug(String.valueOf(args.get(key)));
            if (args.get(key) != null && whiteList.contains(key)) {
                values.put((String) key, String.valueOf(args.get(key)));
            }
        }
        p2PaLAConfig.setValues(values);

        return p2PaLAConfig;
    }

    public static LaypaConfig readLaypaConfigFile(String configFile, List<String> whiteList) throws IOException, org.json.simple.parser.ParseException {
        if (Strings.isNullOrEmpty(configFile) || !Files.exists(Paths.get(configFile))) {
            return null;
        }
        InputStream inputStream = new FileInputStream(configFile);
        Yaml yaml = new Yaml();
        HashMap yamlMap = yaml.load(inputStream);

        return processLaypaConfig(yamlMap, whiteList);
    }

    private static LaypaConfig processLaypaConfig(HashMap yamlMap, List<String> whiteList) {
        LaypaConfig laypaConfig = new LaypaConfig();

        Map<String, Object> values = new HashMap<>();


        for (Object key : yamlMap.keySet()) {
            LOG.info("{}", key);
            if (key.equals("MODEL")) {
                laypaConfig.setModel(String.valueOf(yamlMap.get(key)));
            }
            if (key != null && whiteList.contains(key)) {
                values.put((String) key, String.valueOf(yamlMap.get(key)));
            }
            if (key.equals("LAYPA_UUID")) {
                laypaConfig.setUuid(UUID.fromString(String.valueOf(yamlMap.get(key))));
            }
            if (key.equals("LAYPA_GIT_HASH")) {
                laypaConfig.setGitHash(String.valueOf(yamlMap.get(key)));
            }
        }
        laypaConfig.setValues(values);

        return laypaConfig;
    }

    public static LaypaConfig readLaypaConfigFile(InputStream configFile, List<String> whiteList) throws IOException, org.json.simple.parser.ParseException {
        if (configFile == null) {
            return null;
        }

        Yaml yaml = new Yaml();
        HashMap yamlMap = yaml.load(configFile);


        return processLaypaConfig(yamlMap, whiteList);
    }

    private void addP2PaLAInfo(PcGts page, P2PaLAConfig p2PaLAConfig) {
        if (p2PaLAConfig == null) {
            return;
        }
        LOG.info("adding p2palaconfig info.");
        ArrayList<MetadataItem> metadataItems = new ArrayList<>();
        MetadataItem metadataItem = new MetadataItem();
        metadataItem.setType("processingStep");
        metadataItem.setName("layout-analysis");
        metadataItem.setValue("p2pala");
        Labels labels = new Labels();
        ArrayList<Label> labelsList = new ArrayList<>();
        final Label githashLabel = new Label();
        githashLabel.setType("githash");
        githashLabel.setValue(p2PaLAConfig.getGitHash());
        labelsList.add(githashLabel);
        final Label modelLabel = new Label();
        modelLabel.setType("model");
        modelLabel.setValue(p2PaLAConfig.getModel());
        labelsList.add(modelLabel);
        final Label uuidLabel = new Label();
        if (p2PaLAConfig.getUuid() != null) {
            uuidLabel.setType("uuid");
            uuidLabel.setValue(p2PaLAConfig.getUuid().toString());
            labelsList.add(uuidLabel);
        }
        for (String key : p2PaLAConfig.getValues().keySet()) {
            Label label = new Label();
            label.setType(key);
            Object value = p2PaLAConfig.getValues().get(key);
            label.setValue(String.valueOf(value));
            labelsList.add(label);
        }
        labels.setLabel(labelsList);
        metadataItem.setLabels(labels);
        metadataItems.add(metadataItem);
        page.getMetadata().setMetadataItems(metadataItems);
    }

    private void addLaypaInfo(PcGts page, LaypaConfig laypaConfig) {
        if (laypaConfig == null) {
            return;
        }
        LOG.info("adding laypaconfig info.");
        ArrayList<MetadataItem> metadataItems = new ArrayList<>();
        MetadataItem metadataItem = new MetadataItem();
        metadataItem.setType("processingStep");
        metadataItem.setName("layout-analysis");
        metadataItem.setValue("laypa");
        Labels labels = new Labels();
        ArrayList<Label> labelsList = new ArrayList<>();
        final Label modelLabel = new Label();
        modelLabel.setType("model");
        modelLabel.setValue(laypaConfig.getModel());
        labelsList.add(modelLabel);
        final Label githHashLabel = new Label();
        githHashLabel.setValue(laypaConfig.getGitHash());
        githHashLabel.setType("githash");
        labelsList.add(githHashLabel);
        if (laypaConfig.getUuid() != null) {
            final Label uuidLabel = new Label();
            uuidLabel.setType("uuid");
            uuidLabel.setValue(laypaConfig.getUuid().toString());
            labelsList.add(uuidLabel);
        }
        for (String key : laypaConfig.getValues().keySet()) {
            Label label = new Label();
            label.setType(key);
            Object value = laypaConfig.getValues().get(key);
            label.setValue(String.valueOf(value));
            labelsList.add(label);
        }
        labels.setLabel(labelsList);
        metadataItem.setLabels(labels);
        metadataItems.add(metadataItem);
        page.getMetadata().setMetadataItems(metadataItems);
    }

    @Override
    public void run() {
        try {
            LOG.info(this.identifier);
            int thickness = 10;
            extractAndMergeBaseLines(this.pageSupplier, imageSupplier, baselineImageSupplier, outputFile, margin, this.p2palaconfig, this.laypaConfig,
                    this.threshold, this.namespace, this.recalculateTextLineContoursFromBaselines, this.splitBaselines,
                    thickness, this.triggerLineThicknessMultiplier, this.maxLineThicknessMultiplier, this.minimumBaselineThickness);
        } catch (IOException e) {
            errorFileWriter.ifPresent(errorWriter -> errorWriter.write(identifier, e, "Could not process page"));
            LOG.error("Unexpected error", e);
        } catch (TransformerException e) {
            errorFileWriter.ifPresent(errorWriter -> errorWriter.write(identifier, e, "Could not transform page"));
            LOG.error("Unexpected error", e);
        } catch (org.json.simple.parser.ParseException e) {
            errorFileWriter.ifPresent(errorWriter -> errorWriter.write(identifier, e, "Could not process config"));
            throw new RuntimeException(e);
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
        System.gc();
    }

}