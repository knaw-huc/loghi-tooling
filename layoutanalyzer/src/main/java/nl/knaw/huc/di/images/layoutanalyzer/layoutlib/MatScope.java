package nl.knaw.huc.di.images.layoutanalyzer.layoutlib;

import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Try-with-resources helper that tracks OpenCV {@link Mat} allocations and
 * releases every tracked {@code Mat} when {@link #close()} is invoked.
 * <p>
 * Native {@code Mat} buffers live off the JVM heap and are not reclaimed by
 * the garbage collector. Forgetting to call {@link Mat#release()} on every
 * allocation leaks memory permanently, which is especially harmful in
 * long-running batch jobs and web-service workers that process thousands of
 * pages.
 * <p>
 * Usage:
 * <pre>{@code
 * try (MatScope scope = MatScope.open()) {
 *     Mat image  = scope.track(OpenCVWrapper.imread(path));
 *     Mat gray   = scope.track(OpenCVWrapper.newMat());
 *     Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
 *     // ... use image / gray ...
 * } // image and gray are released here in reverse order
 * }</pre>
 * <p>
 * Implementation notes:
 * <ul>
 *   <li>Mats are released in LIFO order — newest allocations first.</li>
 *   <li>{@code null} and already-released Mats are ignored.</li>
 *   <li>Exceptions thrown from {@link Mat#release()} are caught and logged so
 *       that subsequent allocations are still released — leaking a Mat because
 *       of a stray exception during cleanup would defeat the purpose.</li>
 *   <li>Use {@link #release(Mat)} to release a tracked Mat early; this also
 *       removes it from the tracking deque.</li>
 * </ul>
 * <p>
 * This class is <em>not</em> thread-safe: instances should be confined to a
 * single thread (typically a per-task scope).
 */
public final class MatScope implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MatScope.class);

    private final Deque<Mat> mats = new ArrayDeque<>();
    private boolean closed = false;

    private MatScope() {
    }

    /**
     * Opens a fresh, empty scope.
     */
    public static MatScope open() {
        return new MatScope();
    }

    /**
     * Registers a Mat to be released when this scope is closed.
     *
     * @param mat the Mat to track (may be {@code null}; ignored if so)
     * @return the same Mat for fluent inline use
     */
    public Mat track(Mat mat) {
        if (closed) {
            throw new IllegalStateException("Cannot track Mat in a closed MatScope.");
        }
        if (mat != null) {
            mats.push(mat);
        }
        return mat;
    }

    /**
     * Releases a previously-tracked Mat early and removes it from the scope so
     * {@link #close()} will not attempt a double release.
     */
    public void release(Mat mat) {
        if (mat == null) {
            return;
        }
        mats.remove(mat);
        if (mat.dataAddr() != 0) {
            mat.release();
        }
    }

    /**
     * Number of currently-tracked Mats (useful in tests).
     */
    public int size() {
        return mats.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        while (!mats.isEmpty()) {
            Mat mat = mats.pop();
            try {
                if (mat != null && mat.dataAddr() != 0) {
                    mat.release();
                }
            } catch (Exception ex) {
                LOG.warn("Exception while releasing tracked Mat", ex);
            }
        }
    }
}

