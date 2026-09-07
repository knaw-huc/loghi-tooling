package nl.knaw.huc.di.images.pagexmlutils;

import nl.knaw.huc.di.images.layoutds.models.Page.TextEquiv;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import nl.knaw.huc.di.images.layoutds.models.Page.Word;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class GroundTruthTextLineFormatterCoverageTest {

    @Test public void unicodeIsPreferred() {
        TextLine line = new TextLine();
        line.setTextEquiv(new TextEquiv(null, "plain text", "unicode text"));
        Assert.assertEquals("unicode text",
                GroundTruthTextLineFormatter.getFormattedTextLineStringRepresentation(line, false));
    }

    @Test public void plainTextFallbackWhenUnicodeNull() {
        TextLine line = new TextLine();
        line.setTextEquiv(new TextEquiv(null, "plain text", null));
        Assert.assertEquals("plain text",
                GroundTruthTextLineFormatter.getFormattedTextLineStringRepresentation(line, false));
    }

    @Test public void wordsFallbackWhenNoTextEquiv() {
        TextLine line = new TextLine();
        line.setWords(Arrays.asList(word("Dit"), word("is")));
        Assert.assertEquals("Dit is",
                GroundTruthTextLineFormatter.getFormattedTextLineStringRepresentation(line, false));
    }

    @Test public void nullCustomReturnsPlainText() {
        TextLine line = new TextLine();
        line.setTextEquiv(new TextEquiv(null, null, "hello"));
        line.setCustom(null);
        Assert.assertEquals("hello",
                GroundTruthTextLineFormatter.getFormattedTextLineStringRepresentation(line, true));
    }

    @Test public void includeStylesFalseSkipsStyling() {
        TextLine line = new TextLine();
        line.setTextEquiv(new TextEquiv(null, "Dit is een test"));
        line.setCustom("readingOrder {index:2;} textStyle {offset:7; length:3;superscript:true;}");
        Assert.assertEquals("Dit is een test",
                GroundTruthTextLineFormatter.getFormattedTextLineStringRepresentation(line, false));
    }

    @Test public void superscriptStylingProducesMarkers() {
        TextLine line = new TextLine();
        line.setTextEquiv(new TextEquiv(null, "Dit is een test"));
        line.setCustom("readingOrder {index:2;} textStyle {offset:7; length:3;superscript:true;}");
        Assert.assertEquals("Dit is \u2406e\u2406e\u2406n test",
                GroundTruthTextLineFormatter.getFormattedTextLineStringRepresentation(line, true));
    }

    @Test public void useTagsProducesHtml() {
        TextLine line = new TextLine();
        line.setTextEquiv(new TextEquiv(null, "Dit is een test"));
        line.setCustom("readingOrder {index:2;} textStyle {offset:7; length:3;superscript:true;}");
        Assert.assertEquals("Dit is <sup>een</sup> test",
                GroundTruthTextLineFormatter.getFormattedTextLineStringRepresentation(line, true, true));
    }

    private static Word word(String text) {
        Word w = new Word();
        w.setTextEquiv(new TextEquiv(null, text));
        return w;
    }
}