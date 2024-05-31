package robjam1990.sense.text;


/**
 * Defines the properties of a command input.
 */

public class CommandInput  {
	protected String command;
	protected boolean isCorrection;
	protected boolean isOffended;
	
	public CommandInput(String command) {
		this.command = command;
	}
	
	public CommandInput(String command, boolean isCorrection, boolean isOffended) {
		this.command = command;
		this.isCorrection = isCorrection;
		this.isOffended = isOffended;
	}
	
	public String getCommand() {
		return command;
	}
	
	public void setCommand(String command) {
		this.command = command;
	}
	
	public boolean isCorrection() {
		return isCorrection;
	}
	
	public void setCorrection(boolean isCorrection) {
		this.isCorrection = isCorrection;
	}
	
	public boolean isOffended() {
		return isOffended;
	}
	
	public void setOffended(boolean isOffended) {
		this.isOffended = isOffended;
	}
	
}