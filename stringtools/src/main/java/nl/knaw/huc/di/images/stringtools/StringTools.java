package nl.knaw.huc.di.images.stringtools;

import com.cedarsoftware.util.StringUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Strings;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringTools {
    private static final Logger LOG = LogManager.getLogger(StringTools.class);
    public final static Charset CHARSET_UTF8 = StandardCharsets.UTF_8;
    public static final Charset ISO885915 = Charset.forName("ISO-8859-15");

    private final static TreeMap<Integer, String> map = new TreeMap<>();
    static final HashMap<String, Integer> months;
    private static Pattern romanNumeralPattern = null;

    /**
     * Shared, XXE-hardened {@link DocumentBuilderFactory}. JAXP factories are expensive to construct
     * (they scan the classpath for providers) but are thread-safe to share — only
     * {@link DocumentBuilderFactory#newDocumentBuilder()} must be called per thread.
     */
    private static final DocumentBuilderFactory SECURE_DOCUMENT_BUILDER_FACTORY = buildSecureDocumentBuilderFactory();
    /**
     * Shared, XXE-hardened {@link TransformerFactory} — see {@link #SECURE_DOCUMENT_BUILDER_FACTORY}.
     */
    private static final TransformerFactory SECURE_TRANSFORMER_FACTORY = buildSecureTransformerFactory();

    private static DocumentBuilderFactory buildSecureDocumentBuilderFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            return factory;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Unable to create XXE-hardened DocumentBuilderFactory", e);
        }
    }

    private static TransformerFactory buildSecureTransformerFactory() {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return transformerFactory;
    }

    private static DocumentBuilderFactory createSecureDocumentBuilderFactory() {
        return SECURE_DOCUMENT_BUILDER_FACTORY;
    }

    private static TransformerFactory createSecureTransformerFactory() {
        return SECURE_TRANSFORMER_FACTORY;
    }

    static {
        months = new HashMap<>();
        months.put("januari", 1);
        months.put("jan", 1);
        months.put("februari", 2);
        months.put("feb", 2);
        months.put("febr", 2);
        months.put("maart", 3);
        months.put("mar", 3);
        months.put("april", 4);
        months.put("apr", 4);
        months.put("mei", 5);
        months.put("juni", 6);
        months.put("jun", 6);
        months.put("juli", 7);
        months.put("jul", 7);
        months.put("augustus", 8);
        months.put("aug", 8);
        months.put("september", 9);
        months.put("sept", 9);
        months.put("oktober", 10);
        months.put("okt", 10);
        months.put("oct", 10);
        months.put("november", 11);
        months.put("nov", 11);
        months.put("december", 12);
        months.put("dec", 12);
        months.put("ianuarii", 1);
        months.put("februarii", 2);
        months.put("febr.", 2);
        months.put("martii", 3);
        months.put("aprilis", 4);
        months.put("maii", 5);
        months.put("iunii", 6);
        months.put("iulii", 7);
        months.put("augusti", 8);
        months.put("septembris", 9);
        months.put("octobris", 10);
        months.put("novembris", 11);
        months.put("decembris", 12);
    }

    static {

        map.put(1000, "M");
        map.put(900, "CM");
        map.put(500, "D");
        map.put(400, "CD");
        map.put(100, "C");
        map.put(90, "XC");
        map.put(50, "L");
        map.put(40, "XL");
        map.put(10, "X");
        map.put(9, "IX");
        map.put(5, "V");
        map.put(4, "IV");
        map.put(1, "I");

    }

    public static boolean isNumeric(String s) {
        return s != null && s.matches("^\\d+$");
    }

    public static int getDutchMonthNumber(String month) {
        return months.get(month.toLowerCase());
    }

    public static boolean isDutchMonth(String s) {
        return months.containsKey(s.toLowerCase());
    }

    public static String toRoman(int number) {
        int l = map.floorKey(number);
        if (number == l) {
            return map.get(number);
        }
        return map.get(l) + toRoman(number - l);
    }

    public static int romanToDecimal(java.lang.String romanNumber) {
        int decimal = 0;
        int lastNumber = 0;
        String romanNumeral = romanNumber.toUpperCase();
        /* operation to be performed on upper cases even if user
           enters roman values in lower case chars */
        for (int x = romanNumeral.length() - 1; x >= 0; x--) {
            char convertToDecimal = romanNumeral.charAt(x);

            switch (convertToDecimal) {
                case 'M':
                    decimal = processDecimal(1000, lastNumber, decimal);
                    lastNumber = 1000;
                    break;

                case 'D':
                    decimal = processDecimal(500, lastNumber, decimal);
                    lastNumber = 500;
                    break;

                case 'C':
                    decimal = processDecimal(100, lastNumber, decimal);
                    lastNumber = 100;
                    break;

                case 'L':
                    decimal = processDecimal(50, lastNumber, decimal);
                    lastNumber = 50;
                    break;

                case 'X':
                    decimal = processDecimal(10, lastNumber, decimal);
                    lastNumber = 10;
                    break;

                case 'V':
                    decimal = processDecimal(5, lastNumber, decimal);
                    lastNumber = 5;
                    break;

                case 'I':
                    decimal = processDecimal(1, lastNumber, decimal);
                    lastNumber = 1;
                    break;
                default:
                    throw new NumberFormatException("This is not a roman number");
            }
        }
        return decimal;
    }

    public static int processDecimal(int decimal, int lastNumber, int lastDecimal) {
        if (lastNumber > decimal) {
            return lastDecimal - decimal;
        } else {
            return lastDecimal + decimal;
        }
    }

    public static boolean isRomanNumeral(String numeral) {
        if (romanNumeralPattern == null) {
            romanNumeralPattern = Pattern.compile("^M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$");
        }
        Matcher matcher = romanNumeralPattern.matcher(numeral);

        return matcher.matches();
    }

    public static String loadStringFromStream(InputStream inputStream) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, CHARSET_UTF8));

        String inputLine;
        while ((inputLine = bufferedReader.readLine()) != null) {
            stringBuilder.append(inputLine).append('\n');
        }
        bufferedReader.close();

        return stringBuilder.toString();

    }

    public static String loadStringFromFile(String pathString) throws IOException {
        InputStream inputStream = new FileInputStream(pathString);

        return loadStringFromStream(inputStream);
    }

    public static org.w3c.dom.Document loadXMLFromString(String xml) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = createSecureDocumentBuilderFactory();
        DocumentBuilder builder = factory.newDocumentBuilder();
        InputSource is = new InputSource(new StringReader(xml));
        return builder.parse(is);
    }

    public static String getStringFromDocument(org.w3c.dom.Document doc) {
        try {
            DOMSource domSource = new DOMSource(doc);
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            TransformerFactory tf = createSecureTransformerFactory();
            Transformer transformer = tf.newTransformer();
            transformer.transform(domSource, result);
            return writer.toString();
        } catch (TransformerException ex) {
            LOG.error("Could not serialize XML document", ex);
            return null;
        }
    }

    public static String encodeURIComponent(String s) {
        String result;

        result = URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20")
                .replaceAll("%21", "!")
                .replaceAll("%27", "'")
                .replaceAll("%28", "(")
                .replaceAll("%29", ")")
                .replaceAll("%7E", "~");

        return result;
    }

    public static synchronized String readFile(String path) throws IOException {
        return readFile(Paths.get(path));
    }

    public static final String UTF8_BOM = "\uFEFF";

    public static synchronized String readFile(Path path) throws IOException {
        try {
            String result = Files.readString(path, StandardCharsets.UTF_8);
            if (result.startsWith(UTF8_BOM)) {
                result = result.substring(1);
            }
            return result;
        } catch (java.nio.charset.MalformedInputException e) {
            LOG.error("Malformed input (encoding issue) in file: {}", path, e);
            throw e;
        }
    }

    public static synchronized void writeFile(String path, String contents, boolean append) throws IOException {
        FileWriter fileWriter = new FileWriter(path, StandardCharsets.UTF_8, append);
        PrintWriter out = new PrintWriter(fileWriter);
        out.print(contents);
        out.close();
    }

    public static synchronized void writeFile(Path path, String contents, boolean append) throws IOException {
        FileWriter fileWriter = new FileWriter(path.toAbsolutePath().toString(), StandardCharsets.UTF_8, append);
        PrintWriter out = new PrintWriter(fileWriter);
        out.print(contents);
        out.close();
    }

    public static void writeFile(Path path, String contents) throws IOException {
        writeFile(path, contents, false);
    }
    public static void writeFile(String path, String contents) throws IOException {
        writeFile(path, contents, false);
    }

    public static void writeFileAtomic(String path, String contents, boolean append) throws IOException {
        final Path filePath = Path.of(path).toAbsolutePath();
        final String fileName = filePath.getFileName().toString();
        String randomUUID = UUID.randomUUID().toString();
        final Path tmpFilePath = filePath.getParent().resolve("." + randomUUID + "-" + fileName);

        if (append) {
            Files.write(tmpFilePath, List.of(contents.split("\n")), CHARSET_UTF8, StandardOpenOption.APPEND);
        }
        else {
            Files.write(tmpFilePath, List.of(contents.split("\n")), CHARSET_UTF8);
        }
        Files.move(tmpFilePath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }


    public static void writeFileAtomic(String path, String contents) throws IOException {
        writeFileAtomic(path, contents, false);
    }

    private static boolean isReadingSign(String substring) {
        List<String> readingSigns = new ArrayList<>();
        readingSigns.add(".");
        readingSigns.add(",");
        readingSigns.add(" ");
        readingSigns.add("/");
        readingSigns.add("'");
        readingSigns.add("\"");
        return readingSigns.contains(substring);
    }

    private static Random _random = null;

    private static Random getRandom() {
        if (_random == null) {
            _random = new Random();
        }
        return _random;
    }

    public static String makeNew(String text) {
        if (!Strings.isEmpty(text)) {
            text = text.replace("ﬅ", "st");
            text = text.replace("ﬄ", "ffl");
            text = text.replace("ﬃ", "ffi");
            text = text.replace("ﬂ", "fl");
            text = text.replace("æ", "ae");
            text = text.replace("Æ", "AE");
            text = text.replace("œ", "oe");
        }
        return text;
    }

    public static String makeOld(String text) {
        if (getRandom().nextDouble() > 0.5) {
            text = text.replace("st", "ﬅ");
            text = text.replace("ffl", "ﬄ");
            text = text.replace("ffi", "ﬃ");
            text = text.replace("fl", "ﬂ");
            text = text.replace("ae", "æ");
            text = text.replace("AE", "Æ");
            text = text.replace("oe", "œ");
        }

        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (text.startsWith("s", i)) {
                if (i == text.length() - 1) {
                    stringBuilder.append("s");
                } else if (isReadingSign(text.substring(i + 1, i + 2))) {
                    stringBuilder.append("s");
                } else {
                    if (getRandom().nextDouble() < 0.2) {
                        stringBuilder.append("s");
                    } else {
                        stringBuilder.append("ſ");
                    }
                }
            } else {
                stringBuilder.append(text, i, i + 1);
            }
        }
        return stringBuilder.toString();
    }

    public static Integer getInt(String source) {
        if (source == null) {
            return null;
        }
        try {
            return Integer.parseInt(source.trim());
        } catch (NumberFormatException ex) {
            // intentional: null sentinel signals "not an integer" to callers
            LOG.debug("getInt: '{}' is not an integer", source);
            return null;
        }
    }

    public static void splitPdf(String filepath, String outputPath, String baseOutputFilename) {

        try (PDDocument document = PDDocument.load(new File(filepath))) {

            // Instantiating Splitter class
            Splitter splitter = new Splitter();

            // splitting the pages of a PDF document
            List<PDDocument> Pages = splitter.split(document);

            // Creating an iterator
            Iterator<PDDocument> iterator = Pages.listIterator();

            if (!new File(outputPath).exists() && !new File(outputPath).mkdirs()) {
                LOG.error("unable to create directory: {}", outputPath);
            }

            if (!outputPath.endsWith("/")) {
                outputPath += "/";
            }

            // Saving each page as an individual document
            int i = 1;
            while (iterator.hasNext()) {
                PDDocument pd = iterator.next();
                pd.save(outputPath + baseOutputFilename + "_" + String.format("%07d", i) + ".pdf");
                i++;
            }

        } catch (IOException e) {
            LOG.error("Exception while trying to read pdf document", e);
        }
    }

    public static String splitPdfIntoImages(String filepath, String outputPath, String baseOutputFilename) {
        try (final PDDocument document = PDDocument.load(new File(filepath))) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);


            if (!new File(outputPath).exists() && !new File(outputPath).mkdirs()) {
                LOG.error("unable to create directory: {}", outputPath);
            }

            if (!outputPath.endsWith("/")) {
                outputPath += "/";
            }

            for (int page = 0; page < document.getNumberOfPages(); ++page) {
                BufferedImage bufferedImage = pdfRenderer.renderImageWithDPI(page, 300, ImageType.RGB);
                String fileName = outputPath + baseOutputFilename + "-" + String.format("%07d", page) + ".jpg";
                ImageIOUtil.writeImage(bufferedImage, fileName, 300, 0.90f);
            }
        } catch (IOException e) {
            LOG.error("Exception while trying to create pdf document", e);
            return "Exception while trying to create pdf document - " + e.getMessage();
        }
        return "";
    }

    public static int editDistance(String expected, String result) {
        return StringUtilities.damerauLevenshteinDistance(expected, result);
    }

    public static double characterErrorRate(String expected, String result) {
        if (expected.length() == 0) {
            return editDistance(expected, result);
        } else {

            return (double) editDistance(expected, result) / (double) expected.length();
        }
    }

    public static String cleanUri(String uri) {
        uri = uri.replaceAll("/[^A-Za-z0-9:/]/", "_");
        uri = uri.replace("'", "_");
        uri = uri.replace(" ", "_");
        return uri;
    }

    public static double wordErrorRate(String groundTruth, String result) {
        String[] splittedGroundTruth = groundTruth.split(" ");
        String[] splittedResult = result.split(" ");
        int matches = 0;
        for (int i = 0; i < splittedGroundTruth.length; i++) {
            for (int j = 0; j < splittedResult.length; j++) {
                if (splittedGroundTruth[i].equals(splittedResult[j])) {
                    matches++;
                    if (i == j) {
                        i++;
                    }
                }
            }
        }
        LOG.debug("Word matches: {}", matches);
        return 1 - (double) matches / (double) splittedGroundTruth.length;
    }

    public static Document convertStringToXMLDocument(String xmlString) {
        DocumentBuilderFactory factory;

        DocumentBuilder builder;
        try {
            factory = createSecureDocumentBuilderFactory();
            builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xmlString)));
        } catch (Exception e) {
            LOG.error("Could not convert XML string to document", e);
        }
        return null;
    }

}