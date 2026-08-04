import nl.knaw.huc.di.images.layoutanalyzer.DocumentPage;
import nl.knaw.huc.di.images.layoutanalyzer.LayoutConfiguration;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OpenCvMatLeakTest {

    @BeforeClass
    public static void loadOpenCv() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    @Test
    public void closeReleasesDocumentPageCachedMats() throws Exception {
        Mat image = Mat.ones(64, 64, CvType.CV_8UC1);
        // DocumentPage reads parameters from the global LayoutConfiguration.
        // Without this, getGlobal() throws IllegalStateException (previously
        // System.exit'd, crashing the surefire forked VM).
        LayoutConfiguration.setGlobal(new LayoutConfiguration(image));
        DocumentPage page = new DocumentPage(image, "test");

        page.getGrayImage();
        page.getBinaryImage();
        page.getBinaryOtsu();
        page.getOtsuProfileHorizontal();

        page.close();

        assertMatFieldCleared(page, "grayImage");
        assertMatFieldCleared(page, "binaryImage");
        assertMatFieldCleared(page, "binaryOtsu");
        assertMatFieldCleared(page, "binaryImageCrude");
        assertMatFieldCleared(page, "despeckledImage");

        image.release();
        assertTrue("Source image should be releasable", image.dataAddr() == 0);
    }

    @Test
    public void repeatedMatAllocationsAreReleased() {
        List<Mat> mats = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            mats.add(new Mat(128, 128, CvType.CV_8UC1));
        }

        for (Mat mat : mats) {
            mat.release();
            assertTrue("Allocated Mat should be released", mat.dataAddr() == 0);
        }
    }

    private static void assertMatFieldCleared(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(target);
        assertNull("Expected field to be cleared: " + fieldName, value);
    }
}

