package nl.knaw.huc.di.images.imageanalysiscommon;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import static org.junit.Assert.assertEquals;

public class PoolerTest {

    @BeforeClass
    public static void loadNative() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    @Test
    public void maxPoolReturnsWindowMaximum() {
        Mat m = new Mat(2, 2, CvType.CV_8UC1);
        m.put(0, 0, 10);
        m.put(0, 1, 250);
        m.put(1, 0, 30);
        m.put(1, 1, 40);
        Mat out = Pooler.maxPool(m);
        assertEquals(250.0, out.get(0, 0)[0], 0.0001);
        out.release();
        m.release();
    }

    @Test
    public void minPoolReturnsWindowMinimum() {
        Mat m = new Mat(2, 2, CvType.CV_8UC1);
        m.put(0, 0, 10);
        m.put(0, 1, 250);
        m.put(1, 0, 30);
        m.put(1, 1, 40);
        Mat out = Pooler.minPool(m);
        assertEquals(10.0, out.get(0, 0)[0], 0.0001);
        out.release();
        m.release();
    }
}

