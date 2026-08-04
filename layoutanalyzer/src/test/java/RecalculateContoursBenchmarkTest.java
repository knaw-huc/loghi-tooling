import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RecalculateContoursBenchmarkTest {
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
        add(args, "--scale", "benchmark.scale");
        add(args, "--minimum-interline-distance", "benchmark.minimumInterlineDistance");
        add(args, "--thickness", "benchmark.thickness");
        add(args, "--minimum-baseline-thickness", "benchmark.minimumBaselineThickness");
        add(args, "--ignore-broken", "benchmark.ignoreBroken");
        add(args, "--ignore-xml-errors", "benchmark.ignoreXmlErrors");
        add(args, "--suppress-stderr", "benchmark.suppressStderr");
        add(args, "--dump-contours", "benchmark.dumpContours");

        RecalculateContoursBenchmark.main(args.toArray(new String[0]));
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
