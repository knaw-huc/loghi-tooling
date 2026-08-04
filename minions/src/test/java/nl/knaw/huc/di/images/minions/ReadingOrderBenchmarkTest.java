package nl.knaw.huc.di.images.minions;

import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ReadingOrderBenchmarkTest {
    @Test
    public void runBenchmark() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("benchmark.enabled"));

        List<String> args = new ArrayList<>();
        add(args, "--image-dir", "benchmark.imageDir");
        add(args, "--xml-dir", "benchmark.xmlDir");
        add(args, "--csv", "benchmark.csv");
        add(args, "--label", "benchmark.label");
        add(args, "--limit", "benchmark.limit");
        add(args, "--warmup", "benchmark.warmup");
        add(args, "--runs", "benchmark.runs");
        add(args, "--clean-borders", "benchmark.cleanBorders");
        add(args, "--border-margin", "benchmark.borderMargin");
        add(args, "--as-single-region", "benchmark.asSingleRegion");
        add(args, "--interline-clustering-multiplier", "benchmark.interlineClusteringMultiplier");
        add(args, "--dubious-size-width-multiplier", "benchmark.dubiousSizeWidthMultiplier");
        add(args, "--ignore-xml-errors", "benchmark.ignoreXmlErrors");
        add(args, "--suppress-stderr", "benchmark.suppressStderr");

        ReadingOrderBenchmark.main(args.toArray(new String[0]));
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
