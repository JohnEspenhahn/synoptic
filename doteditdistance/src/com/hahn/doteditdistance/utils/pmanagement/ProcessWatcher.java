package com.hahn.doteditdistance.utils.pmanagement;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProcessWatcher {
	static final String HEADLESS = "-Djava.awt.headless=true";

	private final String name;
	private final Class<?> clazz;
	
	private Process process;
	private PrintWriter printWriter;
	
	private InputDeserializer inputDeserializer;
	private InputForwarder inputForwarder;
	private InputWatcher inputWatcher;
	
	private InputForwarder errForwarder;
	private InputWatcher errWatcher;
	
	public ProcessWatcher(String name, Class<?> clazz) {
		this.name = name;
		this.clazz = clazz;
	}
	
	public ProcessWatcher start(String... args) throws IOException {
		if (process != null) {
			throw new RuntimeException("Already started");
		}
		
		String javaHome = System.getProperty("java.home");
		String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
		String classPath = System.getProperty("java.class.path");
		String className = clazz.getCanonicalName();

		String[] all_args = new String[args.length + 5];
		all_args[0] = javaBin;
		all_args[1] = "-cp";
		all_args[2] = classPath;
		all_args[3] = HEADLESS;
		all_args[4] = className;
		System.arraycopy(args, 0, all_args, 5, args.length);
		
		ProcessBuilder builder = new ProcessBuilder(all_args);
		process = builder.start();
		
		// Allow writing to stdin
		printWriter = new PrintWriter(process.getOutputStream());
		
		// Watch stdout for events
		inputDeserializer = getInputDeserializer();
		inputForwarder = new InputForwarder(process, "[" + name + "] ");
		
		List<InputHandler> handlers = new ArrayList<>();
		if (inputDeserializer != null) handlers.add(inputDeserializer);
		handlers.add(inputForwarder);
		
		inputWatcher = new InputWatcher(process.getInputStream(), handlers);
		
		errForwarder = new InputForwarder(process, "[" + name + "] ", System.err);
		errWatcher = new InputWatcher(process.getErrorStream(), Arrays.asList(errForwarder));
		
		if (inputDeserializer != null)
			new Thread(inputDeserializer).start();
		
		new Thread(inputForwarder).start();
		new Thread(inputWatcher).start();
		
		new Thread(errForwarder).start();
		new Thread(errWatcher).start();
		
		return this;
	}
	
	protected Process getProcess() {
		return process;
	}
	
	protected InputDeserializer getInputDeserializer() {
		return null;
	}
	
	/**
	 * Causes the current thread to wait, if necessary, until the process represented by this Process object has terminated
	 */
	public int waitFor() throws InterruptedException {
		return process.waitFor();
	}
	
	public void waitForEvent(Class<?> event) {
		if (inputDeserializer != null)
			inputDeserializer.waitForEvent(event);
	}
	
	public void println(String s) {
		if (printWriter != null) {
			printWriter.println(s);
		}
	}
	
	public void flush() {
		if (printWriter != null) {
			printWriter.flush();
		}
	}
	
	public void terminate() {
		if (process != null) {
			process.destroyForcibly();
		}
	}
	
}
