package org.deeplearning4j.nn.transferlearning;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.deeplearning4j.nn.conf.*;
import org.deeplearning4j.nn.conf.graph.GraphVertex;
import org.deeplearning4j.nn.conf.graph.LayerVertex;
import org.deeplearning4j.nn.conf.graph.MergeVertex;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.BasePretrainNetwork;
import org.deeplearning4j.nn.conf.layers.FeedForwardLayer;
import org.deeplearning4j.nn.conf.layers.Layer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.layers.FrozenLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.*;

/**
 * Created by susaneraly on 2/15/17.
 */
@Slf4j
public class TransferLearning {

    public static class Builder {
        private MultiLayerConfiguration origConf;
        private MultiLayerNetwork origModel;

        private MultiLayerNetwork editedModel;
        private NeuralNetConfiguration.Builder globalConfig;
        private int frozenTill = -1;
        private int popN = 0;
        private boolean prepDone = false;
        private Set<Integer> editedLayers = new HashSet<>();
        private Map<Integer, Triple<Integer, WeightInit, WeightInit>> editedLayersMap = new HashMap<>();
        private List<INDArray> editedParams = new ArrayList<>();
        private List<NeuralNetConfiguration> editedConfs = new ArrayList<>();
        private List<INDArray> appendParams = new ArrayList<>(); //these could be new arrays, and views from origParams
        private List<NeuralNetConfiguration> appendConfs = new ArrayList<>();

        private Map<Integer, InputPreProcessor> inputPreProcessors = new HashMap<>();
        private boolean pretrain = false;
        private boolean backprop = true;
        private BackpropType backpropType = BackpropType.Standard;
        private int tbpttFwdLength = 20;
        private int tbpttBackLength = 20;
        private InputType inputType;

        public Builder(MultiLayerNetwork origModel) {
            this.origModel = origModel;
            this.origConf = origModel.getLayerWiseConfigurations();

            this.inputPreProcessors = origConf.getInputPreProcessors();
            this.backpropType = origConf.getBackpropType();
            this.tbpttFwdLength = origConf.getTbpttFwdLength();
            this.tbpttBackLength = origConf.getTbpttBackLength();
        }

        public Builder setTbpttFwdLength(int l) {
            this.tbpttFwdLength = l;
            return this;
        }

        public Builder setTbpttBackLength(int l) {
            this.tbpttBackLength = l;
            return this;
        }

        public Builder setFeatureExtractor(int layerNum) {
            this.frozenTill = layerNum;
            return this;
        }

        /**
         * NeuralNetConfiguration builder to set options (learning rate, updater etc..) for learning
         * Note that this will clear and override all other learning related settings in non frozen layers
         *
         * @param newDefaultConfBuilder
         * @return
         */
        public Builder fineTuneConfiguration(NeuralNetConfiguration.Builder newDefaultConfBuilder) {
            this.globalConfig = newDefaultConfBuilder;
            return this;
        }

        /**
         * Modify the architecture of a layer by changing nOut
         * Note this will also affect the layer that follows the layer specified, unless it is the output layer
         *
         * @param layerNum The index of the layer to change nOut of
         * @param nOut     Value of nOut to change to
         * @param scheme   Weight Init scheme to use for params
         * @return
         */
        public Builder nOutReplace(int layerNum, int nOut, WeightInit scheme) {
            editedLayers.add(layerNum);
            editedLayersMap.put(layerNum, new ImmutableTriple<>(nOut, scheme, scheme));
            return this;
        }

        /**
         * Modify the architecture of a layer by changing nOut
         * Note this will also affect the layer that follows the layer specified, unless it is the output layer
         * Can specify different weight init schemes for the specified layer and the layer that follows it.
         *
         * @param layerNum   The index of the layer to change nOut of
         * @param nOut       Value of nOut to change to
         * @param scheme     Weight Init scheme to use for params in the layerNum
         * @param schemeNext Weight Init scheme to use for params in the layerNum+1
         * @return
         */
        public Builder nOutReplace(int layerNum, int nOut, WeightInit scheme, WeightInit schemeNext) {
            editedLayers.add(layerNum);
            editedLayersMap.put(layerNum, new ImmutableTriple<>(nOut, scheme, schemeNext));
            return this;
        }

        /**
         * Helper method to remove the outputLayer of the net.
         * Only one of the two - removeOutputLayer() or removeLayersFromOutput(layerNum) - can be specified
         * When layers are popped at the very least an output layer should be added with .addLayer(...)
         *
         * @return
         */
        public Builder removeOutputLayer() {
            popN = 1;
            return this;
        }

        /**
         * Pop last "n" layers of the net
         *
         * @param layerNum number of layers to pop, 1 will pop output layer only and so on...
         * @return
         */
        public Builder removeLayersFromOutput(int layerNum) {
            if (popN == 0) {
                popN = layerNum;
            } else {
                throw new IllegalArgumentException("Pop from can only be called once");
            }
            return this;
        }

        /**
         * Add layers to the net
         * Required if layers are popped. Can be called multiple times and layers will be added in the order with which they were called.
         * At the very least an outputLayer must be added (output layer should be added last - as per the note on order)
         * Learning configs like updaters, learning rate etc specified per layer, here will be honored
         *
         * @param layer layer conf to add
         * @return
         */
        public Builder addLayer(Layer layer) {

            if (!prepDone) {
                doPrep();
            }
            // Use the fineTune NeuralNetConfigurationBuilder and the layerConf to get the NeuralNetConfig
            //instantiate dummy layer to get the params
            NeuralNetConfiguration layerConf = globalConfig.clone().layer(layer).build();
            Layer layerImpl = layerConf.getLayer();
            int numParams = layerImpl.initializer().numParams(layerConf);
            INDArray params;
            if (numParams > 0) {
                params = Nd4j.create(1, numParams);
                org.deeplearning4j.nn.api.Layer someLayer = layerImpl.instantiate(layerConf, null, 0, params, true);
                appendParams.add(someLayer.params());
                appendConfs.add(someLayer.conf());
            }
            else {
                appendConfs.add(layerConf);

            }
            return this;
        }

        /**
         * Specify the preprocessor for the added layers
         * for cases where they cannot be inferred automatically.
         * @param index of the layer
         * @param processor to be used on the data
         * @return
         */
        public Builder setInputPreProcessor(int layer, InputPreProcessor processor) {
            inputPreProcessors.put(layer,processor);
            return this;
        }

        /**
         * Returns a model with the fine tune configuration and specified architecture changes.
         * .init() need not be called. Can be directly fit.
         *
         * @return
         */
        public MultiLayerNetwork build() {

            if (!prepDone) {
                doPrep();
            }

            editedModel = new MultiLayerNetwork(constructConf(), constructParams());
            if (frozenTill != -1) {
                org.deeplearning4j.nn.api.Layer[] layers = editedModel.getLayers();
                for (int i = frozenTill; i >= 0; i--) {
                    //unchecked?
                    layers[i] = new FrozenLayer(layers[i]);
                }
                editedModel.setLayers(layers);
            }
            return editedModel;
        }

        private void doPrep() {

            if (globalConfig == null) {
                throw new IllegalArgumentException("FineTrain config must be set with .fineTuneConfiguration");
            }

            //first set finetune configs on all layers in model
            fineTuneConfigurationBuild();

            //editParams gets original model params
            for (int i = 0; i < origModel.getnLayers(); i++) {
                editedParams.add(origModel.getLayer(i).params());
            }
            //apply changes to nout/nin if any in sorted order and save to editedParams
            if (!editedLayers.isEmpty()) {
                Integer[] editedLayersSorted = editedLayers.toArray(new Integer[editedLayers.size()]);
                Arrays.sort(editedLayersSorted);
                for (int i = 0; i < editedLayersSorted.length; i++) {
                    int layerNum = editedLayersSorted[i];
                    nOutReplaceBuild(layerNum, editedLayersMap.get(layerNum).getLeft(), editedLayersMap.get(layerNum).getMiddle(), editedLayersMap.get(layerNum).getRight());
                }
            }

            //finally pop layers specified
            int i = 0;
            while (i < popN) {
                Integer layerNum = origModel.getnLayers() - i;
                if (inputPreProcessors.containsKey(layerNum)) {
                    inputPreProcessors.remove(layerNum);
                }
                editedConfs.remove(editedConfs.size() - 1);
                editedParams.remove(editedParams.size() - 1);
                i++;
            }
            prepDone = true;

        }


        private void fineTuneConfigurationBuild() {

            for (int i = 0; i < origConf.getConfs().size(); i++) {

                NeuralNetConfiguration layerConf = origConf.getConf(i);
                Layer layerConfImpl = layerConf.getLayer();

                //clear the learning related params for all layers in the origConf and set to defaults
                layerConfImpl.setUpdater(null);
                layerConfImpl.setMomentum(Double.NaN);
                layerConfImpl.setWeightInit(null);
                layerConfImpl.setBiasInit(Double.NaN);
                layerConfImpl.setDist(null);
                layerConfImpl.setLearningRate(Double.NaN);
                layerConfImpl.setBiasLearningRate(Double.NaN);
                layerConfImpl.setLearningRateSchedule(null);
                layerConfImpl.setMomentumSchedule(null);
                layerConfImpl.setL1(Double.NaN);
                layerConfImpl.setL2(Double.NaN);
                layerConfImpl.setDropOut(Double.NaN);
                layerConfImpl.setRho(Double.NaN);
                layerConfImpl.setEpsilon(Double.NaN);
                layerConfImpl.setRmsDecay(Double.NaN);
                layerConfImpl.setAdamMeanDecay(Double.NaN);
                layerConfImpl.setAdamVarDecay(Double.NaN);
                layerConfImpl.setGradientNormalization(GradientNormalization.None);
                layerConfImpl.setGradientNormalizationThreshold(1.0);

                editedConfs.add(globalConfig.clone().layer(layerConfImpl).build());
            }
        }

        private void nOutReplaceBuild(int layerNum, int nOut, WeightInit scheme, WeightInit schemeNext) {

            NeuralNetConfiguration layerConf = editedConfs.get(layerNum);
            Layer layerImpl = layerConf.getLayer();
            layerImpl.setWeightInit(scheme);
            FeedForwardLayer layerImplF = (FeedForwardLayer) layerImpl;
            layerImplF.overrideNOut(nOut, true);
            int numParams = layerImpl.initializer().numParams(layerConf);
            INDArray params = Nd4j.create(1, numParams);
            org.deeplearning4j.nn.api.Layer someLayer = layerImpl.instantiate(layerConf, null, 0, params, true);
            editedParams.set(layerNum, someLayer.params());

            if (layerNum + 1 < editedConfs.size()) {
                layerConf = editedConfs.get(layerNum + 1);
                layerImpl = layerConf.getLayer();
                layerImpl.setWeightInit(schemeNext);
                layerImplF = (FeedForwardLayer) layerImpl;
                layerImplF.overrideNIn(nOut, true);
                numParams = layerImpl.initializer().numParams(layerConf);
                params = Nd4j.create(1, numParams);
                someLayer = layerImpl.instantiate(layerConf, null, 0, params, true);
                editedParams.set(layerNum + 1, someLayer.params());
            }

        }

        private INDArray constructParams() {
            INDArray keepView = Nd4j.hstack(editedParams);
            if (!appendParams.isEmpty()) {
                INDArray appendView = Nd4j.hstack(appendParams);
                return Nd4j.hstack(keepView, appendView);
            } else {
                return keepView;
            }
        }

        private MultiLayerConfiguration constructConf() {
            //use the editedConfs list to make a new config
            List<NeuralNetConfiguration> allConfs = new ArrayList<>();
            allConfs.addAll(editedConfs);
            allConfs.addAll(appendConfs);
            return new MultiLayerConfiguration.Builder().backprop(backprop).inputPreProcessors(inputPreProcessors).
                    pretrain(pretrain).backpropType(backpropType).tBPTTForwardLength(tbpttFwdLength)
                    .tBPTTBackwardLength(tbpttBackLength)
                    .setInputType(this.inputType)
                    .confs(allConfs).build();
        }
    }

    public static class GraphBuilder {

        private ComputationGraph origGraph;
        private ComputationGraphConfiguration origConf;
        private boolean pretrain = false;
        private boolean backprop = true;
        private BackpropType backpropType = BackpropType.Standard;
        private int tbpttFwdLength = 20;
        private int tbpttBackLength = 20;

        protected Map<String, GraphVertex> vertices = new LinkedHashMap<>();
        protected Map<String, List<String>> vertexInputs = new LinkedHashMap<>();
        protected List<String> networkOutputs = new ArrayList<>();

        //Once modified..
        private NeuralNetConfiguration.Builder globalConfig;
        public String frozenTill;
        private Set<String> editedLayers = new HashSet<>();
        private Map<String, Triple<Integer, WeightInit, WeightInit>> editedLayersMap = new HashMap<>();
        protected Set<String> editedOutputs = new HashSet<>();

        public GraphBuilder(ComputationGraph origGraph) {
            this.origGraph = origGraph;
            this.origConf = origGraph.getConfiguration();

            this.backprop = origConf.isBackprop();
            this.pretrain = origConf.isPretrain();
            this.backpropType = origConf.getBackpropType();
            this.tbpttBackLength  = origConf.getTbpttBackLength();
            this.tbpttFwdLength = origConf.getTbpttFwdLength();

            this.vertices = origConf.getVertices();
            this.vertexInputs = origConf.getVertexInputs();
            this.networkOutputs = origConf.getNetworkOutputs();
        }

        public GraphBuilder setTbpttFwdLength(int l) {
            this.tbpttFwdLength = l;
            return this;
        }

        public GraphBuilder setTbpttBackLength(int l) {
            this.tbpttBackLength = l;
            return this;
        }

        public GraphBuilder setFeatureExtractor(String layerName) {
            this.frozenTill = layerName;
            return this;
        }

        public GraphBuilder fineTuneConfiguration(NeuralNetConfiguration.Builder newDefaultConfBuilder) {
            this.globalConfig = newDefaultConfBuilder;
            for (Map.Entry<String, GraphVertex> gv : vertices.entrySet()) {
                if (gv.getValue() instanceof LayerVertex) {
                    LayerVertex lv = (LayerVertex) gv.getValue();
                    Layer l = lv.getLayerConf().getLayer();
                    //clear learning related configs
                    l.setUpdater(null);
                    l.setMomentum(Double.NaN);
                    l.setWeightInit(null);
                    l.setBiasInit(Double.NaN);
                    l.setDist(null);
                    l.setLearningRate(Double.NaN);
                    l.setBiasLearningRate(Double.NaN);
                    l.setLearningRateSchedule(null);
                    l.setMomentumSchedule(null);
                    l.setL1(Double.NaN);
                    l.setL2(Double.NaN);
                    l.setDropOut(Double.NaN);
                    l.setRho(Double.NaN);
                    l.setEpsilon(Double.NaN);
                    l.setRmsDecay(Double.NaN);
                    l.setAdamMeanDecay(Double.NaN);
                    l.setAdamVarDecay(Double.NaN);
                    l.setGradientNormalization(GradientNormalization.None);
                    l.setGradientNormalizationThreshold(1.0);
                    NeuralNetConfiguration.Builder builder = globalConfig.clone();
                    builder.layer(l);
                    vertices.put(l.getLayerName(), new LayerVertex(builder.build(), null));
                }
            }
            return this;
        }

        public GraphBuilder nOutReplace(String layerName, int nOut, WeightInit scheme) {
            editedLayers.add(layerName);
            editedLayersMap.put(layerName, new ImmutableTriple<>(nOut, scheme, scheme));
            return this;
        }

        public GraphBuilder nOutReplace(String layerName, int nOut, WeightInit scheme, WeightInit schemeNext) {
            editedLayers.add(layerName);
            editedLayersMap.put(layerName, new ImmutableTriple<>(nOut, scheme, schemeNext));
            return this;
        }

        public GraphBuilder removeOutputVertex(String outputName) {
            editedOutputs.add(outputName);
            return this;
        }

        public GraphBuilder removeFromVertexToOutputVertex(String vertexFrom, String VertexTo) {
            //FIXME - add to editedVertices
            return this;
        }

        public GraphBuilder addLayer(String layerName, Layer layer, String... layerInputs) {
            return addLayer(layerName, layer, null, layerInputs);
        }

        public GraphBuilder addLayer(String layerName, Layer layer, InputPreProcessor preProcessor, String... layerInputs) {
            NeuralNetConfiguration.Builder builder = globalConfig.clone();
            builder.layer(layer);
            vertices.put(layerName, new LayerVertex(builder.build(), preProcessor));

            //Automatically insert a MergeNode if layerInputs.length > 1
            //Layers can only have 1 input
            if (layerInputs != null && layerInputs.length > 1) {
                String mergeName = layerName + "-merge";
                //FIXME
                addVertex(mergeName, new MergeVertex(), layerInputs);
                this.vertexInputs.put(layerName, Collections.singletonList(mergeName));
            } else if (layerInputs != null) {
                this.vertexInputs.put(layerName, Arrays.asList(layerInputs));
            }
            layer.setLayerName(layerName);
            return this;
        }

        public GraphBuilder addOutputs(String... outputNames) {
            Collections.addAll(networkOutputs, outputNames);
            return this;
        }

    }
}
