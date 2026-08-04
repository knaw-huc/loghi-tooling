package nl.knaw.huc.di.images.minions;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import nl.knaw.huc.di.images.altoxmlutils.AltoUtils;
import nl.knaw.huc.di.images.imageanalysiscommon.DocumentTypeConverter;
import nl.knaw.huc.di.images.layoutds.models.Alto.AltoDocument;
import nl.knaw.huc.di.images.layoutds.models.DocumentPage;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;



public class MinionConvertOCRResult extends BaseMinion {
    private static final Logger LOG = LoggerFactory.getLogger(MinionConvertOCRResult.class);
    private static final XmlMapper XML_MAPPER = new XmlMapper();

    /**
     * Resolves ALTO/XLink schema imports from the classpath.
     *
     * The ALTO XSD typically uses <xs:import ... schemaLocation="xlink.xsd"/>.
     * When validating, the JAXP SchemaFactory won't automatically map that import
     * to a classpath resource unless we provide a resolver.
     */
    private static final class ClasspathSchemaResolver implements LSResourceResolver {

        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
            String resourceName = null;

            if (systemId != null) {
                // Keep this robust for both "xlink.xsd" and URLs ending in that filename.
                if (systemId.endsWith("alto.xsd")) {
                    resourceName = "alto.xsd";
                } else if (systemId.endsWith("xlink.xsd")) {
                    resourceName = "xlink.xsd";
                }
            }

            // Some resolvers may query by namespace without a helpful systemId.
            if (resourceName == null && namespaceURI != null) {
                if (namespaceURI.contains("xlink")) {
                    resourceName = "xlink.xsd";
                }
            }

            if (resourceName == null) {
                return null;
            }

            InputStream in = MinionConvertOCRResult.class.getResourceAsStream("/" + resourceName);
            if (in == null) {
                return null;
            }

            return new SimpleLSInput(publicId, systemId, in);
        }

        private static final class SimpleLSInput implements LSInput {
            private String publicId;
            private String systemId;
            private InputStream byteStream;

            private SimpleLSInput(String publicId, String systemId, InputStream byteStream) {
                this.publicId = publicId;
                this.systemId = systemId;
                this.byteStream = byteStream;
            }

            @Override
            public Reader getCharacterStream() {
                return null;
            }

            @Override
            public void setCharacterStream(Reader characterStream) {
                // not used
            }

            @Override
            public InputStream getByteStream() {
                return byteStream;
            }

            @Override
            public void setByteStream(InputStream byteStream) {
                this.byteStream = byteStream;
            }

            @Override
            public String getStringData() {
                return null;
            }

            @Override
            public void setStringData(String stringData) {
                // not used
            }

            @Override
            public String getSystemId() {
                return systemId;
            }

            @Override
            public void setSystemId(String systemId) {
                this.systemId = systemId;
            }

            @Override
            public String getPublicId() {
                return publicId;
            }

            @Override
            public void setPublicId(String publicId) {
                this.publicId = publicId;
            }

            @Override
            public String getBaseURI() {
                return null;
            }

            @Override
            public void setBaseURI(String baseURI) {
                // not used
            }

            @Override
            public String getEncoding() {
                return null;
            }

            @Override
            public void setEncoding(String encoding) {
                // not used
            }

            @Override
            public boolean getCertifiedText() {
                return false;
            }

            @Override
            public void setCertifiedText(boolean certifiedText) {
                // not used
            }
        }
    }

    private static boolean validateAltoXML(String xml) {
        try {
            URL altoXsd = MinionConvertOCRResult.class.getResource("/alto.xsd");
            URL xlinkXsd = MinionConvertOCRResult.class.getResource("/xlink.xsd");

            if (altoXsd == null) {
                LOG.error("ALTO XSD not found on classpath: `alto.xsd`");
                return false;
            }
            if (xlinkXsd == null) {
                LOG.error("XLink XSD not found on classpath: `xlink.xsd` (required for xlink:simpleLink)");
                return false;
            }

            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setResourceResolver(new ClasspathSchemaResolver());

            // Compile starting from ALTO. Imports like xlink.xsd are resolved via the resolver.
            Schema schema;
            try (InputStream alto = MinionConvertOCRResult.class.getResourceAsStream("/alto.xsd")) {
                if (alto == null) {
                    LOG.error("ALTO XSD not found on classpath: `alto.xsd`");
                    return false;
                }
                schema = schemaFactory.newSchema(new StreamSource(alto, altoXsd.toExternalForm()));
            }

            Validator validator = schema.newValidator();
            validator.validate(new StreamSource(new StringReader(xml)));

            LOG.info("ALTO XML is valid.");
            return true;
        } catch (SAXException | IOException e) {
            LOG.error("ALTO XML validation error: {}", e.getMessage());
            return false;
        }
    }

    public static String detectFileFormatContents(String content) {
        if (content == null || content.isEmpty()) {
            LOG.error("Content is empty or null");
            return null;
        }
        if (content.startsWith("<?xml")) {
//            remove first line if it is an XML declaration
            content = content.replaceFirst("(?i)^<\\?xml.*?\\?>", "").trim();
        } else if (content.startsWith("<!DOCTYPE")) {
            // remove DOCTYPE declaration
            content = content.replaceFirst("(?i)^<!DOCTYPE.*?>", "").trim();
        } else if (content.startsWith("<")) {
            // If it starts with a tag, we assume it's XML
            content = content.trim();
        } else {
            LOG.error("Content does not start with XML declaration or tag");
            return null;
        }

        if (content.contains("<PcGts")) {
            if (content.contains("http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15")) {
                return "pagexml2013";
            } else if (content.contains("http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15")) {
                return "pagexml2019";
            } else {
                LOG.error("Unknown PageXML version in content");
                return null;
            }
        } else if (content.startsWith("<alto")) {
            return "alto";
        } else if (content.contains("<html") && content.contains("class=\"ocr\"")) {
            return "hocr";
        }
        return null;
    }

    public static String detectFileFormat(String filename) throws IOException {
        if (filename.toLowerCase().endsWith(".xml")) {
            String content = StringTools.readFile(filename);
            return detectFileFormatContents(content);
        } else if (filename.toLowerCase().endsWith("hocr")) {
            return "hocr";
        } else {
            LOG.error("Unknown file format for: {}", filename);
            return null;
        }
    }

    public static void main(String[] args) throws IOException {
        CommandLineParser parser = new DefaultParser();

        Options options = new Options();
        options.addOption("i", "inputpath", true, "Input directory");
        options.addOption("o", "outputpath", true, "Output directory");
        options.addOption("t", "targetformat", true, "Target format (PageXML2013, PageXML2019, hOCR, ALTO)");
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            LOG.error("Error parsing arguments: {}", e.getMessage());
            new HelpFormatter().printHelp("MinionConvertOCRResult", options);
            return;
        }

        String inputPath = cmd.getOptionValue("inputpath", "/scratch/altotest/");
        String outputPath = cmd.getOptionValue("outputpath", "/tmp/limited/");
        String targetFormat = cmd.getOptionValue("targetformat", "PageXML2013").toLowerCase(Locale.ROOT);


//        For each file in the input directory detect if it is a PageXML 2013, PageXML 2019, hOCR, or ALTO file
        File inputDir;
        File[] files;
        switch (targetFormat) {
            case "pagexml2013":
                // Generate PageXML 2013 output
                // String pageXml2013 = ...;
                // StringTools.writeFile(outputFile, pageXml2013);

                break;
            case "pagexml2019":
                // Generate PageXML 2019 output
                inputDir = new File(inputPath);
                files = inputDir.listFiles();
                if (files == null) {
                    LOG.error("Input path does not exist or is not a directory: {}", inputPath);
                    return;
                }
                for (File file : files) {
                    if (file.isFile()) {
                        String sourceFormat = detectFileFormat(file.getAbsolutePath());
                        if (sourceFormat == null) {
                            LOG.warn("Skipping file with unknown format: {}", file.getAbsolutePath());
                        } else if (sourceFormat.equals("pagexml2013")) {
//                            String pageXml = StringTools.readFile(file.getAbsolutePath());
//                            PcGts page = Generator.convertPageXml2013ToPageXml2019(pageXml);
//                            XmlMapper mapper = new XmlMapper();
//                            String pageXml2019 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(page);
//                            String outputFile = outputPath + file.getName();
//                            if (!Files.exists(Paths.get(outputPath))) {
//                                Files.createDirectories(Paths.get(outputPath));
//                            }
//                            StringTools.writeFile(outputFile, pageXml2019);
                        } else if (sourceFormat.equals("alto")) {
                            // Convert ALTO to PageXML 2019
                            LOG.info("Converting ALTO to PageXML 2019: {}", file.getAbsolutePath());
                            String altoXml = StringTools.readFile(file.getAbsolutePath());
                            AltoDocument altoDocument = AltoUtils.readAltoDocumentFromString(altoXml);
                            if (altoDocument == null) {
                                LOG.error("Failed to parse ALTO document: {}", file.getAbsolutePath());
                                continue;
                            }
                            DocumentPage documentPage = DocumentTypeConverter.altoDocumentToDocumentPage(altoDocument);
                            PcGts page = DocumentTypeConverter.documentPageToPage(documentPage);
                            String pageXml2019 = XML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(page);
                            String outputFile = outputPath + "/" + file.getName();
                            Path path = Paths.get(outputPath);
                            if (!Files.exists(path)) {
                                Files.createDirectories(path);
                            }
                            StringTools.writeFile(outputFile, pageXml2019);
                        } else {
                            LOG.error("Unsupported source format: {}", sourceFormat);
                        }
                    }
                }
                break;
            case "hocr":
                // Generate hOCR output
                // String hocr = ...;
                // StringTools.writeFile(outputFile, hocr);
                break;
            case "alto":
                // Generate ALTO output
                inputDir = new File(inputPath);
                files = inputDir.listFiles();
                if (files == null) {
                    LOG.error("Input path does not exist or is not a directory: {}", inputPath);
                    return;
                }
                for (File file : files) {
                    if (file.isFile()) {
                        String sourceFormat = detectFileFormat(file.getAbsolutePath());
                        if (sourceFormat == null) {
                            LOG.warn("Skipping file with unknown format: {}", file.getAbsolutePath());
                        } else if (sourceFormat.equals("pagexml2013")) {
                            String pageXml = StringTools.readFile(file.getAbsolutePath());
                            PcGts page = PageUtils.readPageFromString(pageXml);
                            DocumentPage documentPage = DocumentTypeConverter.pageToDocumentPage(page);
                            AltoDocument altoDocument = DocumentTypeConverter.documentPageToAlto(documentPage);

//                            Write as altoXML
                            String altoXml = XML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(altoDocument);
                            if (validateAltoXML(altoXml)) {
                                String outputFile = outputPath + "/" + file.getName();
                                Path path = Paths.get(outputPath);
                                if (!Files.exists(path)) {
                                    Files.createDirectories(path);
                                }
                                StringTools.writeFile(outputFile, altoXml);
                            }

                        } else {
                            LOG.error("Unsupported source format: {}", sourceFormat);
                        }
                    }
                }

//                StringTools.writeFile(outputFileAlto, altoXmlB);
                break;
            default:
                LOG.error("Unknown format: {}", targetFormat);
                break;
        }
    }
}
