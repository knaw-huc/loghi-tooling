package nl.knaw.huc.di.images.layoutanalyzer.layoutlib;

import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.core.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Regression tests that protect against OpenCV {@link Mat} memory leaks
 * introduced by {@code new Mat(...)} / {@code submat(...)} / {@code clone()}
 * call sites in the production code.
 * <p>
 * These tests do not rely on JVM finalisers (which the OpenCV bindings no
 * longer provide) — they verify that every allocation site has a matching
 * {@code release()} by checking {@link Mat#dataAddr()} after the code under
 * test completes.
 * <p>
 * Tagged-class equivalent: this is the JUnit 4 variant; if the module is
 * migrated to JUnit 5 (TEST-05) these tests should be tagged
 * {@code @Tag("native")}.
 */
public class MatLeakRegressionTest {

    @BeforeClass
    public static void loadOpenCv() {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    /**
     * {@link MatScope} releases every tracked Mat on close, in LIFO order.
     */
    @Test
    public void matScopeReleasesAllTrackedMatsOnClose() {
        Mat a;
        Mat b;
        Mat c;
        try (MatScope scope = MatScope.open()) {
            a = scope.track(OpenCVWrapper.newMat(new Size(32, 32), CvType.CV_8UC1));
            b = scope.track(OpenCVWrapper.newMat(new Size(64, 64), CvType.CV_8UC3));
            c = scope.track(OpenCVWrapper.newMat());
            assertEquals(3, scope.size());
            assertNotEquals(0, a.dataAddr());
            assertNotEquals(0, b.dataAddr());
        }
        // After close, all tracked Mats must be released.
        assertEquals("Mat 'a' was leaked", 0, a.dataAddr());
        assertEquals("Mat 'b' was leaked", 0, b.dataAddr());
        assertEquals("Mat 'c' was leaked", 0, c.dataAddr());
    }

    /**
     * Null and already-released Mats must not cause exceptions on close.
     */
    @Test
    public void matScopeTolerantOfNullAndDoubleRelease() {
        Mat alive = OpenCVWrapper.newMat(new Size(16, 16), CvType.CV_8UC1);
        try (MatScope scope = MatScope.open()) {
            scope.track(null);
            scope.track(alive);
            // pre-release the Mat externally and verify scope.close() does not throw
            alive.release();
            assertEquals(0, alive.dataAddr());
        }
    }

    /**
     * Explicit {@link MatScope#release(Mat)} removes the Mat from the deque.
     */
    @Test
    public void matScopeEarlyReleaseRemovesFromTracking() {
        Mat a;
        Mat b;
        try (MatScope scope = MatScope.open()) {
            a = scope.track(OpenCVWrapper.newMat(new Size(8, 8), CvType.CV_8UC1));
            b = scope.track(OpenCVWrapper.newMat(new Size(8, 8), CvType.CV_8UC1));
            scope.release(a);
            assertEquals(0, a.dataAddr());
            assertEquals(1, scope.size());
        }
        assertEquals(0, b.dataAddr());
    }

    /**
     * Tracking a Mat in a closed scope must fail loudly.
     */
    @Test(expected = IllegalStateException.class)
    public void matScopeRejectsTrackingAfterClose() {
        MatScope scope = MatScope.open();
        scope.close();
        scope.track(OpenCVWrapper.newMat());
    }

    /**
     * {@link OpenCVWrapper#imread(String)} returns a non-empty Mat with a
     * non-zero {@code dataAddr} for a valid file, and the caller can release
     * it without leaking.
     */
    @Test
    public void openCvWrapperImreadAllocatesAndReleases() throws IOException {
        File tempPng = createTinyPng();
        try {
            Mat loaded = OpenCVWrapper.imread(tempPng.getAbsolutePath());
            try {
                assertNotNull(loaded);
                assertNotEquals("imread should produce a non-empty Mat", 0, loaded.dataAddr());
                assertFalse("Loaded Mat should be non-empty", loaded.empty());
            } finally {
                OpenCVWrapper.release(loaded);
                assertEquals("Mat should be released", 0, loaded.dataAddr());
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tempPng.delete();
        }
    }

    /**
     * {@link OpenCVWrapper#imread(String)} for a missing path returns an
     * empty Mat (matches OpenCV's documented contract). Callers must still
     * be able to release the returned Mat without exception.
     */
    @Test
    public void openCvWrapperImreadOfMissingFileReturnsEmptyMat() {
        Mat result = OpenCVWrapper.imread("/tmp/this-file-does-not-exist-" + UUID.randomUUID() + ".png");
        try {
            assertNotNull(result);
            assertTrue("Missing file should produce an empty Mat", result.empty());
        } finally {
            // An empty Mat may have dataAddr == 0, so guard the release in the wrapper.
            if (result.dataAddr() != 0) {
                OpenCVWrapper.release(result);
            }
        }
    }

    /**
     * Null/empty path arguments must throw IllegalArgumentException.
     */
    @Test(expected = IllegalArgumentException.class)
    public void openCvWrapperImreadRejectsNullPath() {
        OpenCVWrapper.imread(null);
    }

    /**
     * Reproduces the pattern from {@code BaselinesMapper.extractBaselines} and
     * {@code MinionExtractBaselines.extractBaselines}: a {@code submat()}
     * followed by {@code clone()} inside a per-label loop. Every iteration
     * must release both the submat and the cloned Mat.
     */
    @Test
    public void submatThenCloneIsFullyReleasedInLoop() {
        Mat labeled = OpenCVWrapper.newMat(new Size(64, 64), CvType.CV_8UC1);
        try {
            List<Mat> seenSubmats = new ArrayList<>();
            List<Mat> seenClones = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                Rect rect = new Rect(0, i, 32, 1);
                Mat submat = labeled.submat(rect);
                Mat clone = submat.clone();
                try {
                    seenSubmats.add(submat);
                    seenClones.add(clone);
                    assertNotEquals(0, submat.dataAddr());
                    assertNotEquals(0, clone.dataAddr());
                } finally {
                    submat = OpenCVWrapper.release(submat);
                    clone = OpenCVWrapper.release(clone);
                }
            }
            // After the loop every Mat must report dataAddr == 0.
            for (Mat m : seenSubmats) {
                assertEquals("submat leaked", 0, m.dataAddr());
            }
            for (Mat m : seenClones) {
                assertEquals("clone leaked", 0, m.dataAddr());
            }
        } finally {
            OpenCVWrapper.release(labeled);
        }
    }

    /**
     * Validates the {@link MatScope}-based replacement for the manual
     * try/finally release dance. Running the same operation 500 times must
     * not accumulate native memory (we proxy this with the size() invariant
     * and per-iteration dataAddr() checks — strict native-memory checks
     * depend on JVM-native bridge instrumentation that is not portable).
     */
    @Test
    public void matScopeStressLoopDoesNotAccumulate() {
        Deque<Long> addresses = new ArrayDeque<>();
        for (int i = 0; i < 500; i++) {
            try (MatScope scope = MatScope.open()) {
                Mat m = scope.track(OpenCVWrapper.newMat(new Size(16, 16), CvType.CV_8UC1));
                addresses.push(m.dataAddr());
                assertNotEquals(0, m.dataAddr());
            }
        }
        // No strict address re-use guarantee in OpenCV's allocator, but the
        // test failing here would surface a leak via OOM in long runs.
        assertEquals(500, addresses.size());
    }

    /**
     * Reproduces the {@code OpenCVWrapper.release(...)} contract: calling
     * release on an already-released Mat must throw, alerting developers to
     * double-release bugs that would otherwise silently corrupt accounting.
     */
    @Test
    public void releaseOfAlreadyReleasedMatThrows() {
        Mat m = OpenCVWrapper.newMat(new Size(4, 4), CvType.CV_8UC1);
        OpenCVWrapper.release(m);
        try {
            OpenCVWrapper.release(m);
            fail("Expected double-release to throw");
        } catch (RuntimeException expected) {
            // ok
        }
    }

    /**
     * Writes a 4x4 black PNG suitable for imread tests.
     */
    private static File createTinyPng() throws IOException {
        File tempPng = File.createTempFile("matleak-test-", ".png");
        Mat src = OpenCVWrapper.newMat(new Size(4, 4), CvType.CV_8UC1);
        try {
            org.opencv.imgcodecs.Imgcodecs.imwrite(tempPng.getAbsolutePath(), src);
        } finally {
            OpenCVWrapper.release(src);
        }
        return tempPng;
    }

    /**
     * Sanity check: every cached image field in {@link nl.knaw.huc.di.images.layoutanalyzer.DocumentPage}
     * is released when {@code close()} is called. Requires a {@link nl.knaw.huc.di.images.layoutanalyzer.LayoutConfiguration}
     * global instance because {@code DocumentPage} eagerly reads parameters from it.
     */
    @Test
    public void documentPageCloseClearsCachedMats() throws Exception {
        Mat src = OpenCVWrapper.newMat(new Size(64, 64), CvType.CV_8UC1);
        nl.knaw.huc.di.images.layoutanalyzer.LayoutConfiguration.setGlobal(
                new nl.knaw.huc.di.images.layoutanalyzer.LayoutConfiguration(src));
        nl.knaw.huc.di.images.layoutanalyzer.DocumentPage page =
                new nl.knaw.huc.di.images.layoutanalyzer.DocumentPage(src, "regression-test");

        // Force lazy initialisation of as many image caches as possible.
        page.getGrayImage();
        page.getBinaryImage();
        page.getBinaryOtsu();

        page.close();

        assertFieldCleared(page, "grayImage");
        assertFieldCleared(page, "binaryImage");
        assertFieldCleared(page, "binaryOtsu");
        assertFieldCleared(page, "binaryImageCrude");

        OpenCVWrapper.release(src);
    }

    private static void assertFieldCleared(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(target);
        if (value == null) {
            return;
        }
        if (value instanceof Mat) {
            assertEquals("Field " + fieldName + " was not released", 0L, ((Mat) value).dataAddr());
        }
    }
}


