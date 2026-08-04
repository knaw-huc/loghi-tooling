package nl.knaw.huc.di.images.minions;

import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SplitImageBenchmarkTest {
    @Test
    public void runBenchmark() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("benchmark.enabled"));

        List<String> args = new ArrayList<>();
        add(args, "--image-dir", "benchmark.imageDir");
        add(args, "--xml-dir", "benchmark.xmlDir");
        add(args, "--output-base", "benchmark.outputBase");
        add(args, "--csv", "benchmark.csv");
        add(args, "--label", "benchmark.label");
        add(args, "--limit", "benchmark.limit");
        add(args, "--warmup", "benchmark.warmup");
        add(args, "--runs", "benchmark.runs");
        add(args, "--min-width", "benchmark.minWidth");
        add(args, "--min-height", "benchmark.minHeight");
        add(args, "--min-width-to-height", "benchmark.minWidthToHeight");
        add(args, "--output-type", "benchmark.outputType");
        add(args, "--channels", "benchmark.channels");
        add(args, "--recalculate-contours", "benchmark.recalculateContours");
        add(args, "--minimum-interline-distance", "benchmark.minimumInterlineDistance");
        add(args, "--png-compression-level", "benchmark.pngCompressionLevel");
        add(args, "--minimum-baseline-thickness", "benchmark.minimumBaselineThickness");
        add(args, "--ignore-xml-errors", "benchmark.ignoreXmlErrors");
        add(args, "--suppress-stderr", "benchmark.suppressStderr");

        SplitImageBenchmark.main(args.toArray(new String[0]));
    }

    private static void add(List<String> args, String flag, String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return;
        }
        args.add(flag);
        args.add(value);
    }
}
