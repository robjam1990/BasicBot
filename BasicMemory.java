package robjam1990.knowledge;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import robjam1990.Bot;
import robjam1990.api.knowledge.Memory;
import robjam1990.api.knowledge.MemoryEventListener;
import robjam1990.api.knowledge.Network;
import robjam1990.api.knowledge.Relationship;
import robjam1990.api.knowledge.Vertex;

/**
 * Defines a set of networks that make up a knowledge base.
 * Defines long term, short term and flash networks.
 * Basic implementation to allow subclasses to avoid defining some of the basic stuff,
 * Note this basic implementation is not persistent.
 */

public class BasicMemory implements Memory {

	/** Back reference to Bot instance. **/
	protected Bot bot;
	protected List<Vertex> activeMemory;
	protected Network shortTermMemory;
	protected Network longTermMemory;
	protected List<MemoryEventListener> listeners;
	protected Map<String, String> properties;
	
	public BasicMemory() {
		this.activeMemory = new ArrayList<Vertex>();
		this.listeners = new ArrayList<MemoryEventListener>();
		this.properties = new HashMap<String, String>();
		initMemory();
	}
	
	public void initMemory() {
		this.longTermMemory = new BasicNetwork();
		this.longTermMemory.setBot(getBot());
		this.shortTermMemory = new BasicNetwork(this.longTermMemory);
		this.shortTermMemory.setBot(getBot());
	}

	/**
	 * Return the current connected database name.
	 */
	public String getMemoryName() {
		return "Basic";
	}

	/**
	 * Return the property setting.
	 */
	public String getProperty(String property) {
		return this.properties.get(property);
	}

	/**
	 * Save the property setting.
	 */
	public String setProperty(String property, String value) {
		return this.properties.put(property, value);
	}

	/**
	 * Remove the property setting.
	 */
	public String removeProperty(String property) {
		return this.properties.remove(property);
	}

	public Map<String, String> getProperties() {
		return properties;
	}

	public void setProperties(Map<String, String> properties) {
		this.properties = properties;
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
		if (this.shortTermMemory != null) {
			this.shortTermMemory.setBot(bot);
		}
		if (this.longTermMemory != null) {
			this.longTermMemory.setBot(bot);
		}
	}
	
	/**
	 * Initialize any configurable settings from the properties.
	 */
	public void initialize(Map<String, Object> properties) {
		return;
	}
	
	/**
	 * Load any properties and init.
	 */
	public void awake() {
		
	}
	
	public void loadProperties(String propertySet) {
		
	}

	public void clearProperties(String propertySet) {
		
	}
	
	/**
	 * Active memory represents the last sensory state.
	 */
	public List<Vertex> getActiveMemory() {
		return activeMemory;
	}
	
	/**
	 * Add the sensory data to the active memory.
	 * Register the vertex in the short-term memory and return the registered version.
	 */
	public synchronized Vertex addActiveMemory(Vertex vertex) {
		Vertex activeVertex = getShortTermMemory().createVertex(vertex);
		getActiveMemory().add(activeVertex);
		for (MemoryEventListener listener : getListeners()) {
			listener.addActiveMemory(vertex);
		}
		notifyAll();
		return activeVertex;
	}

	/**
	 * Represents a non-committed transactional memory.
	 * Helps to define a learn scope and local context.
	 */
	public Network getShortTermMemory() {
		return shortTermMemory;
	}

	/**
	 * Return an isolated transactional memory.
	 * Can be used by senses or sub-conscious thought for concurrent processing.
	 */
	public Network newMemory() {
		BasicNetwork memory = new BasicNetwork(getLongTermMemory());
		memory.setBot(this.bot);
		return memory;
	}

	/**
	 * Represents the persisted memory (or cache there of).
	 */
	public Network getLongTermMemory() {
		return longTermMemory;
	}
	
	public void setActiveMemory(List<Vertex> activeMemory) {
		this.activeMemory = activeMemory;
	}
	
	public void setShortTermMemory(Network shortTermMemory) {
		this.shortTermMemory = shortTermMemory;
	}
	
	public void setLongTermMemory(Network longTermMemory) {		
		this.longTermMemory = longTermMemory;
		if (getShortTermMemory().getParent() != longTermMemory) {
			getShortTermMemory().setParent(longTermMemory);
		}
	}
	
	/**
	 * Merge the short term memory into the long term and clears the short term.
	 * This is similar to a transactional commit.
	 * The changes should also be persisted, as the long term should always just be a cache of the storage.
	 * This implementation does not support persistence.
	 */
	public void save() {
		getBot().log(this, "Saving", Bot.FINE, getShortTermMemory());
		getLongTermMemory().merge(getShortTermMemory());
		getShortTermMemory().resume();
	}
		
	/**
	 * This implementation does not support persistence.
	 */
	public void restore() {
		getBot().log(this, "Restoring", Bot.FINE, this);
	}
	
	/**
	 * This implementation does not support persistence.
	 */
	public void fastRestore(String database, boolean isSchema) {
		getBot().log(this, "Restoring", Bot.FINE, this, database);
	}
		
	/**
	 * This implementation does not support persistence.
	 */
	public void restore(String database, boolean isSchema) {
		getBot().log(this, "Restoring", Bot.FINE, this, database);
	}

	/**
	 * Create a memory database.
	 */
	public void createMemory(String database) { }

	/**
	 * Create a memory database.
	 */
	public void createMemory(String database, boolean isSchema) { }

	/**
	 * Create a memory database.
	 */
	public void createMemoryFromTemplate(String database, String template) {
		createMemoryFromTemplate(database, false, template, false);
	}

	/**
	 * Create a memory database.
	 */
	public void createMemoryFromTemplate(String database, boolean templateIsSchema, String template, boolean isSchema) { }

	/**
	 * Destroy the database.
	 */
	public void destroyMemory(String database) {
		destroyMemory(database, false);
	}

	/**
	 * Destroy the database.
	 */
	public void destroyMemory(String database, boolean isSchema) { }
	
	/**
	 * Delete all content from the database.
	 */
	public void deleteMemory() {
		this.longTermMemory = new BasicNetwork();
		this.longTermMemory.setBot(getBot());
		this.shortTermMemory = new BasicNetwork(this.longTermMemory);
		this.shortTermMemory.setBot(getBot());
	}
	
	/**
	 * Allow import of another memory location.
	 */
	public void importMemory(String location) { }
	
	/**
	 * Allow switching to another memory location.
	 */
	public void switchMemory(String location) { }
	
	/**
	 * Allow switching to another memory location.
	 */
	public void switchMemory(String location, boolean isSchema) { }
	
	/**
	 * Shutdown the memory.
	 */
	public synchronized void shutdown() {
		try {
			save();
		} catch (Exception exception) {
			getBot().log(this, exception);
		}
	}
	
	/**
	 * Reset state when instance is pooled.
	 */
	public void pool() {
		
	}
	
	/**
	 * Reset the short term and active memories.
	 * Revert to the long term state.
	 */
	public void abort() {
		getBot().log(this, "Aborting", Bot.FINE, getShortTermMemory());
		this.activeMemory = new LinkedList<Vertex>();
		this.shortTermMemory = new BasicNetwork();
		this.shortTermMemory.setBot(getBot());
	}

	public String toString() {
		return getClass().getSimpleName() + "(active(" + this.activeMemory.size()
			//+ "), short(" + this.shortTermMemory.size()
			+ "), long(" + this.longTermMemory.size() + "))";			
	}

	public List<MemoryEventListener> getListeners() {
		return listeners;
	}

	protected void setListeners(List<MemoryEventListener> listeners) {
		this.listeners = listeners;
	}
	
	/**
	 * Add the memory listener.
	 * It will be notified of any new active memory.
	 */
	public void addListener(MemoryEventListener listener) {
		getListeners().add(listener);
	}
	
	public void removeListener(MemoryEventListener listener) {
		getListeners().remove(listener);
	}

	public int cacheSize() {
		return 0;
	}
	
	public void freeMemory() {	}

	/**
	 * Self API
	 * Search what references the object by the relationship.
	 * Memory.findReferencesBy(#conversation, #instantiation)
	 */
	public Vertex findReferencesBy(Vertex source, Vertex target, Vertex type) {
		Network network = source.getNetwork();
		Vertex result = network.createInstance(Primitive.ARRAY);
		List<Relationship> relationships = network.findAllRelationshipsTo(target, type);
		int count = 0;
		for (Relationship relationship : relationships) {
			if (!relationship.isInverse()) {
				result.addRelationship(Primitive.ELEMENT, relationship.getSource(), Integer.MAX_VALUE);
				count++;
				if (count > 1000) {
					break;
				}
			}
		}
		network.getBot().log(this, "Found references", Level.FINER, target, type, count);
		return result;
	}
	
	/**
	 * Self API
	 * Search what references the object.
	 * Memory.findReferences(#cool)
	 */
	public Vertex findReferences(Vertex source, Vertex target) {
		Network network = source.getNetwork();
		Vertex result = network.createInstance(Primitive.ARRAY);
		List<Relationship> relationships = network.findAllRelationshipsTo(target);
		int count = 0;
		for (Relationship relationship : relationships) {
			if (!relationship.isInverse()) {
				result.addRelationship(Primitive.ELEMENT, relationship.getSource(), Integer.MAX_VALUE);
				count++;
				if (count > 1000) {
					break;
				}
			}
		}
		network.getBot().log(this, "Found references", Level.FINER, target, count);
		return result;
	}
	
	/**
	 * Self API
	 * Search all instances of the class type.
	 * Memory.findInstances(type)
	 */
	public Vertex findInstances(Vertex source, Vertex type) {
		Network network = source.getNetwork();
		Vertex result = network.createInstance(Primitive.ARRAY);
		List<Relationship> relationships = network.findAllRelationshipsTo(type, network.createVertex(Primitive.INSTANTIATION));
		int count = 0;
		for (Relationship relationship : relationships) {
			if (!relationship.isInverse()) {
				result.addRelationship(Primitive.ELEMENT, relationship.getSource(), Integer.MAX_VALUE);
				count++;
				if (count > 1000) {
					break;
				}
			}
		}
		network.getBot().log(this, "Found references", Level.FINER, type, count);
		return result;
	}
	
	/**
	 * Self API
	 * Search all instances of the class type, created after the date.
	 * Memory.findInstances(type, date)
	 */
	public Vertex findInstances(Vertex source, Vertex type, Vertex date) {
		Network network = source.getNetwork();
		Vertex result = network.createInstance(Primitive.ARRAY);
		if (!(date.getData() instanceof Date)) {
			return network.createVertex(Primitive.NULL);
		}
		List<Relationship> relationships = network.findAllRelationshipsTo(type, network.createVertex(Primitive.INSTANTIATION), (Date)date.getData());
		int count = 0;
		for (Relationship relationship : relationships) {
			if (!relationship.isInverse()) {
				result.addRelationship(Primitive.ELEMENT, relationship.getSource(), Integer.MAX_VALUE);
				count++;
				if (count > 1000) {
					break;
				}
			}
		}
		network.getBot().log(this, "Found references", Level.FINER, type, count);
		return result;
	}
	
	/**
	 * Self API
	 * Lookup an object by id.
	 * Memory.find(id)
	 */
	public Vertex find(Vertex source, Vertex id) {
		Network network = source.getNetwork();
		if (!(id.getData() instanceof Number)) {
			return network.createVertex(Primitive.NULL);
		}
		Vertex object = network.findById(((Number)id.getData()).longValue());
		if (object == null) {
			return network.createVertex(Primitive.NULL);
		}
		return object;
	}
}