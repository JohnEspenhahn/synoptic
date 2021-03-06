package csight.main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import synoptic.invariants.AlwaysFollowedInvariant;
import synoptic.invariants.AlwaysPrecedesInvariant;
import synoptic.invariants.ITemporalInvariant;
import synoptic.invariants.NeverFollowedInvariant;
import synoptic.invariants.TemporalInvariantSet;
import synoptic.main.AbstractMain;
import synoptic.main.SynopticMain;
import synoptic.main.options.AbstractOptions;
import synoptic.main.options.SynopticOptions;
import synoptic.main.parser.TraceParser;
import synoptic.model.DAGsTraceGraph;
import synoptic.model.EventNode;
import synoptic.model.channelid.ChannelId;
import synoptic.model.event.DistEventType;
import synoptic.model.event.StringEventType;
import synoptic.model.export.DotExportFormatter;
import synoptic.util.InternalSynopticException;

import csight.invariants.AlwaysFollowedBy;
import csight.invariants.AlwaysPrecedes;
import csight.invariants.BinaryInvariant;
import csight.invariants.EventuallyHappens;
import csight.invariants.NeverFollowedBy;
import csight.mc.MC;
import csight.mc.MCResult;
import csight.mc.MCcExample;
import csight.mc.mcscm.McScM;
import csight.mc.parallelizer.InvariantTimeoutPair;
import csight.mc.parallelizer.McScMParallelizer;
import csight.mc.parallelizer.ParallelizerInput;
import csight.mc.parallelizer.ParallelizerResult;
import csight.mc.parallelizer.ParallelizerTask;
import csight.mc.parallelizer.ParallelizerTask.ParallelizerCommands;
import csight.mc.spin.Spin;
import csight.model.export.GraphExporter;
import csight.model.fifosys.cfsm.CFSM;
import csight.model.fifosys.gfsm.GFSM;
import csight.model.fifosys.gfsm.GFSMPath;
import csight.model.fifosys.gfsm.GFSMState;
import csight.model.fifosys.gfsm.observed.fifosys.ObsFifoSys;
import csight.util.CounterPair;
import csight.util.Util;

/**
 * <p>
 * This class wraps everything together to provide a command-line interface, as
 * well as an API, to run CSight programmatically.
 * </p>
 * <p>
 * Unlike the synoptic code-base, CSightMain is not a singleton and can be
 * instantiated for every new execution of CSight. However, CSightMain cannot be
 * re-used. That is, a new version _must_ be instantiated for each new execution
 * of the CSight process.
 * </p>
 * <p>
 * For options that CSight recognizes, see CSightOptions.
 * </p>
 */
public class CSightMain {
    static public boolean assertsOn = false;
    static {
        // Dynamic check for asserts. Note: without the '== true' a conservative
        // compiler complaints that the assert is not checking a condition.
        //
        assert (assertsOn = true) == true;
        // assertsOn = false;
    }

    public static Logger logger = null;

    /**
     * The main entrance to the command line version of CSight.
     * 
     * @param args
     *            Command-line options
     */
    public static void main(String[] args) throws Exception {
    	CSightOptions opts = new CSightOptions(args);
        CSightMain main;
        try {
            main = new CSightMain(opts);
        } catch (OptionException oe) {
            if (!oe.isPrintHelpException()) {
                logger.severe(oe.toString());
                throw oe;
            }
            System.exit(1);
            return;
        }

        try {
            main.run();
        } catch (Exception e) {
            if (e.toString() != "") {
                logger.severe(e.toString());
                logger.severe("Unable to continue, exiting. Try cmd line option:\n\t" + opts.getOptDesc("help"));
            }
            System.exit(1);
        }
    }

    /**
     * Sets up project-global logging based on command line options.
     * 
     * @param opts
     */
    public static void setUpLogging(CSightOptions opts) {
        if (logger != null) {
            return;
        }

        // Get the top Logger instance
        logger = Logger.getLogger("CSightMain");

        // Set the logger's log level based on command line arguments
        if (opts.logLvlQuiet) {
            logger.setLevel(Level.WARNING);
        } else if (opts.logLvlVerbose) {
            logger.setLevel(Level.FINE);
        } else if (opts.logLvlExtraVerbose) {
            logger.setLevel(Level.FINEST);
        } else {
            logger.setLevel(Level.INFO);
        }
        return;
    }

    // //////////////////////////////////////////////////////////////////

    private CSightOptions opts = null;

    // The Java McScM/Spin model checker bridge instance that interfaces with
    // the McScM verify binary or Spin.
    private MC mc = null;

    // The channels associated with this CSight execution. These are parsed in
    // checkOptions().
    private List<ChannelId> channelIds = null;

    // Instance of SynopticMain used for parsing the logs and mining invariants.
    private SynopticMain synMain = null;

    // Total number of processes in the log processed by this instance of
    // CSightMain
    private int numProcesses = -1;

    /** Prepares a new CSightMain instance based on opts. */
    public CSightMain(CSightOptions opts) throws OptionException {
        this.opts = opts;
        setUpLogging(opts);
        checkOptions(opts);
    }

    /**
     * Checks the input CSight options for consistency and omissions.
     */
    public void checkOptions(CSightOptions optns) throws OptionException {
        String err = null;

        // Display help for all option groups, including unpublicized ones
        if (optns.allHelp) {
            optns.printLongHelp();
            throw new OptionException();
        }

        // Display help just for the 'publicized' option groups
        if (optns.help) {
            optns.printShortHelp();
            throw new OptionException();
        }

        if (optns.channelSpec == null) {
            err = "Cannot parse a communications log without a channel specification:\n\t"
                    + opts.getOptDesc("channelSpec");
            throw new OptionException(err);
        }

        try {
            channelIds = ChannelId.parseChannelSpec(opts.channelSpec);
        } catch (Exception e) {
            throw new OptionException(e.getMessage());
        }
        if (channelIds.isEmpty()) {
            err = "Could not parse the channel specification:\n\t" + opts.getOptDesc("channelSpec");
            throw new OptionException(err);
        }

        if (optns.outputPathPrefix == null) {
            err = "Cannot output any generated models. Specify output path prefix using:\n\t"
                    + opts.getOptDesc("outputPathPrefix");
            throw new OptionException(err);
        }

        if (optns.mcPath == null) {
            err = "Specify path of the McScM model checker to use for verification:\n\t" + opts.getOptDesc("mcPath");
            throw new OptionException(err);
        }

        if (opts.topKElements < 1) {
            err = "Cannot compare queues with less than 1 top element, " + "set -topK >=1 or use default.";
            throw new OptionException(err);
        }

        if (opts.numParallel < 1) {
            err = "Cannot run less than one model checking processes concurrently";
            throw new OptionException(err);
        }

        // Determine the model checker type.
        if (optns.mcType.equals("spin")) {
            mc = new Spin(opts.mcPath);
            if (opts.spinChannelCapacity <= 0) {
                err = "Invalid channel capacity for use with spin: " + opts.spinChannelCapacity;
                throw new OptionException(err);
            }
            if (opts.runParallel) {
                err = "Parallel model checking not supported for spin";
                throw new OptionException(err);
            }
        } else if (optns.mcType.equals("mcscm")) {
            mc = new McScM(opts.mcPath);
        } else {
            err = "Invalid model checker type '" + opts.mcType + "'";
            throw new OptionException(err);
        }
    }

    public int getNumProcesses() {
        return numProcesses;
    }

    public List<ChannelId> getChannelIds() {
        return channelIds;
    }

    // //////////////////////////////////////////////////
    // "run" Methods that glue together all the major pieces to implement the
    // complete CSight pipeline:

    /**
     * Runs the CSight process based on the settings in opts. In particular, we
     * expect that the logFilenames are specified in opts.
     * 
     * @throws Exception
     */
    public void run() throws Exception {
        if (this.synMain == null) {
            initializeSynoptic();
        }

        if (opts.logFilenames.isEmpty()) {
            String err = "No log filenames specified, exiting. Specify log files at the end of the command line.";
            throw new OptionException(err);
        }

        // //////////////////
        // Parse the input log files into _Synoptic_ structures.
        TraceParser parser = new TraceParser(opts.regExps, opts.partitionRegExp, opts.separatorRegExp, opts.dateFormat);

        List<EventNode> parsedEvents = parseEventsFromFiles(parser, opts.logFilenames);

        // //////////////////
        // Generate the Synoptic DAG from parsed events
        DAGsTraceGraph traceGraph = AbstractMain.genDAGsTraceGraph(parser, parsedEvents);

        // Parser can now be garbage-collected.
        parser = null;

        run(traceGraph);
    }

    /**
     * Runs CSight based on setting in opts, but uses the log from the passed in
     * String, and not from the logFilenames defined in opts.
     * 
     * @param log
     * @throws Exception
     * @throws InterruptedException
     * @throws IOException
     */
    public void run(String log) throws IOException, InterruptedException, Exception {
        if (this.synMain == null) {
            initializeSynoptic();
        }

        // //////////////////
        // Parse the input string into _Synoptic_ structures.
        TraceParser parser = new TraceParser(opts.regExps, opts.partitionRegExp, opts.separatorRegExp, opts.dateFormat);

        List<EventNode> parsedEvents = parseEventsFromString(parser, log);

        // //////////////////
        // Generate the Synoptic DAG from parsed events
        DAGsTraceGraph traceGraph = AbstractMain.genDAGsTraceGraph(parser, parsedEvents);

        // Parser can now be garbage-collected.
        parser = null;

        run(traceGraph);
    }

    /**
     * Runs CSight based on settings in opts, and uses the Synoptic traceGraph
     * passed as an argument instead of parsing files or a string directly.
     * 
     * @param traceGraph
     * @throws Exception
     * @throws InterruptedException
     * @throws IOException
     */
    public void run(DAGsTraceGraph traceGraph) throws IOException, InterruptedException, Exception {

        // Export a visualization of the traceGraph
        String dotFilename = opts.outputPathPrefix + ".trace-graph.dot";
        synoptic.model.export.GraphExporter.exportGraph(dotFilename, traceGraph, true, false);
        // synoptic.model.export.GraphExporter
        // .generatePngFileFromDotFile(dotFilename);

        // //////////////////
        // Mine Synoptic invariants
        TemporalInvariantSet minedInvs = synMain.minePOInvariants(opts.useTransitiveClosureMining, traceGraph);

        logger.info("Mined " + minedInvs.numInvariants() + " invariants");

        if (opts.dumpInvariants) {
            logger.info("Mined invariants:\n" + minedInvs.toPrettyString());
        }

        if (minedInvs.numInvariants() == 0) {
            logger.info("Mined 0 Synoptic invariants. Stopping.");
            return;
        }

        // ///////////////////
        // Convert Synoptic invariants into CSight invariants.
        logger.info("Converting Synoptic invariants to CSight invariants...");
        List<BinaryInvariant> dynInvs = synInvsToDynInvs(minedInvs);

        logger.info(minedInvs.numInvariants() + " Synoptic invs --> " + dynInvs.size() + " CSight invs.");

        if (dynInvs.isEmpty()) {
            logger.info("Mined 0 CSight invariants. Stopping.");
            return;
        }

        // //////////////////
        // Use Synoptic event nodes and ordering constraints
        // between these to generate ObsFSMStates (anonymous states),
        // obsDAGNodes (to contain obsFSMStates and encode dependencies between
        // them), and an ObsDag per execution parsed from the log.
        logger.info("Generating ObsFifoSys from DAGsTraceGraph...");
        List<ObsFifoSys> traces = ObsFifoSys.synTraceGraphToDynObsFifoSys(traceGraph, numProcesses, channelIds,
                opts.consistentInitState);

        assert !traces.isEmpty();

        // Export (just the first!) Observed FIFO System instance:
        dotFilename = opts.outputPathPrefix + ".obsfifosys.tid1.dot";
        GraphExporter.exportObsFifoSys(dotFilename, traces.get(0));
        // GraphExporter.generatePngFileFromDotFile(dotFilename);

        // //////////////////
        // If assume consistent per-process initial state, check that
        // only one ObsFifoSys is created.
        //
        // Also, this option allows stitchings between traces, which may lead to
        // invariant violations. This post-processing step finds invariants that
        // violate such stitchings.
        if (opts.consistentInitState) {
            assert traces.size() == 1;

            logger.info("Finding invalidated invariants in the observed fifo system.");
            Set<BinaryInvariant> faultyInvs = traces.get(0).findInvalidatedInvariants(dynInvs);
            if (!faultyInvs.isEmpty()) {
                logger.warning("Input traces are incomplete --- some mined invariants cannot be satisfied: "
                        + faultyInvs.toString());

                dynInvs.removeAll(faultyInvs);
                logger.info("Ignoring faulty invariant and continuing. New invariants set: " + dynInvs.toString());
            }
        }

        // ///////////////////
        // Create a partition graph (GFSM instance) of the ObsFifoSys instances
        // we've created above. Use the default initial partitioning strategy,
        // based on head of all of the queues of each ObsFifoSysState.
        logger.info("Generating the initial partition graph (GFSM)...");
        GFSM pGraph;
        pGraph = new GFSM(traces, opts.topKElements);

        // Order dynInvs so that the eventually invariants are at the front (the
        // assumption is that they are faster to model check).
        logger.info("Reordering invariants to place \"eventually\" invariants at the front.");
        for (int i = 0; i < dynInvs.size(); i++) {
            if (dynInvs.get(i) instanceof EventuallyHappens) {
                BinaryInvariant inv = dynInvs.remove(i);
                dynInvs.add(0, inv);
            }
        }

        // ///////////////////
        // Model check, refine loop. Check each invariant in the model, and
        // refine the model as needed until all invariants hold.
        // Check if model checking is to be done in parallel and use the
        // corresponding methods.
        if (opts.runParallel) {
            if (opts.mcType.equals("mcscm")) {
                // Parallelization is currently only supported for McScM
                checkInvsRefineGFSMParallel(dynInvs, pGraph);
            } else {
                throw new OptionException("Parallel model checking is currently only supported for McScM");
            }
        } else if (opts.mcType.equals("spin") && opts.spinMultipleInvs) {
            checkMultipleInvsRefineGFSM(dynInvs, pGraph);
        } else {
            checkInvsRefineGFSM(dynInvs, pGraph);
        }

        // ///////////////////
        // Output the final CFSM model (corresponding to pGraph) using GraphViz
        // (dot-format).

        logger.info("Final scm model:");
        logger.info(pGraph.getCFSM(opts.minimize).toScmString("final model"));

        CFSM cfsm = pGraph.getCFSM(opts.minimize);
        String outputFileName = opts.outputPathPrefix + ".dot";
        GraphExporter.exportCFSM(outputFileName, cfsm);
        GraphExporter.generatePngFileFromDotFile(outputFileName);
    }

    // //////////////////////////////////////////////////
    // Various helper methods that integrate with Synoptic and manipulate model
    // data structures. Ordered roughly in the order of their use in the run
    // methods above.

    /** Initializes a version of SynopticMain based on CSight options. */
    public void initializeSynoptic() {
        assert synMain == null;

        AbstractOptions options = new SynopticOptions().toAbstractOptions();
        options.ignoreNonMatchingLines = opts.ignoreNonMatchingLines;
        options.recoverFromParseErrors = opts.recoverFromParseErrors;
        options.debugParse = opts.debugParse;
        this.synMain = new SynopticMain(options, new DotExportFormatter());
    }

    /**
     * Uses parser to parse a set of log files into a list of event nodes. These
     * event nodes are post-processed and ready to be further used to build
     * Synoptic/CSight DAG structures. <br/>
     * <br/>
     * This function also sets the numProcesses field to the number of processes
     * that have been observed in the log.
     * 
     * @param parser
     *            parser to use for parsing the log string
     * @param logFilenames
     *            log filenames to parse using the parser
     * @return
     * @throws Exception
     */
    public List<EventNode> parseEventsFromFiles(TraceParser parser, List<String> logFilenames) throws Exception {
        assert parser != null;
        assert synMain != null;
        assert logFilenames != null;
        assert !logFilenames.isEmpty();

        List<EventNode> parsedEvents;

        parsedEvents = AbstractMain.parseEvents(parser, logFilenames);

        if (parser.logTimeTypeIsTotallyOrdered()) {
            throw new OptionException("CSight expects a log that is partially ordered.");
        }

        postParseEvents(parsedEvents);
        return parsedEvents;
    }

    /**
     * Like the method above, uses parser to parse a log string into a list of
     * event nodes. These event nodes are post-processed and ready to be further
     * used to build Synoptic/CSight DAG structures. <br/>
     * <br/>
     * This function also sets the numProcesses field to the number of processes
     * that have been observed in the log.
     * 
     * @param parser
     *            parser to use for parsing the log string
     * @param log
     *            log string to parse
     * @return
     * @throws Exception
     */
    public List<EventNode> parseEventsFromString(TraceParser parser, String log) throws Exception {
        assert parser != null;

        List<EventNode> parsedEvents = parser.parseTraceString(log, "trace", -1);

        if (parser.logTimeTypeIsTotallyOrdered()) {
            throw new OptionException("CSight expects a log that is partially ordered.");
        }

        postParseEvents(parsedEvents);
        return parsedEvents;
    }

    /**
     * Further parsers the EventNodes -- setting up data structures internal to
     * the DistEventType, that is the EventNode.event.etype instance. This
     * function also determines and records the number of processes in the
     * system.
     */
    private void postParseEvents(List<EventNode> parsedEvents) throws Exception {

        if (parsedEvents.isEmpty()) {
            throw new OptionException("Did not parse any events from the input log files. Stopping.");
        }

        // //////////////////
        // Parse the parsed events further (as distributed
        // events that capture message send/receives). And determine the number
        // of processes in the system.

        Set<ChannelId> usedChannelIds = Util.newSet();
        Set<Integer> usedPids = Util.newSet();

        for (EventNode eNode : parsedEvents) {
            synoptic.model.event.EventType synEType = eNode.getEType();
            if (!(synEType instanceof DistEventType)) {
                throw new InternalSynopticException("Expected a DistEvenType, instead got " + synEType.getClass());
            }
            DistEventType distEType = ((DistEventType) synEType);

            String err = distEType.interpretEType(channelIds);
            if (err != null) {
                throw new OptionException(err);
            }

            // Record the pid and channelId corresponding to this eType.
            usedPids.add(distEType.getPid());
            if (distEType.isCommEvent()) {
                usedChannelIds.add(distEType.getChannelId());
            }
        }

        if (usedChannelIds.size() != channelIds.size()) {
            throw new OptionException("Some specified channelIds are not referenced in the log.");
        }

        // Find the max pid referenced in the log. This will determine the
        // number of processes in the system.
        int maxPid = 0;
        // Use sum of pids to check that all PIDs are referenced.
        int pidSum = 0;
        for (Integer pid : usedPids) {
            pidSum += pid;
            if (maxPid < pid) {
                maxPid = pid;
            }
        }
        numProcesses = maxPid + 1;
        logger.info("Detected " + numProcesses + " processes in the log.");

        if (pidSum != ((maxPid * (maxPid + 1)) / 2) || !usedPids.contains(0)) {
            throw new OptionException("Process ID range for the log has gaps: " + usedPids.toString());
        }

        // Make sure that we have observed at least one event for each process
        // associated with a used channel.
        for (ChannelId chId : channelIds) {
            if (chId.getSrcPid() > maxPid) {
                throw new OptionException("Did not observed any events for process " + chId.getSrcPid()
                        + " that is part of channel " + chId.toString());
            } else if (chId.getDstPid() > maxPid) {
                throw new OptionException("Did not observed any events for process " + chId.getDstPid()
                        + " that is part of channel " + chId.toString());
            }
        }

    }

    /**
     * Converts a set of Synoptic invariants into a set of CSight invariants.
     * Currently we ignore all \parallel and \nparallel invariants, as well as
     * all "x NFby y" invariants where x and y occur at different processes (see
     * Issue 271)
     */
    public static List<BinaryInvariant> synInvsToDynInvs(TemporalInvariantSet minedInvs) {
        List<BinaryInvariant> dynInvs = Util.newList();

        BinaryInvariant dynInv = null;

        DistEventType first, second;
        for (ITemporalInvariant inv : minedInvs) {
            assert (inv instanceof synoptic.invariants.BinaryInvariant);

            synoptic.invariants.BinaryInvariant binv = (synoptic.invariants.BinaryInvariant) inv;

            if (!(binv.getFirst() instanceof DistEventType)) {
                assert (binv.getFirst() instanceof StringEventType);
                assert (inv instanceof AlwaysFollowedInvariant);
                assert binv.getFirst().isInitialEventType();
            }

            assert (binv.getSecond() instanceof DistEventType);

            if (binv.getFirst().isInitialEventType()) {
                // Special case for INITIAL event type since it does not appear
                // in the traces and is therefore not recorded in the eTypesMap.
                first = DistEventType.INITIALEventType;
            } else {
                first = ((DistEventType) binv.getFirst());
            }
            second = ((DistEventType) binv.getSecond());

            if (inv instanceof AlwaysFollowedInvariant) {
                if (first == DistEventType.INITIALEventType) {
                    dynInv = new EventuallyHappens(second);
                } else {
                    dynInv = new AlwaysFollowedBy(first, second);
                }
            } else if (inv instanceof NeverFollowedInvariant) {
                assert first != DistEventType.INITIALEventType;

                // Ignore x NFby y if x and y occur at different processes
                // (Issue 271).
                if (first.getPid() == second.getPid()) {
                    dynInv = new NeverFollowedBy(first, second);
                }

            } else if (inv instanceof AlwaysPrecedesInvariant) {
                assert first != DistEventType.INITIALEventType;
                dynInv = new AlwaysPrecedes(first, second);
            }

            if (dynInv != null) {
                dynInvs.add(dynInv);
                dynInv = null;
            }
        }

        return dynInvs;
    }

    /**
     * Implements the (model check - refine loop). Check each invariant in
     * dynInvs in the pGraph model, and refine pGraph as needed until all
     * invariants are satisfied.
     * 
     * @param invsToSatisfy
     *            CSight invariants to check and satisfy in pGraph
     * @param pGraph
     *            The GFSM model that will be checked and refined to satisfy all
     *            of the invariants in invsTocheck
     * @throws Exception
     * @throws IOException
     * @throws InterruptedException
     * @return The number of iteration of the model checking loop
     */
    public int checkInvsRefineGFSM(List<BinaryInvariant> invs, GFSM pGraph)
            throws Exception, IOException, InterruptedException {
        assert pGraph != null;
        assert invs != null;
        assert !invs.isEmpty();

        // Make a copy of invs, as we'll be modifying the list (removing
        // invariants once they are satisfied by the model).
        List<BinaryInvariant> invsToSatisfy = Util.newList(invs);

        // The set of invariants that have timed-out so far. This set is reset
        // whenever we successfully check/refine an invariant.
        Set<BinaryInvariant> timedOutInvs = Util.newSet();

        // The set of invariants (subset of original invsToSatisfy) that the
        // model satisfies.
        Set<BinaryInvariant> satisfiedInvs = Util.newSet();

        // curInv will _always_ refer to the 0th element of invsToSatisfy.
        BinaryInvariant curInv = invsToSatisfy.get(0);

        int totalInvs = invsToSatisfy.size();
        int invsCounter = 1;

        // ////// Additive and memory-less timeout value adaptation.
        // Initial McScM invocation timeout in seconds.
        int baseTimeout = opts.baseTimeout;

        // How much we increment curTimeout by, when we timeout on checking all
        // invariants.
        int timeoutDelta = opts.timeoutDelta;

        // At what point to we stop the incrementing the timeout and terminate
        // with a failure.
        int maxTimeout = opts.maxTimeout;

        // Current timeout value to use.
        int curTimeout = baseTimeout;

        if (maxTimeout < baseTimeout) {
            throw new OptionException("maxTimeout value must be greater than baseTimeout value");
        }

        logger.info("Model checking " + curInv.toString() + " : " + invsCounter + " / " + totalInvs);

        // This counts the number of times we've refined the gfsm.
        int gfsmCounter = 0;
        // This counts the number of times we've performed model checking on the
        // gfsm.
        int mcCounter = 0;

        String gfsmPrefixFilename = opts.outputPathPrefix;

        exportIntermediateModels(pGraph, curInv, gfsmCounter, gfsmPrefixFilename);

        while (true) {
            assert invsCounter <= totalInvs;
            assert curInv == invsToSatisfy.get(0);
            assert timedOutInvs.size() + satisfiedInvs.size() + invsToSatisfy.size() == totalInvs;

            if (pGraph.isSingleton()) {
                // Skip model checking if all partitions are singletons.
                return mcCounter;
            }
            mcCounter++;

            // Get the CFSM corresponding to the partition graph.
            CFSM cfsm = pGraph.getCFSM(opts.minimize);

            String mcInputStr;
            if (mc instanceof McScM) {
                // Model check the CFSM using the McScM model checker.

                // Augment the CFSM with synthetic states/events to check
                // curInv (only fone for McScM).
                cfsm.augmentWithInvTracing(curInv);

                mcInputStr = cfsm.toScmString("checking_scm_" + curInv.getConnectorString());
            } else if (mc instanceof Spin) {
                List<BinaryInvariant> curInvs = Util.newList();
                curInvs.add(curInv);
                mcInputStr = cfsm.toPromelaString(curInvs, opts.spinChannelCapacity);

            } else {
                throw new RuntimeException("Model checker is not properly specified.");
            }

            logger.info("*******************************************************");
            logger.info("Checking ... " + curInv.toString() + ". Inv " + invsCounter + " / " + totalInvs
                    + ", refinements so far: " + gfsmCounter + ". Timeout = " + curTimeout + ".");
            logger.info("*******************************************************");

            try {
                mc.verify(mcInputStr, curTimeout);
            } catch (TimeoutException e) {
                // The model checker timed out. First, record the timed-out
                // invariant so that we are not stuck re-checking it.
                invsToSatisfy.remove(0);
                timedOutInvs.add(curInv);

                logger.info("Timed out in checking invariant: " + curInv.toString());

                // No invariants are left to try -- increase the timeout value,
                // unless we reached the timeout limit, in which case we throw
                // an exception.
                if (invsToSatisfy.isEmpty()) {
                    curTimeout = reAddTimedOutInvs(invsToSatisfy, timedOutInvs, timeoutDelta, maxTimeout, curTimeout);
                }

                // Try the first invariant (perhaps again, but with a higher
                // timeout value).
                curInv = invsToSatisfy.get(0);
                continue;
            } catch (InterruptedException e) {
                // The CSightMain Thread was interrupted. Stop model checking
                // and terminate with InterruptedException.
                throw new InterruptedException("CSightMain was interrupted.");
            }

            MCResult result = mc.getVerifyResult(cfsm.getChannelIds());
            logger.info(result.toRawString());
            logger.info(result.toString());

            if (result.modelIsSafe()) {
                // Remove the current invariant from the invsToSatisfy list.
                BinaryInvariant curInvCheck = invsToSatisfy.remove(0);
                assert curInvCheck == curInv;
                satisfiedInvs.add(curInv);

                if (invsToSatisfy.isEmpty()) {
                    if (!timedOutInvs.isEmpty()) {
                        // We ran out of non-timed-out invariants to check,
                        // re-add timed-out invariants.
                        curTimeout = reAddTimedOutInvs(invsToSatisfy, timedOutInvs, timeoutDelta, maxTimeout,
                                curTimeout);
                    } else {
                        // No more invariants to check. We are done.
                        logger.info("Finished checking " + invsCounter + " / " + totalInvs + " invariants.");
                        return mcCounter;
                    }
                }

                // Grab and start checking the next invariant.
                curInv = invsToSatisfy.get(0);
                invsCounter += 1;

            } else {
                // Refine the pGraph in an attempt to eliminate the counter
                // example.
                refineCExample(pGraph, result.getCExample());

                // Increment the number of refinements:
                gfsmCounter += 1;

                exportIntermediateModels(pGraph, curInv, gfsmCounter, gfsmPrefixFilename);

                // Model changed through refinement. Therefore, forget any
                // invariants that might have timed out previously,
                // and add all of them back to invsToSatisfy.
                if (!timedOutInvs.isEmpty()) {
                    // Append all of the previously timed out invariants back to
                    // invsToSatisfy.
                    invsToSatisfy.addAll(timedOutInvs);
                    timedOutInvs.clear();
                }
            }
        }
    }

    /**
     * Implements the (model check - refine loop). Check each invariant in
     * dynInvs in the pGraph model, and refine pGraph as needed until all
     * invariants are satisfied. Model checking is performed concurrently with
     * McScMParallelizer.
     * 
     * @param invsToSatisfy
     *            CSight invariants to check and satisfy in pGraph
     * @param pGraph
     *            The GFSM model that will be checked and refined to satisfy all
     *            of the invariants in invsTocheck
     * @throws Exception
     * @throws IOException
     * @throws InterruptedException
     * @return The number of iteration of the model checking loop
     */
    public int checkInvsRefineGFSMParallel(List<BinaryInvariant> invs, GFSM pGraph)
            throws Exception, IOException, InterruptedException {
        assert pGraph != null;
        assert invs != null;
        assert !invs.isEmpty();

        // Make a copy of invs, as we'll be modifying the list (removing
        // invariants once they are satisfied by the model). Each invariant will
        // be associated with its own timeout value, which is currently the base
        // timeout value as no invariant have timed out yet.
        List<InvariantTimeoutPair> invsToSatisfy = Util.newList();
        for (BinaryInvariant inv : invs) {
            invsToSatisfy.add(new InvariantTimeoutPair(inv, opts.baseTimeout));
        }

        // The set of invariants that have reached maxTimeout so far. This set
        // is reset whenever we refine the model.
        Set<InvariantTimeoutPair> maxTimedOutInvs = Util.newSet();

        // The set of invariants (subset of original invsToSatisfy) that the
        // model satisfies.
        Set<BinaryInvariant> satisfiedInvs = Util.newSet();

        // The set of invariants with their corresponding timeouts we are
        // currently running with McScMParallelizer. This set should always have
        // size less than or equal to opts.numParallel.
        Set<InvariantTimeoutPair> curInvs = Util.newSet();

        int totalInvs = invsToSatisfy.size();

        /**
         * AtomicInteger to provide mutability for methods to alter the value of
         * the integer. It is not for concurrent access.
         */
        AtomicInteger invsCounter = new AtomicInteger(1);

        if (opts.maxTimeout < opts.baseTimeout) {
            throw new Exception("maxTimeout value must be greater than baseTimeout value");
        }

        /**
         * AtomicInteger to provide mutability for methods to alter the value of
         * the integer. It is not for concurrent access. This counts the number
         * of times we've refined the gfsm.
         */
        AtomicInteger gfsmCounter = new AtomicInteger(0);
        // This counts the number of times we've performed model checking on the
        // gfsm.
        int mcCounter = 0;

        exportIntermediateModels(pGraph, invsToSatisfy.get(0).getInv(), gfsmCounter.get(), opts.outputPathPrefix);

        if (pGraph.isSingleton()) {
            // Skip model checking if all partitions are singletons.
            return mcCounter;
        }

        // Initialize the Parallelizer.
        // @see McScMParallelizer for taskChannel
        final BlockingQueue<ParallelizerTask> taskChannel = new LinkedBlockingQueue<ParallelizerTask>(1);
        // @see McScMParallelizer for resultsChannel
        final BlockingQueue<ParallelizerResult> resultsChannel = new LinkedBlockingQueue<ParallelizerResult>();

        Thread parallelizer = new Thread(
                new McScMParallelizer(opts.numParallel, opts.mcPath, taskChannel, resultsChannel));

        parallelizer.start();
        parallelizerStartK(invsToSatisfy, curInvs, pGraph, gfsmCounter.get(), totalInvs, taskChannel);

        while (true) {
            assert invsCounter.get() <= totalInvs;
            assert curInvs.size() <= opts.numParallel;
            assert maxTimedOutInvs.size() + satisfiedInvs.size() + invsToSatisfy.size() + curInvs.size() == totalInvs;

            ParallelizerResult result = waitForResult(gfsmCounter.get(), resultsChannel);
            mcCounter++;

            logger.info("Obtained result from parallelizer (refinement: " + gfsmCounter + ")");

            if (result.isException()) {
                logger.severe("Parallelizer encountered exception: " + result.getException().getClass()
                        + " (refinement: " + result.getRefinementCounter() + ")");
                parallelizer.interrupt();
                throw result.getException();
            }

            InvariantTimeoutPair resultPair = result.getInvTimeoutPair();
            // Remove the returned invariant-timeout pair from the current
            // invariant-timeout pair set.
            boolean returnedPairCheck = curInvs.remove(resultPair);
            assert returnedPairCheck;

            if (result.isTimeout()) {
                processTimeOut(pGraph, invsToSatisfy, maxTimedOutInvs, curInvs, opts.timeoutDelta, opts.maxTimeout,
                        gfsmCounter.get(), taskChannel, resultPair);

                // Continue to wait for next result.
                continue;
            }

            if (result.isInterrupted()) {
                // Add the invariant back to beginning of queue checking to
                // check again.
                invsToSatisfy.add(0, resultPair);
                parallelizerStartOne(invsToSatisfy, curInvs, pGraph, mcCounter, taskChannel);

                // Continue to wait for next result.
                continue;
            }

            assert (result.isVerifyResult());
            BinaryInvariant resultInv = resultPair.getInv();

            logger.info("*******************************************************");
            logger.info("Finished Checking ... " + resultPair.getInv().toString() + ". Inv " + invsCounter + " / "
                    + totalInvs + ", refinements so far: " + gfsmCounter + ". Timeout = " + resultPair.getTimeout()
                    + ".");
            logger.info("*******************************************************");

            MCResult mcResult = result.getMCResult();

            logger.info(mcResult.toRawString());
            logger.info(mcResult.toString());

            if (mcResult.modelIsSafe()) {
                if (processSafeModelResult(pGraph, invsToSatisfy, maxTimedOutInvs, satisfiedInvs, curInvs,
                        gfsmCounter.get(), taskChannel, resultInv, invsCounter)) {
                    // Every invariant has been satisfied. We are done.
                    logger.info("Finished checking " + invsCounter + " / " + totalInvs + " invariants.");
                    parallelizer.interrupt();
                    return mcCounter;
                }
                // Continue to wait for next result.
                continue;
            }

            if (processUnsafeModelResult(pGraph, invsToSatisfy, maxTimedOutInvs, curInvs, totalInvs, gfsmCounter,
                    opts.outputPathPrefix, taskChannel, resultsChannel, resultPair, mcResult)) {
                parallelizer.interrupt();
                return mcCounter;
            }
            // Continue to wait for next result.
            continue;
        }
    }

    /**
     * Stops all model checking process and clears curInvs, then refines the
     * GFSM model given an invariant that returned unsafe. All maxTimedOutInvs
     * are added back to invsToSatisfy, and the gfsmCounter is updated. Then,
     * restarts the model checking processes if the new model is not a singleton
     * and returns false. If the model is a singleton, no more model checking is
     * necessary, and returns true.
     * 
     * @param pGraph
     *            the model to check
     * @param invsToSatisfy
     *            the invariants to check
     * @param maxTimedOutInvs
     *            the invs that exceeded maxTimeout
     * @param curInvs
     *            the invariants currently being checked
     * @param totalInvs
     *            the total number of invariants
     * @param gfsmCounter
     *            the refinement counter
     * @param gfsmPrefixFilename
     * @param taskChannel
     * @param resultsChannel
     * @param resultPair
     *            the invariant and its corresponding timeout that just returned
     * @param mcResult
     *            the result of the model checking
     * @return true if the model after refinement is a singleton, else false
     * @throws InterruptedException
     * @throws Exception
     * @throws IOException
     */
    private boolean processUnsafeModelResult(GFSM pGraph, List<InvariantTimeoutPair> invsToSatisfy,
            Set<InvariantTimeoutPair> maxTimedOutInvs, Set<InvariantTimeoutPair> curInvs, int totalInvs,
            AtomicInteger gfsmCounter, String gfsmPrefixFilename, final BlockingQueue<ParallelizerTask> taskChannel,
            final BlockingQueue<ParallelizerResult> resultsChannel, InvariantTimeoutPair resultPair, MCResult mcResult)
            throws InterruptedException, Exception, IOException {
        // Increment the number of refinements:
        gfsmCounter.addAndGet(1);

        taskChannel.clear();
        taskChannel.put(new ParallelizerTask(ParallelizerCommands.STOP_ALL, null, gfsmCounter.get()));
        resultsChannel.clear();

        // Add the invariants that didn't return back to invariants to
        // satisfy.
        invsToSatisfy.addAll(0, curInvs);
        // Add the unsatisfied invariant back to invariants to satisfy.
        invsToSatisfy.add(0, resultPair);

        // Refine the pGraph in an attempt to eliminate the counter
        // example.
        refineCExample(pGraph, mcResult.getCExample());

        exportIntermediateModels(pGraph, invsToSatisfy.get(0).getInv(), gfsmCounter.get(), gfsmPrefixFilename);

        // Model changed through refinement. Therefore, forget any
        // invariants that might have timed out previously,
        // and add all of them back to invsToSatisfy.
        if (!maxTimedOutInvs.isEmpty()) {
            // Append all of the previously timed out invariants back to
            // invsToSatisfy.
            invsToSatisfy.addAll(maxTimedOutInvs);
            maxTimedOutInvs.clear();
        }

        if (pGraph.isSingleton()) {
            return true;
        }
        parallelizerStartK(invsToSatisfy, curInvs, pGraph, gfsmCounter.get(), totalInvs, taskChannel);
        return false;
    }

    /**
     * Adds timeout invariants from parallel model checking back to
     * invsToSatisfy after increasing their timeout value. If the new timeout
     * value exceeds maxTimeout, then the invariants are added to
     * maxTimedOutInvs. If no more invariants can be checked, an Exception is
     * thrown.
     * 
     * @param pGraph
     *            the model currently being checked
     * @param invsToSatisfy
     *            the invariants to check
     * @param maxTimedOutInvs
     *            the invariants that exceeded maxTimeout
     * @param curInvs
     *            the current invariants being checked
     * @param timeoutDelta
     *            the amount to increase timeout
     * @param maxTimeout
     *            the max timeout value
     * @param gfsmCounter
     *            the number of refinements for the model
     * @param taskChannel
     * @param resultPair
     *            the invariant timeout pair that timed out
     * @throws Exception
     *             when maxTimeout is reached
     * @throws InterruptedException
     */
    private void processTimeOut(GFSM pGraph, List<InvariantTimeoutPair> invsToSatisfy,
            Set<InvariantTimeoutPair> maxTimedOutInvs, Set<InvariantTimeoutPair> curInvs, int timeoutDelta,
            int maxTimeout, int gfsmCounter, final BlockingQueue<ParallelizerTask> taskChannel,
            InvariantTimeoutPair resultPair) throws Exception, InterruptedException {
        // The model checker timed out. Increase the timeout value for
        // that invariant, unless we reached the timeout limit, in which
        // case we throw an exception.
        int curTimeout = resultPair.getTimeout();
        BinaryInvariant resultInv = resultPair.getInv();

        logger.info("Timed out in checking invariant: " + resultInv.toString() + " with timeout value " + curTimeout);

        curTimeout += timeoutDelta;

        if (curTimeout > maxTimeout) {
            // Invariant exceeded maxTimeout. We wait and see if any
            // model refinement takes place that may lower the execution
            // time of the invariant.
            maxTimedOutInvs.add(resultPair);
        } else {
            // Append timed out invariant with new timeout value to
            // invsToSatisfy.
            invsToSatisfy.add(new InvariantTimeoutPair(resultInv, curTimeout));
        }

        if (invsToSatisfy.isEmpty()) {
            if (curInvs.isEmpty()) {
                // Every invariant reached max timeout.
                throw new Exception("McScM timed-out on all invariants. Cannot continue.");
            }
            // We wait to see if any invariants currently being checked
            // can complete without exceeding max timeout.

        } else {
            // Start a new model checking process.
            parallelizerStartOne(invsToSatisfy, curInvs, pGraph, gfsmCounter, taskChannel);
        }
    }

    /**
     * Returns true if model checking is completed, given an invariant that
     * returned safe. Else, automatically start the next invariant to check, if
     * applicable, and returns false.
     * 
     * @param pGraph
     * @param invsToSatisfy
     * @param maxTimedOutInvs
     * @param satisfiedInvs
     * @param curInvs
     * @param gfsmCounter
     * @param taskChannel
     * @param resultInv
     * @param invsCounter
     * @return
     * @throws Exception
     * @throws InterruptedException
     */
    private boolean processSafeModelResult(GFSM pGraph, List<InvariantTimeoutPair> invsToSatisfy,
            Set<InvariantTimeoutPair> maxTimedOutInvs, Set<BinaryInvariant> satisfiedInvs,
            Set<InvariantTimeoutPair> curInvs, int gfsmCounter, final BlockingQueue<ParallelizerTask> taskChannel,
            BinaryInvariant resultInv, AtomicInteger invsCounter) throws Exception, InterruptedException {

        satisfiedInvs.add(resultInv);

        if (invsToSatisfy.isEmpty()) {
            // No more invariants to check.
            if (curInvs.isEmpty()) {
                // We returned our last model checking process. No more
                // to check.
                if (!maxTimedOutInvs.isEmpty()) {
                    // There are timed out invariants that we never
                    // checked, but the model has not been refined. The
                    // invariants will still exceed the maxTimeout.
                    throw new Exception("McScM timed-out on all invariants. Cannot continue.");
                }

                return true;
            }
            // We wait for current invariants to finish checking.

            invsCounter.addAndGet(1);
        } else {
            parallelizerStartOne(invsToSatisfy, curInvs, pGraph, gfsmCounter, taskChannel);

            invsCounter.addAndGet(1);
        }
        return false;
    }

    /**
     * Waits and returns the first valid ParallelizerResult. Valid results are
     * either of the current refinement counter, or an exception. There will
     * always be at least one process running of the current refinement counter.
     * 
     * @param refinementCounter
     *            The current refinement counter
     * @param resultsChannel
     *            The results channel
     * @return
     * @throws InterruptedException
     */
    private ParallelizerResult waitForResult(int refinementCounter, BlockingQueue<ParallelizerResult> resultsChannel)
            throws InterruptedException {
        logger.info("Waiting for model checking result from Parallelizer...");

        while (true) {
            ParallelizerResult result = resultsChannel.take();

            if (result.getRefinementCounter() == refinementCounter || result.isException()) {
                return result;
            }
        }

    }

    /**
     * Sends START_K command to McScMParallelizer with its corresponding inputs,
     * and moves invariants from invsToSatisfy to curInvs.
     * 
     * @param invsToSatisfy
     * @param curInvs
     * @param pGraph
     * @param refinementCounter
     * @param totalInvs
     * @param taskChannel
     * @param inputsChannel
     * @throws InterruptedException
     */
    private void parallelizerStartK(List<InvariantTimeoutPair> invsToSatisfy, Set<InvariantTimeoutPair> curInvs,
            GFSM pGraph, int refinementCounter, int totalInvs, BlockingQueue<ParallelizerTask> taskChannel)
            throws InterruptedException {
        assert (!invsToSatisfy.isEmpty());

        logger.info("*******************************************************");
        logger.info("Model Checking ... " + opts.numParallel + " / " + totalInvs + " being checked in parallel.");
        logger.info("*******************************************************");

        List<ParallelizerInput> inputs = new ArrayList<ParallelizerInput>();

        // Run K processes as number of invariants to check may be less than
        // parallelization factor
        int numLeftToCheck = Math.min(opts.numParallel, invsToSatisfy.size());
        for (int i = 0; i < numLeftToCheck; i++) {
            InvariantTimeoutPair invTimeoutToCheck = invsToSatisfy.remove(0);

            ParallelizerInput input = new ParallelizerInput(invTimeoutToCheck, pGraph.getCFSM(opts.minimize));
            inputs.add(input);
            curInvs.add(invTimeoutToCheck);
        }

        logger.fine("Sending START_K task to Parallelizer");
        taskChannel.put(new ParallelizerTask(ParallelizerCommands.START_K, inputs, refinementCounter));
    }

    /**
     * Sends START_ONE command to McScMParallelizer with its corresponding
     * inputs, and moves invariants from invsToSatisfy to curInvs.
     * 
     * @param invsToSatisfy
     * @param curInvs
     * @param pGraph
     * @param refinementCounter
     * @param taskChannel
     * @param inputsChannel
     * @throws InterruptedException
     */
    private void parallelizerStartOne(List<InvariantTimeoutPair> invsToSatisfy, Set<InvariantTimeoutPair> curInvs,
            GFSM pGraph, int refinementCounter, BlockingQueue<ParallelizerTask> taskChannel)
            throws InterruptedException {
        assert (!invsToSatisfy.isEmpty());

        List<ParallelizerInput> inputs = new ArrayList<ParallelizerInput>();

        InvariantTimeoutPair invTimeoutToCheck = invsToSatisfy.remove(0);

        ParallelizerInput input = new ParallelizerInput(invTimeoutToCheck, pGraph.getCFSM(opts.minimize));
        inputs.add(input);
        curInvs.add(invTimeoutToCheck);

        logger.fine("Sending START_ONE task to Parallelizer");
        taskChannel.put(new ParallelizerTask(ParallelizerCommands.START_ONE, inputs, refinementCounter));
    }

    /**
     * Implements the (model check - refine loop). Check each invariant in
     * dynInvs in the pGraph model, and refine pGraph as needed until all
     * invariants are satisfied.
     * 
     * @param invsToSatisfy
     *            CSight invariants to check and satisfy in pGraph
     * @param pGraph
     *            The GFSM model that will be checked and refined to satisfy all
     *            of the invariants in invsTocheck
     * @throws Exception
     * @throws IOException
     * @throws InterruptedException
     * @return The number of iteration of the model checking loop
     */
    public int checkMultipleInvsRefineGFSM(List<BinaryInvariant> invs, GFSM pGraph)
            throws Exception, IOException, InterruptedException {
        assert pGraph != null;
        assert invs != null;
        assert !invs.isEmpty();
        assert mc instanceof Spin;

        if (!(mc instanceof Spin)) {
            throw new RuntimeException(
                    "Only Spin can check multiple invariants at one time. Model checker is not properly specified.");
        }

        Spin spinMC = (Spin) mc;

        // Make a copy of invs, as we'll be modifying the list (removing
        // invariants once they are satisfied by the model).
        List<BinaryInvariant> invsToSatisfy = Util.newList(invs);

        // The set of invariants that have timed-out so far. This set is reset
        // whenever we successfully check/refine an invariant.
        Set<BinaryInvariant> timedOutInvs = Util.newSet();

        // The set of invariants (subset of original invsToSatisfy) that the
        // model satisfies.
        Set<BinaryInvariant> satisfiedInvs = Util.newSet();

        /* Contains all the invs we are checking on the current run. */
        List<BinaryInvariant> curInvs = chooseInvariants(invsToSatisfy, 3);

        int totalInvs = invsToSatisfy.size();

        // Additive and memory-less timeout value adaptation.
        // Initial invocation timeout in seconds.
        int baseTimeout = opts.baseTimeout;

        // How much we increment curTimeout by, when we timeout on checking all
        // invariants.
        int timeoutDelta = opts.timeoutDelta;

        // At what point to we stop the incrementing the timeout and terminate
        // with a failure.
        int maxTimeout = opts.maxTimeout;

        // Current timeout value to use.
        int curTimeout = baseTimeout;

        if (maxTimeout < baseTimeout) {
            throw new OptionException("maxTimeout value must be greater than baseTimeout value");
        }

        logger.info("Model checking " + curInvs.size() + " invariants : " + satisfiedInvs.size() + " / " + totalInvs
                + " satisfied");

        // This counts the number of times we've refined the gfsm.
        int gfsmCounter = 0;
        // This counts the number of times we've performed model checking on the
        // gfsm
        int modelCheckCounter = 0;

        String gfsmPrefixFilename = opts.outputPathPrefix;

        while (true) {
            assert (satisfiedInvs.size() <= totalInvs);
            assert (curInvs.size() <= invsToSatisfy.size());
            assert ((timedOutInvs.size() + satisfiedInvs.size() + invsToSatisfy.size()) == totalInvs);

            if (pGraph.isSingleton()) {
                // Skip model checking if all partitions are singletons
                return modelCheckCounter;
            }

            // Get the CFSM corresponding to the partition graph.
            CFSM cfsm = pGraph.getCFSM(opts.minimize);

            String mcInputStr;
            mcInputStr = cfsm.toPromelaString(curInvs, opts.spinChannelCapacity);
            spinMC.prepare(mcInputStr, 20);

            logger.info("*******************************************************");
            logger.info("Checking ... " + curInvs.size() + " invariants. Inv " + satisfiedInvs.size() + " / "
                    + totalInvs + " satisfied" + ", refinements so far: " + gfsmCounter + ". Timeout = " + curTimeout
                    + ". " + timedOutInvs.size() + " invariants are timed out.");
            logger.info("*******************************************************");

            for (int curInvNum = 0; curInvNum < curInvs.size(); curInvNum++) {
                try {
                    logger.info("Running Spin for invariant " + curInvs.get(curInvNum));
                    modelCheckCounter++;
                    spinMC.verify(mcInputStr, curTimeout, curInvNum);
                } catch (TimeoutException e) {
                    // The model checker timed out. First, record the timed-out
                    // invariant so that we are not stuck re-checking it.
                    BinaryInvariant timedOutInv = curInvs.get(curInvNum);
                    invsToSatisfy.remove(timedOutInv);
                    timedOutInvs.add(timedOutInv);
                    logger.info("Timed out in checking invariant: " + timedOutInv.toString());
                    // Stay in the loop.
                    // Continue checking the rest of the invariants.
                }
            }

            // Verify the results that didn't time out.
            Map<Integer, MCResult> results = spinMC.getMultipleVerifyResults(cfsm.getChannelIds(), curInvs.size());
            logger.info(results.size() + " / " + curInvs.size() + " results returned.");
            for (int i = 0; i < curInvs.size(); i++) {
                /*
                 * Retrieve the current invariant and the matching result. If
                 * the result is null, then this invariant was interrupted and
                 * it should be ignored. We still need to check the other
                 * invariants.
                 */

                BinaryInvariant curInv = curInvs.get(i);
                MCResult result = results.get(i);

                logger.fine("Retrieving results for invariant " + curInv);
                if (result == null) {
                    // This invariant has no result, but the other invariants
                    // might.
                    logger.fine("No results for invariant " + i);
                    continue;
                }

                logger.finest(result.toRawString());
                logger.info(result.toString());

                if (result.modelIsSafe()) {
                    logger.info("Invariant " + curInv.toString() + " is safe.");
                    // Remove the current invariant from the invsToSatisfy list.
                    boolean curInvCheck = invsToSatisfy.remove(curInv);
                    assert curInvCheck;
                    satisfiedInvs.add(curInv);

                } else {

                    // Refine the pGraph in an attempt to eliminate the
                    // counterexample.
                    // The staleness of the counterexample is checked in
                    // refineCExample.
                    if (refineCExample(pGraph, result.getCExample())) {

                        // Increment the number of refinements:
                        gfsmCounter += 1;
                        exportIntermediateModels(pGraph, curInv, gfsmCounter, gfsmPrefixFilename);
                    }

                }

            }
            if (invsToSatisfy.isEmpty() && timedOutInvs.isEmpty()) {
                // No more invariants to check. We are done.
                logger.info("Finished checking " + satisfiedInvs.size() + " / " + totalInvs + " invariants.");
                return modelCheckCounter;
            } else if (invsToSatisfy.isEmpty() && !timedOutInvs.isEmpty()) {
                // No invariants are left to try -- increase the timeout
                // value, unless we reached the timeout limit, in which case
                // we throw an exception.
                curTimeout = reAddTimedOutInvs(invsToSatisfy, timedOutInvs, timeoutDelta, maxTimeout, curTimeout);
            }

            /*
             * HEURISTIC: Select the next invariants to check. gfsmCounter
             * increases as the model gets refined. As the model gets further
             * refinement, invariants are usually satisfied and the model does
             * not change. Increasing the number of invariants we check in one
             * run at the later stages of model checking will speed up
             * refinement.
             */
            curInvs = chooseInvariants(invsToSatisfy, gfsmCounter);
        }
    }

    /**
     * Implements a heuristic for choosing a subset of the invariants that we
     * want to check. The basic idea is that there may be events that are
     * associated with multiple invariants. By choosing these events we optimize
     * model-checking with Spin because we have to track/instrument fewer types
     * of events. We then select invariants that only have these events.
     * 
     * @param invs
     *            Invariants that need to be satisfied.
     * @param minInvs
     *            Minimum number of invariants to return.
     * @return Invariants to check.
     */
    public List<BinaryInvariant> chooseInvariants(List<BinaryInvariant> invs, int minInvs) {
        // Make sure we get at least one invariant.
        minInvs = Math.max(minInvs, 1);

        List<BinaryInvariant> invsToCheck = Util.newList();

        // Count events by number of invariants they are associated with.
        Map<DistEventType, Integer> eventMap = Util.newMap();
        for (BinaryInvariant inv : invs) {
            // Add first event, but not if it's a case of initial.
            DistEventType curEvent = inv.getFirst();
            if (!inv.equals(DistEventType.INITIALEventType)) {
                if (eventMap.containsKey(curEvent)) {
                    eventMap.put(curEvent, eventMap.remove(curEvent) + 1);
                } else {
                    eventMap.put(curEvent, 1);
                }
            }
            // Add second event, but do not double count.
            if (!curEvent.equals(inv.getSecond())) {
                curEvent = inv.getSecond();
                if (eventMap.containsKey(curEvent)) {
                    eventMap.put(curEvent, eventMap.remove(curEvent) + 1);
                } else {
                    eventMap.put(curEvent, 1);
                }
            }
        }

        // Sort events by number of related invariants.
        List<CounterPair<DistEventType>> sortedEventList = Util.newList();
        for (Entry<DistEventType, Integer> entry : eventMap.entrySet()) {
            sortedEventList.add(new CounterPair<DistEventType>(entry.getKey(), entry.getValue()));
        }
        Collections.sort(sortedEventList);

        // minEventCount is the minimum number of events we want involved in our
        // invariant check.
        // Lower minEventCount if we don't have enough events for it.
        int minEventCount = Math.min(sortedEventList.size(), (int) Math.sqrt(minInvs));

        Set<DistEventType> addedEvents = Util.newSet();
        for (int i = 0; i < minEventCount; i++) {
            addedEvents.add(sortedEventList.get(i).getKey());
        }

        // We limit the minimum number of invariants to the size of invs so we
        // don't run out of invariants.
        int minInvCount = Math.min(minInvs, invs.size());

        // Select invariants that have events in addedEvents. If we do not have
        // enough invariants, we add another event before repeating.
        while (invsToCheck.size() < minInvCount) {
            assert sortedEventList.size() >= addedEvents.size();
            invsToCheck.clear();

            // Check each invariant to see if the invariant has both events in
            // addedEvents.
            // Add them to the InvsToCheck if they do.
            for (BinaryInvariant inv : invs) {
                // Add special case for eventually happens, as the first event
                // doesn't matter here.
                if (inv instanceof EventuallyHappens && addedEvents.contains(inv.getSecond())) {
                    invsToCheck.add(inv);
                } else if (addedEvents.contains(inv.getFirst()) && addedEvents.contains(inv.getSecond())) {
                    // Add invariant only if both events are in the set of
                    // events we're using
                    invsToCheck.add(inv);
                }
            }
            // In preparation for the next loop, add the next event in line. We
            // will have one more event to consider if we don't have enough
            // invariants.
            if (sortedEventList.size() > addedEvents.size()) {
                addedEvents.add(sortedEventList.get(addedEvents.size()).getKey());
            }
        }
        return invsToCheck;
    }

    /**
     * Moves the timed-out invariants into the invsToSatisfy list, and updates
     * the curTimeout value (and returns it) based on the timeoutDelta value.
     * Checks that the timeout does not exceed maxTimeout and throws an
     * Exception if it does.
     * 
     * @param invsToSatisfy
     *            invariants we currently need to check
     * @param timedOutInvs
     *            invariants that timed-out previously
     * @param timeoutDelta
     *            how much to increase the timeout by
     * @param maxTimeout
     *            max bound for a timeout
     * @param curTimeout
     *            the current timeout value
     * @return
     * @throws Exception
     *             when reached maxTimeout value
     */

    private int reAddTimedOutInvs(List<BinaryInvariant> invsToSatisfy, Set<BinaryInvariant> timedOutInvs,
            int timeoutDelta, int maxTimeout, int curTimeout) throws Exception {
        logger.info("Timed out in checking these invariants with timeout value " + curTimeout + " :"
                + timedOutInvs.toString());

        curTimeout += timeoutDelta;

        if (curTimeout > maxTimeout) {
            throw new Exception("Timed-out on all invariants. Cannot continue.");
        }

        // Append all of the previously timed out invariants back to
        // invsToSatisfy.
        invsToSatisfy.addAll(timedOutInvs);
        timedOutInvs.clear();
        return curTimeout;
    }

    /**
     * Matches the sequence of events in the counter-example to paths of
     * corresponding GFSM states for each process. Then, refines each process'
     * paths until all paths for some process are successfully refined.
     * 
     * @return Whether a refinement was performed.
     * @throws Exception
     *             if we were not able to eliminate the counter-example
     */
    private boolean refineCExample(GFSM pGraph, MCcExample cexample) throws Exception {

        // Resolve all of the complete counter-example paths by:
        // refining all possible stitching partitions for pid 0.
        // However, if not all of these can be refined then try pid 1, and
        // so on. By construction we are guaranteed to be able to
        // eliminate this execution, so we are making progress as long
        // as we refine at each step.
        for (int i = 0; i < this.getNumProcesses(); i++) {
            // Get set of GFSM paths for process i that generate the
            // sub-sequence of process i events in the counter-example.
            logger.info("Computing process " + i + " paths...");
            Set<GFSMPath> processPaths = pGraph.getCExamplePaths(cexample, i);
            if (processPaths == null) {
                logger.info("No matching paths for process " + i + " exist, continuing.");
                // Treat this as if we refined all of the paths for the process
                // -- none exist!
                return false;
            }

            // logger.info("Process " + i + " paths: " +
            // processPaths.toString());

            // Attempt to refine stitching partitions along these paths.

            // TODO: attempt to find partitions that over all of the paths in
            // processPaths. Then, refine these partitions, for a fewer number
            // of total refinements.

            boolean refinedAll = true;
            for (GFSMPath path : processPaths) {
                logger.info("Attempting to resolve process " + i + " path: ");
                logger.finest(path.toString());
                // If path is no longer a valid path then it was refined and
                // eliminated previously.
                if (!GFSMPath.checkPathCompleteness(path)) {
                    continue;
                }

                // Checks to see if the empty execution is still valid.
                // An event-less trace has a single state by default.
                // This state is automatically the last state and should be both
                // an accept and an initial state. If it is not both, the path
                // is no longer complete.

                // We need to pull the check out so we don't impact
                // checkPathCompleteness.
                if (path.numEvents() == 0) {
                    GFSMState s = path.lastState();
                    if (!s.isAccept() || !s.isInitial()) {
                        continue;
                    }
                }
                // Otherwise, the path still needs to be refined.
                if (!path.refine(pGraph)) {
                    refinedAll = false;
                    break;
                }
            }

            // We were able to refine all paths for process i. Therefore, the
            // abstract counter-example path cannot exist.
            if (refinedAll) {
                return true;
            }

        }
        throw new Exception("Unable to eliminate CFSM counter-example from GFSM.");
    }

    /**
     * Exports the GFSM model, as well as the corresponding CFSM, and CFSM
     * augmented with the invariant we are currently checking.
     * 
     * @param pGraph
     * @param curInv
     * @param gfsmCounter
     * @param gfsmPrefixFilename
     * @throws IOException
     * @throws Exception
     */
    private void exportIntermediateModels(GFSM pGraph, BinaryInvariant curInv, int gfsmCounter,
            String gfsmPrefixFilename) throws IOException, Exception {

        // Export GFSM:
        String dotFilename = gfsmPrefixFilename + ".gfsm." + gfsmCounter + ".dot";
        GraphExporter.exportGFSM(dotFilename, pGraph);
        // GraphExporter.generatePngFileFromDotFile(dotFilename);

        // Export CFSM:
        CFSM cfsm = pGraph.getCFSM(opts.minimize);

        dotFilename = gfsmPrefixFilename + ".cfsm-no-inv." + gfsmCounter + ".dot";
        GraphExporter.exportCFSM(dotFilename, cfsm);
        // GraphExporter.generatePngFileFromDotFile(dotFilename);

        // Export CFSM, augmented with curInv:
        cfsm.augmentWithInvTracing(curInv);
        dotFilename = gfsmPrefixFilename + ".cfsm." + gfsmCounter + ".dot";
        GraphExporter.exportCFSM(dotFilename, cfsm);
        // GraphExporter.generatePngFileFromDotFile(dotFilename);
    }

}
