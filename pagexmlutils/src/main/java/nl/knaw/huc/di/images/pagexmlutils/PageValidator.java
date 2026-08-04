package nl.knaw.huc.di.images.pagexmlutils;


import org.primaresearch.dla.page.io.FileInput;
import org.primaresearch.dla.page.io.InputSource;
import org.primaresearch.dla.page.io.StringInput;
import org.primaresearch.dla.page.io.xml.PageErrorHandler;
import org.primaresearch.dla.page.io.xml.PageXmlInputOutput;
import org.primaresearch.dla.page.io.xml.XmlPageReader;
import org.primaresearch.io.UnsupportedFormatVersionException;
import org.primaresearch.io.xml.XmlValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class PageValidator {
    private static final Logger LOG = LoggerFactory.getLogger(PageValidator.class);
    public static XmlPageReader validate(String input) {
        return validate("localString", new StringInput(input));
    }

    public static XmlPageReader validate(String identifier, InputSource inputSource) {
        XmlPageReader reader = PageXmlInputOutput.getReader();
        try {
            reader.read(inputSource);
        } catch (UnsupportedFormatVersionException e) {
            LOG.error("Unexpected error", e);
        }
        PageErrorHandler pageErrorHandler = reader.getLastErrors();
//        List<IOError> warnings = reader.getWarnings();
//        System.out.println("errors: " + pageErrorHandler.getErrors().size());
//        System.out.println("warnings: " + pageErrorHandler.getWarnings().size());
        for (int i = 0; i < pageErrorHandler.getErrors().size(); i++) {
            XmlValidationError error = (XmlValidationError) pageErrorHandler.getErrors().get(i);
            LOG.warn("{}: {}: {}", identifier, error.getLocation(), error.getMessage());
        }
        return reader;
    }

    public static void validate(File file) {
        if (file.isDirectory()) {
            for (File subFile : file.listFiles()) {
                validate(subFile);
            }
        } else {
            if (file.getName().endsWith(".xml")) {
                FileInput fileInput = new FileInput(file);
                LOG.info("validating file: {}", file.getName());
                validate(fileInput.getFile().getAbsoluteFile().toString(), fileInput);
            }
        }

    }

    public static void main(String[] args) {
        if (args.length > 0) {
            File file = new File(args[0]);
            if (!file.exists()) {
                LOG.warn("file does not exist");
                return;
            }
            validate(file);
        } else {
            LOG.warn("Please provide a directory containing pagexml or a pagexml file");
        }
    }
}
