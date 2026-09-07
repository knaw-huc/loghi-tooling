import nl.knaw.huc.di.images.layoutanalyzer.Statistics;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StatisticsCoverageTest {

    @Test public void variance_124() {
        Statistics s = new Statistics(Arrays.asList(1d, 1d, 4d));
        Assert.assertEquals(2.0, s.getVariance(), 0.0001);
    }
    @Test public void stdDev_124() {
        Statistics s = new Statistics(Arrays.asList(1d, 1d, 4d));
        Assert.assertEquals(Math.sqrt(2.0), s.getStdDev(), 0.0001);
    }
    @Test public void minimum() {
        Statistics s = new Statistics(Arrays.asList(3d, 1d, 2d));
        Assert.assertEquals(1.0, s.getMinimum(), 0.0001);
    }
    @Test public void maximum() {
        Statistics s = new Statistics(Arrays.asList(3d, 1d, 2d));
        Assert.assertEquals(3.0, s.getMaximum(), 0.0001);
    }
    @Test public void medianEven() {
        Statistics s = new Statistics(Arrays.asList(1d, 2d, 3d, 4d));
        Assert.assertEquals(2.5, s.median(), 0.0001);
    }
    @Test public void medianEmptyIsZero() {
        Statistics s = new Statistics(new ArrayList<Double>(), 0, 0);
        Assert.assertEquals(0.0, s.median(), 0.0001);
    }
    @Test public void medianOddUnsorted() {
        Statistics s = new Statistics(Arrays.asList(5d, 1d, 3d));
        Assert.assertEquals(3.0, s.median(), 0.0001);
    }
    @Test public void subRangeMean() {
        List<Double> data = Arrays.asList(10d, 20d, 30d, 40d);
        Statistics s = new Statistics(data, 1, 3); // uses indices 1 and 2 -> [20, 30]
        Assert.assertEquals(25.0, s.getMean(), 0.0001);
    }
    @Test public void varianceSingleIsZero() {
        Statistics s = new Statistics(Arrays.asList(5d));
        Assert.assertEquals(0.0, s.getVariance(), 0.0001);
    }
    @Test public void meanSingle() {
        Statistics s = new Statistics(Arrays.asList(42d));
        Assert.assertEquals(42.0, s.getMean(), 0.0001);
    }
}