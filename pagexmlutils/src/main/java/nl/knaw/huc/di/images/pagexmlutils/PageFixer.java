package nl.knaw.huc.di.images.pagexmlutils;


import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import org.primaresearch.dla.page.io.FileInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.IOException;

public class PageFixer {

    private static final Logger LOG = LoggerFactory.getLogger(PageFixer.class);
    public static void fix(File file, String namespace) throws IOException, TransformerException {
        if (file.isDirectory()) {
            for (File subFile : file.listFiles()) {
                fix(subFile, namespace);
            }
        } else {
            if (file.getName().endsWith(".xml")) {
                FileInput fileInput = new FileInput(file);
                LOG.info("fixing file: {}", file.getName());
                PcGts page = PageUtils.readPageFromFile(file.toPath());
                PageUtils.writePageToFile(page, namespace, file.toPath());
            }
        }

    }

    public static String fix(String pageString, String namespace) throws IOException, TransformerException {
        PcGts page = PageUtils.readPageFromString(pageString);
        return PageUtils.convertAndValidate(page, namespace);
    }

    public static void main(String[] args) throws IOException, TransformerException {
        if (args.length > 0) {
            File file = new File(args[0]);
            if (!file.exists()) {
                LOG.warn("file does not exist");
                return;
            }
            String namespace = PageUtils.NAMESPACE2019;
            if (args.length > 1) {
                namespace = args[1];
            }

            fix(file, namespace);
        } else {
            LOG.warn("Please provide a directory containing pagexml or a pagexml file");
        }
    }
}
