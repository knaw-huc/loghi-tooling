package nl.knaw.huc.di.images.imageanalysiscommon;

import org.junit.Assert;
import org.junit.Test;
import org.opencv.core.Point;
import org.opencv.core.Rect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StringConverterCoverageTest {

    @Test public void pointToString_roundsToNearest() {
        Assert.assertEquals("1,3", StringConverter.pointToString(new Point(1.4, 2.6)));
    }
    @Test public void pointToString_halfUp() {
        Assert.assertEquals("3,4", StringConverter.pointToString(new Point(2.5, 3.5)));
    }
    @Test public void pointToString_zero() {
        Assert.assertEquals("0,0", StringConverter.pointToString(new Point(0, 0)));
    }
    @Test public void pointToString_rect() {
        Assert.assertEquals("10,20 40,20 40,60 10,60",
                StringConverter.pointToString(new Rect(10, 20, 30, 40)));
    }
    @Test public void pointToString_list() {
        List<Point> pts = Arrays.asList(new Point(1, 1), new Point(2, 2));
        Assert.assertEquals("1,1 2,2", StringConverter.pointToString(pts));
    }
    @Test public void pointToString_emptyList() {
        Assert.assertEquals("", StringConverter.pointToString(new ArrayList<Point>()));
    }
    @Test public void stringToPoint_twoPoints() {
        List<Point> pts = StringConverter.stringToPoint("10,20 30,40");
        Assert.assertEquals(2, pts.size());
        Assert.assertEquals(new Point(10, 20), pts.get(0));
    }
    @Test public void stringToPoint_empty() {
        Assert.assertEquals(0, StringConverter.stringToPoint("").size());
    }
    @Test public void stringToPoint_null() {
        Assert.assertEquals(0, StringConverter.stringToPoint(null).size());
    }
    @Test public void stringToPoint_fixErrorsClamps() {
        List<Point> pts = StringConverter.stringToPoint("-5,-10", true);
        Assert.assertEquals(new Point(0, 0), pts.get(0));
    }
    @Test public void stringToPoint_noFixKeepsNegative() {
        List<Point> pts = StringConverter.stringToPoint("-5,-10", false);
        Assert.assertEquals(new Point(-5, -10), pts.get(0));
    }
    @Test public void distance_345() {
        Assert.assertEquals(5.0, StringConverter.distance(new Point(0, 0), new Point(3, 4)), 0.0001);
    }
    @Test public void distanceVertical() {
        Assert.assertEquals(7.0, StringConverter.distanceVertical(new Point(0, 5), new Point(0, 12)), 0.0001);
    }
    @Test public void distanceHorizontal() {
        Assert.assertEquals(7.0, StringConverter.distanceHorizontal(new Point(5, 0), new Point(12, 0)), 0.0001);
    }
    @Test public void expandPointList_size() {
        List<Point> pts = Arrays.asList(new Point(0, 0), new Point(0, 2));
        Assert.assertEquals(3, StringConverter.expandPointList(pts).size());
    }
    @Test public void simplifyPolygon_twoOrFewerReturnsSame() {
        List<Point> pts = Arrays.asList(new Point(0, 0), new Point(5, 5));
        Assert.assertEquals(2, StringConverter.simplifyPolygon(pts).size());
    }
    @Test public void simplifyPolygon_collinearReduces() {
        List<Point> pts = Arrays.asList(new Point(0, 0), new Point(1, 0), new Point(2, 0));
        Assert.assertEquals(2, StringConverter.simplifyPolygon(pts, 5).size());
    }
    @Test public void boundingBoxToPoints() {
        Assert.assertEquals("0,0 10,0 10,10 0,10",
                StringConverter.boundingBoxToPoints(new Rect(0, 0, 10, 10)));
    }
    @Test public void calculateBaselineLength_singlePointZero() {
        Assert.assertEquals(0.0,
                StringConverter.calculateBaselineLength(Arrays.asList(new Point(5, 5))), 0.0001);
    }
    @Test public void calculateBaselineLength_emptyZero() {
        Assert.assertEquals(0.0,
                StringConverter.calculateBaselineLength(new ArrayList<Point>()), 0.0001);
    }
}