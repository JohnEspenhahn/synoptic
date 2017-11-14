package com.hahn.doteditdistance;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Scanner;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.hahn.doteditdistance.utils.pmanagement.ProcessWatcher;

import csight.main.CSightMain;
import no.roek.nlpged.graph.Graph;

public class Main {
	
	static String dotOutputExtension = ".out";
	private static Options argsOptions = new Options()
				.addOption(Option.builder("s").desc("CSight run seperator").hasArg().build())
				.addOption(Option.builder("mcp").longOpt("mcPath").desc("CSight model checker path").hasArg().required().build())
				.addOption(Option.builder("r").desc("CSight line regex").hasArg().build())
				.addOption(Option.builder("t").longOpt("base-timeout").hasArg().build())
				.addOption(Option.builder("cached").longOpt("cached-base-graph").desc("Allow usage of cached dot graph").build())
				.addOption(Option.builder("logs").longOpt("logs-under-test").desc("Files to be tested against the given base log").hasArgs().required().valueSeparator(' ').build());
	
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("No base log file specified");
			return;
		}
		
		final String baseLogFile = args[0];
		final String graphLogFile = baseLogFile + dotOutputExtension;		
		final String parsedDotFile = graphLogFile + ".dot.graph";
		Graph[] baseGraphs = new Graph[0];
		
		CommandLine cmd;
		try {
			cmd = (new DefaultParser()).parse(argsOptions, args);
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		
		Scanner s = new Scanner(new File(baseLogFile + "-channels"));
		String channels = s.nextLine();
		s.close();
		
		String[] csightArgs = new String[] {
				// "-debugParse",
				"-r", cmd.getOptionValue("r", "^(?<VTIME>)(?<PID>)(?<TYPE>)\\s?[@\\w\\d_]*\\s?[\\w\\d_]*$"),
				"-q", channels,
				"-s", "^--$",
				"--mcType", "spin",
				"-i", // ignore invalid lines
				"--mcPath", cmd.getOptionValue("mcp"),
				"-o", graphLogFile, baseLogFile // last 2 must be these
			};
		
		final File fParsedDotFile = new File(parsedDotFile);
		if (!cmd.hasOption("cached") || !fParsedDotFile.exists()) {
			// Try to load cached dot file
			final String dotFile = graphLogFile + ".dot";
			if ((new File(dotFile)).exists()) 
				baseGraphs = DotReader.dotGraphs(dotFile);
			
			// If failed to load cached, recompute
			if (baseGraphs == null || baseGraphs.length == 0) {
				try {
					ProcessWatcher cwatch = new ProcessWatcher("CSight", CSightMain.class);
					int exitStatus = cwatch.start(csightArgs).waitFor();
					cwatch.terminate();
					
					if (exitStatus != 0) {
						System.err.println("CSight failed to generate DAG for base log file " + baseLogFile);
						return;
					}
				} catch (Exception e) {
					e.printStackTrace();
					return;
				}
			}
			
			// Parse dot2graphs and cache
			baseGraphs = DotReader.dotGraphs(dotFile);
			try {
				ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(fParsedDotFile));
				oos.writeObject(baseGraphs);
				oos.close();
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		} else {
			// Load cached dot2graphs
			try {
				ObjectInputStream ois = new ObjectInputStream(new FileInputStream(fParsedDotFile));
				baseGraphs = (Graph[]) ois.readObject();
				ois.close();
			} catch (IOException | ClassNotFoundException e) {
				e.printStackTrace();
				return;
			}
		}
		
		// Run csight for logs under test
		for (String log: cmd.getOptionValues("logs")) {
			csightArgs[csightArgs.length-2] = log + dotOutputExtension;
			csightArgs[csightArgs.length-1] = log; 
			try {				
				ProcessWatcher cwatch = new ProcessWatcher("CSight", CSightMain.class);
				int exitStatus = cwatch.start(csightArgs).waitFor();
				cwatch.terminate();
				
				if (exitStatus != 0) {
					System.err.println("CSight failed to generate DAG for log file " + log);
					return;
				}
				
				// Find edit distance
				DotEditDistance.dotEditDistance(baseGraphs, log + dotOutputExtension + ".dot");
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		System.exit(0);
	}

}
