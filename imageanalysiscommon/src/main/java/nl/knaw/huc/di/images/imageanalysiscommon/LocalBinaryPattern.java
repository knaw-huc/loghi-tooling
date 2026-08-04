package nl.knaw.huc.di.images.imageanalysiscommon;


import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class LocalBinaryPattern {
    private static final Logger LOG = LoggerFactory.getLogger(LocalBinaryPattern.class);
    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }


    private static void doPattern(Mat image, int[] masterpattern) {
        int[] pattern = new int[8];
        for (int i = 0; i < 8; i++) {
            pattern[i] = 0;
        }

        int diff = 10;
        double[] center = image.get(1, 1);//,center);
        double[] data;//= new byte[0];
        data = image.get(0, 0);
        if (data[0] > center[0] + diff) {
            pattern[0]++;
        }
        data = image.get(0, 1);
        if (data[0] > center[0] + diff) {
            pattern[1]++;
        }
        data = image.get(0, 2);
        if (data[0] > center[0] + diff) {
            pattern[2]++;
        }
        data = image.get(1, 2);
        if (data[0] > center[0] + diff) {
            pattern[3]++;
        }
        data = image.get(2, 2);
        if (data[0] > center[0] + diff) {
            pattern[4]++;
        }
        data = image.get(2, 1);
        if (data[0] > center[0] + diff) {
            pattern[5]++;
        }
        data = image.get(2, 0);
        if (data[0] > center[0] + diff) {
            pattern[6]++;
        }
        data = image.get(1, 0);
        if (data[0] > center[0] + diff) {
            pattern[7]++;
        }

        int target = 0;
        int times = 1;
        for (int i = 0; i < 8; i++) {
            target += pattern[i] * times;
            times *= 2;
        }
        masterpattern[target]++;
    }

    private static int[] getHisto(Mat image) {
        int[] pattern = new int[256];
        for (int i = 0; i < 256; i++) {
            pattern[i] = 0;
        }
        for (int i = 1; i < image.height() - 2; i++) {
//            System.out.println(i);
            for (int j = 1; j < image.width() - 2; j++) {
                Mat submat = image.submat(i, i + 3, j, j + 3);
                try {
                    doPattern(submat, pattern);
                } finally {
                    submat.release();
                }
            }

        }
        int total = 0;
        for (int i = 0; i < 256; i++) {
            total += pattern[i];
        }
        for (int i = 0; i < 256; i++) {
            pattern[i] *= (double) 25600 / (double) total;
        }
        return pattern;
    }


    private static Mat resize(Mat input){
        Mat resizedImage = new Mat();
        Size sz = new Size(250,250);
        Imgproc.resize( input, resizedImage, sz );
        return resizedImage;
    }


    private static String outputPath = "/home/rutger/localbinary.csv";
    public static void main(String[] args) throws IOException {

        String inputPath = "/data/tmp/tinder/";

        if (args.length>=2){
            inputPath = args[0];
            outputPath = args[1];
        }
        Path path = Paths.get(inputPath);
        PrintWriter out = new PrintWriter(outputPath);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path file : stream) {

                if (!file.toAbsolutePath().toString().endsWith(".jpg")) {
                    continue;
                }
                Mat input = Imgcodecs.imread(file.toAbsolutePath().toString(), Imgcodecs.IMREAD_GRAYSCALE);

                Mat resizedImage = resize(input);

//                int[] pattern1 = getHisto(input.submat((int) (input.height() * 0.25), (int) (input.height() * 0.75), (int) (input.width() * 0.25), (int) (input.width() * 0.75)));
                int[] pattern1 = getHisto(resizedImage);

                out.print(file.toAbsolutePath().toString() + "\t");
                LOG.info(file.toAbsolutePath().toString() + "\t");
                String patternString = "";
                for (int i = 0; i < 256; i++) {
                    patternString += pattern1[i] + "\t";
                    LOG.info(patternString);
                }

                out.print(patternString);
                out.println();
                LOG.info("");
                out.flush();

                resizedImage.release();
                input.release();
//                //        input = Imgcodecs.imread("/data/tmp/tinder/W148_000154_sap.jpg", Imgcodecs.IMREAD_GRAYSCALE);
//                //        input = Imgcodecs.imread("/mnt/externala/openn.library.upenn.edu/Data/0020/Data/WaltersManuscripts/W148/data/W.148/300/W148_000156_300.tif", Imgcodecs.IMREAD_GRAYSCALE);
//                input = Imgcodecs.imread("/data/tmp/tinder/9112_000010_sap.jpg", Imgcodecs.IMREAD_GRAYSCALE);
//                //        input = Imgcodecs.imread("/data/tmp/tinder/3610_000011_sap.jpg", Imgcodecs.IMREAD_GRAYSCALE);
//
//                int[] pattern2 = getHisto(input.submat((int) (input.height() * 0.25), (int) (input.height() * 0.75), (int) (input.width() * 0.25), (int) (input.width() * 0.75)));

//                int[] pattern = new int[256];
//                for (int i = 0; i < 256; i++) {
//                    pattern[i] = pattern1[i] - pattern2[i];
//                }
//                for (int i = 0; i < 256; i++) {
//                    System.out.println(i + " " + pattern[i]);
//                }
//
//                int error = 0;
//                for (int i = 0; i < 256; i++) {
//                    error += Math.abs(pattern[i]);
//                }
//                System.out.println("error " + error);
            }
        }
        out.close();

    }



    public static List<String> findFamiliar(Mat input) throws IOException {
        Mat resizedImage = resize(input);
        Mat centerSubmat = resizedImage.submat((int) (resizedImage.height() * 0.25), (int) (resizedImage.height() * 0.75), (int) (resizedImage.width() * 0.25), (int) (resizedImage.width() * 0.75));
        int[] patternToMatch;
        try {
            patternToMatch = getHisto(centerSubmat);
        } finally {
            centerSubmat.release();
            resizedImage.release();
        }
//        int[] patternToMatch = getHisto(input.submat((int) (input.height() * 0), (int) (input.height() ), (int) (input.width() ), (int) (input.width() )));

        HashMap<String, int[]> testData = readCsv();
        HashMap<String, Integer> result = new HashMap<>();

        for (String uri : testData.keySet()) {
            int error = 0;
            int[] pattern = testData.get(uri);
            for (int i = 0; i < 256; i++) {
                error += Math.abs(pattern[i] - patternToMatch[i]);
            }
            result.put(uri, (int) Math.sqrt(error));
        }

        Object[] a = result.entrySet().toArray();
        Arrays.sort(a, Comparator.comparing(o -> ((Map.Entry<String, Integer>) o).getValue()));

        List<String> results = new ArrayList<>();
        for (Object e : a) {
            results.add(((Map.Entry<String, Integer>) e).getKey());
            LOG.info("{} : {}", ((Map.Entry<String, Integer>) e).getKey(), ((Map.Entry<String, Integer>) e).getValue());
        }

        return results;

    }

    private static HashMap<String, int[]> readCsv() throws IOException {
        Reader reader = Files.newBufferedReader(Paths.get(outputPath));
        CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withDelimiter('\t'));
        HashMap<String, int[]> testDatas = new HashMap<>();
        for (CSVRecord csvRecord : csvParser) {
            String uri = csvRecord.get(0);
            int[] pattern = new int[256];

            for (int i = 1; i <= 256; i++) {
                pattern[i - 1] = StringTools.getInt(csvRecord.get(i));
            }
            testDatas.put(uri, pattern);
        }
        return testDatas;
    }

}
