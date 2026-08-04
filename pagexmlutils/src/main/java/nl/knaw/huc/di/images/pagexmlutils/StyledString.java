package nl.knaw.huc.di.images.pagexmlutils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.ArrayDeque;
import java.util.Deque;


public class StyledString {
    public static String STRIKETHROUGHCHAR = "␃"; //Unicode Character “␃” (U+2403)
    public static String UNDERLINECHAR = "␅"; //Unicode Character “␅” (U+2405)
    public static String SUBSCRIPTCHAR = "␄"; // Unicode Character “␄” (U+2404)
    public static String SUPERSCRIPTCHAR = "␆"; // Unicode Character “␆” (U+2406)
    private static final Map<String, String> CHARACTER_STYLE_MAP = Map.of(
            SUPERSCRIPTCHAR, "superscript",
            UNDERLINECHAR, "underlined",
            SUBSCRIPTCHAR, "subscript",
            STRIKETHROUGHCHAR, "strikethrough"
    );

    private static final Map<String, String> STYLE_CHARACTER_MAP = Map.of(
            "superscript", SUPERSCRIPTCHAR,
            "underlined", UNDERLINECHAR,
            "subscript", SUBSCRIPTCHAR,
            "strikethrough", STRIKETHROUGHCHAR
    );

    private final List<StyledChar> styledCharList;

    private StyledString(List<StyledChar> styledCharList) {

        this.styledCharList = styledCharList;
    }

    public static StyledString fromString(String from) {
        List<StyledChar> styledCharList = new ArrayList<>();
        for (char character : from.toCharArray()) {
            styledCharList.add(new StyledChar(character));
        }

        return new StyledString(styledCharList);
    }

    public static StyledString fromStringWithStyleCharacters(String stringWithStyles) {
        final List<StyledChar> styledCharList = new ArrayList<>(); // positional list; ordering is by character offset and must preserve duplicates
        List<String> styles = new ArrayList<>();
        for (char character : stringWithStyles.toCharArray()) {
            final String stringOfCharacter = String.valueOf(character);
            if (CHARACTER_STYLE_MAP.containsKey(stringOfCharacter)) {
                styles.add(stringOfCharacter);
            } else {
                styledCharList.add(new StyledChar(character, styles));
                styles = new ArrayList<>();
            }
        }
        return new StyledString(styledCharList);
    }

    public static boolean isAllowedStyle(String style) {
        return STYLE_CHARACTER_MAP.containsKey(style);
    }

    /**
     * Processes the specified marker in the given text, wrapping matched content with provided HTML tags.
     * This method constructs a regex pattern to find the marker, removes the marker symbols, and wraps the content with specified HTML open and close tags.
     * @param text The input text to be processed.
     * @param marker The marker symbol to look for in the text.
     * @param openTag The HTML tag to prepend to the matched content.
     * @param closeTag The HTML tag to append to the matched content.
     * @return A String where specified markers have been replaced with the corresponding HTML tags.
     */
    private static String processSpecificMarker(String text, String marker, String openTag, String closeTag) {
        // Construct regex for the marker
        String tagStarts = "(<sub>|<s>|<u>|<sup>)?";
        String tagEndings = "(</sub>|</s>|</u>|</sup>)?";
        String regex = "(" + marker + tagStarts + "[^<]" + tagEndings + ")+";

        // Create a string builder to store the processed text
        StringBuilder sb = new StringBuilder();

        // Create a pattern and matcher to find the marker in the text
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);

        while (m.find()) {
            // Remove marker symbols and wrap the content with the specified HTML tags
            String groupContent = m.group(0).replaceAll(Pattern.quote(marker), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(openTag + groupContent + closeTag));
        }
        m.appendTail(sb);

        // Consolidate tags and return the processed text
        String regex2 = "(" + closeTag + ")( ?[␅␃␄␆]*)(" + openTag + ")";
        return sb.toString().replaceAll(regex2, "$2");
    }

    /**
     * Applies HTML tagging to the text for subscript, superscript, strikethrough, and underline markers.
     * This method sequentially processes the text for each marker type using processSpecificMarker.
     * @param text The input text to apply HTML tagging to.
     * @return The processed text with HTML tags applied.
     */
    public static String applyHtmlTagging(String text) {
        // Process subscript markers
        text = processSpecificMarker(text, "␄", "<sub>", "</sub>");
        // Process superscript markers
        text = processSpecificMarker(text, "␆", "<sup>", "</sup>");
        // Process strikethrough markers
        text = processSpecificMarker(text, "␃", "<s>", "</s>");
        // Process underline markers
        text = processSpecificMarker(text, "␅", "<u>", "</u>");
        return text;
    }

    /**
     * Closes any unclosed HTML tags in the input string. This method ensures that every opening tag has a corresponding closing tag.
     * If an unmatched closing tag is found, an IllegalArgumentException is thrown.
     * @param input The input string potentially containing unclosed HTML tags.
     * @return The input string with all unclosed tags properly closed.
     * @throws IllegalArgumentException If an unknown tag is found.
     */
    private static String closeUnclosedTags(String input) {
        final Pattern tagPattern = Pattern.compile("(<u>|</u>|<s>|</s>|<sub>|</sub>|<sup>|</sup>)");
        Matcher matcher = tagPattern.matcher(input);
        StringBuilder result = new StringBuilder(input);
        Deque<TagPosition> tagStack = new ArrayDeque<>();
        int offset = 0; // Adjusts for insertions due to added closing tags

        while (matcher.find()) {
            String tag = matcher.group();
            int start = matcher.start() + offset;
            if (!tag.startsWith("</")) {
                // Push opening tags onto the stack
                tagStack.push(new TagPosition(tag, start));
            } else {
                // Pop matching opening tags, or throw for unknown tags
                if (!tagStack.isEmpty() && isMatchingClosingTag(tagStack.peek().tag, tag)) {
                    tagStack.pop();
                } else {
                    throw new IllegalArgumentException("Unknown tag found: " + tag);
                }
            }
        }

        // Insert closing tags for any remaining unclosed tags
        while (!tagStack.isEmpty()) {
            TagPosition openTag = tagStack.pop();
            String closingTag = openTag.tag.replace("<", "</");
            int insertPosition = findEndOfWordPosition(result.toString(), openTag.position);
            result.insert(insertPosition, closingTag);
            offset += closingTag.length();
        }

        return result.toString();
    }

    private static class TagPosition {
        String tag;
        int position;

        TagPosition(String tag, int position) {
            this.tag = tag;
            this.position = position;
        }
    }

    // Checks if a closing tag matches the last opening tag
    private static boolean isMatchingClosingTag(String openTag, String closeTag) {
        return closeTag.equals("</" + openTag.substring(1));
    }

    // Finds the position to insert a closing tag, either at the next space or the end of the string
    private static int findEndOfWordPosition(String str, int startPos) {
        int spacePos = str.indexOf(' ', startPos);
        return (spacePos != -1) ? spacePos : str.length();
    }

    /**
     * Applies markers to text with consideration for nested tags, ensuring all HTML tags are properly closed before applying.
     * This method first closes unclosed tags, then processes the text to apply markers while respecting tag nesting.
     * @param text The input text to process.
     * @return The processed text with markers applied, considering nested tags.
     */
    public static String applyMarkersWithNestedTags(String text) {
        // Ensure all tags are properly closed
        text = closeUnclosedTags(text);
        // Pattern to check for html tags
        final Pattern TAG_PATTERN = Pattern.compile("(<u>|</u>|<s>|</s>|<sub>|</sub>|<sup>|</sup>)");
        StringBuilder processedText = new StringBuilder();
        Deque<String> tagStack = new ArrayDeque<>();
        int lastEnd = 0;

        Matcher matcher = TAG_PATTERN.matcher(text);
        while (matcher.find()) {
            String beforeTag = text.substring(lastEnd, matcher.start());
            processedText.append(applyStackMarkers(beforeTag, tagStack));
            updateTagStack(tagStack, matcher.group());
            lastEnd = matcher.end();
        }

        if (!tagStack.isEmpty() && lastEnd < text.length()) {
            processedText.append(applyStackMarkers(text.substring(lastEnd), tagStack));
        } else {
            processedText.append(text.substring(lastEnd));
        }

        return enforceMarkerOrder(processedText.toString());
    }

    // Helper method to update the tag stack based on the current tag
    private static void updateTagStack(Deque<String> tagStack, String tag) {
        if (!tag.startsWith("</")) {
            tagStack.push(getMarkerForTag(tag));
        } else if (!tagStack.isEmpty()) {
            tagStack.pop();
        }
    }

    // Returns marker for given tag
    private static String getMarkerForTag(String tag) {
        switch (tag) {
            case "<u>": return "␅";
            case "<s>": return "␃";
            case "<sub>": return "␄";
            case "<sup>": return "␆";
            default: return "";
        }
    }

    // Enforces marker order from underline to strikethrough
    private static String enforceMarkerOrder(String processedText) {
        return processedText
                .replaceAll("␃␃", "␃") // Fix double occurrences strikethrough
                .replaceAll("␅␅", "␅") // Fix double occurrences underline
                .replaceAll("␃␅", "␅␃") // Fix underline and strikethrough ordering
                .replaceAll("␄␅␃", "␅␃␄") // Move subscript to the back
                .replaceAll("␆␅␃", "␅␃␆"); // Move superscript to the back
    }

    // Applies markers based on the current stack of tags
    private static String applyStackMarkers(String text, Deque<String> markers) {
        StringBuilder markedText = new StringBuilder();
        // Apply the tagging regularly
        for (char ch : text.toCharArray()) {
            if (!Character.isWhitespace(ch)) {
                markers.forEach(markedText::append);
            }
            markedText.append(ch);
        }
        return markedText.toString();
    }

    public List<StringStyle> getStyles() {
        List<StringStyle>  stringStyles = new ArrayList<>();
        List<String> currentStyle = null;
        int styleLength = 0;
        int styleOffset = 0;
        for (int i = 0; i < styledCharList.size(); i++) {
            final StyledChar styledChar = styledCharList.get(i);
            final java.util.LinkedHashSet<String> charStyle = styledChar.styles;
            if (currentStyle == null && !charStyle.isEmpty()) {
                currentStyle = new ArrayList<>(charStyle);
                styleOffset = i;
            }

            if (currentStyle != null) {
                if (currentStyle.equals(new ArrayList<>(charStyle))) {
                    styleLength++;
                } else  {
                    final List<String> styleNames = currentStyle.stream().map(CHARACTER_STYLE_MAP::get).collect(Collectors.toList());
                    stringStyles.add(new StringStyle(styleOffset, styleLength, styleNames));
                    currentStyle = new ArrayList<>(charStyle);
                    styleLength = 1;
                    styleOffset = i;
                }
            }
        }

        if (currentStyle != null && !currentStyle.isEmpty()) {
            final List<String> styleNames = currentStyle.stream().map(CHARACTER_STYLE_MAP::get).collect(Collectors.toList());
            stringStyles.add(new StringStyle(styleOffset, styleLength, styleNames));
        }

        return stringStyles;
    }

    public void applyStyles(int offset, int length, List<String> styles) {
        for (int i = offset; i < (offset + length); i++) {
            final List<String> styleChars = styles.stream().map(STYLE_CHARACTER_MAP::get).collect(Collectors.toList());
            styledCharList.get(i).applyStyles(styleChars);
        }
    }

    @Override
    public String toString() {
        final StringBuilder stringBuilder = new StringBuilder();
        styledCharList.forEach(stringBuilder::append);

        return stringBuilder.toString();
    }

    public String getCleanText() {
        final StringBuilder stringBuilder = new StringBuilder();
        styledCharList.forEach(character -> stringBuilder.append(character.character));
        return stringBuilder.toString();
    }

    public static class StyledChar {
        private final char character;
        // OPT-05: LinkedHashSet de-duplicates style names but preserves insertion order
        // so serialised output is stable for the same logical document. (TreeSet would
        // sort alphabetically and break callers that depend on emit order.)
        private final java.util.LinkedHashSet<String> styles;

        public StyledChar(char character) {
            this.character = character;
            this.styles = new java.util.LinkedHashSet<>();
        }

        public StyledChar(char character, java.util.Collection<String> styles) {
            this.character = character;
            this.styles = new java.util.LinkedHashSet<>(styles);
        }

        public void applyStyles(java.util.Collection<String> styleCharacter) {
            styles.addAll(styleCharacter);
        }

        @Override
        public String toString() {
            final StringBuilder stringBuilder = new StringBuilder();
            styles.forEach(stringBuilder::append);
            stringBuilder.append(character);

            return stringBuilder.toString();
        }
    }

    public static class StringStyle {
        private final int offset;
        private final int length;
        private final List<String> styles; // stylenames

        public StringStyle(int offset, int length, List<String> styles) {
            this.offset = offset;
            this.length = length;
            this.styles = styles;
        }

        public int getOffset() {
            return offset;
        }

        public int getLength() {
            return length;
        }

        public List<String> getStyles() {
            return styles;
        }

        @Override
        public String toString() {
            return "StringStyle{" +
                    "offset=" + offset +
                    ", length=" + length +
                    ", styles=" + styles +
                    '}';
        }
    }
}
