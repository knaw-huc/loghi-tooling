package nl.knaw.huc.di.images.minions;


import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

public class MinionGarbageCharacterCalculator {


    private static final Logger LOG = LoggerFactory.getLogger(MinionGarbageCharacterCalculator.class);
    private static Options getOptions() {
        final Options options = new Options();
        options.addOption(Option.builder("page_file").required().hasArg().desc("Page file to check on garbage characters").build());
        options.addOption(Option.builder("characters_file").required().hasArg().desc("Text file with allowed characters").build());
        options.addOption("help", false, "prints this help dialog");

        return options;
    }

    public static void printHelp(Options options, String callName) {
        final HelpFormatter helpFormatter = new HelpFormatter();

        helpFormatter.printHelp(callName, options, true);
    }

    public static void main(String[] args) {
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException ex) {
            printHelp(options, "java " + MinionGeneratePageImages.class.getName());
            return;
        }

        if (cmd.hasOption("help")) {
            printHelp(options, "java " + MinionGeneratePageImages.class.getName());
            return;
        }

        final String page_file_string = cmd.getOptionValue("page_file");
        final String characters_file_string = cmd.getOptionValue("characters_file");

        final PcGts page;
        try {
            page = PageUtils.readPageFromFile(Path.of(page_file_string));
        } catch (IOException e) {
            LOG.warn("Could not read page file: {}", e.getMessage());
            return;
        }
        final String characters;
        try {
            characters = StringTools.readFile(characters_file_string);
        } catch (IOException e) {
            LOG.warn("Could not read characters file: {}", e.getMessage());
            return;
        }

        final String unicodeText = page.getPage().getTextRegions().stream()
                .flatMap(region -> region.getTextLines().stream())
                .filter(line -> line.getTextEquiv() != null)
                .map(line -> line.getTextEquiv().getUnicode()).collect(Collectors.joining("\n"));
        final Set<Character> allowedChars = characters.chars().mapToObj(character -> (char) character).collect(Collectors.toSet());

        int countNotAllowedCharacters = 0;
        for (char c : unicodeText.toCharArray()) {
            if (!allowedChars.contains(c)) {
                countNotAllowedCharacters++;
            }
        }

        final int textLength = unicodeText.length();
        LOG.info("total characters: {}", textLength);
        LOG.info("garbage characters: {}", countNotAllowedCharacters);
        LOG.info("garbage characters percentage: {}", (countNotAllowedCharacters * 100 / (textLength > 0 ? textLength : 1)));
    }
}
