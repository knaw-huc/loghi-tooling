package nl.knaw.huc.di.images.layoutanalyzer;

import org.opencv.core.Mat;

public class LayoutConfiguration {
    private static LayoutConfiguration global;
    private boolean outputDebug;
    private boolean outputFile;
    private int smoothFactor = -1;
    private int gaussSizeWidth;
    private int gaussSizeHeight;
    private int sigmaX;
    private int cocoMinSize;
    private int cocoMaxHeight;
    private int bilateralFilterHeight;
    private boolean useBilateralFilter = false;
    private int textBlockMinWidth;
    private boolean doOtsu= false;
    private static int dropletMaxAway = 250;
    private boolean outputTextLineImages;
    private boolean determineInitialBlur =false;

    public LayoutConfiguration(Mat image) {
        smoothFactor = image.width() / 150; // tried 100, but fails on some machine print, tried 50, but is too much peaks are flattened out, 100 works better and provides sharper peaks
        // smoothfactor is (of course) dependant on type of document...
        // especially pages that are completely filled are a problem
        outputDebug = true;
        outputFile = true;
        gaussSizeWidth = image.width() / 10;
        gaussSizeHeight = image.width() / 200;
        if (gaussSizeWidth % 2 == 0) {
            gaussSizeWidth++;
        }
        if (gaussSizeHeight % 2 == 0) {
            gaussSizeHeight++;
        }
        sigmaX = image.width() / 20;
        cocoMinSize = image.width() / 1000;
        cocoMaxHeight = image.width() / 10;
        textBlockMinWidth = image.width() / 20;
        dropletMaxAway = image.width() / 10;
    }

    public static LayoutConfiguration getGlobal() {
        if (global == null) {
            // Previously this called System.exit(1), which crashes the JVM
            // (a JUnit forked VM in particular). Throwing an IllegalStateException
            // is far more debuggable and lets callers handle the missing config.
            throw new IllegalStateException("LayoutConfiguration global instance has not been initialised. " +
                    "Call LayoutConfiguration.setGlobal(...) before using LayoutProc / DocumentPage helpers that rely on it.");
        }
        return global;
    }

    public static void setGlobal(LayoutConfiguration global) {
        LayoutConfiguration.global = global;
    }

    public int getTextBlockMinWidth() {
        return textBlockMinWidth;
    }

    public void setTextBlockMinWidth(int textBlockMinWidth) {
        this.textBlockMinWidth = textBlockMinWidth;
    }

    public boolean isOutputDebug() {
        return outputDebug;
    }

    public void setOutputDebug(boolean outputDebug) {
        this.outputDebug = outputDebug;
    }

    public boolean isOutputFile() {
        return outputFile;
    }

    public void setOutputFile(boolean outputFile) {
        this.outputFile = outputFile;
    }

    public int getSmoothFactor() {
        return smoothFactor;
    }

    public void setSmoothFactor(int smoothFactor) {
        this.smoothFactor = smoothFactor;
    }

    public int getGaussSizeWidth() {
        return gaussSizeWidth;
    }

    public void setGaussSizeWidth(int gaussSizeWidth) {
        this.gaussSizeWidth = gaussSizeWidth;
    }

    public int getGaussSizeHeight() {
        return gaussSizeHeight;
    }

    public void setGaussSizeHeight(int gaussSizeHeight) {
        this.gaussSizeHeight = gaussSizeHeight;
    }

    public int getSigmaX() {
        return sigmaX;
    }

    public void setSigmaX(int sigmaX) {
        this.sigmaX = sigmaX;
    }

    public int getCocoMinSize() {
        return cocoMinSize;
    }

    public void setCocoMinSize(int cocoMinSize) {
        this.cocoMinSize = cocoMinSize;
    }

    public int getCocoMaxHeight() {
        return cocoMaxHeight;
    }

    public void setCocoMaxHeight(int cocoMaxHeight) {
        this.cocoMaxHeight = cocoMaxHeight;
    }

    public int getBilateralFilterHeight() {
        return bilateralFilterHeight;
    }

    public void setBilateralFilterHeight(int bilateralFilterHeight) {
        this.bilateralFilterHeight = bilateralFilterHeight;
    }

    public boolean getUseBilateralFilter() {
        return useBilateralFilter;
    }

    public boolean isUseBilateralFilter() {
        return useBilateralFilter;
    }

    public void setUseBilateralFilter(boolean useBilateralFilter) {
        this.useBilateralFilter = useBilateralFilter;
    }

    public boolean doTesseract() {
        boolean doTesseract = false;
        return doTesseract;
    }

    public boolean doDroplet() {
        boolean doDroplet = true;
        return doDroplet;
    }
    public boolean drawDroplet() {
        boolean drawDroplet = true;
        return drawDroplet;
    }

    public static int getDropletMaxAway() {
        return dropletMaxAway;
    }

    public void setDropletMaxAway(int dropletMaxAway) {
        this.dropletMaxAway = dropletMaxAway;
    }


    public boolean documentEdgeIsBorder() {
        return false;

    }

    public boolean getOutputTextLineImages() {
        return outputTextLineImages;
    }

    public boolean doOtsu() {
        return doOtsu;
    }
    public void setDoOtsu(boolean otsu) {
        doOtsu = otsu;
    }

    public boolean getDetermineInitialBlur() {
        return determineInitialBlur;
    }

    public void setDetermineInitialBlur(boolean determineInitialBlur) {
        this.determineInitialBlur = determineInitialBlur;
    }
}