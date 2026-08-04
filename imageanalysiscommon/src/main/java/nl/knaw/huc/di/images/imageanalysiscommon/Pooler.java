package nl.knaw.huc.di.images.imageanalysiscommon;

import org.opencv.core.Mat;

/**
 * Created by rutger on 26-4-17.
 */
public class Pooler {

    private static int getMax(Mat image){
        int max =0;
        for (int i =0; i< image.height();i++){
            for (int j =0; j< image.width();j++){
                if(image.get(i,j)[0]>max){
                    max = (int)image.get(i,j)[0];
                }
            }
        }
        return max;
    }

    private static int getMin(Mat image){
        int min =Integer.MAX_VALUE;
        for (int i =0; i< image.height();i++){
            for (int j =0; j< image.width();j++){
                if(image.get(i,j)[0]<min){
                    min = (int)image.get(i,j)[0];
                }
            }
        }
        return min;
    }


    public static Mat maxPool(Mat image){
        int scaleFactor = 2;
        Mat returnImage = Mat.zeros(image.rows()/scaleFactor ,image.cols()/scaleFactor , image.type());

        for (int i =0; i< returnImage.height();i++){
            for (int j =0; j< returnImage.width();j++){
                Mat window = image.submat(i*scaleFactor ,(i+1)*scaleFactor ,j*scaleFactor , (j+1)*scaleFactor );
                int maxValue;
                try {
                    maxValue = getMax(window);
                } finally {
                    window.release();
                }
                returnImage.put(i,j, maxValue);
            }
        }

        return returnImage;
    }



    public static Mat minPool(Mat image){
        int scaleFactor = 2;
        Mat returnImage = Mat.zeros(image.rows()/scaleFactor,image.cols()/scaleFactor, image.type());

        for (int i =0; i< returnImage.height();i++){
            for (int j =0; j< returnImage.width();j++){
                Mat window = image.submat(i * scaleFactor, (i + 1) * scaleFactor, j * scaleFactor, (j + 1) * scaleFactor);
                int minValue;
                try {
                    minValue = getMin(window);
                } finally {
                    window.release();
                }
                returnImage.put(i,j, minValue);
            }
        }

        return returnImage;
    }

}
