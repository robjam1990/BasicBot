package robjam1990.emotion;

import robjam1990.api.knowledge.Vertex;
import robjam1990.knowledge.Primitive;

/**
 * A convenience enum of different emotional states.
 */

public enum EmotionalState {
	NONE,
	LOVE, LIKE, DISLIKE, HATE,
	RAGE, ANGER, CALM, SERENE,
	ECSTATIC, HAPPY, SAD, CRYING,
	PANIC, AFRAID, CONFIDENT, COURAGEOUS,
	SURPRISE, BORED,
	LAUGHTER, SERIOUS,
	GOOD, GREAT, TERRIBLE, BAD;
	
	public Primitive primitive() {
		return new Primitive(name().toLowerCase());
	}
	
	/**
	 * Apply the emotion to the vertex.
	 * Associate to the relevant emotion.
	 */
	public void apply(Vertex vertex) {
		
		if (this == EmotionalState.LOVE) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.LOVE);
			vertex.addRelationship(Primitive.EMOTION, Primitive.LOVE);
		} else if (this == EmotionalState.LIKE) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.LOVE);
		} else if (this == EmotionalState.DISLIKE) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.LOVE);
		} else if (this == EmotionalState.HATE) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.LOVE);
			vertex.removeRelationship(Primitive.EMOTION, Primitive.LOVE);
		}
		
		else if (this == EmotionalState.RAGE) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.ANGER);
			vertex.addRelationship(Primitive.EMOTION, Primitive.ANGER);
		} else if (this == EmotionalState.ANGER) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.ANGER);
		} else if (this == EmotionalState.CALM) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.ANGER);
		} else if (this == EmotionalState.SERENE) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.ANGER);
			vertex.removeRelationship(Primitive.EMOTION, Primitive.ANGER);
		}
		
		else if (this == EmotionalState.ECSTATIC) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.HAPPINESS);
			vertex.addRelationship(Primitive.EMOTION, Primitive.HAPPINESS);
		} else if (this == EmotionalState.HAPPY) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.HAPPINESS);
		} else if (this == EmotionalState.SAD) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.HAPPINESS);
		} else if (this == EmotionalState.CRYING) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.HAPPINESS);
			vertex.removeRelationship(Primitive.EMOTION, Primitive.HAPPINESS);
		}
		
		else if (this == EmotionalState.PANIC) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.FEAR);
			vertex.addRelationship(Primitive.EMOTION, Primitive.FEAR);
		} else if (this == EmotionalState.AFRAID) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.FEAR);
		} else if (this == EmotionalState.CONFIDENT) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.FEAR);
		} else if (this == EmotionalState.COURAGEOUS) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.FEAR);
			vertex.removeRelationship(Primitive.EMOTION, Primitive.FEAR);
		}
		
		else if (this == EmotionalState.SURPRISE) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.SURPRISE);
		} else if (this == EmotionalState.BORED) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.SURPRISE);
		}
		
		else if (this == EmotionalState.LAUGHTER) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.HUMOR);
		} else if (this == EmotionalState.SERIOUS) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.HUMOR);
		}
		
		else if (this == EmotionalState.GREAT) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.SENTIMENT);
			vertex.addRelationship(Primitive.EMOTION, Primitive.SENTIMENT);
		} else if (this == EmotionalState.GOOD) {
			vertex.addRelationship(Primitive.EMOTION, Primitive.SENTIMENT);
		} else if (this == EmotionalState.BAD) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.SENTIMENT);
		} else if (this == EmotionalState.TERRIBLE) {
			vertex.removeRelationship(Primitive.EMOTION, Primitive.SENTIMENT);
			vertex.removeRelationship(Primitive.EMOTION, Primitive.SENTIMENT);
		}
	}
	
	public boolean isSentiment() {
		if (this == EmotionalState.GREAT  || this == EmotionalState.GOOD || this == EmotionalState.BAD || this == EmotionalState.TERRIBLE) {
			return true;
		} else {
			return false;
		}
		
	}
}

