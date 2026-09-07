package nl.knaw.huc.di.images.imageanalysiscommon;

import org.junit.Assert;
import org.junit.Test;

public class UnicodeToAsciiTranslitiratorTest {

    private final UnicodeToAsciiTranslitirator translitirator = new UnicodeToAsciiTranslitirator();

    @Test public void toAscii_asciiPassthrough()   { Assert.assertEquals("hello", translitirator.toAscii("hello")); }
    @Test public void toAscii_cafe()               { Assert.assertEquals("cafe", translitirator.toAscii("caf\u00e9")); }   // café
    @Test public void toAscii_naive()              { Assert.assertEquals("naive", translitirator.toAscii("na\u00efve")); } // naïve
    @Test public void toAscii_empty()              { Assert.assertEquals("", translitirator.toAscii("")); }
    @Test public void toAscii_digitsPunctuation()  { Assert.assertEquals("123!?", translitirator.toAscii("123!?")); }
    @Test public void toAscii_resultIsAsciiOnly()  {
        String result = translitirator.toAscii("\u03a9\u2248\u00e7"); // Ω≈ç
        Assert.assertTrue("result should be ASCII-only but was: " + result, result.matches("\\p{ASCII}*"));
    }
}