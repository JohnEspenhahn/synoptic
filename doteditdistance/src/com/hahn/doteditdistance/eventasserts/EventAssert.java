package com.hahn.doteditdistance.eventasserts;

import java.util.Map;

public abstract class EventAssert {
	
	public abstract boolean isSatisfied(int pass, Map<String,Integer> seen, String thread);

	public abstract String getEventName();
	
}
