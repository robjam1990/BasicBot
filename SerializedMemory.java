package robjam1990.knowledge.serialized;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

import robjam1990.api.knowledge.MemoryStorageException;
import robjam1990.api.knowledge.Network;
import robjam1990.api.knowledge.Relationship;
import robjam1990.api.knowledge.Vertex;
import robjam1990.knowledge.BasicMemory;
import robjam1990.knowledge.BasicNetwork;

/**
 * SerializedMemory stores the memory to a binary file using Java object serialization.
 * The entire memory is loaded into memory.
 * This memory is fast and good for embedded environments such as Android, but does not scale to large memory sizes.
 * SerializedMemory is much slower than MicroMemory.
 */
public class SerializedMemory extends BasicMemory {

	public static File storageDir = null;
	public static String storageFileName = "memory.ser";

	public static void reset() {
		File file = new File(storageDir, storageFileName);
		if (file.exists()) {
			file.delete();
		}
		file = new File(storageDir, storageFileName + "x");
		if (file.exists()) {
			file.delete();
		}
	}

	public static boolean checkExists() {
		File file = new File(storageDir, storageFileName);
		return (file.exists());
	}
	
	public void shutdown() throws MemoryStorageException {
		super.shutdown();
		long start = System.currentTimeMillis();
		File file = new File(storageDir, storageFileName);
		getBot().log(this, "Saving memory to file", Level.INFO, file);
		try {
			List<Vertex> vertices = new ArrayList<Vertex>(((BasicNetwork)getLongTermMemory()).getVerticesById().values());
			// Flatten objects to avoid stack overflow.
			List<Relationship> relationships = new ArrayList<Relationship>(vertices.size());
			for (Vertex vertex : vertices) {
				for (Iterator<Relationship> iterator = vertex.allRelationships(); iterator.hasNext(); ) {
					relationships.add(iterator.next());
				}
			}
			FileOutputStream fileStream = new FileOutputStream(file);
			ObjectOutputStream objectStream = new ObjectOutputStream(fileStream);
			objectStream.writeObject(vertices);
			objectStream.writeObject(relationships);
			objectStream.flush();
			objectStream.close();
			fileStream.flush();
			fileStream.close();
			long time = System.currentTimeMillis() - start;
			getBot().log(this, "Memory saved", Level.INFO, getLongTermMemory().size(), file.length(), time);
		} catch (IOException exception) {
			throw new MemoryStorageException(exception);
		}
	}

	@Override
	public void restore() throws MemoryStorageException {
		restore(storageFileName, true);
	}
	
	@SuppressWarnings("unchecked")
	public void restore(String database, boolean isSchema) throws MemoryStorageException {
		if (database.isEmpty()) {
			database = storageFileName;
		}
		File file = new File(storageDir, database);
		getBot().log(this, "Restoring memory from file", Level.INFO, file.length());
		Network longTermMemory = new BasicNetwork();
		longTermMemory.setBot(getBot());
		if (file.exists()) {
			try {
				long start = System.currentTimeMillis();
				FileInputStream fileStream = new FileInputStream(file);
				ObjectInputStream objectStream = new ObjectInputStream(fileStream);
				//System.out.println(start);
				List<Vertex> vertices = (List<Vertex>) objectStream.readObject();
				List<Relationship> relationships = (List<Relationship>) objectStream.readObject();
				for (Vertex vertex : vertices) {
					longTermMemory.addVertex(vertex);
				}
				for (Relationship relationship : relationships) {
					relationship.getSource().addRelationship(relationship, true);
					((BasicNetwork)longTermMemory).addRelationship(relationship);
				}
				objectStream.close();
				fileStream.close();
				long time = System.currentTimeMillis() - start;
				getBot().log(this, "Memory restored file", Level.INFO, longTermMemory.size(), file.length(), time);
			} catch (Exception exception) {
				throw new MemoryStorageException(exception);
			}
		}
		setLongTermMemory(longTermMemory);
	}
}
