package com.hahn.doteditdistance;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.paypal.digraph.parser.GraphEdge;
import com.paypal.digraph.parser.GraphNode;
import com.paypal.digraph.parser.GraphParser;
import com.paypal.digraph.parser.GraphParserException;

import no.roek.nlpged.graph.Edge;
import no.roek.nlpged.graph.Graph;
import no.roek.nlpged.graph.Node;

public class DotReader {
	
	public static Graph read(String name, InputStream is) {
			GraphParser parser;
			try {
				parser = new GraphParser(is);
			} catch (GraphParserException e) {
				return null;
			}
		
			Map<String, LinkedNode> lns = new HashMap<>();
			
			// Init nodes
			for (GraphNode node : parser.getNodes().values()) {
				lns.put(node.getId(), new LinkedNode(node.getId()));
			}
			
			// Link nodes via edges
			for (GraphEdge edge : parser.getEdges().values()) {
				LinkedNode start = lns.get(edge.getNode1().getId()),
						   end = lns.get(edge.getNode2().getId());
				
				start.addOutEdge(edge);
				end.addInEdge(edge);
			}
			
			// Invert
			return buildInvertedGraph(name, lns);
	}
	
	private static Graph buildInvertedGraph(String name, Map<String, LinkedNode> linkedNodeMap) {
		Graph graph = new Graph(name);
		
		Map<String, Node> createdNodes = new HashMap<>();		
		for (LinkedNode ln: linkedNodeMap.values()) {
			// Link in edges to created nodes
			for (GraphEdge in: ln.getInEdges()) {
				Node start;
				
				String inLbl = (String) in.getAttribute("label");
				inLbl = (inLbl == null ? (String) in.getNode1().getAttribute("label") : inLbl);
				
				if (createdNodes.containsKey(inLbl)) {
					start = createdNodes.get(inLbl);
				} else {
					start = new Node(inLbl, inLbl, new String[] { "node" });
					createdNodes.put(inLbl, start);
					graph.addNode(start);
				}
				
				// Normal cases
				for (GraphEdge out: ln.getOutEdges()) {
					Node end;
					
					String outLbl = (String) out.getAttribute("label");
					outLbl = (outLbl == null ? (String) out.getNode2().getAttribute("label") : outLbl);
					
					if (createdNodes.containsKey(outLbl)) {
						end = createdNodes.get(outLbl);
					} else {
						end = new Node(outLbl, outLbl, new String[] { "node" });
						createdNodes.put(outLbl, end);
						graph.addNode(end);
					}
					
					String id = start.getId() + " -> " + end.getId();
					graph.addEdge(new Edge(id, start, end, id, new String[0]));
				}
			}
		}
		
		return graph;
	}
	
	static class LinkedNode {
		
		private String id;
		private List<GraphEdge> inEdges, outEdges;
		
		public LinkedNode(String id) {
			this.id = id;
			this.inEdges = new ArrayList<>();
			this.outEdges = new ArrayList<>();
		}
		
		public String getId() {
			return id;
		}
		
		public List<GraphEdge> getOutEdges() {
			return Collections.unmodifiableList(outEdges);
		}
		
		public List<GraphEdge> getInEdges() {
			return Collections.unmodifiableList(inEdges);
		}
		
		public void addInEdge(GraphEdge e) {
			this.inEdges.add(e);
		}
		
		public void addOutEdge(GraphEdge e) {
			this.outEdges.add(e);
		}
		
	}
}
