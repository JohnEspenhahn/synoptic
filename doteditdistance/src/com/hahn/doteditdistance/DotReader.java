package com.hahn.doteditdistance;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Scanner;

import com.paypal.digraph.parser.GraphEdge;
import com.paypal.digraph.parser.GraphNode;
import com.paypal.digraph.parser.GraphParser;
import com.paypal.digraph.parser.GraphParserException;

import no.roek.nlpged.graph.Edge;
import no.roek.nlpged.graph.Graph;
import no.roek.nlpged.graph.Node;

public class DotReader {
	
	public final static String delim = "// digraph \\{";
	
	public static Graph[] dotGraphs(String file1) {
		List<Graph> graphs = new ArrayList<>();
		try {
			Scanner s1 = new Scanner(new File(file1));
			s1.useDelimiter(delim);
			
			while (s1.hasNext()) {
				Graph g1 = read("g1", new ByteArrayInputStream(s1.next().getBytes()));
				if (g1 != null) {
					graphs.add(g1);
				} else {
					break;
				}
			}
						
			s1.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return graphs.toArray(new Graph[graphs.size()]);
	}
	
	public static Graph read(String name, InputStream is) {
			GraphParser parser;
			try {
				parser = new GraphParser(is);
			} catch (GraphParserException e) {
				return null;
			}
			
			// Init nodes
			List<GraphNode> starts = new ArrayList<>();
			for (GraphNode node : parser.getNodes().values()) {
				if (node.getId().equals("start_0")) {
					starts.add(node);
				}
				
				node.setAttribute("out", new ArrayList<GraphEdge>());
			}
			
			if (starts.size() == 0)
				throw new RuntimeException("Start of graph not found");
			
			// Link nodes via edges
			for (GraphEdge edge : parser.getEdges().values()) {
				GraphNode n1 = edge.getNode1();
				
				@SuppressWarnings("unchecked")
				List<GraphEdge> l = (ArrayList<GraphEdge>) n1.getAttribute("out");
				l.add(edge);
			}
			
			// Invert
			return buildInvertedGraph(name, starts, parser);
	}
	
	private static Graph buildInvertedGraph(String name, List<GraphNode> starts, GraphParser parser) {
		Graph graph = new Graph(name);
		
		Queue<NodeTuple> q = new LinkedList<>();
		
		Node start = new Node("start", "start", new String[] { "node" });
		graph.addNode(start);
		for (GraphNode inode: starts) {
			// Skip first layer of edges out of start
			@SuppressWarnings("unchecked")
			List<GraphEdge> outEdges = (List<GraphEdge>) inode.getAttribute("out");
			outEdges.sort(edgeCompare);
			
			for (GraphEdge out: outEdges)
				q.add(new NodeTuple(out.getNode2(), null));
		}
		
		while (!q.isEmpty()) {
			NodeTuple t = q.poll();
			
			if (t.onode != null)
				graph.addNode(t.onode);
			
			@SuppressWarnings("unchecked")
			List<GraphEdge> outEdges = (List<GraphEdge>) t.inode.getAttribute("out");
			outEdges.sort(edgeCompare);
			
			for (GraphEdge out: outEdges) {
				GraphNode inode = out.getNode2();
				
				String lbl = (String) out.getAttribute("label");
				lbl = (lbl == null ? (String) inode.getAttribute("label") : lbl);
				Node onode = new Node(lbl, lbl, new String[] { "node" });
				
				Boolean enqueued = (Boolean) inode.getAttribute("enqueued");
				if (enqueued == null || enqueued != true) {
					inode.setAttribute("enqueued", true);
					q.add(new NodeTuple(inode, onode));
				}
				
				String onode_id = (t.onode == null ? "start" : t.onode.getId());
				String id = onode_id + " -> " + onode.getId();
				
				graph.addEdge(new Edge(id, (t.onode == null ? start : t.onode), onode, id, new String[0]));
			}
		}
		
		return graph;
	}
	
	private static final Comparator<GraphEdge> edgeCompare = new Comparator<GraphEdge>() {

		@Override
		public int compare(GraphEdge e1, GraphEdge e2) {
			return ((String) e1.getAttribute("label")).compareTo((String) e2.getAttribute("label"));
		}
		
	};
	
	static class NodeTuple {
		
		GraphNode inode;
		Node onode;
		
		NodeTuple(GraphNode i, Node o) {
			this.inode = i;
			this.onode = o;
		}
		
	}
	
}
