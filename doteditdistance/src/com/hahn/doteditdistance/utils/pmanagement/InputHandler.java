package com.hahn.doteditdistance.utils.pmanagement;

public abstract class InputHandler implements Runnable {
	
	protected boolean running;
	protected final Process p;
	
	public InputHandler(Process parent) {
		this.p = parent;
		this.running = true;
	}
	
	abstract void handleInputAsync(String input);
	
	public final void stop() {
		running = false;
		onStopping();
	}
	
	protected abstract void onStopping();

}
