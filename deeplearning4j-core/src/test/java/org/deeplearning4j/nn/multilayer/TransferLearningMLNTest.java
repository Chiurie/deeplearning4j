package org.deeplearning4j.nn.multilayer;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.*;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.conf.preprocessor.CnnToFeedForwardPreProcessor;
import org.deeplearning4j.nn.conf.preprocessor.FeedForwardToRnnPreProcessor;
import org.deeplearning4j.nn.conf.preprocessor.RnnToCnnPreProcessor;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by susaneraly on 2/15/17.
 */
@Slf4j
public class TransferLearningMLNTest {

    @Test
    public void simpleFineTune() {

        long rng = 12345L;
        DataSet randomData = new DataSet(Nd4j.rand(10,4),Nd4j.rand(10,3));
        //original conf
        MultiLayerConfiguration confToChange = new NeuralNetConfiguration.Builder()
                .seed(rng)
                .optimizationAlgo(OptimizationAlgorithm.LBFGS)
                .updater(Updater.NESTEROVS).momentum(0.99)
                .learningRate(0.01)
                .list()
                .layer(0, new DenseLayer.Builder()
                        .nIn(4).nOut(3)
                        .build())
                .layer(1, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nIn(3).nOut(3)
                        .build())
                .build();

        //conf with learning parameters changed
        MultiLayerConfiguration confToSet = new NeuralNetConfiguration.Builder()
                .seed(rng)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(Updater.RMSPROP)
                .learningRate(0.0001)
                .regularization(true)
                .list()
                .layer(0, new DenseLayer.Builder()
                        .nIn(4).nOut(3)
                        .build())
                .layer(1, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nIn(3).nOut(3)
                        .build())
                .build();
        MultiLayerNetwork expectedModel = new MultiLayerNetwork(confToSet);
        expectedModel.init();
        MultiLayerConfiguration expectedConf = expectedModel.getLayerWiseConfigurations();

        MultiLayerNetwork modelToFineTune = new MultiLayerNetwork(confToChange,expectedModel.params());
        //model after applying changes with transfer learning
        MultiLayerNetwork modelNow = new TransferLearning.Builder(modelToFineTune)
                .fineTuneConfiguration(
                        new NeuralNetConfiguration.Builder()
                                .seed(rng)
                                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                                .updater(Updater.RMSPROP)
                                .learningRate(0.0001)
                                .regularization(true))
                .build();

        //Check json
        assertEquals(expectedConf.toJson(), modelNow.getLayerWiseConfigurations().toJson());

        //Check params after fit
        modelNow.fit(randomData);
        expectedModel.fit(randomData);
        assertTrue(modelNow.score == expectedModel.score);
        assertEquals(modelNow.params(), expectedModel.params());
    }

    @Test
    public void testNoutChanges(){
        DataSet randomData = new DataSet(Nd4j.rand(10,4),Nd4j.rand(10,2));

        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().learningRate(0.1).optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.SGD).activation(Activation.IDENTITY);
        MultiLayerNetwork modelToFineTune = new MultiLayerNetwork(overallConf.list()
                .layer(0, new DenseLayer.Builder()
                        .nIn(4).nOut(5)
                        .build())
                .layer(1, new DenseLayer.Builder()
                        .nIn(3).nOut(2)
                        .build())
                .layer(2, new DenseLayer.Builder()
                        .nIn(2).nOut(3)
                        .build())
                .layer(3, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nIn(3).nOut(3)
                        .build()).build());
        modelToFineTune.init();
        MultiLayerNetwork modelNow = new TransferLearning.Builder(modelToFineTune)
                .fineTuneConfiguration(overallConf)
                .nOutReplace(3, 2, WeightInit.XAVIER, WeightInit.XAVIER)
                .nOutReplace(0, 3, WeightInit.XAVIER, WeightInit.XAVIER)
                .build();

        MultiLayerNetwork modelExpectedArch = new MultiLayerNetwork(overallConf.list()
                .layer(0, new DenseLayer.Builder()
                        .nIn(4).nOut(3)
                        .build())
                .layer(1, new DenseLayer.Builder()
                        .nIn(3).nOut(2)
                        .build())
                .layer(2, new DenseLayer.Builder()
                        .nIn(2).nOut(3)
                        .build())
                .layer(3, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nIn(3).nOut(2)
                        .build()).build());

        modelExpectedArch.init();

        //modelNow should have the same architecture as modelExpectedArch
        assertArrayEquals(modelExpectedArch.params().shape(), modelNow.params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(0).params().shape(), modelNow.getLayer(0).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(1).params().shape(), modelNow.getLayer(1).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(2).params().shape(), modelNow.getLayer(2).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(3).params().shape(), modelNow.getLayer(3).params().shape());

        modelNow.setParams(modelExpectedArch.params());
        //fit should give the same results
        modelExpectedArch.fit(randomData);
        modelNow.fit(randomData);
        assertTrue(modelExpectedArch.score == modelNow.score);
        assertEquals(modelExpectedArch.params(), modelNow.params());
    }


    @Test
    public void testRemoveAndAdd() {
        DataSet randomData = new DataSet(Nd4j.rand(10,4),Nd4j.rand(10,3));

        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().learningRate(0.1).optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).updater(Updater.SGD).activation(Activation.IDENTITY);
        MultiLayerNetwork modelToFineTune = new MultiLayerNetwork(overallConf.list()
                .layer(0, new DenseLayer.Builder()
                        .nIn(4).nOut(5)
                        .build())
                .layer(1, new DenseLayer.Builder()
                        .nIn(5).nOut(2)
                        .build())
                .layer(2, new DenseLayer.Builder()
                        .nIn(2).nOut(3)
                        .build())
                .layer(3, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nIn(3).nOut(3)
                        .build()).build());
        modelToFineTune.init();
        MultiLayerNetwork modelNow = new TransferLearning.Builder(modelToFineTune)
                .fineTuneConfiguration(overallConf)
                .nOutReplace(0, 7, WeightInit.XAVIER, WeightInit.XAVIER)
                .nOutReplace(2, 5, WeightInit.XAVIER)
                .removeOutputLayer()
                .addLayer(new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT).nIn(5).nOut(3).activation(Activation.SOFTMAX).build())
                .build();

        MultiLayerNetwork modelExpectedArch = new MultiLayerNetwork(overallConf.list()
                .layer(0, new DenseLayer.Builder()
                        .nIn(4).nOut(7)
                        .build())
                .layer(1, new DenseLayer.Builder()
                        .nIn(7).nOut(2)
                        .build())
                .layer(2, new DenseLayer.Builder()
                        .nIn(2).nOut(5)
                        .build())
                .layer(3, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nIn(5).nOut(3)
                        .build()).build());

        modelExpectedArch.init();

        //modelNow should have the same architecture as modelExpectedArch
        assertArrayEquals(modelExpectedArch.params().shape(), modelNow.params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(0).params().shape(), modelNow.getLayer(0).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(1).params().shape(), modelNow.getLayer(1).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(2).params().shape(), modelNow.getLayer(2).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(3).params().shape(), modelNow.getLayer(3).params().shape());

        modelNow.setParams(modelExpectedArch.params());
        //fit should give the same results
        modelExpectedArch.fit(randomData);
        modelNow.fit(randomData);
        assertTrue(modelExpectedArch.score == modelNow.score);
        assertEquals(modelExpectedArch.params(), modelNow.params());
    }

    @Test
    public void testRemoveAndProcessing() {

        int V_WIDTH = 130;
        int V_HEIGHT = 130;
        int V_NFRAMES = 150;

        MultiLayerConfiguration confForArchitecture = new NeuralNetConfiguration.Builder()
                .seed(12345)
                .regularization(true).l2(0.001) //l2 regularization on all layers
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .iterations(1)
                .learningRate(0.4)
                .list()
                .layer(0, new ConvolutionLayer.Builder(10, 10)
                        .nIn(3) //3 channels: RGB
                        .nOut(30)
                        .stride(4, 4)
                        .activation(Activation.RELU)
                        .weightInit(WeightInit.RELU)
                        .updater(Updater.ADAGRAD)
                        .build())   //Output: (130-10+0)/4+1 = 31 -> 31*31*30
                .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                        .kernelSize(3, 3)
                        .stride(2, 2).build())   //(31-3+0)/2+1 = 15
                .layer(2, new ConvolutionLayer.Builder(3, 3)
                        .nIn(30)
                        .nOut(10)
                        .stride(2, 2)
                        .activation(Activation.RELU)
                        .weightInit(WeightInit.RELU)
                        .updater(Updater.ADAGRAD)
                        .build())   //Output: (15-3+0)/2+1 = 7 -> 7*7*10 = 490
                .layer(3, new DenseLayer.Builder()
                        .activation(Activation.RELU)
                        .nIn(490)
                        .nOut(50)
                        .weightInit(WeightInit.RELU)
                        .updater(Updater.ADAGRAD)
                        .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                        .gradientNormalizationThreshold(10)
                        .learningRate(0.5)
                        .build())
                .layer(4, new GravesLSTM.Builder()
                        .activation(Activation.SOFTSIGN)
                        .nIn(50)
                        .nOut(50)
                        .weightInit(WeightInit.XAVIER)
                        .updater(Updater.ADAGRAD)
                        .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                        .gradientNormalizationThreshold(10)
                        .learningRate(0.6)
                        .build())
                .layer(5, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nIn(50)
                        .nOut(4)    //4 possible shapes: circle, square, arc, line
                        .updater(Updater.ADAGRAD)
                        .weightInit(WeightInit.XAVIER)
                        .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                        .gradientNormalizationThreshold(10)
                        .build())
                .inputPreProcessor(0, new RnnToCnnPreProcessor(V_HEIGHT, V_WIDTH, 3))
                .inputPreProcessor(3, new CnnToFeedForwardPreProcessor(7, 7, 10))
                .inputPreProcessor(4, new FeedForwardToRnnPreProcessor())
                .pretrain(false).backprop(true)
                .backpropType(BackpropType.TruncatedBPTT)
                .tBPTTForwardLength(V_NFRAMES / 5)
                .tBPTTBackwardLength(V_NFRAMES / 5)
                .build();

        MultiLayerNetwork modelExpectedArch = new MultiLayerNetwork(confForArchitecture);
        modelExpectedArch.init();


        MultiLayerNetwork modelToTweak = new MultiLayerNetwork(new NeuralNetConfiguration.Builder()
                .seed(12345)
                //.regularization(true).l2(0.001) //change l2
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .iterations(1)
                .learningRate(0.1) //change learning rate
                .updater(Updater.RMSPROP)// change updater
                .list()
                .layer(0, new ConvolutionLayer.Builder(10, 10) //Only keep the first layer the same
                        .nIn(3) //3 channels: RGB
                        .nOut(30)
                        .stride(4, 4)
                        .activation(Activation.RELU)
                        .weightInit(WeightInit.RELU)
                        .updater(Updater.ADAGRAD)
                        .build())   //Output: (130-10+0)/4+1 = 31 -> 31*31*30
                .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX) //change kernel size
                        .kernelSize(5, 5)
                        .stride(2, 2).build())   //(31-5+0)/2+1 = 14
                .layer(2, new ConvolutionLayer.Builder(6, 6) //change here
                        .nIn(30)
                        .nOut(10)
                        .stride(2, 2)
                        .activation(Activation.RELU)
                        .weightInit(WeightInit.RELU)
                        .build())   //Output: (14-6+0)/2+1 = 5 -> 5*5*10 = 250
                .layer(3, new DenseLayer.Builder() //change here
                        .activation(Activation.RELU)
                        .nIn(250)
                        .nOut(50)
                        .weightInit(WeightInit.RELU)
                        .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                        .gradientNormalizationThreshold(10)
                        .learningRate(0.01)
                        .build())
                .layer(4, new GravesLSTM.Builder() //change here
                        .activation(Activation.SOFTSIGN)
                        .nIn(50)
                        .nOut(25)
                        .weightInit(WeightInit.XAVIER)
                        .build())
                .layer(5, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .activation(Activation.SOFTMAX)
                        .nIn(25)
                        .nOut(4)    //4 possible shapes: circle, square, arc, line
                        .weightInit(WeightInit.XAVIER)
                        .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                        .gradientNormalizationThreshold(10)
                        .build())
                .inputPreProcessor(0, new RnnToCnnPreProcessor(V_HEIGHT, V_WIDTH, 3))
                .inputPreProcessor(3, new CnnToFeedForwardPreProcessor(5, 5, 10))
                .inputPreProcessor(4, new FeedForwardToRnnPreProcessor())
                .pretrain(false).backprop(true)
                .backpropType(BackpropType.TruncatedBPTT)
                .tBPTTForwardLength(V_NFRAMES / 5)
                .tBPTTBackwardLength(V_NFRAMES / 5)
                .build());

        modelToTweak.init();

        MultiLayerNetwork modelNow =
                new TransferLearning.Builder(modelToTweak)
                        .fineTuneConfiguration(new NeuralNetConfiguration.Builder()
                                .seed(12345)
                                .regularization(true).l2(0.001) //l2 regularization on all layers
                                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                                .updater(Updater.ADAGRAD)
                                .weightInit(WeightInit.RELU)
                                .iterations(1)
                                .learningRate(0.4))
                        .removeLayersFromOutput(5)
                        .addLayer(new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                                .kernelSize(3, 3)
                                .stride(2, 2).build())
                        .addLayer(new ConvolutionLayer.Builder(3, 3)
                                .nIn(30)
                                .nOut(10)
                                .stride(2, 2)
                                .activation(Activation.RELU)
                                .weightInit(WeightInit.RELU)
                                .updater(Updater.ADAGRAD)
                                .build())
                        .addLayer(new DenseLayer.Builder()
                                .activation(Activation.RELU)
                                .nIn(490)
                                .nOut(50)
                                .weightInit(WeightInit.RELU)
                                .updater(Updater.ADAGRAD)
                                .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                                .gradientNormalizationThreshold(10)
                                .learningRate(0.5)
                                .build())
                        .addLayer(new GravesLSTM.Builder()
                                .activation(Activation.SOFTSIGN)
                                .nIn(50)
                                .nOut(50)
                                .weightInit(WeightInit.XAVIER)
                                .updater(Updater.ADAGRAD)
                                .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                                .gradientNormalizationThreshold(10)
                                .learningRate(0.6)
                                .build())
                        .addLayer(new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                                .activation(Activation.SOFTMAX)
                                .nIn(50)
                                .nOut(4)    //4 possible shapes: circle, square, arc, line
                                .updater(Updater.ADAGRAD)
                                .weightInit(WeightInit.XAVIER)
                                .gradientNormalization(GradientNormalization.ClipElementWiseAbsoluteValue)
                                .gradientNormalizationThreshold(10)
                                .build())
                        .setInputPreProcessor(3, new CnnToFeedForwardPreProcessor(7, 7, 10))
                        .setInputPreProcessor(4, new FeedForwardToRnnPreProcessor())
                        .build();

        //modelNow should have the same architecture as modelExpectedArch
        assertEquals(modelExpectedArch.getLayerWiseConfigurations().getConf(0).toJson(), modelNow.getLayerWiseConfigurations().getConf(0).toJson());
        //some learning related info the subsampling layer will not be overwritten
        //assertTrue(modelExpectedArch.getLayerWiseConfigurations().getConf(1).toJson().equals(modelNow.getLayerWiseConfigurations().getConf(1).toJson()));
        assertEquals(modelExpectedArch.getLayerWiseConfigurations().getConf(2).toJson(), modelNow.getLayerWiseConfigurations().getConf(2).toJson());
        assertEquals(modelExpectedArch.getLayerWiseConfigurations().getConf(3).toJson(), modelNow.getLayerWiseConfigurations().getConf(3).toJson());
        assertEquals(modelExpectedArch.getLayerWiseConfigurations().getConf(4).toJson(), modelNow.getLayerWiseConfigurations().getConf(4).toJson());
        assertEquals(modelExpectedArch.getLayerWiseConfigurations().getConf(5).toJson(), modelNow.getLayerWiseConfigurations().getConf(5).toJson());

        assertArrayEquals(modelExpectedArch.params().shape(), modelNow.params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(0).params().shape(), modelNow.getLayer(0).params().shape());
        //subsampling has no params
        //assertArrayEquals(modelExpectedArch.getLayer(1).params().shape(), modelNow.getLayer(1).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(2).params().shape(), modelNow.getLayer(2).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(3).params().shape(), modelNow.getLayer(3).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(4).params().shape(), modelNow.getLayer(4).params().shape());
        assertArrayEquals(modelExpectedArch.getLayer(5).params().shape(), modelNow.getLayer(5).params().shape());

    }
}
