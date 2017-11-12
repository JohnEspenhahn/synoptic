package com.hahn.doteditdistance.utils.pmanagement;

import java.io.PrintStream;
import java.util.LinkedList;
import java.util.Queue;

public class InputForwarder extends InputHandler {

	private Queue<String> q;
	private PrintStream out;
	private String prefix;
	
	public InputForwarder(Process p, String prefix, PrintStream out) {
		super(p);
		
		if (out == null) 
			throw new IllegalArgumentException("out cannot be null in InputForwarder");
		
		this.q = new LinkedList<String>();
		this.prefix = prefix;
		this.out = out;
	}
	
	public InputForwarder(Process p, String prefix) {
		this(p, prefix, System.out);
	}
	
	@Override
	public void run() {
		while (running && p.isAlive()) {
			String value;
			synchronized (q) {
				while (q.isEmpty()) {
					try {
						q.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				
				value = q.remove();
			}
			
			forwardValue(prefix + value);
		}
	}
	
	protected void forwardValue(String value) {
		out.print(value);
	}
	
	@Override
	public void onStopping() {
		q.notifyAll();
	}

	@Override
	public void handleInputAsync(String input) {
		synchronized (q) {
			q.add(input);
			q.notify();
		}
	}

}
