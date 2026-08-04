package nl.knaw.huc.di.images.minions;

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.BinaryLineStrip;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import nl.knaw.huc.di.images.layoutds.models.Page.TextStyle;
import nl.knaw.huc.di.images.pagexmlutils.GroundTruthTextLineFormatter;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;


/*
    this Minion just cuts
 */
public class MinionCutFromImageBasedOnPageXMLNew extends BaseMinion implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(MinionCutFromImageBasedOnPageXMLNew.class);
    public static final int DEFAULT_MINIMUM_INTERLINE_DISTANCE = 35;
    public static final int DEFAULT_PNG_COMPRESSION_LEVEL = 1;
    final static double SHRINK_FACTOR = 4;

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    private final String outputBase;
    private final String imageFileName;
    private final boolean overwriteExistingPage;
    private final int minWidth;
    private final int minHeight;
    private final int minWidthToHeight;
    private final String outputType;
    private final int channels;
    private final boolean writeTextContents;
    private final Integer rescaleHeight;
    private final boolean outputConfidenceFile;
    private final boolean outputBoxFile;
    private final boolean outputTxtFile;
    private final boolean recalculateTextLineContoursFromBaselines;
    private final int minimumXHeight;
    private final boolean useDiforNames;
    private final boolean writeDoneFiles;
    private final boolean ignoreDoneFiles;
    private final Consumer<String> errorLog;
    private final Consumer<PcGts> pageSaver;
    private final Runnable doneFileWriter;
    private final Integer fixedXHeight;
    private final String identifier;
    private final Supplier<Mat> imageSupplier;
    private final Supplier<PcGts> pageSupplier;
    private final boolean includeTextStyles;
    private final boolean useTags;
    private final boolean skipUnclear;
    private final Double minimumConfidence;
    private final Double maximumConfidence;

    private final int minimumInterlineDistance;
    private final int pngCompressionLevel;
    private final Optional<ErrorFileWriter> errorFileWriter;
    private final int coffeeStains;
    private String tmpdir = null;
    private final int minimumBaselineThickness;
    private static final Random random = new Random();

    public MinionCutFromImageBasedOnPageXMLNew(String identifier, Supplier<Mat> imageSupplier,
                                               Supplier<PcGts> pageSupplier, String outputBase,
                                               String imageFileName, boolean overwriteExistingPage,
                                               int minWidth, int minHeight, int minWidthToHeight, String outputType,
                                               int channels, boolean writeTextContents, Integer rescaleHeight,
                                               boolean outputConfidenceFile, boolean outputBoxFile, boolean outputTxtFile,
                                               boolean recalculateTextLineContoursFromBaselines,
                                               Integer fixedXHeight, int minimumXHeight, boolean useDiforNames,
                                               boolean writeDoneFiles, boolean ignoreDoneFiles,
                                               Consumer<String> errorLog, boolean includeTextStyles, boolean useTags,
                                               boolean skipUnclear, Double minimumConfidence, Double maximumConfidence,
                                               int minimumInterlineDistance, int pngCompressionLevel,
                                               Optional<ErrorFileWriter> errorFileWriter, String tmpdir,
                                               int minimumBaselineThickness, int coffeeStains) {
        this(identifier, imageSupplier, pageSupplier, outputBase, imageFileName, overwriteExistingPage, minWidth,
                minHeight, minWidthToHeight, outputType, channels, writeTextContents, rescaleHeight, outputConfidenceFile,
                outputBoxFile,
                outputTxtFile, recalculateTextLineContoursFromBaselines, fixedXHeight, minimumXHeight, useDiforNames,
                writeDoneFiles, ignoreDoneFiles, errorLog, page -> {
                }, () -> {
                }, includeTextStyles, useTags, skipUnclear,
                minimumConfidence, maximumConfidence, minimumInterlineDistance, pngCompressionLevel, errorFileWriter,
                tmpdir, minimumBaselineThickness, coffeeStains);
    }

    public MinionCutFromImageBasedOnPageXMLNew(String identifier, Supplier<Mat> imageSupplier,
                                               Supplier<PcGts> pageSupplier, String outputBase, String imageFileName,
                                               boolean overwriteExistingPage,
                                               int minWidth, int minHeight, int minWidthToHeight, String outputType,
                                               int channels, boolean writeTextContents, Integer rescaleHeight,
                                               boolean outputConfidenceFile,
                                               boolean outputBoxFile, boolean outputTxtFile,
                                               boolean recalculateTextLineContoursFromBaselines,
                                               Integer fixedXHeight, int minimumXHeight, boolean useDiforNames,
                                               boolean writeDoneFiles, boolean ignoreDoneFiles,
                                               Consumer<String> errorLog, Consumer<PcGts> pageSaver,
                                               Runnable doneFileWriter, boolean includeTextStyles, boolean useTags,
                                               boolean skipUnclear, Double minimumConfidence, Double maximumConfidence,
                                               int minimumInterlineDistance,int pngCompressionLevel,
                                               Optional<ErrorFileWriter> errorFileWriter, String tmpdir,
                                               int minimumBaselineThickness, int coffeeStains) {
        this.identifier = identifier;
        this.imageSupplier = imageSupplier;
        this.pageSupplier = pageSupplier;
        this.outputBase = outputBase;
        this.imageFileName = imageFileName;
        this.overwriteExistingPage = overwriteExistingPage;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
        this.minWidthToHeight = minWidthToHeight;
        this.outputType = outputType;
        this.channels = channels;
        this.writeTextContents = writeTextContents;
        this.rescaleHeight = rescaleHeight;
        this.outputConfidenceFile = outputConfidenceFile;
        this.outputBoxFile = outputBoxFile;
        this.outputTxtFile = outputTxtFile;
        this.recalculateTextLineContoursFromBaselines = recalculateTextLineContoursFromBaselines;
        this.fixedXHeight = fixedXHeight;
        this.minimumXHeight = minimumXHeight;
        this.useDiforNames = useDiforNames;
        this.writeDoneFiles = writeDoneFiles;
        this.ignoreDoneFiles = ignoreDoneFiles;
        this.errorLog = errorLog;
        this.pageSaver = pageSaver;
        this.doneFileWriter = doneFileWriter;
        this.includeTextStyles = includeTextStyles;
        this.useTags = useTags;
        this.skipUnclear = skipUnclear;
        this.minimumConfidence = minimumConfidence;
        this.maximumConfidence = maximumConfidence;
        this.minimumInterlineDistance = minimumInterlineDistance;
        this.pngCompressionLevel = pngCompressionLevel;
        this.errorFileWriter = errorFileWriter;
        this.tmpdir = tmpdir;
        if (this.tmpdir == null) {
            this.tmpdir = System.getProperty("java.io.tmpdir");
            new File(this.tmpdir).mkdirs();
        }else{
            if (!new File(this.tmpdir).exists()) {
                if (!new File(this.tmpdir).mkdirs()){
                    LOG.error("tmpdir does not exist and could not be created: " + this.tmpdir);
                }
            }
        }
        this.minimumBaselineThickness =minimumBaselineThickness;
        this.coffeeStains = coffeeStains;

    }


    public static Mat addCoffeeStains(Mat inputImage, int stainCount) {
        Mat outputImage = inputImage.clone();

        for (int i = 0; i < stainCount; i++) {
            // Random position
            int centerX = random.nextInt(outputImage.cols());
            int centerY = random.nextInt(outputImage.rows());

            // Random size
            int radiusX = random.nextInt(50) + 20; // Random radius between 20 and 70
            int radiusY = random.nextInt(50) + 20;

            // Random color (brownish)
            Scalar stainColor = new Scalar(random.nextInt(50) + 100, random.nextInt(30) + 50, random.nextInt(20), 50);

            // Create a transparent overlay
            Mat overlay = OpenCVWrapper.newMat(outputImage.size(), outputImage.type(), new Scalar(0, 0, 0, 0));
            Imgproc.ellipse(overlay, new Point(centerX, centerY), new Size(radiusX, radiusY),
                    random.nextInt(360), 0, 360, stainColor, -1, Imgproc.LINE_AA);

            // Add irregular edges using Gaussian blur
            OpenCVWrapper.GaussianBlur(overlay, overlay, new Size(15, 15), 10);

            // Blend the overlay with the original image
            Core.addWeighted(overlay, 0.5, outputImage, 1.0, 0, outputImage);
            overlay = OpenCVWrapper.release(overlay);
        }

        return outputImage;
    }

    private static Options getOptions() {
        Options options = new Options();
        options.addOption(Option.builder("input_path").required(true).hasArg(true)
                .desc("Directory that contains the images.").build()
        );
        options.addOption(Option.builder("outputbase").required(true).hasArg(true)
                .desc("outputbase. Base output where imagesnippets will be stored").build()
        );
        options.addOption("output_type", true, "jpg or png, default png");
        options.addOption("channels", true, "3 (jpg/png) or 4 (png), default 4");
        options.addOption("threads", true, "number of threads to use, default 1");
        options.addOption("write_text_contents", false, "default false. Use when generating snippets from ground truth");
        options.addOption("xheight", true, "fixed x-height to use. This can help when used on multiple pages that contain text of very similar height.");
        options.addOption("minimum_xheight", true, "minimum x-height to use.");
        options.addOption("rescaleheight", true, "rescale height");
        options.addOption("min_width", true, "minimum width of baseline");
        options.addOption("difor_names", false, "use the name convention used in the Digital Forensics project");
        options.addOption("page_path", true, "folder that contains the page xml files, by default input_path/page will be used");
        options.addOption("no_page_update", false, "do not update existing page");
        options.addOption("write_done", true, "write done files for images that are processed (default false)");
        options.addOption("ignore_done", false, "ignore done files and (re)process all images");
        options.addOption("copy_font_file", false, "Move the font file if it exists");
        options.addOption("help", false, "prints this help dialog");
        options.addOption("include_text_styles", false, "include text styles in output as special characters");
        options.addOption("use_tags", false, "use tags instead of special characters");
        options.addOption("no_text_line_contour_recalculation", false, "recalculate textline contours based on the baseline");
        options.addOption("skip_unclear", false, "skip lines containing 'unclear' tag. In general set this when training, but not for inferencing");
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");
        options.addOption("minimum_confidence", true, "minimum confidence for a textline to be included in the output. Default null, meaning include all textlines");
        options.addOption("maximum_confidence", true, "maximum confidence for a textline to be included in the output. Default null, meaning include all textlines");
        options.addOption("minimum_interlinedistance", true, "Minimum interlinedistance, default 35");
        options.addOption("output_confidence_file", false, "output confidence files");
        options.addOption("png_compressionlevel", true, "png_compressionlevel 1 best speed, 9 best compression, default 1");
        options.addOption("tmpdir", true, "temporary directory to use, default java.io.tmpdir");
        options.addOption("minimum_baseline_thickness", true, "minimum baseline thickness to use, default 1");
//        options.addOption("add_coffee_stains", true, "add coffee stains to the image, default 0 (no stains), use a number to specify the amount of stains");
        options.addOption("fix_errors", false, "fix errors in the page xml file, default false. This will remove empty textlines and textlines that are not connected to a baseline");

        return options;
    }

    private void atomicImwrite(String path, Mat mat) {
        atomicImwrite(path, mat, null);
    }

    private static boolean onSameFileSystem(Path sourcePath, Path targetPath) {
        try {
            while (!Files.exists(sourcePath)) {
                sourcePath = sourcePath.getParent();
            }
            FileStore sourceFS = Files.getFileStore(sourcePath);
            while (!Files.exists(targetPath)) {
                targetPath = targetPath.getParent();
            }
            FileStore targetFS = Files.getFileStore(targetPath);
            return sourceFS.equals(targetFS);
        }catch (IOException e){
            LOG.error("could not determine filesystem for source and target");
            return false;
        }
    }

    private void atomicImwrite(String path, Mat mat, MatOfInt parametersMatOfInt) {
        String filename = new File(path).getName();
        String tmpFileName = UUID.randomUUID() + "_" + filename;
        String source = tmpdir + '/' + tmpFileName;

        Path sourcePath = Paths.get(source);
        Path targetPath = Paths.get(path);
        if (parametersMatOfInt == null) {
            Imgcodecs.imwrite(source, mat);
        } else {
            Imgcodecs.imwrite(source, mat, parametersMatOfInt);
        }

        try {
            moveAtomicIfPossible(sourcePath, targetPath);
        } catch (IOException e) {
            LOG.error("could not move file to {}", path);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        int numthreads = 1;
        Path inputPath = Paths.get("/tmp/docker/");
        String outputBase = "/tmp/saeagrergg4a4g";

        boolean overwriteExistingPage = true;
        int minHeight = 5;
        int minWidth = 5;
        int minWidthToHeight = 0;
        Integer rescaleHeight = null;
        String outputType = "png";
        int channels = 4;
        boolean writeTextContents = false;
        boolean outputConfidenceFile = false;
        boolean outputBoxFile = true;
        boolean outputTxtFile = true;
        boolean diforNames;
        boolean recalculateTextLineContoursFromBaselines;
        boolean writeDoneFiles = true;
        boolean ignoreDoneFiles;
        boolean copyFontFile = false;
        boolean includeTextStyles = false;
        boolean useTags = false;
        boolean skipUnclear = false;
        Double minimumConfidence = null;
        Double maximumConfidence = null;
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;
        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException e) {
            printHelp(options, "java " + MinionCutFromImageBasedOnPageXMLNew.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java " + MinionCutFromImageBasedOnPageXMLNew.class.getName());
            return;
        }

        if (commandLine.hasOption("input_path")) {
            inputPath = Paths.get(commandLine.getOptionValue("input_path"));
        }
        if (commandLine.hasOption("outputbase")) {
            outputBase = commandLine.getOptionValue("outputbase");
        }
        if (commandLine.hasOption("output_type")) {
            outputType = commandLine.getOptionValue("output_type");
        }
        if (commandLine.hasOption("channels")) {
            channels = Integer.parseInt(commandLine.getOptionValue("channels"));
        }
        if (commandLine.hasOption("write_text_contents")) {
            writeTextContents = true;
        }
        Integer fixedXHeight = null;
        if (commandLine.hasOption("xheight")) {
            fixedXHeight = Integer.parseInt(commandLine.getOptionValue("xheight"));
        }
        int minimumXHeight = LayoutProc.MINIMUM_XHEIGHT;
        if (commandLine.hasOption("minimum_xheight")) {
            minimumXHeight = Integer.parseInt(commandLine.getOptionValue("minimum_xheight"));
        }

        if (commandLine.hasOption("threads")) {
            numthreads = Integer.parseInt(commandLine.getOptionValue("threads"));
        }
        if (commandLine.hasOption("rescaleheight")) {
            rescaleHeight = Integer.parseInt(commandLine.getOptionValue("rescaleheight"));
        }

        if (commandLine.hasOption("min_width")) {
            minWidth = Integer.parseInt(commandLine.getOptionValue("min_width"));
        }

        final Path pagePath;
        if (commandLine.hasOption("page_path")) {
            pagePath = Paths.get(commandLine.getOptionValue("page_path"));
        } else {
            pagePath = inputPath.resolve("page");
        }

        overwriteExistingPage = !commandLine.hasOption("no_page_update");

        writeDoneFiles = commandLine.hasOption("write_done");
        copyFontFile = commandLine.hasOption("copy_font_file");
        includeTextStyles = commandLine.hasOption("include_text_styles");

        useTags = commandLine.hasOption("use_tags");
        // Provide warning if include_text_styles is not true since it requires the text styles for conversion
        if (useTags && !includeTextStyles) {
            LOG.warn("-use_tags is used without -include_text_styles, this will yield plain text. " +
                    "Please pass -include_text_styles as well to ensure html-tag results.");
        }

        skipUnclear = commandLine.hasOption("skip_unclear");

        if (commandLine.hasOption("minimum_confidence")) {
            minimumConfidence = Double.parseDouble(commandLine.getOptionValue("minimum_confidence"));
        }
        if (commandLine.hasOption("maximum_confidence")) {
            maximumConfidence = Double.parseDouble(commandLine.getOptionValue("maximum_confidence"));
        }

        int minimumInterlineDistance = DEFAULT_MINIMUM_INTERLINE_DISTANCE;
        if (commandLine.hasOption("minimum_interlinedistance")) {
            minimumInterlineDistance = Integer.parseInt(commandLine.getOptionValue("minimum_interlinedistance"));
        }

        int pngCompressionLevel = DEFAULT_PNG_COMPRESSION_LEVEL;
        if (commandLine.hasOption("png_compressionlevel")) {
            pngCompressionLevel = Integer.parseInt(commandLine.getOptionValue("png_compressionlevel"));
        }

        ignoreDoneFiles = commandLine.hasOption("ignore_done");
        diforNames = commandLine.hasOption("difor_names");
        recalculateTextLineContoursFromBaselines = !commandLine.hasOption("no_text_line_contour_recalculation");
        outputConfidenceFile = commandLine.hasOption("output_confidence_file");
        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013 : PageUtils.NAMESPACE2019;
        String tmpdir = null;
        if (commandLine.hasOption("tmpdir")) {
            tmpdir = commandLine.getOptionValue("tmpdir");
        }
        int minimumBaselineThickness = 1;
        if (commandLine.hasOption("minimum_baseline_thickness")) {
            minimumBaselineThickness = Integer.parseInt(commandLine.getOptionValue("minimum_baseline_thickness"));
        }

        int coffeeStains = 0;
//        if (commandLine.hasOption("add_coffee_stains")) {
//            coffeeStains = Integer.parseInt(commandLine.getOptionValue("add_coffee_stains"));
//        }
        boolean fixErrors = commandLine.hasOption("fix_errors");

        ExecutorService executor = Executors.newFixedThreadPool(numthreads);

        DirectoryStream<Path> fileStream = Files.newDirectoryStream(inputPath);
        List<Path> files = new ArrayList<>();
        fileStream.forEach(files::add);
        files.sort(Comparator.comparing(Path::toString));

        for (Path imageFile : files) {
            if (!hasImageExtension(imageFile)) {
                LOG.info("Ignore file '{}', not an image", imageFile);
                continue;
            }

            Supplier<Mat> imageSupplier = () -> {
                String inputFile = imageFile.toAbsolutePath().toString();
                // OpenCVWrapper.imread uses the synchronised allocation lock so concurrent
                // workers cannot trample on the native allocator. Ownership of the returned
                // Mat is transferred to the consumer; it is released by the consumer.
                return OpenCVWrapper.imread(inputFile);
            };
            final String identifier = FilenameUtils.removeExtension(imageFile.getFileName().toString());
            final String pageFileName = identifier + ".xml";
            final Path pageFile = pagePath.resolve(pageFileName);

            if (Files.notExists(pageFile)) {
                LOG.error(pageFile + " does not exist. Continuing");
                continue;
            }

            Supplier<PcGts> pageSupplier = () -> {
                try {
                    PcGts page = PageUtils.readPageFromFile(pageFile, fixErrors);
                    if (page == null) {
                        LOG.error(pageFile + " does not appear to be a valid PageXml file. It is null");
                        return null;
                    }
                    return page;
                } catch (Exception ex) {
                    LOG.error("Unexpected error", ex);
                    LOG.error(pageFile + " does not appear to be a valid PageXml file: " + ex.getMessage());
                    return null;
                }
            };

            Consumer<PcGts> pageSaver = page -> {
                try {
                    PageUtils.writePageToFile(page, namespace, pageFile);
                } catch (IOException e) {
                    LOG.error("Could not save page", e);
                } catch (TransformerException e) {
                    LOG.error("Could not transform page to 2013 version", e);
                }
            };

            Runnable doneFileWriter = () -> {
                try {
                    StringTools.writeFile(imageFile + ".done", "");
                } catch (IOException e) {
                    LOG.error("Could not write done file.", e);
                }
            };

            /* HACK Move the copy here so I don't have to do it in the actual minion cutting */
            if (copyFontFile) {
                if (!new File(outputBase).exists()) {
                    if (!new File(outputBase).mkdir()) {
                        LOG.error(identifier + " could not create outputdir: " + outputBase);
                    }
                }
                String fileNameWithoutExtension = FilenameUtils.removeExtension(imageFile.getFileName().toString());
                File copyInputFile = new File(imageFile.getParent().toFile(), fileNameWithoutExtension + "_font.txt");
                File copyOutputFile = new File(outputBase, fileNameWithoutExtension + "_font.txt");
                Files.copy(copyInputFile.toPath(), copyOutputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            Runnable worker = new MinionCutFromImageBasedOnPageXMLNew(identifier, imageSupplier, pageSupplier,
                    outputBase, imageFile.getFileName().toString(), overwriteExistingPage,
                    minWidth, minHeight, minWidthToHeight, outputType, channels, writeTextContents, rescaleHeight,
                    outputConfidenceFile, outputBoxFile, outputTxtFile, recalculateTextLineContoursFromBaselines,
                    fixedXHeight, minimumXHeight, diforNames, writeDoneFiles, ignoreDoneFiles, error -> {
            }, pageSaver,
                    doneFileWriter, includeTextStyles, useTags, skipUnclear, minimumConfidence, maximumConfidence,
                    minimumInterlineDistance, pngCompressionLevel, Optional.empty(), tmpdir, minimumBaselineThickness, coffeeStains);
            executor.execute(worker);
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                LOG.warn("Timed out waiting for cut-from-image workers to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while waiting for cut-from-image workers", e);
        }
        LOG.info("Finished all threads");
    }

    private static boolean hasImageExtension(Path file) {
        String lowercase = file.toString().toLowerCase();
        return lowercase.endsWith(".jpg") || lowercase.endsWith(".jpeg") ||
                lowercase.endsWith(".png") || lowercase.endsWith(".tif") ||
                lowercase.endsWith(".tiff");
    }

    private void runFile(Mat image) throws IOException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        // Avoid a full-image clone on the default path (coffeeStains == 0). addCoffeeStains
        // already clones internally, so we only need to release localImage when we owned it.
        Mat localImage;
        boolean ownsLocalImage;
        if (coffeeStains > 0) {
            localImage = addCoffeeStains(image, coffeeStains);
            ownsLocalImage = true;
        } else {
            localImage = image;
            ownsLocalImage = false;
        }

        try {
            String fileNameWithoutExtension = FilenameUtils.removeExtension(imageFileName);
            File balancedOutputBase = new File(outputBase, fileNameWithoutExtension);
            int thickness = 10;
            LOG.debug(identifier + " processing...");
            if (!new File(outputBase).exists()) {
                if (!new File(outputBase).mkdir()) {
                    LOG.error(identifier + " could not create outputdir: " + outputBase);
                }
            }

            if (localImage.size().width == 0 || localImage.size().height == 0) {
                LOG.error(identifier + " broken image");
                Files.createDirectory(Paths.get(balancedOutputBase + "/" + fileNameWithoutExtension));
                errorFileWriter.ifPresent(errorWriter -> errorWriter.writeToFile(balancedOutputBase + "/" + fileNameWithoutExtension + "/processing.error", "broken image"));
                return;
            }

            File balancedOutputBaseTmp = Files.createTempDirectory( Paths.get(tmpdir),
                    "LoghiCutter_" + UUID.randomUUID() + "." + balancedOutputBase.toPath().getFileName()).toFile();

            PcGts page = this.pageSupplier.get();
            if (page == null) {
                LOG.error(identifier + " Page is null");
                errorFileWriter.ifPresent(errorWriter -> errorWriter.writeToFile(balancedOutputBase + "/" + fileNameWithoutExtension + "/processing.error", identifier + " Page is null"));
                return;
            }

            final Stopwatch recalc = Stopwatch.createStarted();
            boolean ignoreBroken = true;
            if (recalculateTextLineContoursFromBaselines) {
                LayoutProc.recalculateTextLineContoursFromBaselines(identifier, localImage, page,
                        SHRINK_FACTOR, minimumInterlineDistance, thickness, minimumBaselineThickness, ignoreBroken);
            }

            LOG.debug(identifier + "recalc: " + recalc.stop());
            List<TextLine> textLines = PageUtils.getTextLines(page, skipUnclear, minimumConfidence, maximumConfidence, ignoreBroken);
            StringBuilder allTextLineContents = new StringBuilder();
            // Allocate PNG compression params once per page; reused for every line strip.
            final boolean isPng = "png".equals(this.outputType);
            MatOfInt pngParams = isPng ? new MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, this.pngCompressionLevel) : null;
            try {
            for (TextLine textLine : textLines) {
                List<Point> contourPoints = StringConverter.stringToPoint(textLine.getCoords().getPoints());
                if (contourPoints.isEmpty()) {
                    //TODO: this should not abort the flow
                    continue;
                }
                List<Point> baseLinePoints = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
                Integer xHeight = null;
                TextStyle textStyle = textLine.getTextStyle();
                if (textStyle != null) {
                    xHeight = textStyle.getxHeight();
                }

                if (fixedXHeight != null) {
                    xHeight = fixedXHeight;
                }
                if (xHeight == null || xHeight < minimumXHeight) {
                    xHeight = minimumXHeight;
                }
                // TODO: determine xheight by histogram
                // TODO: determin xheight by CoCo (printed/printlike only)
                // TODO determine xheight by moving entire baseline up and counting binary pixels
                // TODO: determine xheight by smearing
                boolean includeMask = this.channels == 4;
                String lineStripId = identifier + "-" + textLine.getId();
                BinaryLineStrip binaryLineStrip = LayoutProc.getBinaryLineStrip(imageSupplier.toString(), localImage, contourPoints,
                        baseLinePoints, xHeight, includeMask, minWidth, lineStripId, 4, 3, 2);
                Mat lineStripMat = null;
                try {
                    if (binaryLineStrip != null && binaryLineStrip.getLineStrip() != null) {
                        // Take ownership of the line strip directly. The finally block releases
                        // lineStripMat; we transfer the reference out of binaryLineStrip so that
                        // its cleanup is a no-op, avoiding a per-line full Mat clone.
                        lineStripMat = binaryLineStrip.getLineStrip();
                        binaryLineStrip.setLineStrip(null);
                        xHeight = binaryLineStrip.getxHeight();
                        if (textLine.getTextStyle() == null) {
                            textLine.setTextStyle(new TextStyle());
                        }
                        textLine.getTextStyle().setxHeight(xHeight);
                        if (lineStripMat.width() >= minWidth
                                && lineStripMat.height() > minHeight
                                && (lineStripMat.width() / lineStripMat.height()) >= minWidthToHeight) {
//                            String randomUUIDString = inputXmlFilePath.getFileName().toString() + "-" + textRegion.getId()+"-"+textLine.getId();
                            if (rescaleHeight != null) {
                                double targetHeight = rescaleHeight;
                                double heightScale = targetHeight / (double) (lineStripMat.height());
                                double newWidth = heightScale * lineStripMat.width();
                                if (newWidth < 32) {
                                    newWidth = 32;
                                }
                                Size targetSize = new Size(newWidth, targetHeight);
                                Mat binaryLineStripNew = OpenCVWrapper.newMat(targetSize, lineStripMat.type());
                                OpenCVWrapper.resize(lineStripMat, binaryLineStripNew, targetSize);
                                binaryLineStrip.setLineStrip(null);
                                lineStripMat = OpenCVWrapper.release(lineStripMat);
                                lineStripMat = binaryLineStripNew;
                            }
                            if (writeTextContents) {
                                String textValue = GroundTruthTextLineFormatter.getFormattedTextLineStringRepresentation(textLine, includeTextStyles, useTags);
                                if (Strings.isNullOrEmpty(textValue)) {
                                    LOG.warn(identifier + " empty line " + textLine.getId());
                                    binaryLineStrip.setLineStrip(null);
                                    lineStripMat = OpenCVWrapper.release(lineStripMat);
                                    continue;
                                }

                                if (lineStripMat.width() > minWidth
                                        && lineStripMat.height() > minHeight
                                        && (lineStripMat.width() / lineStripMat.height()) >= minWidthToHeight) {
                                    if (outputTxtFile) {
                                        StringTools.writeFile(new File(balancedOutputBaseTmp, lineStripId + ".txt").getAbsolutePath(), textValue);
                                    }
                                    if (outputConfidenceFile) {
                                        String confValue = "1";
                                        if (textLine.getTextEquiv() != null && textLine.getTextEquiv().getConf() != null) {
                                            confValue = textLine.getTextEquiv().getConf();
                                        }
                                        StringTools.writeFile(new File(balancedOutputBaseTmp, lineStripId + ".conf").getAbsolutePath(), confValue);
                                    }
                                    if (outputBoxFile) {
                                        String boxValue = LayoutProc.convertToBoxFile(lineStripMat.height(), lineStripMat.width(), StringTools.makeNew(textValue));
                                        StringTools.writeFile(new File(balancedOutputBaseTmp, lineStripId + ".box").getAbsolutePath(), boxValue);
                                    }
                                }
                            }
                            String outputPath;
                            if (this.useDiforNames) {
                                outputPath = new File(balancedOutputBaseTmp, "textline_" + fileNameWithoutExtension + "_" + textLine.getId() + "." + this.outputType).getAbsolutePath();
                                LOG.debug(identifier + " save snippet: " + outputPath);
                            } else {
                                outputPath = new File(balancedOutputBaseTmp, lineStripId + "." + this.outputType).getAbsolutePath();
                            }
                            try {
                                // balancedOutputBaseTmp is a private tmp dir under tmpdir; the
                                // whole dir is atomic-moved to its final home at the end of the
                                // page. Per-line tmpfile-and-move is redundant here, so write
                                // straight to the target path.
                                if (isPng) {
                                    writePngLineStrip(outputPath, lineStripMat, pngParams);
                                } else {
                                    Imgcodecs.imwrite(outputPath, lineStripMat);
                                }
                            } catch (Exception e) {
                                errorLog.accept("Cannot write " + outputPath);
                                lineStripMat = OpenCVWrapper.release(lineStripMat);
                                throw e;
                            }
                            if (writeTextContents) {
                                Double confidence = 1.;
                                if (textLine.getTextEquiv() != null && !Strings.isNullOrEmpty(textLine.getTextEquiv().getConf())) {
                                    confidence = Double.parseDouble(textLine.getTextEquiv().getConf());
                                }
                                allTextLineContents.append(outputPath).append("\t")
                                        .append(confidence).append("\t")
                                        .append(textLine.getTextEquiv().getUnicode()).append("\n");
                            }

                        }
                    }
                } finally{
                    if (lineStripMat != null) {
                        lineStripMat = OpenCVWrapper.release(lineStripMat);
                    }
                    if (binaryLineStrip != null) {
                        if (binaryLineStrip.getLineStrip() != null) {
                            binaryLineStrip.setLineStrip(OpenCVWrapper.release(binaryLineStrip.getLineStrip()));
                        }
                        binaryLineStrip.setLineStrip(null);
                    }
                }
            }
            } finally {
                if (pngParams != null) {
                    pngParams = OpenCVWrapper.release(pngParams);
                }
            }
            if (writeTextContents) {
                StringTools.writeFile(new File(balancedOutputBaseTmp, "loghi-all-lines.txt").getAbsolutePath(), allTextLineContents.toString());
            }

            if (balancedOutputBase.exists()) {
                deleteFolderRecursively(balancedOutputBase.toPath());
            }
            moveAtomicIfPossible(balancedOutputBaseTmp, balancedOutputBase);

            if (overwriteExistingPage) {
                pageSaver.accept(page);
            }

            LOG.debug(identifier + " single image took: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));

            if (this.writeDoneFiles) {
                this.doneFileWriter.run();
            }
        } finally {
            if (ownsLocalImage) {
                localImage = OpenCVWrapper.release(localImage);
            }
        }
    }

    public static void copyFolder(Path source, Path destination) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(child -> copy(child, destination.resolve(source.relativize(child))));
        }
    }

    private static void copy(Path source, Path destination) {
        try {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            throw new RuntimeException(exception.getMessage(), exception);
        }
    }


    private static void moveAtomicIfPossible(File source, File destination) throws IOException {
        moveAtomicIfPossible(source.toPath(), destination.toPath());
    }
    private static void moveAtomicIfPossible(Path source, Path target) throws IOException {
        boolean onSameFileSystem = onSameFileSystem(source, target);
        if (!onSameFileSystem) {
            LOG.warn("source and target are not on the same filesystem, this is slow and not atomic. Set -tmpdir to a location on the same filesystem as the target for improved speed and atomicity.");
        }
        if (onSameFileSystem) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }else{
            if (Files.isDirectory(source)) {
                copyFolder(source, target);
                deleteFolderRecursively(source);
            } else {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void writePngLineStrip(String absolutePath, Mat lineStripMat, MatOfInt pngParams) {
        // pngParams is provided by the caller and reused across all line strips of a page,
        // so we never allocate a fresh MatOfInt per line.
        Imgcodecs.imwrite(absolutePath, lineStripMat, pngParams);
    }

    private static void deleteFolderRecursively(Path source) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            // delete directories or folders
            @Override
            public FileVisitResult postVisitDirectory(Path dir,
                                                      IOException exc)
                    throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }

            // delete files
            @Override
            public FileVisitResult visitFile(Path file,
                                             BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void run() {
        Mat image = null;
        try {
            image = imageSupplier.get();
            if (this.ignoreDoneFiles || !Files.exists(Paths.get(this.identifier + ".done"))) {
                this.runFile(image);
            }
        } catch (IOException e) {
            LOG.error("Could not process image {}", this.imageFileName, e);
            errorFileWriter.ifPresent(errorWriter -> errorWriter.write(identifier, e, "Image could not be processed"));
            LOG.error("Unexpected error", e);
        } finally {
            image = OpenCVWrapper.release(image); // Release image
//            try {
//                this.close();
//            } catch (Exception e) {
//                LOG.error("Unexpected error", e);
//            }
        }
    }

//    @Override
//    public void close() throws Exception {
//        System.gc();
//    }
}
