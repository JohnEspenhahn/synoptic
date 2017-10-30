package com.hahn.doteditdistance;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;

import no.roek.nlpged.algorithm.GraphEditDistance;
import no.roek.nlpged.graph.Graph;

public class DotEditDistance {
	
	public static void main(String[] args) {
		dotEditDistance(args[0], args[1]);
	}
	
	public static void dotEditDistance(String file1, String file2) {
		dotEditDistance(DotReader.dotGraphs(file1), file2);
	}
	
	public static void dotEditDistance(Graph[] baseline, String file2) {
		int idx = 0;
		double totalDistance = 0;
		try {
			Scanner s2 = new Scanner(new File(file2));
			s2.useDelimiter(DotReader.delim);
			
			while (idx < baseline.length && s2.hasNext()) {
				System.out.println("//////////////////////////////");
				System.out.println("// RUNNING GRAPH " + idx);
				
				Graph g1 = baseline[idx];				
				Graph g2 = DotReader.read("g2", new ByteArrayInputStream(s2.next().getBytes()));
				if (g2 == null) {
					System.err.println("Reached end of graph file 2)");
					break;
				}
				System.out.println(g2.toString());
				
				GraphEditDistance ged = new GraphEditDistance(g1, g2);
				
				double distance = ged.getDistance();
				totalDistance += distance;
				
				ged.printMatrix();
				System.out.println("Distance = " + distance);
					
				idx += 1;
			}
		
			s2.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		if (idx != baseline.length) {
			System.err.println("// Expected " + baseline.length + " graphs, but got " + idx);
		}
		
		System.out.println();
		System.out.println("//////////////////////////////");
		System.out.println("// Total distance = " + totalDistance);
	}

}
