package com.hahn.doteditdistance.eventasserts;

import java.util.Map;

public class EventAssertBefore extends EventAssert {
	
	public static final String EVENT = "BEFORE";

	private String beforeLogType;

	public EventAssertBefore(String beforeLogType) {
		this.beforeLogType = beforeLogType;
	}
	
	@Override
	public String getEventName() {
		return EVENT;
	}

	@Override
	public boolean isSatisfied(int pass, Map<String, Integer> seen, String thread) {
		switch (pass) {
		case 0:
			return !seen.containsKey(beforeLogType);
		case 1:
			return seen.containsKey(beforeLogType);
		default:
			return true;
		}
	}

}
