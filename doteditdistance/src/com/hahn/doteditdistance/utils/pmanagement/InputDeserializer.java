package com.hahn.doteditdistance.utils.pmanagement;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class InputDeserializer extends InputHandler {
	private List<String> q;
	private Map<String, Object> eventLocks;
	
	protected InputDeserializer(Process p) {
		super(p);
		this.q = new LinkedList<String>();
		
		this.eventLocks = new HashMap<String,Object>();
	}
	
	public void registerEvent(Class<?> clazz) {
		eventLocks.put(clazz.getSimpleName(), new Object());
	}
	
	public void waitForEvent(Class<?> event) {
		String eventName = event.getSimpleName();
		if (!eventLocks.containsKey(eventName)) {
			System.err.println("Unknown event " + eventName);
			return;
		}
		
		Object key = eventLocks.get(eventName);
		synchronized (key) {
			try {
				System.err.println("Waiting for event " + eventName);
				key.wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		System.err.println("Got event " + eventName);
	}
	
	public void handleInputAsync(String input) {
		deserializeAsync(input);
	}
	
	public void deserializeAsync(String s) {
		if (s == null) return;
		
		synchronized (q) {
			q.add(s);
			q.notify();
		}
	}
	
	protected void deserialize(String s) {
		if (s == null) return;
		
		// Hack, todo make better
		if (s.startsWith("I***")) {
			final String EvtType = "EvtType(";
			int evtIdx = s.indexOf(EvtType);
			if (evtIdx == -1) return;
			
			int evtStart = evtIdx + EvtType.length();
			int evtEnd = s.indexOf(')', evtStart);
			if (evtEnd == -1) return;
			
			notifyEvent(s.substring(evtStart, evtEnd));
		}
	}
	
	protected void notifyEvent(String event) {
		if (!eventLocks.containsKey(event))
			return;
		
		Object key = eventLocks.get(event);
		synchronized (key) {
			key.notifyAll();
		}
	}
	
	@Override
	public void run() {
		while (running && p.isAlive()) {
			String value;
			synchronized(q) {
				while (q.isEmpty()) {
					try {
						q.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				
				value = q.remove(0);
			}
			
			deserialize(value);
		}
	}
	
	@Override
	public void onStopping() {
		q.notifyAll();
	}

}
