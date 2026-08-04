package nl.knaw.huc.di.images.imageanalysiscommon;

import nl.knaw.huc.di.images.layoutds.models.DocumentPage;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * TEST-02 baseline smoke tests for {@link DocumentTypeConverter}.
 * <p>
 * Full conversion round-trips need realistic Page XML / ALTO / hOCR fixtures and a careful
 * design of equality predicates over OCR models; the original {@code //TODO RUTGERCHECK}
 * tracker entry already acknowledges this is a larger piece of work. These tests cover the
 * null-input contract and the trivially constructable conversions so future regressions in
 * those branches are caught immediately.
 */
public class DocumentTypeConverterTest {

    @Test
    public void pageToDocumentPage_nullPage_returnsNull() {
        assertNull(DocumentTypeConverter.pageToDocumentPage(null));
    }

    @Test
    public void documentPageToPage_nullDocumentPage_returnsNull() {
        assertNull(DocumentTypeConverter.documentPageToPage(null));
    }

    @Test
    public void documentPageToPage_emptyDocumentPage_yieldsPcGtsWithDefaultPage() {
        DocumentPage documentPage = new DocumentPage("test.jpg", new Date(), 200, 100);

        PcGts pcGts = DocumentTypeConverter.documentPageToPage(documentPage);
        assertNotNull("conversion must succeed for a freshly constructed DocumentPage", pcGts);
        assertNotNull("PcGts must always have a Page", pcGts.getPage());
        assertNotNull("PcGts must always have Metadata", pcGts.getMetadata());
    }

    @Test
    public void pageToDocumentPage_minimalPage_yieldsDocumentPage() {
        PcGts page = new PcGts();
        page.getPage().setImageFilename("scan_0001.jpg");
        page.getPage().setImageWidth(100);
        page.getPage().setImageHeight(200);

        DocumentPage documentPage = DocumentTypeConverter.pageToDocumentPage(page);
        assertNotNull("conversion from a minimal PcGts must succeed", documentPage);
    }
}




