package com.hahn.doteditdistance.eventasserts;

import java.util.Map;

public class EventAssertValue extends EventAssert {
	
	public static final String EVENT = "VALUE";
	private int value;

	public EventAssertValue(String param) {
		try {
			value = Integer.parseInt(param);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid parameter for EventAsserValue: " + param);
		}
	}

	@Override
	public boolean isSatisfied(int pass, Map<String, Integer> seen, String thread) {
		switch (pass) {
		case 0:
			return true;
		default:
			return true;
		}
	}

	@Override
	public String getEventName() {
		return EVENT;
	}

}
