package nl.knaw.huc.di.images.pagexmlutils;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static nl.knaw.huc.di.images.pagexmlutils.StyledString.applyHtmlTagging;
import static nl.knaw.huc.di.images.pagexmlutils.StyledString.applyMarkersWithNestedTags;
import static nl.knaw.huc.di.images.pagexmlutils.StyledString.fromString;
import static nl.knaw.huc.di.images.pagexmlutils.StyledString.fromStringWithStyleCharacters;
import static nl.knaw.huc.di.images.pagexmlutils.StyledString.isAllowedStyle;

public class StyledStringCoverageTest {

    @Test public void fromString_toString_roundTrip() {
        Assert.assertEquals("abc", fromString("abc").toString());
    }
    @Test public void fromString_getCleanText() {
        Assert.assertEquals("abc", fromString("abc").getCleanText());
    }
    @Test public void isAllowedStyle_superscript()    { Assert.assertTrue(isAllowedStyle("superscript")); }
    @Test public void isAllowedStyle_underlined()     { Assert.assertTrue(isAllowedStyle("underlined")); }
    @Test public void isAllowedStyle_subscript()      { Assert.assertTrue(isAllowedStyle("subscript")); }
    @Test public void isAllowedStyle_strikethrough()  { Assert.assertTrue(isAllowedStyle("strikethrough")); }
    @Test public void isAllowedStyle_unknownFalse()   { Assert.assertFalse(isAllowedStyle("blink")); }

    @Test public void applyHtmlTagging_subscript() {
        Assert.assertEquals("<sub>2</sub>", applyHtmlTagging("\u24042")); // ␄2
    }
    @Test public void applyHtmlTagging_superscript() {
        Assert.assertEquals("<sup>2</sup>", applyHtmlTagging("\u24062")); // ␆2
    }
    @Test public void applyHtmlTagging_strikethrough() {
        Assert.assertEquals("<s>a</s>", applyHtmlTagging("\u2403a")); // ␃a
    }
    @Test public void applyHtmlTagging_underline() {
        Assert.assertEquals("<u>a</u>", applyHtmlTagging("\u2405a")); // ␅a
    }
    @Test public void applyHtmlTagging_plainUnchanged() {
        Assert.assertEquals("abc", applyHtmlTagging("abc"));
    }
    @Test public void applyMarkersWithNestedTags_underline() {
        Assert.assertEquals("\u2405a\u2405b", applyMarkersWithNestedTags("<u>ab</u>")); // ␅a␅b
    }
    @Test public void getCleanText_stripsStyleChars() {
        Assert.assertEquals("a", fromStringWithStyleCharacters("\u2405a").getCleanText()); // ␅a
    }
    @Test public void applyStyles_then_getStyles() {
        StyledString styled = fromString("abc");
        styled.applyStyles(0, 1, Collections.singletonList("underlined"));
        List<StyledString.StringStyle> styles = styled.getStyles();
        Assert.assertEquals(1, styles.size());
        Assert.assertEquals(0, styles.get(0).getOffset());
        Assert.assertEquals(1, styles.get(0).getLength());
        Assert.assertEquals(Collections.singletonList("underlined"), styles.get(0).getStyles());
    }
}