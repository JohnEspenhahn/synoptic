package com.hahn.logcatter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.util.Scanner;
import java.util.Set;

import com.hahn.doteditdistance.eventasserts.EventAsserts;
import com.hahn.doteditdistance.utils.logger.Logger;

public class LogCatter {
	
	private static Options argsOptions = new Options()
			.addOption(Option.builder("o").longOpt("out").desc("The name of the output log").hasArg().build())
			.addOption(Option.builder("p").longOpt("processes").desc("Process log file names").hasArgs().required().build())
			.addOption(Option.builder("d").longOpt("dir").desc("Path to directory containing process log files").hasArg().build())
			.addOption(Option.builder("a").longOpt("asserts").desc("Path to assets definition file").hasArg().build());
	
	public static void main(String[] args) {		
		CommandLine cmd;
		try {
			cmd = (new DefaultParser()).parse(argsOptions, args);
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		
		// Optionally prompt for output file name
		String outputFileName = cmd.getOptionValue("out", null);
		if (outputFileName == null) {
			Scanner s = new Scanner(System.in);
			System.out.println("Output: ");
			outputFileName = s.nextLine();
			s.close();
		}
		
		final String processPath = cmd.getOptionValue("dir", ".");
		
		EventAsserts eas = EventAsserts.parse(cmd.getOptionValue("asserts", processPath + File.separator + "assets"));
		if (eas == null) {
			System.err.println("Failed to parse asset definition file. Try providing file path as `--asserts`");
			return;
		}
		
		String channels = LogCatter.concatOutput(eas, outputFileName, cmd.getOptionValues("p"), processPath);
		if (channels == null) {
			System.err.println("Failed to concat output and generate channels");
			return;
		}
	}
	
	/**
	 * Processes and concats log files
	 * @param output The file to output to
	 * @param processNames The names of the process log files
	 * @param path The common root path for the log files
	 * @return Channels used in the logs
	 */
	public static String concatOutput(EventAsserts eas, String output, String[] processNames, String path) {
		clearOutput(output);
		
		List<String> errors = new ArrayList<>();
		
		Map<String,String[]> channel_map = new HashMap<>();
		Set<String[]> channel_used = new HashSet<>();
		
		try {
			// Parse annotations in two passes
			for (String n: processNames) {			
				Map<String,Integer> seen = new HashMap<>();
				for (int pass = 0; pass <= 1; pass++) {
					Scanner s = new Scanner(new File(path, n));
					while (s.hasNextLine()) {
						String line = s.nextLine();
						
						// Check for annotation
						if (line.startsWith("#")) {
							String[] args = line.split(" ");
							String annotation = args[1];
							
							if (pass == 0) {
								seen.put(annotation, seen.getOrDefault(annotation, 0) + 1);
								
								switch (annotation) {
								case Logger.REGISTER_CHANNEL_LOG:
									String out = args[2];
									String pid = args[3];
									
									// Register port-based channel names to process-based channel name mappings
									String new_out = String.format("%s_%s", n, pid);
									channel_map.put(out, new String[] { new_out, n, pid });
									
									String new_in = String.format("%s_%s", pid, n);
									channel_map.put(Logger.flipChannelId(out), new String[] { new_in, pid, n });
									break;
								}
							}
							
							String thread = args[args.length-1];
							if (!thread.startsWith("@")) thread = "";
							else thread = thread.substring(1);								
							
							List<String> failed = eas.isSatisfied(annotation, pass, seen, thread);
							if (!failed.isEmpty()) {
								errors.add(String.format("process:%s;event:%s;failed_asserts:%s", n, annotation, failed.toString()));
							}
						}
					}
					s.close();
				}
			}
			
			// Concat logs
			FileWriter fs = new FileWriter(new File(output));
			for (String n: processNames) {
				Scanner s = new Scanner(new File(path, n));
					
				// Update channels and concatinate
				while (s.hasNextLine()) {
					String line = s.nextLine();
					String[] args = line.split(" ");
					
					// Skip annotations
					if (!args[0].equals("#")) {
						// Update all port-based channel names to process-based channel names
						for (Entry<String,String[]> e: channel_map.entrySet()) {
							if (args[2].contains(e.getKey())) {
								args[2] = args[2].replaceFirst(e.getKey(), e.getValue()[0]);
								line = String.join(" ", args);
								
								channel_used.add(e.getValue());
							}
						}
					}
						
					fs.write(line);
					fs.write('\n');
				}
				
				s.close();
			}
			fs.write("--\n");
			fs.close();
			
			// Write errors
			FileWriter fs_error = new FileWriter(new File(output + "-errors"));
			for (String err: errors) {
				fs_error.write(err);
				fs_error.write("\n");
			}
			fs_error.close();
			
			// Convert channels into string
			StringBuilder channels = new StringBuilder();
			for (String[] channel_def: channel_used) {
				Logger.appendChannelString(channels, channel_def[0], channel_def[1], channel_def[2]);
			}
			
			// Write channels to file
			String channels_str = channels.toString();
			FileWriter fs_channel = new FileWriter(new File(output + "-channels"));
			fs_channel.write(channels_str);
			fs_channel.close();
			
			return channels_str;
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	private static void clearOutput(String output) {
		File f = new File(output);
		if (f.exists()) f.delete();
		
		File f_channel = new File(output + "-channels");
		if (f_channel.exists()) f_channel.delete();
		
		File f_error = new File(output + "-errors");
		if (f_error.exists()) f_error.delete();
	}

}
