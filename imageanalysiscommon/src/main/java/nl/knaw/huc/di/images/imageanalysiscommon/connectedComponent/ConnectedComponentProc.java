package nl.knaw.huc.di.images.imageanalysiscommon.connectedComponent;

import nl.knaw.huc.di.images.imageanalysiscommon.visualization.VisualizationHelper;
import nl.knaw.huc.di.images.layoutds.models.connectedComponent.ConnectedComponent;
import nl.knaw.huc.di.images.layoutds.models.connectedComponent.Label;
import nl.knaw.huc.di.images.layoutds.models.connectedComponent.Pixel;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ConnectedComponentProc {
    private int[][] board;
    private BufferedImage input;
    private int width;
    private int height;

    private static int min(int[] neighboringLabels, Map<Integer, Label> allLabels) {
        if (neighboringLabels.length == 0) {
            return 0; // TEST-06: returns the neutral sentinel 0 because callers never invoke this with an empty input (see callers in this file); the branch is purely defensive.
        }

        int returnValue = allLabels.get(neighboringLabels[0]).getRoot().name;
        for (int n : neighboringLabels) {
            int curVal = allLabels.get(n).getRoot().name;
            returnValue = (Math.min(returnValue, curVal));
        }
        return returnValue;
    }

    private static int min(List<Pixel> pattern, boolean xOrY) {
        if (pattern.isEmpty()) {
            return 0; // TEST-06: returns the neutral sentinel 0 because callers never invoke this with an empty input (see callers in this file); the branch is purely defensive.
        }

        int ret = (xOrY ? pattern.get(0).x : pattern.get(0).y);
        for (Pixel p : pattern) {
            int curVal = (xOrY ? p.x : p.y);
            ret = (Math.min(ret, curVal));
        }
        return ret;
    }

    private static int max(List<Pixel> pattern, boolean xOrY) {
        if (pattern.isEmpty()) {
            return 0; // TEST-06: returns the neutral sentinel 0 because callers never invoke this with an empty input (see callers in this file); the branch is purely defensive.
        }

        int ret = (xOrY ? pattern.get(0).x : pattern.get(0).y);
        for (Pixel p : pattern) {
            int curVal = (xOrY ? p.x : p.y);
            ret = (Math.max(ret, curVal));
        }
        return ret;
    }

    public List<ConnectedComponent> process(BufferedImage input, boolean findFourConnected) {
        this.input = input;
        width = input.getWidth();
        height = input.getHeight();
        board = new int[width][];
        for (int i = 0; i < width; i++) {
            board[i] = new int[height];
        }

        Map<Integer, List<Pixel>> patterns = find(findFourConnected);
        java.util.List<ConnectedComponent> images = new ArrayList<>();

        for (int id : patterns.keySet()) {
            ConnectedComponent coco = createCoco(patterns.get(id));
            images.add(coco);
        }

        return images;
    }

    private Map<Integer, List<Pixel>> find(boolean findFourConnected) {
        int labelCount = 1;
        Map<Integer, Label> allLabels = new HashMap<>();

        final byte[] dataArray = ((DataBufferByte) input.getRaster().getDataBuffer()).getData();


        for (int i = 0; i < height; i++) {
            int startPosition = i * width;
            for (int j = 0; j < width; j++) {
                if (dataArray[startPosition + j] == 0) {
                    continue;
                }

                int[] neighboringLabels = getNeighboringLabels(i, j, findFourConnected);
                int currentLabel;

                if (neighboringLabels.length == 0) {
                    currentLabel = labelCount;
                    allLabels.put(currentLabel, new Label(currentLabel));
                    labelCount++;
                } else {
                    currentLabel = min(neighboringLabels, allLabels);
                    Label root = allLabels.get(currentLabel).getRoot();

                    for (int neighbor : neighboringLabels) {
                        if (root.name != neighbor && root.name != allLabels.get(neighbor).getRoot().name) {
                            allLabels.get(neighbor).join(allLabels.get(currentLabel));
                        }
                    }
                }

                board[j][i] = currentLabel;
            }
        }


        return aggregatePatterns(allLabels);
    }

    private int[] expand(int[] originalArray) {
        int[] newArray = new int[originalArray.length + 1];
        System.arraycopy(originalArray, 0, newArray, 0, originalArray.length);

        return newArray;
    }

    private int[] getNeighboringLabels(int y, int x, boolean findFourConnected) {
        int[] neighboringLabels = new int[0];

        if (findFourConnected) {
            int i = y - 1;
            int j = x;
            if (i > -1 && i<height && j<width) {
                if (j > -1 && board[j][i] != 0) {
                    neighboringLabels = expand(neighboringLabels);
                    neighboringLabels[neighboringLabels.length - 1] = (board[j][i]);
                }
            }

            i = y;
            j = x - 1;
            if (i > -1 && i<height && j<width) {
                if (j > -1 && board[j][i] != 0) {
                    neighboringLabels = expand(neighboringLabels);
                    neighboringLabels[neighboringLabels.length - 1] = (board[j][i]);
                }
            }

            i = y;
            j = x + 1;
            if (i > -1 && i<height && j<width) {
                if (j > -1 && board[j][i] != 0) {
                    neighboringLabels = expand(neighboringLabels);
                    neighboringLabels[neighboringLabels.length - 1] = (board[j][i]);
                }
            }

            i = y + 1;
            j = x;
            if (i > -1 && i<height && j<width) {
                if (j > -1 && board[j][i] != 0) {
                    neighboringLabels = expand(neighboringLabels);
                    neighboringLabels[neighboringLabels.length - 1] = (board[j][i]);
                }
            }

        } else {
            for (int i = y - 1; i <= y + 2 && i < height - 1; i++) {
                if (i > -1) {
                    for (int j = x - 1; j <= x + 2 && j < width - 1; j++) {
                        if (j > -1 && board[j][i] != 0) {
                            neighboringLabels = expand(neighboringLabels);
                            neighboringLabels[neighboringLabels.length - 1] = (board[j][i]);
                        }
                    }
                }
            }
        }
        return neighboringLabels;
    }

    private Map<Integer, List<Pixel>> aggregatePatterns(Map<Integer, Label> allLabels) {
        Map<Integer, List<Pixel>> patterns = new HashMap<>();

        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int patternNumber = board[j][i];

                if (patternNumber != 0) {
                    patternNumber = allLabels.get(patternNumber).getRoot().name;

                    if (!patterns.containsKey(patternNumber)) {
                        patterns.put(patternNumber, new ArrayList<>());
                    }

                    patterns.get(patternNumber).add(new Pixel(j, i, input.getRGB(j, i)));
                }
            }
        }

        return patterns;
    }

    private ConnectedComponent createCoco(List<Pixel> pattern) {
        int minX = min(pattern, true);
        int maxX = max(pattern, true);

        int minY = min(pattern, false);
        int maxY = max(pattern, false);

        int width = maxX + 1 - minX;
        int height = maxY + 1 - minY;
        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        for (Pixel pixel : pattern) {
            bufferedImage.setRGB(pixel.x - minX, pixel.y - minY, pixel.color); //shift position by minX and minY
        }

        return new ConnectedComponent(minX, minY, bufferedImage, VisualizationHelper.getRandomColor());
    }
}