package com.hahn.doteditdistance.eventasserts;

import java.util.Map;

public class EventAssertAfter extends EventAssert {
	
	public static final String EVENT = "AFTER";

	private String afterLogType;

	public EventAssertAfter(String afterLogType) {
		this.afterLogType = afterLogType;
	}
	
	@Override
	public String getEventName() {
		return EVENT;
	}

	@Override
	public boolean isSatisfied(int pass, Map<String, Integer> seen, String thread) {
		switch (pass) {
		case 0:
			return seen.containsKey(afterLogType);
		default:
			return true;
		}
	}

}
