package synoptic.model.export;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.simple.JSONValue;

import synoptic.model.EventNode;
import synoptic.model.Partition;
import synoptic.model.PartitionGraph;
import synoptic.model.event.EventType;
import synoptic.model.interfaces.IGraph;
import synoptic.model.interfaces.INode;

/**
 * Outputs a partition graph as a JSON object. Uses the JSON-simple library,
 * licensed under Apache 2.0 (the same license as Synoptic and its
 * sub-projects), available at https://code.google.com/p/json-simple/.
 * 
 * @author Tony Ohmann (ohmann@cs.umass.edu)
 * @param <T>
 *            The node type of the partition graph.
 */
public class JsonExporter {

    /**
     * Each event mapped to its relevant JSON information, the trace ID and its
     * index within the trace
     */
    private static Map<EventNode, EventInstance> eventMap = new HashMap<EventNode, EventInstance>();

    /**
     * Simple pair of a trace ID and an event index within the trace to uniquely
     * identify a specific event instance/node
     */
    private static class EventInstance {
        public int traceID;
        public int eventIndexWithinTrace;

        public EventInstance(int traceID, int eventIndexWithinTrace) {
            this.traceID = traceID;
            this.eventIndexWithinTrace = eventIndexWithinTrace;
        }
    }

    /**
     * Export the JSON object representation of the partition graph pGraph to
     * the filename specified
     * 
     * @param baseFilename
     *            The filename to which the JSON object should be written sans
     *            file extension
     * @param graph
     *            The partition graph to output
     */
    public static <T extends INode<T>> void exportJsonObject(
            String baseFilename, IGraph<T> graph) {

        // The graph must be a partition graph
        assert graph instanceof PartitionGraph;
        PartitionGraph pGraph = (PartitionGraph) graph;

        Map<String, Object> finalModelMap = new LinkedHashMap<String, Object>();

        // Add log to final model map
        List<Map<String, Object>> logListOfTraces = makeLogJSON(pGraph);
        finalModelMap.put("log", logListOfTraces);

        // Add partitions to final model map

        // Add invariants to final model map

        // Output the final model map as a JSON object
        try {
            PrintWriter output = new PrintWriter(baseFilename + ".json");
            JSONValue.writeJSONString(finalModelMap, output);
            output.close();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates the 'log' of the JSON object: a list of traces within the log of
     * this partition graph
     * 
     * @param pGraph
     *            The partition graph whose log we're outputting
     */
    private static List<Map<String, Object>> makeLogJSON(PartitionGraph pGraph) {
        // The log (list of traces) to go into the JSON object
        List<Map<String, Object>> logListOfTraces = new LinkedList<Map<String, Object>>();

        // Get all partitions in the partition graph
        Set<Partition> allPartitions = pGraph.getNodes();

        // Get the INITIAL partition, which will be used to retrieve all traces
        // and their events
        Partition initialPart = null;
        for (Partition part : allPartitions) {
            if (part.isInitial()) {
                initialPart = part;
                break;
            }
        }

        // There must have been an INITIAL partition found
        assert initialPart != null;
        if (initialPart == null) {
            return null;
        }

        // Follow all traces and store them in the log list of traces
        int traceID = 0;
        for (EventNode startingEvent : initialPart.getEventNodes().iterator()
                .next().getAllSuccessors()) {
            // One trace, contains the trace number and a list of events
            Map<String, Object> singleTraceMap = new LinkedHashMap<String, Object>();
            // List of events
            List<Map<String, Object>> singleTraceEventsList = new LinkedList<Map<String, Object>>();

            int eventIndexWithinTrace = 0;
            for (EventNode event = startingEvent; !event.isTerminal(); event = event
                    .getAllSuccessors().iterator().next()) {
                // One event, contains event index, event type, and timestamp
                Map<String, Object> singleEventMap = new LinkedHashMap<String, Object>();

                // Populate this event's index within the trace and its type
                singleEventMap.put("eventIndex", eventIndexWithinTrace++);
                EventType evType = event.getEType();
                singleEventMap.put("eventType", evType.toString());

                // Populate this event's time if it's not INITIAL or TERMINAL
                if (!evType.isSpecialEventType()) {
                    singleEventMap.put("timestamp", event.getTime());
                }

                // Add this event to this trace's list of events
                singleTraceEventsList.add(singleEventMap);

                // Record this event's event instance information to ease the
                // creation of the partition part of the JSON later
                eventMap.put(event, new EventInstance(traceID,
                        eventIndexWithinTrace));
            }

            // Populate the single trace
            singleTraceMap.put("traceID", traceID++);
            singleTraceMap.put("events", singleTraceEventsList);

            // Put the trace into the log's list of traces
            logListOfTraces.add(singleTraceMap);
        }

        return logListOfTraces;
    }
}