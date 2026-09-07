package nl.knaw.huc.di.images.stringtools;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class StringToolsCoverageTest {

    // --- isNumeric ---
    @Test public void isNumeric_null_false()          { Assert.assertFalse(StringTools.isNumeric(null)); }
    @Test public void isNumeric_empty_false()         { Assert.assertFalse(StringTools.isNumeric("")); }
    @Test public void isNumeric_digits_true()         { Assert.assertTrue(StringTools.isNumeric("123")); }
    @Test public void isNumeric_leadingZeros_true()   { Assert.assertTrue(StringTools.isNumeric("007")); }
    @Test public void isNumeric_negative_false()      { Assert.assertFalse(StringTools.isNumeric("-1")); }

    // --- months ---
    @Test public void getDutchMonthNumber_jan()       { Assert.assertEquals(1, StringTools.getDutchMonthNumber("jan")); }
    @Test public void getDutchMonthNumber_mixedCase() { Assert.assertEquals(12, StringTools.getDutchMonthNumber("December")); }
    @Test public void isDutchMonth_true()             { Assert.assertTrue(StringTools.isDutchMonth("mei")); }
    @Test public void isDutchMonth_false()            { Assert.assertFalse(StringTools.isDutchMonth("foo")); }

    // --- roman numerals ---
    @Test public void toRoman_4()                     { Assert.assertEquals("IV", StringTools.toRoman(4)); }
    @Test public void toRoman_2020()                  { Assert.assertEquals("MMXX", StringTools.toRoman(2020)); }
    @Test public void toRoman_3999()                  { Assert.assertEquals("MMMCMXCIX", StringTools.toRoman(3999)); }
    @Test(expected = NullPointerException.class)
    public void toRoman_zero_throws()                 { StringTools.toRoman(0); }
    @Test public void romanToDecimal_MMXX()           { Assert.assertEquals(2020, StringTools.romanToDecimal("MMXX")); }
    @Test public void romanToDecimal_lowercase()      { Assert.assertEquals(9, StringTools.romanToDecimal("ix")); }
    @Test public void romanToDecimal_MCMLIV()         { Assert.assertEquals(1954, StringTools.romanToDecimal("MCMLIV")); }
    @Test(expected = NumberFormatException.class)
    public void romanToDecimal_invalid_throws()       { StringTools.romanToDecimal("A"); }
    @Test public void processDecimal_add()            { Assert.assertEquals(1954, StringTools.processDecimal(1000, 100, 954)); }
    @Test public void processDecimal_subtract()       { Assert.assertEquals(954, StringTools.processDecimal(100, 1000, 1054)); }
    @Test public void isRomanNumeral_empty_true()     { Assert.assertTrue(StringTools.isRomanNumeral("")); }
    @Test public void isRomanNumeral_IIII_false()     { Assert.assertFalse(StringTools.isRomanNumeral("IIII")); }

    // --- uri / encoding ---
    @Test public void encodeURIComponent_space()      { Assert.assertEquals("a%20b", StringTools.encodeURIComponent("a b")); }
    @Test public void encodeURIComponent_preserves()  { Assert.assertEquals("!'()~", StringTools.encodeURIComponent("!'()~")); }
    @Test public void cleanUri_apostropheAndSpace()   { Assert.assertEquals("o_brien_smith", StringTools.cleanUri("o'brien smith")); }

    // --- text helpers ---
    @Test public void makeNew_ligatures()             { Assert.assertEquals("flae", StringTools.makeNew("\uFB02\u00E6")); } // "ﬂæ"
    @Test public void makeNew_null()                  { Assert.assertNull(StringTools.makeNew(null)); }
    @Test public void getInt_valid()                  { Assert.assertEquals(Integer.valueOf(123), StringTools.getInt("123")); }
    @Test public void getInt_invalid_null()           { Assert.assertNull(StringTools.getInt("abc")); }
    @Test public void editDistance_kittenSitting()    { Assert.assertEquals(3, StringTools.editDistance("kitten", "sitting")); }
    @Test public void characterErrorRate_quarter()    { Assert.assertEquals(0.25, StringTools.characterErrorRate("abcd", "abce"), 0.0001); }

    // --- stream / file round-trip ---
    @Test public void loadStringFromStream_appendsNewlines() throws IOException {
        String result = StringTools.loadStringFromStream(
                new ByteArrayInputStream("line1\nline2".getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals("line1\nline2\n", result);
    }
    @Test public void writeFile_then_readFile_roundTrip() throws IOException {
        Path tmp = Files.createTempFile("stringtools-test", ".txt");
        try {
            StringTools.writeFile(tmp.toString(), "hello world");
            Assert.assertEquals("hello world", StringTools.readFile(tmp.toString()));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}