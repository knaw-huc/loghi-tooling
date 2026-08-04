package nl.knaw.huc.di.images.imageanalysiscommon;


import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class ImageMetaDataReader {


    private static final Logger LOG = LoggerFactory.getLogger(ImageMetaDataReader.class);
    public static void main(String[] args) throws ImageProcessingException, IOException {
        readMetaData();
    }

    public static void readMetaData() throws ImageProcessingException, IOException {
        Metadata metadata = ImageMetadataReader.readMetadata(new File("/data/2.05.31/1/NL-HaNA_2.05.31_1_0080.jpg"));

        String createDate = null;
        String lat = null;
        String lon = null;
        for (Directory directory : metadata.getDirectories()) {
            for (Tag tag : directory.getTags()) {
                String tagName = tag.getTagName();
                String desc = tag.getDescription();
                LOG.info("{}", tagName);
                LOG.info("{}", desc);
                LOG.info("");
                switch (tagName) {
                    case "Date/Time Original":
                        createDate = desc.split(" ")[0].replace(":", "-");
                        break;
                    case "GPS Latitude":
                        lat = desc;
                        break;
                    case "GPS Longitude":
                        lon = desc;
                        break;
                }
            }
        }
    }
}
