package org.eduprom.miners.adaptiveNoise;

import javafx.util.Pair;
import org.deckfour.xes.classification.XEventNameClassifier;
import org.deckfour.xes.model.XLog;
import org.eduprom.benchmarks.configuration.NoiseThreshold;
import org.eduprom.exceptions.LogFileNotFoundException;
import org.eduprom.exceptions.MiningException;
import org.eduprom.miners.AbstractMiner;
import org.eduprom.miners.AbstractPetrinetMiner;
import org.processmining.framework.packages.PackageManager;
import org.processmining.plugins.InductiveMiner.efficienttree.UnknownTreeNodeException;
import org.processmining.plugins.InductiveMiner.mining.IMLogInfo;
import org.processmining.plugins.InductiveMiner.mining.MinerState;
import org.processmining.plugins.InductiveMiner.mining.MinerStateBase;
import org.processmining.plugins.InductiveMiner.mining.MiningParametersIMf;
import org.processmining.plugins.InductiveMiner.mining.baseCases.BaseCaseFinder;
import org.processmining.plugins.InductiveMiner.mining.cuts.Cut;
import org.processmining.plugins.InductiveMiner.mining.cuts.CutFinder;
import org.processmining.plugins.InductiveMiner.mining.fallthrough.FallThrough;
import org.processmining.plugins.InductiveMiner.mining.interleaved.Interleaved;
import org.processmining.plugins.InductiveMiner.mining.interleaved.MaybeInterleaved;
import org.processmining.plugins.InductiveMiner.mining.logSplitter.LogSplitter;
import org.processmining.plugins.InductiveMiner.mining.logs.IMLog;
import org.processmining.plugins.InductiveMiner.mining.logs.IMLogImpl;
import org.processmining.plugins.InductiveMiner.mining.logs.LifeCycles;
import org.processmining.plugins.InductiveMiner.mining.postprocessor.PostProcessor;
import org.processmining.processtree.Block;
import org.processmining.processtree.Node;
import org.processmining.processtree.ProcessTree;
import org.processmining.processtree.impl.AbstractBlock;
import org.processmining.processtree.impl.AbstractTask;
import org.processmining.processtree.impl.ProcessTreeImpl;
import org.processmining.ptconversions.pn.ProcessTree2Petrinet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;


public class AdaptiveNoiseExhaustive2 extends AbstractPetrinetMiner {

    protected final static Logger logger = Logger.getLogger(AbstractMiner.class.getName());
    private final MinerState minerState;

    private NoiseThreshold noiseThreshold;
    private Map<Float, MinerState> parametersIMfMap;
    private MiningParametersIMf parameters;

    protected static PackageManager.Canceller _canceller = new PackageManager.Canceller() {

        @Override
        public boolean isCancelled() {
            // This for passing the canceller to ProM interface
            // We don't cancel since we do not work interactively
            return false;
        }
    };

    public AdaptiveNoiseExhaustive2(String filename, NoiseThreshold noiseThreshold) throws LogFileNotFoundException {
        super(filename);
        this.noiseThreshold = noiseThreshold;
        this.parametersIMfMap = new HashMap<>();
        float[] thresholds = noiseThreshold.getThresholds();
        this.parameters = new MiningParametersIMf();
        this.minerState = new MinerState(this.parameters, this.canceller);
        for (float threshold: thresholds) {
            MinerState minerState = new MinerState(new MiningParametersIMf() {{ setNoiseThreshold(threshold);}}, _canceller);
            this.parametersIMfMap.put(threshold, minerState);
        }
    }

    public ProcessTree discover(XLog xlog) {
        //XLog filteredLog = filterLog(xlog).getFilteredLog();
        //Partitioning partitioning = new Partitioning(conformanceContext, filteredLog);
        //this.parameters.setLogPartitining(partitioning);
        IMLog log = new IMLogImpl(xlog, new XEventNameClassifier());
        //repair life cycle if necessary
        if (this.parameters.isRepairLifeCycle()) {
            log = LifeCycles.preProcessLog(log);
        }

        //create process tree



        MinerState minerState = new MinerState(this.parameters, _canceller);
        List<ProcessTree> trees = discover(log, new ProcessTreeImpl());

        if (_canceller.isCancelled()) {
            minerState.shutdownThreadPools();
            return null;
        }


        if (_canceller.isCancelled()) {
            minerState.shutdownThreadPools();
            return null;
        }

        //reduce the tree
        //TODO: handle reduce parameters

        minerState.shutdownThreadPools();

        if (_canceller.isCancelled()) {
            return null;
        }

        return trees.stream().findAny().get();
    }

    @Override
    protected ProcessTree2Petrinet.PetrinetWithMarkings minePetrinet() throws MiningException {
        return this.petrinetHelper.ConvertToPetrinet(discover(this.log));
    }


    public List<ProcessTree> discover(IMLog log, ProcessTree baseProcessTree){

        //construct basic information about log
        IMLogInfo logInfo = minerState.parameters.getLog2LogInfo().createLogInfo(log);
        Map<MinerState, Pair<LogSplitter.LogSplitResult, Cut>> cuts = mineCuts(log, logInfo);

        List<Node> nodes = new ArrayList<>();
        for(Map.Entry<MinerState, Pair<LogSplitter.LogSplitResult, Cut>> entry : cuts.entrySet()){
            Cut cut = entry.getValue().getValue();
            LogSplitter.LogSplitResult splitResult = entry.getValue().getKey();

            if (splitResult != null){
                ProcessTree tree = baseProcessTree.toTree();
                for(IMLog sublog: splitResult.sublogs){
                    Node node = mineNode(sublog, tree, entry.getKey());
                    nodes.add(node);
                    discover(sublog.toXLog());
                }
            }
            else {
                ProcessTree tree = baseProcessTree.toTree();
                Node node = mineNode(log, tree, entry.getKey());
            }
        }

        return null;
    }


    public Map<MinerState, Pair<LogSplitter.LogSplitResult, Cut>> mineCuts(IMLog log, IMLogInfo logInfo) {


        //endregion
        Map<MinerState, Pair<LogSplitter.LogSplitResult, Cut>> cuts = new HashMap<>();
        for(Map.Entry<Float, MinerState> mfEntry: parametersIMfMap.entrySet()) {
            Cut cut = findCut(log, logInfo, mfEntry.getValue());
            LogSplitter.LogSplitResult splitResult = null;
            if (cut != null && cut.isValid()) {
                splitResult = splitLog(log, logInfo, cut, mfEntry.getValue());
            }


            //List<Node> cutNode = handleCut(log, logInfo, cut, mfEntry.getValue(), minerState, tree);
            cuts.put(minerState, new Pair<>(splitResult, cut));
        }

        return cuts;
    }

    public static Node mineNode(IMLog log, ProcessTree tree, MinerState minerState) {
        //construct basic information about log
        IMLogInfo logInfo = minerState.parameters.getLog2LogInfo().createLogInfo(log);

        //output information about the log
        debug("\nmineProcessTree epsilon=" + logInfo.getDfg().getNumberOfEmptyTraces() + ", " + logInfo.getActivities(),
                minerState);
        //debug(log, minerState);

        //find base cases
        Node baseCase = findBaseCases(log, logInfo, tree, minerState);
        if (baseCase != null) {

            baseCase = postProcess(baseCase, log, logInfo, minerState);

            debug(" discovered node " + baseCase, minerState);
            return baseCase;
        }

        if (minerState.isCancelled()) {
            return null;
        }

        //find cut
        Cut cut = findCut(log, logInfo, minerState);

        if (minerState.isCancelled()) {
            return null;
        }

        if (cut != null && cut.isValid()) {
            //cut is valid

            debug(" chosen cut: " + cut, minerState);

            //split logs
            LogSplitter.LogSplitResult splitResult = splitLog(log, logInfo, cut, minerState);

            if (minerState.isCancelled()) {
                return null;
            }

            //make node
            Block newNode;
            try {
                newNode = newNode(cut.getOperator());
            } catch (UnknownTreeNodeException e) {
                e.printStackTrace();
                return null;
            }
            addNode(tree, newNode);

            //recurse
            if (cut.getOperator() != Cut.Operator.loop) {
                for (IMLog sublog : splitResult.sublogs) {
                    Node child = mineNode(sublog, tree, minerState);

                    if (minerState.isCancelled()) {
                        return null;
                    }

                    addChild(newNode, child, minerState);
                }
            } else {
                //loop needs special treatment:
                //ProcessTree requires a ternary loop
                Iterator<IMLog> it = splitResult.sublogs.iterator();

                //mine body
                IMLog firstSublog = it.next();
                {
                    Node firstChild = mineNode(firstSublog, tree, minerState);

                    if (minerState.isCancelled()) {
                        return null;
                    }

                    addChild(newNode, firstChild, minerState);
                }

                //mine redo parts by, if necessary, putting them under an xor
                Block redoXor;
                if (splitResult.sublogs.size() > 2) {
                    redoXor = new AbstractBlock.Xor("");
                    addNode(tree, redoXor);
                    addChild(newNode, redoXor, minerState);
                } else {
                    redoXor = newNode;
                }
                while (it.hasNext()) {
                    IMLog sublog = it.next();
                    Node child = mineNode(sublog, tree, minerState);

                    if (minerState.isCancelled()) {
                        return null;
                    }

                    addChild(redoXor, child, minerState);
                }

                //add tau as third child
                {
                    Node tau = new AbstractTask.Automatic("tau");
                    addNode(tree, tau);
                    addChild(newNode, tau, minerState);
                }
            }

            Node result = postProcess(newNode, log, logInfo, minerState);

            debug(" discovered node " + result, minerState);
            return result;

        } else {
            //cut is not valid; fall through
            Node result = findFallThrough(log, logInfo, tree, minerState);

            result = postProcess(result, log, logInfo, minerState);

            debug(" discovered node " + result, minerState);
            return result;
        }
    }

    public static void debug(Object x, MinerState minerState) {
        if (minerState.parameters.isDebug()) {
            System.out.println(x.toString());
        }
    }
    //region inductive methods

    private static Node postProcess(Node newNode, IMLog log, IMLogInfo logInfo, MinerState minerState) {
        for (PostProcessor processor : minerState.parameters.getPostProcessors()) {
            newNode = processor.postProcess(newNode, log, logInfo, minerState);
        }

        return newNode;
    }

    private static Block newNode(Cut.Operator operator) throws UnknownTreeNodeException {
        switch (operator) {
            case loop :
                return new AbstractBlock.XorLoop("");
            case concurrent :
                return new AbstractBlock.And("");
            case sequence :
                return new AbstractBlock.Seq("");
            case xor :
                return new AbstractBlock.Xor("");
            case maybeInterleaved :
                return new MaybeInterleaved("");
            case interleaved :
                return new Interleaved("");
            case or :
                return new AbstractBlock.Or("");
        }
        throw new UnknownTreeNodeException();
    }

    /**
     *
     * @param tree
     * @param node
     *            The log used as input for the mining algorithm. Provide null
     *            if this node was not directly derived from a log (e.g. it is a
     *            child in a flower-loop).
     */
    public static void addNode(ProcessTree tree, Node node) {
        node.setProcessTree(tree);
        tree.addNode(node);
    }

    public static Node findBaseCases(IMLog log, IMLogInfo logInfo, ProcessTree tree, MinerState minerState) {
        Node n = null;
        Iterator<BaseCaseFinder> it = minerState.parameters.getBaseCaseFinders().iterator();
        while (n == null && it.hasNext()) {

            if (minerState.isCancelled()) {
                return null;
            }

            n = it.next().findBaseCases(log, logInfo, tree, minerState);
        }
        return n;
    }

    public static Cut findCut(IMLog log, IMLogInfo logInfo, MinerState minerState) {
        Cut c = null;
        Iterator<CutFinder> it = minerState.parameters.getCutFinders().iterator();
        while (it.hasNext() && (c == null || !c.isValid())) {

            if (minerState.isCancelled()) {
                return null;
            }

            c = it.next().findCut(log, logInfo, minerState);
        }
        return c;
    }

    public static Node findFallThrough(IMLog log, IMLogInfo logInfo, ProcessTree tree, MinerState minerState) {
        Node n = null;
        Iterator<FallThrough> it = minerState.parameters.getFallThroughs().iterator();
        while (n == null && it.hasNext()) {

            if (minerState.isCancelled()) {
                return null;
            }

            n = it.next().fallThrough(log, logInfo, tree, minerState);
        }
        return n;
    }

    public static LogSplitter.LogSplitResult splitLog(IMLog log, IMLogInfo logInfo, Cut cut, MinerState minerState) {
        LogSplitter.LogSplitResult result = minerState.parameters.getLogSplitter().split(log, logInfo, cut, minerState);

        if (minerState.isCancelled()) {
            return null;
        }

        //merge the discarded events of this log splitting into the global discarded events list
        minerState.discardedEvents.addAll(result.discardedEvents);

        return result;
    }

    public static void addChild(Block parent, Node child, MinerStateBase minerState) {
        if (!minerState.isCancelled() && parent != null && child != null) {
            parent.addChild(child);
        }
    }

    //endregion
}
