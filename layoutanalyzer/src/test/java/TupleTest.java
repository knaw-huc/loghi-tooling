import nl.knaw.huc.di.images.layoutanalyzer.Tuple;
import org.junit.Assert;
import org.junit.Test;

public class TupleTest {

    @Test public void getX_returnsX() {
        Tuple<String, Integer> t = new Tuple<>("a", 1);
        Assert.assertEquals("a", t.getX());
    }
    @Test public void getY_returnsY() {
        Tuple<String, Integer> t = new Tuple<>("a", 1);
        Assert.assertEquals(Integer.valueOf(1), t.getY());
    }
    @Test public void publicFieldsAreAccessible() {
        Tuple<String, Integer> t = new Tuple<>("a", 1);
        Assert.assertEquals("a", t.x);
        Assert.assertEquals(Integer.valueOf(1), t.y);
    }
    @Test public void nullValuesAreAllowed() {
        Tuple<String, String> t = new Tuple<>(null, "b");
        Assert.assertNull(t.getX());
        Assert.assertEquals("b", t.getY());
    }
}