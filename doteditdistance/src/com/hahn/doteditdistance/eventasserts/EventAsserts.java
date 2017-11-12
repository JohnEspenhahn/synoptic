package com.hahn.doteditdistance.eventasserts;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class EventAsserts {
	
	private Map<String, List<EventAssert>> eas = new HashMap<>();
	
	private EventAsserts() { }
	
	protected void addFor(String log_type, EventAssert event) {
		List<EventAssert> l;
		if (eas.containsKey(log_type))
			l = eas.get(log_type);
		else {
			l = new ArrayList<>();
			eas.put(log_type, l);
		}
		
		l.add(event);
	}
	
	public List<String> isSatisfied(String log_type, int pass, Map<String,Integer> seen, String thread) {
		List<String> failed = new ArrayList<>();
		
		if (eas.containsKey(log_type))
			for (EventAssert a: eas.get(log_type))
				if (!a.isSatisfied(pass, seen, thread))
					failed.add(a.getEventName());
		
		return failed;
	}
	
	private static EventAssert parseItem(String event, String param) { 
		switch (event.toUpperCase()) {
		case "AFTER":
			return new EventAssertAfter(param);
		case "BEFORE":
			return new EventAssertBefore(param);
		case "ONTHREAD":
			return new EventAssertOnThread(param);
		default:
			throw new RuntimeException("Unknown assert event " + event);
		}
	}

	public static EventAsserts parse(String fileName) {
		EventAsserts eas = new EventAsserts();
		
		Scanner s;
		try {
			s = new Scanner(new File(fileName));

			while (s.hasNextLine()) {
				String line = s.nextLine();
				if (line.length() < 3 || line.startsWith("#")) 
					continue;
				
				String[] args = line.split(" ");
				
				String log_type = args[0];
				String event = args[1];
				String param = args[2];
				
				eas.addFor(log_type, parseItem(event, param));
			}
			
			s.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return null;
		}
		
		return eas;
	}

}
