package robjam1990.sense;

import java.util.HashMap;
import java.util.Map;

import robjam1990.Bot;
import robjam1990.api.knowledge.Vertex;
import robjam1990.api.sense.Awareness;
import robjam1990.api.sense.Sense;
import robjam1990.api.sense.Tool;

/**
 * Controls and manages the senses.
 */

public class BasicAwareness implements Awareness {
	
	protected Bot bot;
	protected Map<String, Sense> senses;
	protected Map<String, Sense> sensesByShortName;
	
	protected Map<String, Tool> tools;
	protected Map<String, Tool> toolsByShortName;

	public BasicAwareness() {
		this.senses = new HashMap<String, Sense>();
		this.sensesByShortName = new HashMap<String, Sense>();
		this.tools = new HashMap<String, Tool>();
		this.toolsByShortName = new HashMap<String, Tool>();
	}

	/**
	 * Return Bot.
	 */
	public Bot getBot() {
		return bot;
	}
	
	/**
	 * Set Bot.
	 */
	public void setBot(Bot bot) {
		this.bot = bot;
	}
	
	/**
	 * Initialize any configurable settings from the properties.
	 */
	@Override
	public void initialize(Map<String, Object> properties) {
		return;
	}
	
	/**
	 * Reset state when instance is pooled.
	 */
	@Override
	public void pool() {
		getBot().log(this, "Pool", Bot.FINE);
		for (Sense sense : getSenses().values()) {
			try {
				sense.pool();
			} catch (Exception exception) {
				getBot().log(this, exception);
			}
		}
		for (Tool tool : getTools().values()) {
			try {
				tool.pool();
			} catch (Exception exception) {
				getBot().log(this, exception);
			}
		}
	}

	@Override
	public void shutdown() {
		getBot().log(this, "Shutdown", Bot.FINE);
		for (Sense sense : getSenses().values()) {
			try {
				sense.shutdown();
			} catch (Exception exception) {
				getBot().log(this, exception);
			}
		}
		for (Tool tool : getTools().values()) {
			try {
				tool.shutdown();
			} catch (Exception exception) {
				getBot().log(this, exception);
			}
		}
	}

	@Override
	public void awake() {
		getBot().log(this, "Awake", Bot.FINE);
		for (Sense sense : getSenses().values()) {
			try {
				sense.awake();
			} catch (Exception exception) {
				getBot().log(sense, exception);
			}
		}
		for (Tool tool : getTools().values()) {
			try {
				tool.awake();
			} catch (Exception exception) {
				getBot().log(tool, exception);
			}
		}
	}
	
	public Map<String, Sense> getSenses() {
		return senses;
	}

	@SuppressWarnings("unchecked")
	public <T> T getSense(Class<T> type) {
		return (T)getSense(type.getName());
	}
	
	public Sense getSense(String senseName) {
		Sense sense = getSenses().get(senseName);
		if (sense == null) {
			sense = this.sensesByShortName.get(senseName);
		}
		return sense;
	}
	
	public void addSense(Sense sense) {
		sense.setBot(getBot());
		getSenses().put(sense.getName(), sense);
		// Also index simple name.
		this.sensesByShortName.put(sense.getClass().getSimpleName(), sense);
		this.sensesByShortName.put(sense.getClass().getSimpleName().toLowerCase(), sense);
	}
	
	public void removeSense(Sense sense) {
		getSenses().remove(sense.getName());
		// Also index simple name.
		this.sensesByShortName.remove(sense.getClass().getSimpleName());
		this.sensesByShortName.remove(sense.getClass().getSimpleName().toLowerCase());
	}
	
	public Map<String, Tool> getTools() {
		return tools;
	}

	@SuppressWarnings("unchecked")
	public <T> T getTool(Class<T> type) {
		return (T)getTool(type.getName());
	}
	
	public Tool getTool(String name) {
		Tool tool = getTools().get(name);
		if (tool == null) {
			tool = this.toolsByShortName.get(name);
		}
		return tool;
	}
	
	public void addTool(Tool tool) {
		tool.setBot(getBot());
		getTools().put(tool.getName(), tool);
		// Also index simple name.
		this.toolsByShortName.put(tool.getClass().getSimpleName(), tool);
		this.toolsByShortName.put(tool.getClass().getSimpleName().toLowerCase(), tool);
	}
	
	public void removeTool(Tool tool) {
		getTools().remove(tool.getName());
		// Also index simple name.
		this.toolsByShortName.remove(tool.getClass().getSimpleName());
		this.toolsByShortName.remove(tool.getClass().getSimpleName().toLowerCase());
	}
	
	public String toString() {
		return getClass().getSimpleName();
	}

	/**
	 * Allow the sense to output the response.
	 */
	public void output(Vertex output) {
		getBot().mood().evaluateOutput(output);
		getBot().avatar().evaluateOutput(output);
		for (Sense sense : getSenses().values()) {
			try {
				sense.output(output);
			} catch (Throwable ignore) {
				getBot().log(this, ignore);
			}
		}
	}
}

