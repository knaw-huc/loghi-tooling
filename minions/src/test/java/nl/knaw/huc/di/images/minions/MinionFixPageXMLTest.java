package nl.knaw.huc.di.images.minions;

import com.fasterxml.jackson.core.JsonProcessingException;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.transform.TransformerException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MinionFixPageXMLTest {

    @TempDir
    Path tempDir;

    private final String sampleXml2013 = "<PcGts xmlns=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15\">" +
            "<Page>" +
            "<TextRegion id=\"region_1\">" +
            "<TextLine id=\"line_1\">" +
            "<TextEquiv>Sample Text</TextEquiv>" +
            "<Word>Sample Word</Word>" +
            "</TextLine>" +
            "</TextRegion>" +
            "</Page>" +
            "</PcGts>";

    private final String sampleXml2019 = "<PcGts xmlns=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15\">" +
            "<Page>" +
            "<TextRegion id=\"region_1\">" +
            "<TextLine id=\"line_1\">" +
            "<TextEquiv>Sample Text</TextEquiv>" +
            "<Word>Sample Word</Word>" +
            "</TextLine>" +
            "</TextRegion>" +
            "</Page>" +
            "</PcGts>";

    @Test
    void testXSI2013to2013() throws JsonProcessingException, TransformerException {
        String stringToFind2013 ="PcGts xmlns=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15 http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15/pagecontent.xsd\"";
        String result = MinionFixPageXML.fixPageXml(sampleXml2013, PageUtils.NAMESPACE2013, false, false);
        assertTrue(result.contains(stringToFind2013));
    }

    @Test
    void testXSI2013to2019() throws JsonProcessingException, TransformerException {
        String stringToFind2019 ="PcGts xmlns=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15 http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15/pagecontent.xsd\"";
        String result2019 = MinionFixPageXML.fixPageXml(sampleXml2013, PageUtils.NAMESPACE2019, false, false);
        assertTrue(result2019.contains(stringToFind2019));

    }

    @Test
    void testXSI2019to2013() throws JsonProcessingException, TransformerException {
        String stringToFind2013 ="PcGts xmlns=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15 http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15/pagecontent.xsd\"";
        String result2019 = MinionFixPageXML.fixPageXml(sampleXml2019, PageUtils.NAMESPACE2013, false, false);
        assertTrue(result2019.contains(stringToFind2013));

    }

    @Test
    void testXSI2019to2019() throws JsonProcessingException, TransformerException {
        String stringToFind2019 ="PcGts xmlns=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15 http://schema.primaresearch.org/PAGE/gts/pagecontent/2019-07-15/pagecontent.xsd\"";
        String result2019 = MinionFixPageXML.fixPageXml(sampleXml2019, PageUtils.NAMESPACE2019, false, false);
        assertTrue(result2019.contains(stringToFind2019));

    }

}
