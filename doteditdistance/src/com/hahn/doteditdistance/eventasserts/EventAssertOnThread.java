package com.hahn.doteditdistance.eventasserts;

import java.util.Map;

public class EventAssertOnThread extends EventAssert {

	public static final String EVENT = "ONTHREAD";

	private String thread;
	
	protected EventAssertOnThread(String thread) {
		this.thread = thread;
	}
	
	@Override
	public String getEventName() {
		return EVENT;
	}
	
	@Override
	public boolean isSatisfied(int pass, Map<String, Integer> seen, String thread) {
		if (pass == 0)
			return thread != null && this.thread.equals(thread);
		else
			return true;
	}

}
