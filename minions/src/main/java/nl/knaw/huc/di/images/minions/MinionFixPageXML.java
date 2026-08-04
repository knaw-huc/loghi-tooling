package nl.knaw.huc.di.images.minions;


import com.fasterxml.jackson.core.JsonProcessingException;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import nl.knaw.huc.di.images.layoutds.models.Page.TextRegion;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.io.File;
import java.nio.file.*;

/* this just reads and then writes the pagexml again. Fixing some small problems that exist in existing pagexml */
public class MinionFixPageXML {

    private static final Logger LOG = LoggerFactory.getLogger(MinionFixPageXML.class);
    public static String fixPageXml(String pageXmlString, String targetNameSpace, boolean removeText, boolean removeWords) throws JsonProcessingException, TransformerException {
        PcGts page = PageUtils.readPageFromString(pageXmlString);
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            if (removeText) {
                textRegion.setTextEquiv(null);
            }
            for (TextLine textLine : textRegion.getTextLines()) {
                if (removeText) {
                    textLine.setTextEquiv(null);
                }
                if (removeWords) {
                    textLine.setWords(null);
                }

            }
        }
        String newPageXml = PageUtils.convertPcGtsToString(page, targetNameSpace);
        return newPageXml;
    }

    private static void readPages(Path directory, boolean removeWords, boolean removeText, String targetNamespace) throws Exception {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path path : files) {
                File file = new File(path.toAbsolutePath().toString());
                if (file.isDirectory()) {
                    readPages(path, removeWords, removeText, targetNamespace);
                } else {

                    String filename = path.toAbsolutePath().toString();
                    LOG.info("{}", filename);
                    if (filename.endsWith(".xml")) {
                        String pageXmlString = StringTools.readFile(filename);
                        String newPageXml = fixPageXml(pageXmlString, targetNamespace, removeText, removeWords);
                        StringTools.writeFile(filename, newPageXml);
                    }
                }
            }
        } catch (AccessDeniedException adx) {
            LOG.warn("access denied: {}", directory.toAbsolutePath().toString());
        }
    }

    private static Options getOptions() {
        Options options = new Options();
        options.addOption("h", "input_path", true, "path to input pagexml files");
        options.addOption("r", "removetext", false, "remove text from the pagexml");
        options.addOption("w", "removewords", false, "remove words from the pagexml");
        options.addOption("n", "namespace", true, "target namespace: default is 2019, use for 2013: http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15");
        return options;
    }


    public static void main(String[] args) throws Exception {
        String inputXmls;
        String namespace = PageUtils.NAMESPACE2019;
        boolean removeWords = false;
        boolean removeText = false;

        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;
        try {
            commandLine = parser.parse(options, args);
        } catch (org.apache.commons.cli.ParseException e) {
            LOG.warn("{}", e.getMessage());
            return;
        }
        if (commandLine.hasOption("input_path")) {
            inputXmls = commandLine.getOptionValue("input_path");
        } else {
            LOG.warn("no input path given");
            return;
        }
        if (commandLine.hasOption("removetext")) {
            removeText = true;
        }
        if (commandLine.hasOption("removewords")) {
            removeWords = true;
        }
        if (commandLine.hasOption("namespace")) {
            namespace = commandLine.getOptionValue("namespace");
        }

        Path path = Paths.get(inputXmls);
        readPages(path, removeWords, removeText, namespace);
    }
}