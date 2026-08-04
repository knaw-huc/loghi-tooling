package nl.knaw.huc.di.images.minions;

import com.google.common.base.Strings;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.imageanalysiscommon.UnicodeToAsciiTranslitirator;
import nl.knaw.huc.di.images.imageanalysiscommon.imageConversion.ImageConversionHelper;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.Page.*;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.*;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

import static org.opencv.core.CvType.CV_8U;
import static org.opencv.imgproc.Imgproc.THRESH_BINARY;
import static org.opencv.imgproc.Imgproc.THRESH_BINARY_INV;

public class MinionGeneratePageImages implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MinionGeneratePageImages.class);

    public static final String FONT_PATH = "font_path";
    public static final String TEXT_PATH = "text_path";
    public static final String OUTPUT_PATH = "output_path";
    public static final String CHANCE_ITALIC = "chance_italic";
    public static final String CHANCE_BOLD = "chance_bold";
    public static final String CHANCE_UNDERLINE = "chance_underline";
    public static final String CHANCE_UPPERCASE = "chance_uppercase";
    public static final String CHANCE_LINE = "chance_line";
    public static final String MIN_FONT_SIZE = "min_font_size";
    public static final String MAX_FONT_SIZE = "max_font_size";
    public static final String MAX_TEXT_LENGTH = "max_text_length";
    public static final String MULTIPLY = "multiply";
    public static final String MAX_FILES = "max_files";
    public static final String BLUR_WINDOW = "blur_window";
    public static final String BLUR_SIGMAX = "blur_sigmax";
    public static final String CHARACTERS = "characters";
    public static final UnicodeToAsciiTranslitirator UNICODE_TO_ASCII_TRANSLITIRATOR = new UnicodeToAsciiTranslitirator();
    static double chanceUpperCase = 0.2d;
    static int maxTextLength = 150;
    private static String largeText = "";
    private static Random random = null;
    private static String allowedCharacters = "ſﬅﬄﬃﬂæÆœ #$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_abcdefghijklmnopqrstuvwxyz{|}\"_";
    static int blurWindow =11;
    static int blurSigmaX =25;

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    private static List<String> readFonts(String pathString) throws Exception {
        Path path = Paths.get(pathString);
        ArrayList<String> fonts = new ArrayList<>();

        try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
            for (Path file : files) {
                if (file.getFileName().toString().endsWith(".ttf")) {
                    try {
                        GraphicsEnvironment ge =
                                GraphicsEnvironment.getLocalGraphicsEnvironment();
                        Font font;
                        try {
                             font = Font.createFont(Font.TRUETYPE_FONT, new File(file.toAbsolutePath().toString()));
                        }catch(java.awt.FontFormatException fontFormatException){
                            LOG.error(fontFormatException.getMessage());
                            LOG.error("FontFormatException for file: " + file.toAbsolutePath());
                            continue;
                        }
                        ge.registerFont(font);
                        BufferedImage img = generateTextLine("bleet", new Color(255, 255, 255), new Color(0, 0, 0), 250, 250, new Font(font.getName(), Font.PLAIN, 40));

                        Mat result = OpenCVWrapper.newMat();
                        Mat input = ImageConversionHelper.bufferedImageToMat(img);
                        Mat grayImage = OpenCVWrapper.newMat();
                        if (input.type() != CV_8U) {
                            Imgproc.cvtColor(input, grayImage, Imgproc.COLOR_BGR2GRAY);
                        } else {
                            input.copyTo(grayImage);
                        }

                        Imgproc.threshold(grayImage, result, 10, 255, THRESH_BINARY);

                        fonts.add(font.getName());
                    } catch (IOException e) {
                        LOG.error("Unexpected error", e);
                    }
                }
            }
        }


        return fonts;
    }

    private static ArrayList<String> getAllUsableFonts() {
        ArrayList<String> returnFonts = new ArrayList<>();
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String fontName : fonts) {
            Font font = new Font(fontName, Font.PLAIN, 32);
            if (font.canDisplay('ſ')
                    && font.canDisplay('ﬅ')
                    && font.canDisplay('ﬄ')
                    && font.canDisplay('ﬃ')
                    && font.canDisplay('ﬂ')
                    && font.canDisplay('æ')
                    && font.canDisplay('Æ')
                    && font.canDisplay('œ')
                    && !fontName.equals("Adobe Blank")
            ) {
                LOG.info(fontName +" added");
                returnFonts.add(fontName);
            }

        }
        return returnFonts;
    }

    private static boolean canDisplay(Font font, String text) {
        for (char character : text.toCharArray()) {
            if (!font.canDisplay(character) || (!Strings.isNullOrEmpty(allowedCharacters) && !allowedCharacters.contains(String.valueOf(character)))) {
                return false;
            }
        }
        return true;
    }

    private static Options getOptions() {
        Options options = new Options();
// -input_path /media/rutger/DIFOR1/data/republicready/385578/3116_1586_2/ -outputbase /media/rutger/DIFOR1/data/republicready/snippets/ -output_type png -channels 4 -write_text_contents
        options.addOption(Option.builder(TEXT_PATH).required(true).hasArg(true)
                .desc("path with plain text files that are used to generated page and images.").build()
        );
        options.addOption(Option.builder(OUTPUT_PATH).required(true).hasArg(true)
                .desc("Base output where imagesnippets will be stored").build()
        );
        options.addOption(Option.builder(FONT_PATH).required(true).hasArg(true).desc("Path of the fonts to use")
                .build()
        );
        options.addOption("make_old", false, "make_old, uses old style chars such as long s (default: false)");
        options.addOption("add_salt_and_pepper", false, "Adds salt and pepper noise");
        options.addOption("random_augment", false, "randomly augment the images");
        options.addOption("underline", false, "underline the generated text");
        options.addOption("save_font", false, "Save the font used in a txt file");
        options.addOption("help", false, "prints this help dialog");
        options.addOption(CHANCE_ITALIC, true, "chance that lines that should be italic (default: 0.2)");
        options.addOption(CHANCE_BOLD, true, "chance that lines that should be bold (default: 0.2)");
        options.addOption(CHANCE_UNDERLINE, true, "chance that lines that should be underline (default: 0.2)");
        options.addOption(CHANCE_UPPERCASE, true, "chance that lines that should be uppercase (default: 0.2)");
        options.addOption(CHANCE_LINE, true, "chance that lines that should be underline (default: 0.2)");
        options.addOption(MIN_FONT_SIZE, true, "Minimal font size (default: 36)");
        options.addOption(MAX_FONT_SIZE, true, "Maximum font size (default: 96)");
        options.addOption(MAX_TEXT_LENGTH, true, "Maximum number of characters of the text (default: 150)");
        options.addOption(MULTIPLY, true, "The amount of times a piece of text should be used. (default: 1)");
        options.addOption(MAX_FILES, true, "The maximum number of generated files (default: 500000");
        options.addOption(BLUR_WINDOW, true, "The blur window (default: 11)");
        options.addOption(BLUR_SIGMAX, true, "Blur sigma X(default: 25");
        options.addOption(CHARACTERS, true, "allowed characters: use --characters \"\" for allowing everything" );
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");

        return options;
    }

    public static void printHelp(Options options, String callName) {
        final HelpFormatter helpFormatter = new HelpFormatter();

        helpFormatter.printHelp(callName, options, true);
    }

    public static void main(String[] args) throws Exception {
        String outputpath = "/media/rutger/HDI0002/synthetic/";

        String textPath = "/media/rutger/HDI0002/toLaptop/text/";

        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;
        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException ex) {
            printHelp(options, "java " + MinionGeneratePageImages.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java " + MinionGeneratePageImages.class.getName());
            return;
        }

        if (commandLine.hasOption(TEXT_PATH)) {
            textPath = commandLine.getOptionValue(TEXT_PATH);
        }
        if (commandLine.hasOption(OUTPUT_PATH)) {
            outputpath = commandLine.getOptionValue(OUTPUT_PATH);
        }
        List<String> fonts;
        if (commandLine.hasOption(FONT_PATH)) {
            fonts = readFonts(commandLine.getOptionValue(FONT_PATH));
        } else {
            fonts = readFonts("/home/rutger/Downloads/toLaptop/fonts/usable/");
        }

        boolean random_augment = commandLine.hasOption("random_augment");
        boolean add_salt_and_pepper = commandLine.hasOption("add_salt_and_pepper");
        boolean makeOld = commandLine.hasOption("make_old");
        boolean underline = commandLine.hasOption("underline");
        boolean saveFont = commandLine.hasOption("save_font");
        double chanceItalic = 0.2d;
        double chanceBold = 0.2d;
        double chanceUnderline = 0.2d;
        double chanceLine = 0.2d;
        int fontMinSize = 36;
        int fontMaxSize = 96;
        int multiply = 1;
        int maxFiles = 500000;

        chanceItalic = getDoubleValue(commandLine, chanceItalic, CHANCE_ITALIC);
        chanceBold = getDoubleValue(commandLine, chanceBold, CHANCE_BOLD);
        chanceUnderline = getDoubleValue(commandLine, chanceUnderline, CHANCE_UNDERLINE);
        chanceUpperCase = getDoubleValue(commandLine, chanceUpperCase, CHANCE_UPPERCASE);
        chanceLine = getDoubleValue(commandLine, chanceLine, CHANCE_LINE);
        fontMinSize = getIntValue(commandLine, fontMinSize, MIN_FONT_SIZE);
        fontMaxSize = getIntValue(commandLine, fontMaxSize, MIN_FONT_SIZE);
        maxTextLength = getIntValue(commandLine, maxTextLength, MAX_TEXT_LENGTH);
        multiply = getIntValue(commandLine, multiply, MULTIPLY);
        maxFiles = getIntValue(commandLine, maxFiles, MAX_FILES);

        blurWindow = getIntValue(commandLine, blurWindow, BLUR_WINDOW);
        blurSigmaX = getIntValue(commandLine, blurSigmaX, BLUR_SIGMAX);
        allowedCharacters = commandLine.getOptionValue(CHARACTERS,allowedCharacters);
        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

        String fileFormat ="synthetic%010d";
        int counter = 0;
        StringBuilder merged = new StringBuilder();
        Path path = Paths.get(textPath);
        if (!new File(outputpath).exists()) {
            new File(outputpath).mkdirs();
        }
        PrintWriter out = new PrintWriter(outputpath + "synthetic.txt");

        try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
            for (Path file : files) {
                if (!file.toAbsolutePath().toString().endsWith(".txt")) {
                    continue;
                }
                try {
                    largeText = StringTools.readFile(file.toAbsolutePath().toString());
                } catch (Exception ex) {
                    LOG.error(file.toAbsolutePath()+ " threw an exception");
                    continue;
                }
                List<String> splitted = Arrays.asList(largeText.split("\n"));
                if (multiply > 1 && splitted.size()< 40) {
                    LOG.warn("File " + file + " has only " + splitted.size() + "/40 lines, multiply is returning the same text lines");
                }

                for (int i = 0; i < multiply; i++) {
                    int skip = (int) (getRandom().nextDouble() * splitted.size()) - 40;
                    if (skip < 0) {
                        skip = 0;
                    }
                    List<String> sampled_splitted = splitted.stream().skip(skip).limit(40).collect(Collectors.toList());

                    Font font = getRandomFont(fonts, fontMinSize, fontMaxSize, chanceBold, chanceItalic);
                    Font font2;
                    Map<TextAttribute, Object> attributes = new HashMap<>();
                    //Tracking should be somewhere between -0.1 and 0.3
//                        double tracking = (getRandom().nextDouble() * 0.4) - 0.1;
                    double tracking = (getRandom().nextDouble() * 0.4) - 0.1;
                    attributes.put(TextAttribute.TRACKING, tracking);
                    font2 = font.deriveFont(attributes);

                    int maxTextWidth = 0;
                    int maxheight = 0;
                    int totalHeight = 0;
                    int spaceWidth = 0;
                    double spacing = 0.5 + 3 * getRandom().nextDouble();
                    BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics2D = img.createGraphics();

                    graphics2D.setFont(font2);
                    graphics2D.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
                    graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    graphics2D.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
                    graphics2D.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
                    graphics2D.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                    graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics2D.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics2D.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    FontMetrics fm = graphics2D.getFontMetrics();
                    ArrayList<String> textList = new ArrayList<>();
                    for (String line : sampled_splitted) {
                        String text = line;
                        if (text.length() > maxTextLength) {
                            text = text.substring(0, maxTextLength).trim();
                        }
                        text = text.trim();
                        if (text.isEmpty()) {
                            continue;
                        }
                        if (text.contains(">>pagina-aanduiding<<")) {
                            continue;
                        }
                        if (makeOld) {
                            text = StringTools.makeOld(text);
                        }
                        if (chanceUpperCase > getRandom().nextDouble()) {
                            text = text.toUpperCase();
                        }

//                    List<String> fonts = getAllUsableFonts(text);
                        if (!canDisplay(font, text)) {
                            continue;
                        }

                        int textWidth = fm.stringWidth(" " + text + " ");
                        spaceWidth = fm.stringWidth(" ");
                        LOG.warn("textWidth = {}", textWidth);
                        if (textWidth > maxTextWidth) {
                            LOG.warn("maxwidth = {}", textWidth);
                            maxTextWidth = textWidth;
                        }
                        int height = fm.getHeight();
                        totalHeight += height;
                        if (height > maxheight) {
                            maxheight = height;
                        }
                        textList.add(text);
                    }

                    if (maxTextWidth == 0) {
                        LOG.debug(file + " has maxWidth 0 with font: " +font.getName() + " and counter: "+counter+" and font2: " + font2.getName());
                        continue;
                    }
                    counter++;
                    String filename = String.format(fileFormat, counter)+"-"+ font2.getName()
                            .replace("!","_")
                            .replace("?","_")
                            .replace("/","_")
                            .replace("\\","_")
                            .trim()
                            .replace(" ","_");
                    BufferedImage bufferedImage = generatePageClean(textList, maxTextWidth, maxheight, font2,
                            spaceWidth, spacing, outputpath, filename, underline, chanceUnderline, namespace);
                    //baseline is exact at position "height" and runs from spaceWidth to spaceWidth+width
                    Mat originalMat = ImageConversionHelper.bufferedImageToMat(bufferedImage);
                    if (chanceLine > getRandom().nextDouble()) {
                        org.opencv.core.Point startPoint = new org.opencv.core.Point(getRandom().nextInt(originalMat.width()), getRandom().nextInt(originalMat.height()));
                        org.opencv.core.Point endPoint = new org.opencv.core.Point(getRandom().nextInt(originalMat.width()), getRandom().nextInt(originalMat.height()));
                        Scalar color = new Scalar(getRandom().nextInt(256), getRandom().nextInt(256), getRandom().nextInt(256));
                        int lineThickness = 1+getRandom().nextInt(10);
                        Imgproc.line(originalMat, startPoint, endPoint, color, lineThickness);
                    }

                    Mat mat = originalMat;
                    String fullPath = outputpath + "/" + filename + ".png";
                    LOG.info(filename + " " + font.getName());

                    if (add_salt_and_pepper) {
                        Mat saltAndPepperNoise = Mat.zeros(mat.rows(), mat.cols(), CV_8U);
                        Core.randu(saltAndPepperNoise, 0, 255);
                        Mat black = Mat.zeros(mat.rows(), mat.cols(), CV_8U);
                        Mat white = Mat.zeros(mat.rows(), mat.cols(), CV_8U);

                        int upperbound = 255 - getRandom().nextInt(55);
                        int lowerbound = getRandom().nextInt(55);
                        Imgproc.threshold(saltAndPepperNoise, black, upperbound, 255, THRESH_BINARY);
                        Imgproc.threshold(saltAndPepperNoise, white, lowerbound, 255, THRESH_BINARY_INV);


                        mat.setTo(new Scalar(200, 200, 200), white);
                        mat.setTo(new Scalar(0, 0, 0), black);
                        saltAndPepperNoise.release();
                        black.release();
                        white.release();
                        int randomSigmaX = getRandom().nextInt(blurSigmaX);
                        Imgproc.GaussianBlur(mat, mat, new Size(blurWindow, blurWindow), randomSigmaX);
                    }

                    if (saveFont) {
                        String font_path = outputpath + "/" + filename + "_font.txt";
                        StringTools.writeFile(new File(font_path).getAbsolutePath(), font.getName());
                    }
                    Imgcodecs.imwrite(fullPath, mat);
                    mat = OpenCVWrapper.release(mat);
                    out.print(merged);
                    merged = new StringBuilder();
                    if (counter == maxFiles) {
                        out.flush();
                        out.close();
                        return;
                    }
                }
            }
        }

        out.flush();
        out.close();
    }

    private static double getDoubleValue(CommandLine cmd, double defaultValue, String optionName) {
        if (cmd.hasOption(optionName)) {
            return Double.parseDouble(cmd.getOptionValue(optionName));
        }
        return defaultValue;
    }

    private static int getIntValue(CommandLine cmd, int defaultValue, String optionName) {
        if (cmd.hasOption(optionName)) {

            return Integer.parseInt(cmd.getOptionValue(optionName));

        }
        return defaultValue;
    }

    private static Font getRandomFont(List<String> fonts, int fontMinsize, int fontMaxSize, double chanceBold, double chanceItalic) {
        String fontName = fonts.get(getRandom().nextInt(fonts.size()));
        Font font;
        int fontSize = getRandom().nextInt(fontMaxSize - fontMinsize) + fontMinsize;
        if (getRandom().nextDouble() < chanceItalic) {
            font = new Font(fontName, Font.ITALIC, fontSize);
        } else if (getRandom().nextDouble() < chanceBold) {
            font = new Font(fontName, Font.BOLD, fontSize);

        } else {
            font = new Font(fontName, Font.PLAIN, fontSize);

        }
        return font;
    }

    private static BufferedImage generateTextLine(String text, Color foregroundColor, Color backgroundColor, int height, int width, Font font) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(backgroundColor);
        g2d.fillRect(0, 0, img.getWidth(), img.getHeight());
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        g2d.setColor(foregroundColor);
        g2d.drawString(text, 0, fm.getAscent());
        g2d.dispose();

        return img;
    }

    private static BufferedImage generatePageClean(List<String> lines, int totalWidth, int height, Font font,
                                                   int spaceWidth, double spacing, String outputpath,
                                                   String filename, boolean underline, double chanceUnderline,
                                                   String namespace) throws IOException, TransformerException {
        PcGts page = new PcGts();
        page.setMetadata(new Metadata());
        page.getMetadata().setCreator("Loghi MinionGeneratePageImages");
        page.getMetadata().setCreated(new Date());
        page.getMetadata().setLastChange(new Date());
        TextRegion textRegion = new TextRegion();
        textRegion.setId(UUID.randomUUID().toString());

        LOG.debug("totalWidth: "+totalWidth);
        LOG.debug("lines: "+lines.size());
        BufferedImage bufferedImage = new BufferedImage(totalWidth, (int) (height * (lines.size()+2) + lines.size() * spacing * 2), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics2D = bufferedImage.createGraphics();
        Color backGroundColor = new Color(getRandom().nextInt(60) + 180, getRandom().nextInt(60) + 180, getRandom().nextInt(30) + 180);
        graphics2D.setColor(backGroundColor);
        graphics2D.fillRect(0, 0, bufferedImage.getWidth(), bufferedImage.getHeight());
        graphics2D.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics2D.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        graphics2D.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        graphics2D.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics2D.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics2D.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics2D.setFont(font);
        Color foreGroundColor = new Color(getRandom().nextInt(60), getRandom().nextInt(60), getRandom().nextInt(60));
        graphics2D.setColor(foreGroundColor);
        FontMetrics fontMetrics = graphics2D.getFontMetrics();
        int linecounter = 0;
        double baselineY = 0;
        page.getPage().setImageWidth(bufferedImage.getWidth());
        page.getPage().setImageHeight(bufferedImage.getHeight());

        page.getPage().setImageFilename(filename + ".png");
        for (String line : lines) {

            String text = line.trim();
            if (chanceUpperCase > getRandom().nextDouble()) {
                text = text.toUpperCase();
                if (text.length() > maxTextLength) {
                    text = text.substring(0, (int) (maxTextLength / 1.5)).trim();
                }
            }

            if (text.length() > maxTextLength) {
                text = text.substring(0, maxTextLength).trim();
            }

            int stringHeight = fontMetrics.getHeight();
            baselineY += (stringHeight + (0.1 + getRandom().nextDouble()) * spacing);
            if (Strings.isNullOrEmpty(text.trim())) {
                continue;
            }
            linecounter++;
            int stringWidth = fontMetrics.stringWidth(text);
            if (stringWidth < 1) {
                LOG.warn("stringwidth less than 1: {}", stringWidth);
                continue;
            }
            graphics2D.drawString(text, spaceWidth, (int) (baselineY));
            final int textWidth = Math.max(graphics2D.getFontMetrics().stringWidth(text), 1);
            final double charWidth = textWidth / (double) text.length();
            final double maxLength = Math.min(text.length(), spaceWidth * charWidth);
            LOG.debug("maxLength: " + maxLength);
            if (maxLength< 1) {
                LOG.warn("maxLength less than 1: {}", maxLength);
                continue;
            }

            boolean underlined = false;
            if (underline && getRandom().nextDouble() < chanceUnderline) {
                underlined = true;
                double linelocation = baselineY + height * getRandom().nextDouble() * 0.3;
                graphics2D.drawLine(spaceWidth, (int) linelocation, fontMetrics.stringWidth(" " + text + " ") - spaceWidth, (int) linelocation);
            }
            TextLine textLine = new TextLine();
            textLine.setId(UUID.randomUUID().toString());
            TextEquiv textEquiv = new TextEquiv(1d, UNICODE_TO_ASCII_TRANSLITIRATOR.toAscii(text), text);
            textLine.setTextEquiv(textEquiv);

            TextLineCustom textLineCustom = new TextLineCustom();
            textLineCustom.setReadingOrder("readingOrder {index:" + linecounter + ";}");
            if (underlined) {
                textLineCustom.addCustomTextStyle("underlined", 0, textEquiv.getUnicode().length());
            }
            textLine.setCustom(textLineCustom.toString());

            Baseline baseline = new Baseline();
            textLine.setBaseline(baseline);
            Coords coords = new Coords();
            textLine.setCoords(coords);
            List<Point> points = new ArrayList<>();
            points.add(new Point(spaceWidth, baselineY));
            final int baseLineWidth = Math.min(spaceWidth + stringWidth, totalWidth);
            points.add(new Point(baseLineWidth, baselineY));
            baseline.setPoints(StringConverter.pointToString(points));

            points.add(new Point(baseLineWidth + 1, baselineY + 1));
            points.add(new Point(baseLineWidth - 1, baselineY - 1));

            coords.setPoints(StringConverter.pointToString(points));

            textRegion.getTextLines().add(textLine);
            textRegion.setCoords(coords);
        }
        if (textRegion.getTextLines().size()>0) {
            page.getPage().getTextRegions().add(textRegion);
        }
        ArrayList<String> regionOrderList = new ArrayList<>();
        regionOrderList.add(null);
        LayoutProc.reorderRegions(page, regionOrderList);
        final Path pageFolder = Paths.get(outputpath).resolve("page");
        pageFolder.toFile().mkdir();
        String fullPath = pageFolder.resolve(filename + ".xml").toFile().getAbsolutePath();
        PageUtils.writePageToFile(page, namespace, Paths.get(fullPath));
        graphics2D.dispose();
        return bufferedImage;
    }
    private static String getRandomText() {
        String[] splitted = largeText.split("\n");
        int size = splitted.length;
        int candidate = getRandom().nextInt(size);
        String text = splitted[candidate].trim();
        if (text.length() > 100) {
            return text.substring(0, 100).trim();
        }
        return splitted[candidate].trim();
    }

    private static Random getRandom() {
        if (random == null) {
            random = new Random();
        }
        return random;
    }

    @Override
    public void close() throws Exception {
        System.gc();

    }
}