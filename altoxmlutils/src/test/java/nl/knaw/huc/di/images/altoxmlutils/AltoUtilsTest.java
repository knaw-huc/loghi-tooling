package nl.knaw.huc.di.images.altoxmlutils;

import nl.knaw.huc.di.images.layoutds.models.Alto.AltoDocument;
import nl.knaw.huc.di.images.layoutds.models.Alto.MeasurementUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class AltoUtilsTest {

    private static final String ALTO =
            "<alto>" +
            "  <Description>" +
            "    <MeasurementUnit>inch</MeasurementUnit>" +
            "    <sourceImageInformation><fileName>test.jpg</fileName></sourceImageInformation>" +
            "  </Description>" +
            "  <Layout>" +
            "    <Page ID=\"page1\" HEIGHT=\"100\" WIDTH=\"200\">" +
            "      <PrintSpace ID=\"ps1\" HPOS=\"0\" VPOS=\"0\" WIDTH=\"200\" HEIGHT=\"100\">" +
            "        <TextBlock ID=\"block1\" HPOS=\"1\" VPOS=\"2\" WIDTH=\"50\" HEIGHT=\"10\">" +
            "          <TextLine ID=\"line1\" HPOS=\"1\" VPOS=\"2\" WIDTH=\"50\" HEIGHT=\"10\">" +
            "            <String ID=\"s1\" CONTENT=\"Hello\" WC=\"0.9\" HPOS=\"1\" VPOS=\"2\" WIDTH=\"20\" HEIGHT=\"10\"/>" +
            "          </TextLine>" +
            "        </TextBlock>" +
            "      </PrintSpace>" +
            "    </Page>" +
            "  </Layout>" +
            "</alto>";

    private AltoDocument document;

    @Before
    public void setUp() {
        document = AltoUtils.readAltoDocumentFromString(ALTO);
    }

    @Test public void invalidXmlReturnsNull() {
        Assert.assertNull(AltoUtils.readAltoDocumentFromString("this is not xml <<"));
    }
    @Test public void validXmlIsParsed() {
        Assert.assertNotNull(document);
    }
    @Test public void measurementUnitIsInch() {
        Assert.assertEquals(MeasurementUnit.inch, document.getDescription().getMeasurementUnit());
    }
    @Test public void sourceImageFileName() {
        Assert.assertEquals("test.jpg",
                document.getDescription().getSourceImageInformation().getFileName());
    }
    @Test public void pageDimensions() {
        Assert.assertEquals(Integer.valueOf(100), document.getLayout().getPage().getHeight());
        Assert.assertEquals(Integer.valueOf(200), document.getLayout().getPage().getWidth());
    }
    @Test public void pageId() {
        Assert.assertEquals("page1", document.getLayout().getPage().getId());
    }
    @Test public void printSpaceWidthAndOneBlock() {
        Assert.assertEquals(200, document.getLayout().getPage().getPrintSpace().getWidth());
        Assert.assertEquals(1, document.getLayout().getPage().getPrintSpace().getPrintSpaceBlocks().size());
    }
    @Test public void textBlockHasOneTextLine() {
        Assert.assertEquals(1, document.getLayout().getPage().getPrintSpace()
                .getPrintSpaceBlocks().get(0).getTextLines().size());
    }
}