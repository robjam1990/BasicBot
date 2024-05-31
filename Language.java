package robjam1990.thought.language;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import robjam1990.Bot;
import robjam1990.BotException;
import robjam1990.aiml.AIMLParser;
import robjam1990.api.knowledge.Network;
import robjam1990.api.knowledge.Relationship;
import robjam1990.api.knowledge.Vertex;
import robjam1990.api.sense.Sense;
import robjam1990.emotion.EmotionalState;
import robjam1990.knowledge.Primitive;
import robjam1990.knowledge.TextData;
import robjam1990.self.SelfCompiler;
import robjam1990.self.SelfDecompiler;
import robjam1990.self.SelfExecutionException;
import robjam1990.self.SelfInterpreter;
import robjam1990.self.SelfParseException;
import robjam1990.sense.context.Context;
import robjam1990.sense.http.Http;
import robjam1990.sense.http.Wiktionary;
import robjam1990.sense.wikidata.Wikidata;
import robjam1990.thought.BasicThought;
import robjam1990.util.TextStream;
import robjam1990.util.Utils;

/**
 * Processes words to determine meaning and response.
 */
public class Language extends BasicThought {
	public static float CONVERSATION_MATCH_PERCENTAGE = 0.5f;
	public static float DISCUSSION_MATCH_PERCENTAGE = 0.9f;
	public static int COVERSATIONAL_RESPONSE_DELAY = 1000;
	public static int MAX_PROCCESS_TIME = 100000;
	public static int MAX_STATE_PROCESS = 10000;
	public static int MAX_RESPONSE_PROCESS = 2000;
	public static int MAX_FILE_SIZE = 10000000;  // 10 meg
	public static int MAX_DEPTH = 100;
	public static int MAX_STACK = 500;
	public static int MAX_LOOP = 5000;
	public static boolean PROCESS_HTTP = true;

	protected boolean enableEmote = true;
	protected boolean enableResponseMatch = true;
	protected boolean checkExactMatchFirst = true;
	protected boolean fixFormulaCase = true;
	protected boolean learnGrammar = false;
	protected boolean checkSynonyms = true;
	protected boolean splitParagraphs = true;
	protected boolean reduceQuestions = true;
	protected boolean trackCase = false;
	protected boolean aimlCompatibility = false;
	protected boolean synthesizeResponse = false;
	protected float conversationMatchPercentage = CONVERSATION_MATCH_PERCENTAGE;
	protected float discussionMatchPercentage = DISCUSSION_MATCH_PERCENTAGE;
	protected float learningRate = 0.5f;
	protected Boolean allowLearning;

	protected int maxStateProcess = MAX_STATE_PROCESS;
	protected int maxResponseMatchProcess = MAX_RESPONSE_PROCESS;
	protected String language = null;
	protected int recursiveInputDepth;
	protected int recursiveFormulaDepth;
	protected long startTime;
	protected boolean abort;
	
	public int defaultResponses;
	public int confidence;
	public float sentiment;
	
	protected int nlp = 4;
	protected boolean penalizeExtraneousWords = true;
	protected float penalizeExtraneousWordsAmount = 1.0f;
	protected float fragmentMatchPercent = 0.1f;

	/** Store the state machine used for the last response (if any). */
	protected Long lastStateMachineId;
	/** Store the state used for the last response (if any). */
	protected Long lastStateId;
	/** Store the quotient used for the last response (if any). */
	protected Long lastQuotientId;
	/** Store the meta relationship for last response (if any). */
	protected Long lastResponseMetaId;
	/** Store if a mimic was used. */
	protected boolean wasMimic;
	/** Store the learning mode. */
	protected LearningMode learningMode = LearningMode.Disabled;
	/** Store the correction mode. */
	protected CorrectionMode correctionMode = CorrectionMode.Administrators;
	
	/** Optimize state loading. */
	protected Set<Long> loadedStates = new HashSet<Long>();
	
	/**
	 * Defines the various language conversational states.
	 */
	public enum LanguageState {
		Ignore, Associate, Conversational, Listening, ListeningOnly, Discussion, Answering
	}
	/**
	 * Defines who the bot will learn from.
	 */
	public enum LearningMode {
		Disabled, Administrators, Users, Everyone
	}
	/**
	 * Defines who the bot will allow corrections from.
	 */
	public enum CorrectionMode {
		Disabled, Administrators, Users, Everyone
	}
	
	/**
	 * Add the input to the conversation.
	 */
	public static void addToConversation(Vertex input, Vertex conversation) {
		input.addRelationship(Primitive.CONVERSATION, conversation);
		Vertex previous = conversation.lastRelationship(Primitive.INPUT);
		if (previous != null) {
			previous.addRelationship(Primitive.NEXT, input);
			input.addRelationship(Primitive.PREVIOUS, previous);
		}
		conversation.appendRelationship(Primitive.INPUT, input);
		Vertex sentence = input.getRelationship(Primitive.INPUT);
		if (sentence != null && sentence.instanceOf(Primitive.SENTENCE)) {
			// Also track sentences used in the conversation.
			conversation.addRelationship(Primitive.SENTENCE, sentence);
		}
	}
	
	/**
	 * Add the sentence from the previous input to the relationships response meta info.
	 */
	public static void addSentencePreviousMeta(Relationship relationship, Vertex previousQuestionInput, Network network) {
		// Associate previous question as meta info.
		if (previousQuestionInput != null) {
			Vertex previousQuestion = previousQuestionInput.getRelationship(Primitive.INPUT);
			if (previousQuestion != null) {
				Vertex meta = network.createMeta(relationship);
				meta.addRelationship(Primitive.PREVIOUS, previousQuestion);
			}
		}
	}
	
	/**
	 * Add the keywords as required keywords for a response match to the question meta.
	 */
	public static void addSentenceKeyWordsMeta(Vertex question, Vertex answer, String keywords, Network network) {
		if (keywords == null || keywords.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.KEYWORD, network);
		} else {
			Relationship relationship = question.getRelationship(Primitive.RESPONSE, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.internalRemoveRelationships(Primitive.KEYWORD);
				TextStream stream = new TextStream(keywords);
				while (!stream.atEnd()) {
					String text = stream.nextWord();
					if (text == null) {
						break;
					}
					// Ignore ,
					if (text.equals(",")) {
						continue;
					}
					Vertex word = null;
					if (text.equals("\"")) {
						// Support compound keywords.
						text = stream.nextQuotes();
						word = network.createWord(text);
					} else {
						word = network.createWord(text);
					}
					if (answer.isPinned()) {
						SelfCompiler.getCompiler().pin(word);
					}
					meta.addRelationship(Primitive.KEYWORD, word);
					word.addRelationship(Primitive.INSTANTIATION, Primitive.KEYWORD);
					word.addRelationship(Primitive.KEYQUESTION, question);

					if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
						Vertex lowercase = network.createWord(text.toLowerCase());
						if (answer.isPinned()) {
							SelfCompiler.getCompiler().pin(lowercase);
						}
						if (lowercase != word) {
							meta.addRelationship(Primitive.KEYWORD, lowercase);
							lowercase.addRelationship(Primitive.INSTANTIATION, Primitive.KEYWORD);
							lowercase.addRelationship(Primitive.KEYQUESTION, question);
						}
					}
				}
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
				network.checkReduction(question);
				Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						relationship = synonym.getTarget().getRelationship(Primitive.RESPONSE, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.internalRemoveRelationships(Primitive.KEYWORD);
							TextStream stream = new TextStream(keywords);
							while (!stream.atEnd()) {
								String text = stream.nextWord();
								if (text == null) {
									break;
								}
								// Ignore ,
								if (text.equals(",")) {
									continue;
								}
								Vertex word = null;
								if (text.equals("\"")) {
									// Support compound keywords.
									text = stream.nextQuotes();
									word = network.createWord(text);
								} else {
									word = network.createWord(text);
								}
								meta.addRelationship(Primitive.KEYWORD, word);

								if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
									Vertex lowercase = network.createWord(text.toLowerCase());
									if (lowercase != word) {
										meta.addRelationship(Primitive.KEYWORD, lowercase);
									}
								}
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the emotes to the response.
	 */
	public static void addSentenceEmotesMeta(Vertex question, Vertex answer, String emotes, Network network) {
		addSentenceEmotesMeta(question, answer, Primitive.RESPONSE, emotes, network);
	}
	
	/**
	 * Add the emotes to the response.
	 */
	public static void addSentenceEmotesMeta(Vertex question, Vertex answer, Primitive type, String emotes, Network network) {
		if (emotes == null || emotes.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.EMOTION, network);
		} else {
			Relationship relationship = question.getRelationship(type, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.internalRemoveRelationships(Primitive.EMOTION);
				List<String> words = Utils.getWords(emotes);
				for (String emote : words) {
					EmotionalState.valueOf(emote.toUpperCase()).apply(meta);
				}
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
				network.checkReduction(question);
				Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						relationship = synonym.getTarget().getRelationship(type, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.internalRemoveRelationships(Primitive.EMOTION);
							List<String> words = Utils.getWords(emotes);
							for (String emote : words) {
								EmotionalState.valueOf(emote.toUpperCase()).apply(meta);
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the actions to the response.
	 */
	public static void addSentenceActionMeta(Vertex question, Vertex answer, String actions, Network network) {
		addSentenceActionMeta(question, answer, Primitive.RESPONSE, actions, network);
	}
	
	/**
	 * Add the actions to the response.
	 */
	public static void addSentenceActionMeta(Vertex question, Vertex answer, Primitive responseType, String actions, Network network) {
		if (actions == null || actions.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.ACTION, network);
		} else {
			Relationship relationship = question.getRelationship(responseType, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.internalRemoveRelationships(Primitive.ACTION);
				List<String> words = Utils.getWords(actions);
				for (String action : words) {
					if (!action.equals("none")) {
						meta.addRelationship(Primitive.ACTION, new Primitive(action));
					}
				}
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
				network.checkReduction(question);
				Collection<Relationship> reductions = question.getRelationships(Primitive.REDUCTION);
				if (reductions != null) {
					for (Relationship reduction : reductions) {
						relationship = reduction.getTarget().getRelationship(responseType, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.internalRemoveRelationships(Primitive.ACTION);
							List<String> words = Utils.getWords(actions);
							for (String action : words) {
								if (!action.equals("none")) {
									meta.addRelationship(Primitive.ACTION, new Primitive(action));
								}
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the actions to the response.
	 */
	public static void addSentencePoseMeta(Vertex question, Vertex answer, String poses, Network network) {
		addSentencePoseMeta(question, answer, Primitive.RESPONSE, poses, network);
	}
	
	/**
	 * Add the actions to the response.
	 */
	public static void addSentencePoseMeta(Vertex question, Vertex answer, Primitive responseType, String poses, Network network) {
		if (poses == null || poses.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.POSE, network);
		} else {
			Relationship relationship = question.getRelationship(responseType, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.internalRemoveRelationships(Primitive.POSE);
				List<String> words = Utils.getWords(poses);
				for (String pose : words) {
					if (!pose.equals("none")) {
						meta.addRelationship(Primitive.POSE, new Primitive(pose));
					}
				}
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
				network.checkReduction(question);
				Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						relationship = synonym.getTarget().getRelationship(responseType, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.internalRemoveRelationships(Primitive.POSE);
							List<String> words = Utils.getWords(poses);
							for (String pose : words) {
								if (!pose.equals("none")) {
									meta.addRelationship(Primitive.POSE, new Primitive(pose));
								}
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the next for a response match to the question meta.
	 */
	public static void addSentenceNextMeta(Vertex question, Vertex answer, Vertex next, Network network) {
		 addSentenceNextMeta(question, answer, Primitive.RESPONSE, next, network);
	 }
	
	/**
	 * Add the next for a response match to the question meta.
	 */
	public static void addSentenceNextMeta(Vertex question, Vertex answer, Primitive type, Vertex next, Network network) {
		Relationship relationship = question.getRelationship(type, answer);
		if (relationship != null) {
			Vertex meta = relationship.getMeta();
			if (next != null) {
				meta = network.createMeta(relationship);
				meta.addRelationship(Primitive.NEXT, next);
			}
		}
		if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
			network.checkReduction(question);
			Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
			if (synonyms != null) {
				for (Relationship synonym : synonyms) {
					relationship = synonym.getTarget().getRelationship(Primitive.RESPONSE, answer);
					if (relationship != null) {
						Vertex meta = relationship.getMeta();
						if (next != null) {
							meta = network.createMeta(relationship);
							meta.addRelationship(Primitive.NEXT, next);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the previous for a response match to the question meta.
	 */
	public static void addSentencePreviousMeta(Vertex question, Vertex answer, Vertex previous, boolean require, Network network) {
		addSentencePreviousMeta(question, answer, Primitive.RESPONSE, previous, require, network);
	}
	
	/**
	 * Add the previous for a response match to the question meta.
	 */
	public static void addSentencePreviousMeta(Vertex question, Vertex answer, Primitive type, Vertex previous, boolean require, Network network) {
		Relationship relationship = question.getRelationship(type, answer);
		if (relationship != null) {
			Vertex meta = relationship.getMeta();
			if (previous != null) {
				meta = network.createMeta(relationship);
				meta.addRelationship(Primitive.PREVIOUS, previous);
			}
			if (meta != null) {
				if (require) {
					meta.addRelationship(Primitive.REQUIRE, Primitive.PREVIOUS);
				} else {
					Relationship required = meta.getRelationship(Primitive.REQUIRE, Primitive.PREVIOUS);
					if (required != null) {
						relationship.getMeta().internalRemoveRelationship(required);
					}
				}
			}
		}
		if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
			network.checkReduction(question);
			Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
			if (synonyms != null) {
				for (Relationship synonym : synonyms) {
					relationship = synonym.getTarget().getRelationship(Primitive.RESPONSE, answer);
					if (relationship != null) {
						Vertex meta = relationship.getMeta();
						if (previous != null) {
							meta = network.createMeta(relationship);
							meta.addRelationship(Primitive.PREVIOUS, previous);
						}
						if (meta != null) {
							if (require) {
								meta.addRelationship(Primitive.REQUIRE, Primitive.PREVIOUS);
							} else {
								Relationship required = meta.getRelationship(Primitive.REQUIRE, Primitive.PREVIOUS);
								if (required != null) {
									relationship.getMeta().internalRemoveRelationship(required);
								}
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the keywords as required keywords for a response match to the question meta.
	 */
	public static void addSentenceRequiredMeta(Vertex question, Vertex answer, String required, Network network) {
		if (required == null || required.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.REQUIRED, network);
		} else {
			Relationship relationship = question.getRelationship(Primitive.RESPONSE, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.internalRemoveRelationships(Primitive.REQUIRED);
				TextStream stream = new TextStream(required);
				while (!stream.atEnd()) {
					String text = stream.nextWord();
					if (text == null) {
						break;
					}
					// Ignore ,
					if (text.equals(",")) {
						required = required.replace(", ", " ");
						required = required.replace(",", " ");
						continue;
					}
					if (text.equals("Pattern") && stream.peek() == '(') {
						// Support required patterns.
						stream.next();
						if (stream.next() != '"') {
							throw new BotException("Missing opening quote, patterns must be of the form 'Pattern(\"\")'");
						}
						text = stream.nextQuotes();
						if (stream.next() != ')') {
							throw new BotException("Missing closing bracket, patterns must be of the form 'Pattern(\"\")'");
						}
						Vertex pattern = network.createPattern(text);
						if (answer.isPinned()) {
							SelfCompiler.getCompiler().pin(pattern);
						}
						meta.addRelationship(Primitive.REQUIRED, pattern);
						Collection<Relationship> words = network.createFragment(text).getRelationships(Primitive.WORD);
						if (words != null) {
							for (Relationship word : words) {
								word.getTarget().addRelationship(Primitive.QUESTION, question);
							}
						}
					} else if (text.equals("\"")) {
						// Support required fragments.
						text = stream.nextQuotes();
						Vertex fragment = network.createFragment(text);
						if (answer.isPinned()) {
							SelfCompiler.getCompiler().pin(fragment);
						}
						meta.addRelationship(Primitive.REQUIRED, fragment);
						Collection<Relationship> words = network.createFragment(text).getRelationships(Primitive.WORD);
						if (words != null) {
							for (Relationship word : words) {
								word.getTarget().addRelationship(Primitive.QUESTION, question);
							}
						}
					} else if (text.equals("[")) {
						// Support required lists.
						text = "[" + stream.upTo(']', true);
						Vertex pattern = network.createPattern("^ " + text + " ^");
						if (answer.isPinned()) {
							SelfCompiler.getCompiler().pin(pattern);
						}
						meta.addRelationship(Primitive.REQUIRED, pattern);
						Collection<Relationship> words = network.createFragment(text).getRelationships(Primitive.WORD);
						if (words != null) {
							for (Relationship word : words) {
								word.getTarget().addRelationship(Primitive.QUESTION, question);
							}
						}
					} else {
						// Support required words.
						Vertex word = network.createWord(text);
						if (answer.isPinned()) {
							SelfCompiler.getCompiler().pin(word);
						}
						meta.addRelationship(Primitive.REQUIRED, word);
						word.addRelationship(Primitive.QUESTION, question);
					}
				}
				// Remember the text for editing.
				meta.setRelationship(Primitive.REQUIRED_TEXT, network.createVertex(required.trim()));
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
				network.checkReduction(question);
				Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						relationship = synonym.getTarget().getRelationship(Primitive.RESPONSE, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.internalRemoveRelationships(Primitive.REQUIRED);
							TextStream stream = new TextStream(required);
							while (!stream.atEnd()) {
								String text = stream.nextWord();
								if (text == null) {
									break;
								}
								// Ignore ,
								if (text.equals(",")) {
									continue;
								}
								if (text.equals("Pattern") && stream.peek() == '(') {
									// Support required patterns.
									stream.next();
									if (stream.next() != '"') {
										throw new BotException("Missing opening quote, patterns must be of the form 'Pattern(\"\")'");
									}
									text = stream.nextQuotes();
									if (stream.next() != ')') {
										throw new BotException("Missing closing bracket, patterns must be of the form 'Pattern(\"\")'");
									}
									Vertex pattern = network.createPattern(text);
									if (answer.isPinned()) {
										SelfCompiler.getCompiler().pin(pattern);
									}
									meta.addRelationship(Primitive.REQUIRED, pattern);
									Collection<Relationship> words = network.createFragment(text).getRelationships(Primitive.WORD);
									if (words != null) {
										for (Relationship word : words) {
											word.getTarget().addRelationship(Primitive.QUESTION, question);
										}
									}
								} else if (text.equals("\"")) {
									// Support required fragments.
									text = stream.nextQuotes();
									Vertex fragment = network.createFragment(text);
									if (answer.isPinned()) {
										SelfCompiler.getCompiler().pin(fragment);
									}
									meta.addRelationship(Primitive.REQUIRED, fragment);
									Collection<Relationship> words = network.createFragment(text).getRelationships(Primitive.WORD);
									if (words != null) {
										for (Relationship word : words) {
											word.getTarget().addRelationship(Primitive.QUESTION, question);
										}
									}
								} else if (text.equals("[")) {
									// Support required lists.
									text = "[" + stream.upTo(']', true);
									Vertex pattern = network.createPattern("^ " + text + " ^");
									if (answer.isPinned()) {
										SelfCompiler.getCompiler().pin(pattern);
									}
									meta.addRelationship(Primitive.REQUIRED, pattern);
									Collection<Relationship> words = network.createFragment(text).getRelationships(Primitive.WORD);
									if (words != null) {
										for (Relationship word : words) {
											word.getTarget().addRelationship(Primitive.QUESTION, question);
										}
									}
								} else {
									// Support required words.
									Vertex word = network.createWord(text);
									if (answer.isPinned()) {
										SelfCompiler.getCompiler().pin(word);
									}
									meta.addRelationship(Primitive.REQUIRED, word);
									word.addRelationship(Primitive.QUESTION, question);
								}
							}
							// Remember the text for editing.
							meta.setRelationship(Primitive.REQUIRED_TEXT, network.createVertex(required.trim()));
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the topic as a desired topic for a response match to the question meta.
	 */
	public static void addSentenceTopicMeta(Vertex question, Vertex answer, String topic, boolean require, Network network) {
		addSentenceTopicMeta(question, answer, Primitive.RESPONSE, topic, require, null, network);
	}
	
	/**
	 * Add the topic as a desired topic for a response match to the question meta.
	 */
	public static void addSentenceTopicMeta(Vertex question, Vertex answer, String topic, boolean require, Boolean exclusive, Network network) {
		addSentenceTopicMeta(question, answer, Primitive.RESPONSE, topic, require, exclusive, network);
	}
	
	/**
	 * Add the topic as a desired topic for a response match to the question meta.
	 */
	public static void addSentenceTopicMeta(Vertex question, Vertex answer, Primitive responseType, String topic, boolean require, Boolean exclusiveTopic, Network network) {
		if (topic == null || topic.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.TOPIC, responseType, network);
		} else {
			Vertex topicFragment = network.createFragment(topic);
			if (answer.isPinned()) {
				SelfCompiler.getCompiler().pin(topicFragment);
			}
			topicFragment.addRelationship(Primitive.INSTANTIATION, Primitive.TOPIC);
			if (exclusiveTopic != null) {
				if (exclusiveTopic) {
					topicFragment.addRelationship(Primitive.ASSOCIATED, Primitive.EXCLUSIVE);
				} else {
					Relationship relationship = topicFragment.getRelationship(Primitive.ASSOCIATED, Primitive.EXCLUSIVE);
					if (relationship != null) {
						topicFragment.internalRemoveRelationship(relationship);
					}
				}
			}
			network.createVertex(Primitive.TOPIC).addRelationship(Primitive.INSTANCE, topicFragment);
			topicFragment.addRelationship(Primitive.TOPIC_QUESTION, question);
			Relationship relationship = question.getRelationship(responseType, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.setRelationship(Primitive.TOPIC, topicFragment);
				if (require) {
					meta.addRelationship(Primitive.REQUIRE, Primitive.TOPIC);
				} else {
					Relationship required = meta.getRelationship(Primitive.REQUIRE, Primitive.TOPIC);
					if (required != null) {
						relationship.getMeta().internalRemoveRelationship(required);
					}
				}
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
				network.checkReduction(question);
				Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						relationship = synonym.getTarget().getRelationship(responseType, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.setRelationship(Primitive.TOPIC, topicFragment);
							if (require) {
								meta.addRelationship(Primitive.REQUIRE, Primitive.TOPIC);
							} else {
								Relationship required = meta.getRelationship(Primitive.REQUIRE, Primitive.TOPIC);
								if (required != null) {
									relationship.getMeta().internalRemoveRelationship(required);
								}
							}
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the new response.
	 */
	public static void addResponse(Vertex question, Vertex answer, Network network) {
		if (network.getBot().mind().getThought(Language.class).getReduceQuestions()
					&& !question.hasRelationship(Primitive.RESPONSE)
					&& !question.hasRelationship(Primitive.INSTANTIATION, Primitive.PATTERN)) {
			question = network.createSentence(Utils.reduce(question.getDataValue()));
		}
		addResponse(question, answer, null, null, null, network.getBot().mind().getThought(Language.class).getLearningRate(), network);
	}
	
	/**
	 * Add the new response.
	 */
	public static void addResponse(Vertex question, Vertex answer, String topic, String keywords, String required, Network network) {
		if (network.getBot().mind().getThought(Language.class).getReduceQuestions()
					&& !question.hasRelationship(Primitive.RESPONSE)
					&& !question.hasRelationship(Primitive.INSTANTIATION, Primitive.PATTERN)) {
			question = network.createSentence(Utils.reduce(question.getDataValue()));
		}
		addResponse(question, answer, topic, keywords, required, network.getBot().mind().getThought(Language.class).getLearningRate(), network);
	}
	
	/**
	 * Add the new response.
	 */
	public static Relationship addResponse(Vertex question, Vertex answer, String topic, String keywords, String required, float correctness, Network network) {
		Relationship relationship = question.addWeakRelationship(Primitive.RESPONSE, answer, correctness);
		question.associateAll(Primitive.WORD, question, Primitive.QUESTION);
		if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
			network.checkReduction(question);
			question.weakAssociateAll(Primitive.REDUCTION, answer, Primitive.RESPONSE, correctness);
		}
		if (topic != null && !topic.isEmpty()) {
			Language.addSentenceTopicMeta(question, answer, topic, false, network);
		}
		if (keywords != null && !keywords.isEmpty()) {
			Language.addSentenceKeyWordsMeta(question, answer, keywords, network);
		}
		if (required != null && !required.isEmpty()) {
			Language.addSentenceRequiredMeta(question, answer, required, network);
		}
		Language.addSentenceFragmentMeta(question, answer, answer.isPinned(), network);
		
		return relationship;
	}
	
	/**
	 * Add the command as the command for a response match to the question meta.
	 */
	public static void addSentenceFragmentMeta(Vertex question, Vertex answer, boolean pin, Network network) {
		Collection<Relationship> words = question.orderedRelationships(Primitive.WORD);
		if (words != null) {
			for (int i = 0; i < words.size() - 1; i++) {
				String fragmentText = ((Relationship)words.toArray()[i]).getTarget().getDataValue() + " " + ((Relationship)words.toArray()[i + 1]).getTarget().getDataValue();
				Vertex fragment = network.createFragment(fragmentText);
				fragment.addRelationship(Primitive.QUESTION, question);
				question.addRelationship(Primitive.FRAGMENT, fragment);
				fragment.setPinned(pin);
			}
		}
	}
	
	/**
	 * Add the command as the command for a response match to the question meta.
	 */
	public static void addSentenceCommandMeta(Vertex question, Vertex answer, String command, boolean pin, Network network) {
		addSentenceCommandMeta(question, answer, Primitive.RESPONSE, command, pin, network);
	}
	
	/**
	 * Add the command as the command for a response match to the question meta.
	 */
	public static void addSentenceCommandMeta(Vertex question, Vertex answer, Primitive responseType, String command, boolean pin, Network network) {
		if (command == null || command.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.COMMAND, responseType,network);
		} else {
			Vertex expression = network.createTemplate("Template(\"{Http.toJSON(" + command + ")}\")");
			if (pin) {
				SelfCompiler.getCompiler().pin(expression);
			}
			Relationship relationship = question.getRelationship(responseType, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.setPinned(pin);
				meta.setRelationship(Primitive.COMMAND, expression);
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
				network.checkReduction(question);
				Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						relationship = synonym.getTarget().getRelationship(responseType, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.setRelationship(Primitive.COMMAND, expression);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the script as the think for a response match to the question meta.
	 */
	public static void addSentenceThinkMeta(Vertex question, Vertex answer, String think, boolean pin, Network network) {
		addSentenceThinkMeta(question, answer, Primitive.RESPONSE, think, pin, network);
	}
	
	/**
	 * Add the script as the think for a response match to the question meta.
	 */
	public static void addSentenceThinkMeta(Vertex question, Vertex answer, Primitive responseType, String think, boolean pin, Network network) {
		if (think == null || think.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.THINK, responseType, network);
		} else {
			Vertex expression = network.createTemplate("Template(\"{think {" + think + "}}\")");
			if (pin) {
				SelfCompiler.getCompiler().pin(expression);
			}
			Relationship relationship = question.getRelationship(responseType, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.setRelationship(Primitive.THINK, expression);
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
				network.checkReduction(question);
				Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						relationship = synonym.getTarget().getRelationship(responseType, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.setRelationship(Primitive.THINK, expression);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the script as the condition for a response match to the question meta.
	 */
	public static void addSentenceConditionMeta(Vertex question, Vertex answer, String condition, boolean pin, Network network) {
		addSentenceConditionMeta(question, answer, Primitive.RESPONSE, condition, pin, network);
	}
	
	/**
	 * Add the script as the condition for a response match to the question meta.
	 */
	public static void addSentenceConditionMeta(Vertex question, Vertex answer, Primitive responseType, String condition, boolean pin, Network network) {
		if (condition == null || condition.trim().isEmpty()) {
			clearSentenceMeta(question, answer, Primitive.CONDITION, responseType, network);
		} else {
			Vertex expression = network.createTemplate("Template(\"{if (true == (" + condition + ")) { \"true\" } else { \"false\" }}\")");
			if (pin) {
				SelfCompiler.getCompiler().pin(expression);
			}
			Relationship relationship = question.getRelationship(responseType, answer);
			if (relationship != null) {
				Vertex meta = network.createMeta(relationship);
				meta.setRelationship(Primitive.CONDITION, expression);
			}
			if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
			network.checkReduction(question);
				Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						relationship = synonym.getTarget().getRelationship(responseType, answer);
						if (relationship != null) {
							Vertex meta = network.createMeta(relationship);
							meta.setRelationship(Primitive.CONDITION, expression);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Add the script as the condition for a response match to the question meta.
	 */
	public static void setConfidence(Vertex question, Vertex answer, Primitive responseType, String confidence, Network network) {
		float confidenceValue = 100.0f;
		if (confidence == null || confidence.equals("")) {
			confidenceValue = 90.0f;
		} else {
			try {
				confidenceValue = Float.parseFloat(confidence.trim());
				if (confidenceValue > 100.0f || confidenceValue < -100.0f) {
					throw new BotException("Invalid confidence number. Confidence must be a number between -100 and 100.");
				}
			} catch (NumberFormatException e) {
				throw new BotException("Invalid confidence number. Confidence entered is not a number.");
			}
		}
		Relationship relationship = question.getRelationship(responseType, answer);
		if (relationship != null) {
			relationship.setCorrectness(confidenceValue / 100);
		}
		if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
			network.checkReduction(question);
			Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
			if (synonyms != null) {
				for (Relationship synonym : synonyms) {
					relationship = synonym.getTarget().getRelationship(responseType, answer);
					if (relationship != null) {
						relationship.setCorrectness(confidenceValue / 100);
					}
				}
			}
		}
	}
	
	/**
	 * Clear any relations in the meta of the type.
	 */
	public static void clearSentenceMeta(Vertex question, Vertex answer, Primitive type, Network network) {
		clearSentenceMeta(question, answer, type, Primitive.RESPONSE, network);
	}
	
	/**
	 * Clear any relations in the meta of the type.
	 */
	public static void clearSentenceMeta(Vertex question, Vertex answer, Primitive type, Primitive responseType, Network network) {
		Relationship relationship = question.getRelationship(responseType, answer);
		if (relationship != null && relationship.hasMeta()) {
			relationship.getMeta().internalRemoveRelationships(type);
		}
		if (network.getBot().mind().getThought(Language.class).getReduceQuestions()) {
			network.checkReduction(question);
			Collection<Relationship> synonyms = question.getRelationships(Primitive.REDUCTION);
			if (synonyms != null) {
				for (Relationship synonym : synonyms) {
					relationship = synonym.getTarget().getRelationship(responseType, answer);
					if (relationship != null && relationship.hasMeta()) {
						relationship.getMeta().internalRemoveRelationships(type);
					}
				}
			}
			synonyms = question.getRelationships(Primitive.SYNONYM);
			if (synonyms != null) {
				for (Relationship synonym : synonyms) {
					relationship = synonym.getTarget().getRelationship(responseType, answer);
					if (relationship != null && relationship.hasMeta()) {
						relationship.getMeta().internalRemoveRelationships(type);
					}
				}
			}
		}
	}

	/**
	 * Add the sentence from the previous input to the relationships response meta info.
	 */
	public static boolean addCorrection(Vertex originalQuestionInput, Vertex originalQuestion, Vertex wrongResponseInput, Vertex correction, Vertex previousQuestionInput, Network network) {
		boolean wasCorrect= false;
		Relationship relationship = null;
		boolean reduce = network.getBot().mind().getThought(Language.class).getReduceQuestions();
		if (reduce && !originalQuestion.hasRelationship(Primitive.RESPONSE)) {
			originalQuestion = network.createSentence(Utils.reduce(originalQuestion.getDataValue()));
		}
		if (wrongResponseInput != null) {
			Vertex wrongResponse = wrongResponseInput.mostConscious(Primitive.INPUT);
			// If correcting with same answer, then don't uncorrect.
			if (wrongResponse != correction) {
				relationship = originalQuestion.getRelationship(Primitive.RESPONSE, wrongResponse);
				// Could be a wrong response, or just a wrong response in the context.
				// First mark wrong in context, then mark wrong in general.
				if ((relationship == null) || (relationship.getCorrectness() < 0.5)) {
					originalQuestion.removeRelationship(Primitive.RESPONSE, wrongResponse);
					if (reduce) {
						network.checkReduction(originalQuestion);
						originalQuestion.inverseAssociateAll(Primitive.REDUCTION, wrongResponse, Primitive.RESPONSE);
					}
				} else {
					relationship.setCorrectness(relationship.getCorrectness() / 2);
					originalQuestionInput.removeRelationship(Primitive.RESPONSE, wrongResponseInput);
					// Dissociate previous question as meta info.
					removeSentencePreviousMeta(relationship, previousQuestionInput, network);
				}
			} else {
				wasCorrect = true;
			}
			Vertex input = wrongResponseInput.copy();
			input.addRelationship(Primitive.ASSOCIATED, Primitive.CORRECTION);
			input.setRelationship(Primitive.INPUT, correction);
			relationship = network.getBot().mind().getThought(Comprehension.class).checkTemplate(input, network);
			if (relationship != null) {
				correction = relationship.getTarget();
			}
		}
		relationship = originalQuestion.addRelationship(Primitive.RESPONSE, correction);
		if (!originalQuestion.instanceOf(Primitive.PATTERN)) {
			originalQuestion.associateAll(Primitive.WORD, originalQuestion, Primitive.QUESTION);
		}
		if (reduce) {
			network.checkReduction(originalQuestion);
			originalQuestion.associateAll(Primitive.REDUCTION, correction, Primitive.RESPONSE);
		}
		correction.addRelationship(Primitive.RESPONSE_QUESTION, originalQuestion);
		originalQuestion.setPinned(true);
		correction.setPinned(true);
		// Associate previous question as meta info.
		addSentencePreviousMeta(relationship, previousQuestionInput, network);
		return wasCorrect;
	}
	
	/**
	 * Remove the sentence from the previous input to the relationships response meta info.
	 */
	public static void removeSentencePreviousMeta(Relationship relationship, Vertex previousQuestionInput, Network network) {
		// Associate previous question as meta info.
		if (previousQuestionInput != null) {
			Vertex previousQuestion = previousQuestionInput.getRelationship(Primitive.INPUT);
			if (previousQuestion != null) {
				Vertex meta = network.createMeta(relationship);
				meta.removeRelationship(Primitive.PREVIOUS, previousQuestion);
			}
		}
	}
	
	/**
	 * Create a new thought.
	 */
	public Language() {
	}

	@Override
	public void awake() {
		String property = this.bot.memory().getProperty("Language.enableEmote");
		if (property != null) {
			setEnableEmote(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.language");
		if (property != null) {
			setLanguage(property);
		}
		property = this.bot.memory().getProperty("Language.learningMode");
		if (property != null) {
			setLearningMode(LearningMode.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.maxResponseMatchProcess");
		if (property != null) {
			setMaxResponseMatchProcess(Integer.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.maxStateProcess");
		if (property != null) {
			setMaxStateProcess(Integer.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.conversationMatchPercentage");
		if (property != null) {
			setConversationMatchPercentage(Float.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.discussionMatchPercentage");
		if (property != null) {
			setDiscussionMatchPercentage(Float.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.learningRate");
		if (property != null) {
			setLearningRate(Float.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.checkExactMatchFirst");
		if (property != null) {
			setCheckExactMatchFirst(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.checkSynonyms");
		if (property != null) {
			setCheckSynonyms(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.enableResponseMatch");
		if (property != null) {
			setEnableResponseMatch(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.learnGrammar");
		if (property != null) {
			setLearnGrammar(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.splitParagraphs");
		if (property != null) {
			setSplitParagraphs(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.synthesizeResponse");
		if (property != null) {
			setSynthesizeResponse(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.fixFormulaCase");
		if (property != null) {
			setFixFormulaCase(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.correctionMode");
		if (property != null) {
			setCorrectionMode(CorrectionMode.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.reduceQuestions");
		if (property != null) {
			setReduceQuestions(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.trackCase");
		if (property != null) {
			setTrackCase(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.aimlCompatibility");
		if (property != null) {
			setAimlCompatibility(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.nlp");
		if (property != null) {
			setNLP(Integer.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.fragmentMatchPercentage");
		if (property != null) {
			setFragmentMatchPercentage(Float.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.penalizeExtraWords");
		if (property != null) {
			setPenalizeExtraWords(Boolean.valueOf(property));
		}
		property = this.bot.memory().getProperty("Language.extraWordPenalty");
		if (property != null) {
			setExtraWordPenalty(Float.valueOf(property));
		}
	}

	/**
	 * Migrate to new properties system.
	 */
	public void migrateProperties() {
		Network memory = getBot().memory().newMemory();
		Vertex language = memory.createVertex(getPrimitive());
		Vertex property = language.getRelationship(Primitive.EMOTE);
		if (property != null) {
			setEnableEmote((Boolean)property.getData());
		}
		property = language.getRelationship(Primitive.LANGUAGE);
		if (property != null) {
			setLanguage((String)property.getData());
		}
		property = language.getRelationship(Primitive.LEARNING);
		if (property != null) {
			setLearningMode(LearningMode.valueOf((String)property.getData()));
		}
		property = language.getRelationship(Primitive.MAXRESPONSEMATCHPROCESS);
		if (property != null) {
			setMaxResponseMatchProcess((Integer)property.getData());
		}
		property = language.getRelationship(Primitive.MAXSTATEPROCESS);
		if (property != null) {
			setMaxStateProcess((Integer)property.getData());
		}
		property = language.getRelationship(Primitive.CONVERSATIONMATCHPERCENTAGE);
		if (property != null) {
			setConversationMatchPercentage((Float)property.getData());
		}
		property = language.getRelationship(Primitive.DISCUSSIONMATCHPERCENTAGE);
		if (property != null) {
			setDiscussionMatchPercentage((Float)property.getData());
		}
		property = language.getRelationship(Primitive.LEARNINGRATE);
		if (property != null) {
			setLearningRate((Float)property.getData());
		}
		property = language.getRelationship(Primitive.CHECKEXACTMATCHFIRST);
		if (property != null) {
			setCheckExactMatchFirst((Boolean)property.getData());
		}
		property = language.getRelationship(Primitive.ENABLERESPONSEMATCH);
		if (property != null) {
			setEnableResponseMatch((Boolean)property.getData());
		}
		property = language.getRelationship(Primitive.LEARNGRAMMAR);
		if (property != null) {
			setLearnGrammar((Boolean)property.getData());
		}
		property = language.getRelationship(Primitive.FIXFORMULACASE);
		if (property != null) {
			setFixFormulaCase((Boolean)property.getData());
		}
		property = language.getRelationship(Primitive.CORRECTION);
		if (property != null) {
			if (property.getData() instanceof Boolean) {
				if ((Boolean)property.getData()) {
					setCorrectionMode(CorrectionMode.Everyone);
				} else {
					setCorrectionMode(CorrectionMode.Administrators);					
				}
			} else {
				setCorrectionMode(CorrectionMode.valueOf((String)property.getData()));
			}
		}
		
		// Remove old properties.
		language.internalRemoveRelationships(Primitive.EMOTE);
		language.internalRemoveRelationships(Primitive.LANGUAGE);
		language.internalRemoveRelationships(Primitive.LEARNING);
		language.internalRemoveRelationships(Primitive.MAXRESPONSEMATCHPROCESS);
		language.internalRemoveRelationships(Primitive.MAXSTATEPROCESS);
		language.internalRemoveRelationships(Primitive.CONVERSATIONMATCHPERCENTAGE);
		language.internalRemoveRelationships(Primitive.DISCUSSIONMATCHPERCENTAGE);
		language.internalRemoveRelationships(Primitive.LEARNINGRATE);
		language.internalRemoveRelationships(Primitive.CHECKEXACTMATCHFIRST);
		language.internalRemoveRelationships(Primitive.ENABLERESPONSEMATCH);
		language.internalRemoveRelationships(Primitive.LEARNGRAMMAR);
		language.internalRemoveRelationships(Primitive.FIXFORMULACASE);
		language.internalRemoveRelationships(Primitive.CORRECTION);
		
		memory.save();
		
		saveProperties();
	}

	public CorrectionMode getCorrectionMode() {
		return correctionMode;
	}

	public void setCorrectionMode(CorrectionMode correctionMode) {
		this.correctionMode = correctionMode;
	}

	public LearningMode getLearningMode() {
		return learningMode;
	}

	public void setLearningMode(LearningMode learningMode) {
		this.learningMode = learningMode;
	}
	
	public int getNLP() {
		return nlp;
	}

	public void setNLP(int nlp) {
		this.nlp = nlp;
	}

	public boolean getEnableResponseMatch() {
		return enableResponseMatch;
	}

	public void setEnableResponseMatch(boolean enableResponseMatch) {
		this.enableResponseMatch = enableResponseMatch;
	}

	public boolean getCheckExactMatchFirst() {
		return checkExactMatchFirst;
	}

	public void setCheckExactMatchFirst(boolean checkExactMatchFirst) {
		this.checkExactMatchFirst = checkExactMatchFirst;
	}

	public float getConversationMatchPercentage() {
		return conversationMatchPercentage;
	}

	public void setConversationMatchPercentage(float conversationMatchPercentage) {
		this.conversationMatchPercentage = conversationMatchPercentage;
	}
	
	public boolean getSynthesizeResponse() {
		return synthesizeResponse;
	}

	public void setSynthesizeResponse(boolean synthesizeResponse) {
		this.synthesizeResponse = synthesizeResponse;
	}

	public float getLearningRate() {
		return learningRate;
	}

	public void setLearningRate(float learningRate) {
		this.learningRate = learningRate;
	}

	public boolean getCheckSynonyms() {
		return checkSynonyms;
	}

	public void setCheckSynonyms(boolean checkSynonyms) {
		this.checkSynonyms = checkSynonyms;
	}

	public float getDiscussionMatchPercentage() {
		return discussionMatchPercentage;
	}

	public void setDiscussionMatchPercentage(float discussionMatchPercentage) {
		this.discussionMatchPercentage = discussionMatchPercentage;
	}
	
	public float getFragmentMatchPercentage() {
		return fragmentMatchPercent;
	}

	public void setFragmentMatchPercentage(float fragmentMatchPercentage) {
		this.fragmentMatchPercent = fragmentMatchPercentage;
	}
	
	public float getExtraWordPenalty() {
		return penalizeExtraneousWordsAmount;
	}

	public void setExtraWordPenalty(float extraWordPenalty) {
		this.penalizeExtraneousWordsAmount = extraWordPenalty;
	}

	public int getMaxStateProcess() {
		return maxStateProcess;
	}

	public void setMaxStateProcess(int maxStateProcess) {
		this.maxStateProcess = maxStateProcess;
	}

	public int getMaxResponseMatchProcess() {
		return maxResponseMatchProcess;
	}

	public void setMaxResponseMatchProcess(int maxResponseMatchProcess) {
		this.maxResponseMatchProcess = maxResponseMatchProcess;
	}

	public boolean getReduceQuestions() {
		return reduceQuestions;
	}

	public void setReduceQuestions(boolean reduceQuestions) {
		this.reduceQuestions = reduceQuestions;
	}
	
	public boolean getPenalizeExtraWords() {
		return penalizeExtraneousWords;
	}

	public void setPenalizeExtraWords(boolean penalizeExtraWords) {
		this.penalizeExtraneousWords = penalizeExtraWords;
	}

	public boolean getTrackCase() {
		return trackCase;
	}

	public void setTrackCase(boolean trackCase) {
		this.trackCase = trackCase;
	}

	public boolean getAimlCompatibility() {
		return aimlCompatibility;
	}

	public void setAimlCompatibility(boolean aimlCompatibility) {
		this.aimlCompatibility = aimlCompatibility;
	}

	public boolean getFixFormulaCase() {
		return fixFormulaCase;
	}

	public void setFixFormulaCase(boolean fixFormulaCase) {
		this.fixFormulaCase = fixFormulaCase;
	}

	public boolean getLearnGrammar() {
		return learnGrammar;
	}

	public void setLearnGrammar(boolean learnGrammar) {
		this.learnGrammar = learnGrammar;
	}

	public void saveProperties() {
		Network memory = getBot().memory().newMemory();
		
		memory.saveProperty("Language.correctionMode", getCorrectionMode().name(), true);
		memory.saveProperty("Language.enableEmote", String.valueOf(getEnableEmote()), true);
		memory.saveProperty("Language.language", getLanguage(), true);
		memory.saveProperty("Language.learningMode", getLearningMode().name(), true);
		memory.saveProperty("Language.maxResponseMatchProcess", String.valueOf(getMaxResponseMatchProcess()), true);
		memory.saveProperty("Language.maxStateProcess", String.valueOf(getMaxStateProcess()), true);
		memory.saveProperty("Language.discussionMatchPercentage", String.valueOf(getDiscussionMatchPercentage()), true);
		memory.saveProperty("Language.learningRate", String.valueOf(getLearningRate()), true);
		memory.saveProperty("Language.conversationMatchPercentage", String.valueOf(getConversationMatchPercentage()), true);
		memory.saveProperty("Language.enableResponseMatch", String.valueOf(getEnableResponseMatch()), true);
		memory.saveProperty("Language.checkExactMatchFirst", String.valueOf(getCheckExactMatchFirst()), true);
		memory.saveProperty("Language.checkSynonyms", String.valueOf(getCheckSynonyms()), true);
		memory.saveProperty("Language.learnGrammar", String.valueOf(getLearnGrammar()), true);
		memory.saveProperty("Language.splitParagraphs", String.valueOf(getSplitParagraphs()), true);
		memory.saveProperty("Language.synthesizeResponse", String.valueOf(getSynthesizeResponse()), true);
		memory.saveProperty("Language.fixFormulaCase", String.valueOf(getFixFormulaCase()), true);
		memory.saveProperty("Language.reduceQuestions", String.valueOf(getReduceQuestions()), true);
		memory.saveProperty("Language.trackCase", String.valueOf(getTrackCase()), true);
		memory.saveProperty("Language.aimlCompatibility", String.valueOf(getAimlCompatibility()), true);
		memory.saveProperty("Language.nlp", String.valueOf(getNLP()), true);
		memory.saveProperty("Language.fragmentMatchPercentage", String.valueOf(getFragmentMatchPercentage()), true);
		memory.saveProperty("Language.penalizeExtraWords", String.valueOf(getPenalizeExtraWords()), true);
		memory.saveProperty("Language.extraWordPenalty", String.valueOf(getExtraWordPenalty()), true);
		
		memory.save();
	}

	public Map<Primitive, Object> clearVoiceProperties() {
		Map<Primitive, Object> properties = new HashMap<Primitive, Object>();
		Network memory = getBot().memory().newMemory();
		Vertex language = memory.createVertex(getPrimitive());
		Vertex property = language.getRelationship(Primitive.VOICE);
		if (property != null) {
			properties.put(Primitive.VOICE, property.getData());
			property.setPinned(false);
		}
		property = language.getRelationship(Primitive.NATIVEVOICE);
		if (property != null) {
			properties.put(Primitive.NATIVEVOICE, property.getData());
			property.setPinned(false);
		}
		property = language.getRelationship(Primitive.NATIVEVOICENAME);
		if (property != null) {
			properties.put(Primitive.NATIVEVOICENAME, property.getData());
			property.setPinned(false);
		}
		property = language.getRelationship(Primitive.PITCH);
		if (property != null) {
			properties.put(Primitive.PITCH, property.getData());
			property.setPinned(false);
		}
		property = language.getRelationship(Primitive.SPEECHRATE);
		if (property != null) {
			properties.put(Primitive.SPEECHRATE, property.getData());
			property.setPinned(false);
		}
		property = language.getRelationship(Primitive.LANGUAGE);
		if (property != null) {
			properties.put(Primitive.LANGUAGE, property.getData());
		}
		
		language.internalRemoveRelationships(Primitive.VOICE);
		language.internalRemoveRelationships(Primitive.NATIVEVOICE);
		language.internalRemoveRelationships(Primitive.NATIVEVOICENAME);
		language.internalRemoveRelationships(Primitive.PITCH);
		language.internalRemoveRelationships(Primitive.SPEECHRATE);
		
		memory.save();
		return properties;
	}
	
	/**
	 * Stop analysing network.
	 */
	public void stop() {
		pool();
	}
	
	/**
	 * Reset state when instance is pooled.
	 */
	@Override
	public void pool() {
		this.allowLearning = null;
	}
		
	public Boolean getAllowLearning() {
		return allowLearning;
	}

	public void setAllowLearning(Boolean allowLearning) {
		this.allowLearning = allowLearning;
	}

	public boolean getSplitParagraphs() {
		return splitParagraphs;
	}

	public void setSplitParagraphs(boolean splitParagraphs) {
		this.splitParagraphs = splitParagraphs;
	}

	/**
	 * Analyse the active memory for language.
	 */
	public void think() {
		if (isStopped() || !isEnabled()) {
			return;
		}
		Network network = getShortTermMemory();
		List<Vertex> activeMemory = getBot().memory().getActiveMemory();
		for (int i = 0; i < activeMemory.size(); i++) {
			Vertex vertex = network.createVertex(activeMemory.get(i));
			log("Processing", Level.FINER, vertex);
			try {
				if (vertex.instanceOf(Primitive.INPUT)) {
					boolean command = false;
					Vertex input = vertex;
					List<Vertex> sentences = new ArrayList<Vertex>();
					List<Vertex> responses = new ArrayList<Vertex>();
					Vertex inputValue = input.getRelationship(Primitive.INPUT);
					if (inputValue != null) {
						for (Vertex sentence : input.orderedRelations(Primitive.INPUT)) {
							if (sentence.instanceOf(Primitive.SENTENCE)) {
								if (getSplitParagraphs()) {
									// Check if the input is a paragraph.
									Vertex paragraph = network.createParagraph(sentence);
									if (paragraph.instanceOf(Primitive.PARAGRAPH)) {
										sentences.addAll(paragraph.orderedRelations(Primitive.SENTENCE));
									} else {
										sentences.add(paragraph);
									}
								} else {
									sentences.add(sentence);
								}
							} else if (sentence.instanceOf(Primitive.PARAGRAPH)) {
								if (getSplitParagraphs()) {
									sentences.addAll(sentence.orderedRelations(Primitive.SENTENCE));
								} else {
									sentences.add(sentence);
								}
							} else {
								// Process command.
								command = true;
								sentences.add(sentence);
							}
						}
					}
					List<Relationship> targets = vertex.orderedRelationships(Primitive.TARGET);
					Vertex target = null;
					if (targets != null && (targets.size() == 1)) {
						target = targets.get(0).getTarget();
					}
					Vertex speaker = input.mostConscious(Primitive.SPEAKER);
					Vertex self = network.createVertex(Primitive.SELF);
					Vertex inputSense = input.mostConscious(Primitive.SENSE);
					Vertex conversation = input.mostConscious(Primitive.CONVERSATION);
					boolean correction = input.hasRelationship(Primitive.ASSOCIATED, Primitive.CORRECTION);
					boolean offended = input.hasRelationship(Primitive.ASSOCIATED, Primitive.OFFENDED);
					boolean newConversation = (inputValue != null) && (inputValue.is(Primitive.NULL));
					// Track sentiment and confidence stats.
					Relationship relationship = input.getRelationship(Primitive.EMOTION, Primitive.SENTIMENT);
					if (relationship != null) {
						this.sentiment = relationship.getCorrectness();
					}
					this.confidence = 100;
					this.lastResponseMetaId = null;
					LanguageState state = LanguageState.Answering;
					Sense sense = null;
					if (inputSense != null) {
						sense = getBot().awareness().getSense(((Primitive)inputSense.getData()).getIdentity());
						if (sense != null) {
							state = sense.getLanguageState();
						}
					}
					// Create output.
					Vertex output = network.createInstance(Primitive.INPUT);
					try {
						Vertex response = null;
						int index = 0;
						Map<Vertex, Vertex> variables = new HashMap<Vertex, Vertex>();
						SelfCompiler.addGlobalVariables(input, null, network, variables);
						getBot().avatar().reset();
						if (newConversation) {
							// If Correcting then this is a response to that previous question.
							response = processGreeting(input, conversation, network, state, variables);
							if (response != null) {
								Vertex meta = null;
								if (this.lastResponseMetaId != null) {
									meta = network.findById(this.lastResponseMetaId);
									this.lastResponseMetaId = null;
								}
								this.bot.mood().evaluateResponse(response, meta);
								this.bot.avatar().evaluateResponse(output, response, meta, variables, network);
								responses.add(response);
								log("Greeting", Level.INFO, response, speaker, conversation);
							}
						} else {
							for (Vertex sentence : sentences) {
								if (command) {
									response = processUnderstanding(input, sentence, this.conversationMatchPercentage, variables, network);
									if (response == null) {
										response = network.createVertex("");
									}
								} else {
									checkQuestion(sentence, network);
									if (PROCESS_HTTP) {
										// TODO make this occur in discovery
										//processHttp(sentence, network);
									}
									log("Processing sentence", Level.FINE, sentence, speaker, targets);
									if (offended) {
										// If offensive then remove the last sentence.
										processOffensive(input, speaker, target, conversation, network);
									}
									if (correction) {
										// If Correcting then this is a response to that previous question.
										response = processCorrection(input, sentence, speaker, target, conversation, network);
									} else if (state == LanguageState.Associate) {
										// Associate the context selection with the sentence.
										response = processAssociation(sentence, network);
									} else if ((state == LanguageState.Listening) || (state == LanguageState.ListeningOnly)) {
										processListening(input, sentence, speaker, conversation, targets, network, state);
										// If target is self, then give an answer.
										if ((state == LanguageState.Listening) && (target == self)) {
											response = processConversational(input, sentence, conversation, variables, network, state);
										} else {
											// Associate response, process understanding, but don't respond.
											processUnderstanding(input, sentence, this.conversationMatchPercentage, variables, network);
											return;
										}
									} else if (state == LanguageState.Discussion) {
										processListening(input, sentence, speaker, conversation, targets, network, state);
										// If target is self, then give an answer.
										if (target == self) {
											response = processConversational(input, sentence, conversation, variables, network, state);							
										} else if ((targets == null) || targets.isEmpty() || (targets.size() > 1)) {
											// Process anything to a group, or to no one as a discussion.
											response = processDiscussion(input, sentence, conversation, variables, network);
										} else {
											processUnderstanding(input, sentence, this.conversationMatchPercentage, variables, network);
											log("Discussion response to other", Level.FINE, targets);
										}
									} else if (state == LanguageState.Conversational) {
										processListening(input, sentence, speaker, conversation, targets, network, state);
										response = processConversational(input, sentence, conversation, variables, network, state);
									} else if (state == LanguageState.Answering) {
										processListening(input, sentence, speaker, conversation, targets, network, state);
										response = processConversational(input, sentence, conversation, variables, network, state);
									}
								}
								// Check for label.
								if ((response != null) && response.instanceOf(Primitive.LABEL)) {
									response = response.mostConscious(Primitive.RESPONSE);
								}
								// Check for formula and transpose.
								boolean wasTemplate = false;
								if ((response != null) && response.instanceOf(Primitive.FORMULA)) {
									log("Response is template formula", Level.FINE, response);
									wasTemplate = true;
									Vertex result = evaluateFormula(response, variables, network);
									if (result == null) {
										log("Template formula cannot be evaluated", Level.FINE, response);
										response = checkDuplicateOrOffensiveResponse(response, sentence, conversation, input, variables, network, true, false, true, true);
										// Must check for formula again.
										if ((response != null) && response.instanceOf(Primitive.FORMULA)) {
											log("Response is template formula", Level.FINE, response);
											result = evaluateFormula(response, variables, network);
											if (result == null) {
												log("Template formula cannot be evaluated", Level.FINE, response);
												response = null;
											} else {
												// Track template was used in conversation.
												conversation.addRelationship(Primitive.SENTENCE, response);
												response = getWord(result, network);
											}
										}
									} else {
										// Track template was used in conversation.
										conversation.addRelationship(Primitive.SENTENCE, response);
										response = getWord(result, network);
									}
								}
								// Check for label again (formula).
								if ((response != null) && response.instanceOf(Primitive.LABEL)) {
									response = response.mostConscious(Primitive.RESPONSE);
								}
								if ((response != null) && !(response.getData() instanceof String)) {
									response = getWord(response, network);
								}
								if (response == null || (!(response.getData() instanceof String) && !response.instanceOf(Primitive.PARAGRAPH))) {
									// Answering must respond.
									if (state == LanguageState.Answering) {
										// Try default response again.
										Vertex language = network.createVertex(getPrimitive());
										List<Relationship> defaultResponses = language.orderedRelationships(Primitive.RESPONSE);
										response = getDefaultResponse(defaultResponses, input, sentence, conversation, variables, network);
										if ((response != null) && response.instanceOf(Primitive.FORMULA)) {
											log("Failure default response is template formula", Level.FINE, response);
											Vertex result = evaluateFormula(response, variables, network);
											if (result == null) {
												log("Template formula cannot be evaluated", Level.FINE, response);
												response = null;
											} else {
												response = getWord(result, network);
											}
										}
										if (response == null) {
											response = sentence;
										}
									} else {
										continue;
									}
								}
								index++;
								if ((sentences.size() == 1) || sentence.instanceOf(Primitive.QUESTION) || (index == sentences.size())) {
									// If offensive, find another response.
									response = checkDuplicateOrOffensiveResponse(response, sentence, conversation, input, variables, network, true, false, wasTemplate, false);
									// Must check for formula again.
									if ((response != null) && response.instanceOf(Primitive.FORMULA)) {
										log("Response is template formula", Level.FINE, response);
										Vertex result = evaluateFormula(response, variables, network);
										if (result == null) {
											log("Template formula cannot be evaluated", Level.FINE, response);
											// Try default response again.
											Vertex language = network.createVertex(getPrimitive());
											List<Relationship> defaultResponses = language.orderedRelationships(Primitive.RESPONSE);
											response = getDefaultResponse(defaultResponses, input, sentence, conversation, variables, network);
											if ((response != null) && response.instanceOf(Primitive.FORMULA)) {
												log("Failure default response is template formula", Level.FINE, response);
												result = evaluateFormula(response, variables, network);
												if (result == null) {
													response = null;
												} else {
													response = getWord(result, network);
												}
											}
											if (response == null) {
												response = sentence;
											}
										} else {
											// Track template was used in conversation.
											conversation.addRelationship(Primitive.SENTENCE, response);
											response = getWord(result, network);
										}
									}
									if (response !=  null) {
										Vertex meta = null;
										if (this.lastResponseMetaId != null) {
											meta = network.findById(this.lastResponseMetaId);
											this.lastResponseMetaId = null;
										}
										this.bot.mood().evaluateResponse(response, meta);
										this.bot.avatar().evaluateResponse(output, response, meta, variables, network);
										responses.add(response);
									}
								}
								log("Response", Level.INFO, response, speaker, conversation);
							}
						}
						if (responses.isEmpty()) {
							if (sense != null) {
								sense.notifyExceptionListeners(new BotException("No response"));
							}
							return;
						}
						if (inputSense != null) {
							// Associate input sense with output.
							output.addRelationship(Primitive.SENSE, inputSense);
						}
						if (speaker != null) {
							output.addRelationship(Primitive.TARGET, speaker);
						}
						Vertex topic = input.mostConscious(Primitive.TOPIC);
						if (topic != null) {
							output.addRelationship(Primitive.TOPIC, topic);
						}
						if (input.hasRelationship(Primitive.ASSOCIATED, Primitive.WHISPER)) {
							output.addRelationship(Primitive.ASSOCIATED, Primitive.WHISPER);
						}
						if (!newConversation) {
							output.addRelationship(Primitive.QUESTION, input);
							input.addRelationship(Primitive.RESPONSE, output);
						}
						output.addRelationship(Primitive.SPEAKER, self);
						//self.addRelationship(Primitive.INPUT, output);
						if (responses.get(0).getData() != null) {
							output.setName(responses.get(0).getData().toString());
						}
						if (responses.size() == 1) {
							if (this.wasMimic) {
								output.addRelationship(Primitive.MIMIC, input);
							}
							response = responses.get(0);
							Relationship relation = output.addRelationship(Primitive.INPUT, response);
							relation.setCorrectness(Math.max(this.confidence, 1));
						} else {
							Vertex paragraph = network.createInstance(Primitive.PARAGRAPH);
							index = 0;
							for (Vertex each : responses) {
								paragraph.addRelationship(Primitive.SENTENCE, each, index);
								index++;
							}
							Relationship relation =  output.addRelationship(Primitive.INPUT, paragraph);
							relation.setCorrectness(Math.max(this.confidence, 1));
						}
						if (conversation != null) {
							Language.addToConversation(output, conversation);
						}
						this.wasMimic = false;
						// Record response time.
						output.setCreationDate(new Date());
						getBot().memory().save();
						// Allow the sense to output the response.
						getBot().awareness().output(output);
					} catch (RuntimeException exception) {
						if (sense != null) {
							sense.notifyExceptionListeners(exception);
						}
						throw exception;
					} catch (Error exception) {
						if (sense != null) {
							sense.notifyExceptionListeners(exception);
						}
						throw exception;
					}
				}
			} finally {
				this.recursiveInputDepth = 0;
				this.recursiveFormulaDepth = 0;
				this.startTime = 0;
				this.abort = false;
			}
		}
	}
	
	/**
	 * Process the input and return the response.
	 */
	public Vertex input(Vertex input, Vertex sentence, Map<Vertex, Vertex> variables, Network network) {
		log("REDIRECT", Level.FINE, sentence, this.recursiveInputDepth);
		if (this.recursiveInputDepth >= MAX_DEPTH) {
			if (this.recursiveInputDepth == MAX_DEPTH) {
				this.recursiveInputDepth++;
				log("Input", Level.WARNING, "Max recursive depth exceeded", this.recursiveInputDepth, sentence);
			}
			return null;
		}
		this.recursiveInputDepth++;
		// Check for formula and transpose
		if (sentence.instanceOf(Primitive.FORMULA)) {
			Vertex result = evaluateFormula(sentence, variables, network);
			if (result == null) {
				log("Template formula cannot be evaluated", Level.FINE, sentence);
				return null;
			} else {
				sentence = getWord(result, network);
			}
		}
		if (sentence.hasRelationship(Primitive.INSTANTIATION, Primitive.SENTENCE) && (sentence.getData() == null)) {
			sentence = createSentenceText(sentence, network);
		} else if (!sentence.hasRelationship(Primitive.INSTANTIATION, Primitive.SENTENCE) && (sentence.getData() instanceof String)) {
			sentence = network.createSentence((String)sentence.getData(), true, false, false);
		}
		Vertex conversation = input.getRelationship(Primitive.CONVERSATION);
		Vertex response = processConversational(input, sentence, conversation, variables, network, LanguageState.Answering);
		if ((response != null) && response.instanceOf(Primitive.FORMULA)) {
			log("Response is template formula", Level.FINE, response);
			SelfCompiler.addGlobalVariables(input, sentence, network, variables);
			Vertex result = evaluateFormula(response, variables, network);
			if (result == null) {
				log("Template formula cannot be evaluated", Level.FINE, response);
				response = null;
			} else {
				response = getWord(result, network);
			}
		}
		this.wasMimic = false;
		return response;
	}
	
	/**
	 * Evaluate the sentence formula/template in the context of the input.
	 */
	public Vertex evaluateFormula(Vertex formula, Map<Vertex, Vertex> variables, Network network) {
		return evaluateFormulaTemplate(formula, variables, System.currentTimeMillis(), network);
	}
	
	/**
	 * Evaluate the sentence formula/template in the context of the input.
	 */
	public Vertex evaluateFormulaTemplate(Vertex formula, Map<Vertex, Vertex> variables, long startTime, Network network) {
		try {
			//sentence.addRelationship(Primitive.FORMULA, formula);
			List<Vertex> relationships = formula.orderedRelations(Primitive.WORD);
			if (relationships == null) {
				// Allow empty/silence template.
				if ((formula.getData() != null && formula.getData().equals("Template(\"\")"))
						|| (formula.getName() != null && formula.getName().equals("Template(\"\")"))) {
					return network.createVertex("");
				}
				return null;
			}
			List<Vertex> words = new ArrayList<Vertex>(relationships.size());
			boolean caseSensitive = false;
			long processTime = Math.min(this.maxStateProcess, MAX_PROCCESS_TIME);
			if (getBot().isDebugFiner()) {
				processTime = processTime * 10;
			}
			for (Vertex word : relationships) {
				if ((System.currentTimeMillis() - startTime) > processTime) {
					throw new SelfExecutionException(formula, "Max formula processing time exceeded");
				}
				Vertex result = null;
				if (word.is(Primitive.WILDCARD)) {
					Vertex value = variables.get(word);
					if (value == null) {
						return null;
					}
					result = value;
				} else if (word.instanceOf(Primitive.VARIABLE)) {
					Vertex value = variables.get(word);
					if (value == null) {
						if (word.hasName()) {
							value = variables.get(word.getName());
						}
						if (value == null) {
							return null;
						}
					}
					result = value;
				} else if (word.instanceOf(Primitive.EXPRESSION) || word.instanceOf(Primitive.EQUATION)) {
					Vertex quotient = SelfInterpreter.getInterpreter().evaluateExpression(word, variables, network, startTime, processTime, 0);
					variables.remove(network.createVertex(Primitive.RETURN));
					if (quotient == null) {
						return null;
					}
					// Check for label.
					if (quotient.instanceOf(Primitive.LABEL)) {
						quotient = quotient.mostConscious(Primitive.RESPONSE);
					}
					while (quotient.instanceOf(Primitive.FORMULA)) {
						this.recursiveFormulaDepth++;
						if (this.recursiveFormulaDepth > MAX_DEPTH) {
							throw new SelfExecutionException(word, "Max recursive template formula execution");
						}
						quotient = evaluateFormulaTemplate(quotient, variables, startTime, network);
						this.recursiveFormulaDepth--;
						if (quotient == null) {
							return null;
						}
						// Check for label.
						if (quotient.instanceOf(Primitive.LABEL)) {
							quotient = quotient.mostConscious(Primitive.RESPONSE);
						}
					}
					if (quotient.is(Primitive.NULL)) {
						return null;
					} else if (quotient.is(Primitive.RETURN)) {
						// think: just execute the equation.
					} else {
						result = quotient;
					}
					if (result != null && result.hasRelationship(Primitive.TYPE, Primitive.CASESENSITVE)) {
						caseSensitive = true;
					}
				} else {
					result = word;
				}
				if (result != null) {
					if (result.instanceOf(Primitive.WORD)) {
						words.add(result);
					} else if (result.instanceOf(Primitive.SENTENCE) || result.instanceOf(Primitive.FRAGMENT)) {
						if (relationships.size() == 1 && (result.instanceOf(Primitive.SENTENCE))) {
							return result;
						}
						Vertex space = null;
						Vertex sentenceWithSpaces = result;
						// Check if sentence has text or not.
						if (result.getData() instanceof String) {
							// It is has text, and doesn't contain any spaces, then it must be reparsed with spaces.
							if (!result.hasRelationship(Primitive.TYPE, Primitive.SPACE) && !result.hasRelationship(Primitive.WORD, Primitive.SPACE)
									&& ((result.getRelationships(Primitive.WORD) == null) || (result.getRelationships(Primitive.WORD).size() > 1))) {
								sentenceWithSpaces = network.createSentence((String)result.getData(), true, false, true);
							}
						} else {
							// It is a dynamic sentence, may need to add spaces.
							if (!result.hasRelationship(Primitive.TYPE, Primitive.SPACE) && !result.hasRelationship(Primitive.WORD, Primitive.SPACE)) {
								space = network.createVertex(Primitive.SPACE);
							}
						}
						List<Vertex> nestedWords = sentenceWithSpaces.orderedRelations(Primitive.WORD);
						if (nestedWords != null) {
							for (int index = 0; index < nestedWords.size(); index++) {
								Vertex nestedWord = nestedWords.get(index);
								words.add(nestedWord);
								if ((space != null) && (index < (nestedWords.size() - 1))) {
									words.add(space);
								}
							}
						}
					} else {
						words.add(result);
					}
				}
			}
			Vertex sentence = network.createTemporyVertex();
			sentence.addRelationship(Primitive.INSTANTIATION, Primitive.SENTENCE);
			if (formula.hasRelationship(Primitive.TYPE, Primitive.SPACE) || formula.hasRelationship(Primitive.WORD, Primitive.SPACE)) {
				sentence.addRelationship(Primitive.TYPE, Primitive.SPACE);
			}
			if (caseSensitive) {
				sentence.addRelationship(Primitive.TYPE, Primitive.CASESENSITVE);
			}
			Vertex previous = network.createVertex(Primitive.NULL);
			for (int index = 0; index < words.size(); index++) {
				if ((System.currentTimeMillis() - startTime) > processTime) {
					throw new SelfExecutionException(formula, "Max formula processing time exceeded");
				}
				Vertex word = words.get(index);
				if (word.is(Primitive.SPACE)) {
					sentence.addRelationship(Primitive.WORD, word, index);
					continue;
				}
				Vertex next = null;
				if (words.size() > (index + 1)) {
					next = words.get(index + 1);
					int nextIndex = index + 2;
					while (next.is(Primitive.SPACE) && (nextIndex < words.size())) {
						next = words.get(nextIndex);
						nextIndex++;
					}
				} else {
					next = network.createVertex(Primitive.NULL);
				}
				word = getWordFollowing(word, previous, next, network);
				sentence.addRelationship(Primitive.WORD, word, index);
				previous = word;
			}
			return createSentenceText(sentence, network);
		} catch (Exception exception) {
			log(exception);
			return null;
		}
	}
	
	/**
	 * Determine if the pattern matches the sentence.
	 */
	public boolean evaluatePattern(Vertex pattern, Vertex sentence, Primitive variable, Map<Vertex, Vertex> variables, Network network) {
		List<Vertex> elements = pattern.orderedRelations(Primitive.WORD);
		List<Vertex> words = sentence.orderedRelations(Primitive.WORD);
		//List<Vertex> sentenceTextWords = words;
		if (words == null && (sentence.getData() instanceof String) && (!((String)sentence.getData()).isEmpty())) {
			// Sentence may not have been parsed.
			sentence = network.createSentence(sentence.getDataValue());
			words = sentence.orderedRelations(Primitive.WORD);
		}
		Vertex sentenceText = sentence;
		if (sentence.getDataValue() == null) {
			// Dead code: now handling compound words within the pattern match.
			// Could be temporary compound word sentence.
			//sentenceText = createSentenceText(sentence, network);
			//sentenceTextWords = sentenceText.orderedRelations(Primitive.WORD);
			// Disabling compound pattern support as causes issues.
			sentence = createSentenceText(sentence, network);
			sentenceText = sentence;
			words = sentence.orderedRelations(Primitive.WORD);
		}
		if (elements == null && (pattern.getData() instanceof String) && (!((String)pattern.getData()).isEmpty()) && (!((String)pattern.getData()).startsWith("Pattern"))) {
			// Sentence may not have been parsed.
			pattern = network.createSentence(pattern.getDataValue());
			elements = pattern.orderedRelations(Primitive.WORD);
		}
		// Empty pattern should match empty input.
		if (elements == null && words == null) {
			return true;
		}
		if (elements == null) {
			return false;
		}
		// If the input is empty pass in an empty string to allow match with ^
		if (words == null) {
			words = new ArrayList<Vertex>(1);
			words.add(network.createVertex(""));
		}
		// Check for single regex pattern and evaluate the entire sentence using regex.
		if (elements.size() == 1) {
			Vertex element = elements.get(0);
			if (element.instanceOf(Primitive.REGEX)) {
				Vertex regex = element.getRelationship(Primitive.REGEX);
				if (regex != null && regex.getData() instanceof String) {
					Pattern regexPattern = Pattern.compile(regex.getDataValue());
					Matcher matcher = regexPattern.matcher(sentenceText.getDataValue());
					if (matcher.matches()) {
						List<List<Vertex>> star = new ArrayList<>();
						if (matcher.groupCount() > 0) {
							for (int index = 1; index <= matcher.groupCount(); index++) {
								List<Vertex> currentStar = new ArrayList<Vertex>();
								String text = matcher.group(index);
								for (String word : Utils.getWords(text)) {
									Vertex vertex = network.createVertex(word);
									if (!isPunctuation(vertex)) {
										currentStar.add(network.createVertex(word));
									}
								}
								star.add(currentStar);
							}
						} else {
							List<Vertex> currentStar = new ArrayList<Vertex>();
							String text = matcher.group();
							for (String word : Utils.getWords(text)) {
								Vertex vertex = network.createVertex(word);
								if (!isPunctuation(vertex)) {
									currentStar.add(network.createVertex(word));
								}
							}
							star.add(currentStar);
						}
						recordStar(star, variable, variables, network);
						return true;
					}
				}
			}
		}
		boolean result = evaluatePattern(pattern, sentence, variable, variables, network, elements, words, 0);
		// Dead code: now handling compound words within the pattern match.
		//if (result == false && sentenceText != sentence && sentenceTextWords != null) {
		//	result = evaluatePattern(pattern, sentenceText, variable, variables, network, elements, sentenceTextWords, 0);
		//}
		return result;
	}
	
	public static boolean isPunctuation(Vertex word) {
		if (word.instanceOf(Primitive.PUNCTUATION)) {
			return true;
		}
		if (!(word.getData() instanceof String)) {
			return false;
		}
		if (word.getDataValue().length() != 1) {
			return false;
		}
		char character = word.getDataValue().charAt(0);
		if (!Character.isLetterOrDigit(character)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Determine if the pattern matches the sentence.
	 */
	public boolean evaluatePattern(Vertex pattern, Vertex sentence, Primitive variable, Map<Vertex, Vertex> variables, Network network,
			List<Vertex> elements, List<Vertex> words, int wildcardSkip) {
		boolean wasWildcard = false;
		int elementIndex = 0;
		int wildcardSkips = 0;
		boolean end = false;
		boolean hadMatchAfterWildcard = false;
		List<List<Vertex>> star = new ArrayList<List<Vertex>>();
		List<Vertex> currentStar = null;
		for (int index = 0; index < words.size(); index++) {
			if (elementIndex >= elements.size()) {
				if (!wasWildcard) {
					// Ignore trailing punctuation.
					while (index < words.size()) {
						Vertex word = words.get(index);
						if (!isPunctuation(word)) {
							if (hadMatchAfterWildcard) {
								return evaluatePattern(pattern, sentence, variable, variables, network, elements, words, wildcardSkip + 1);
							}
							return false;
						}
						index++;
					}
					recordStar(star, variable, variables, network);
					return true;
				}
				end = true;
			}
			Vertex element = null;
			if (!end) {
				element = elements.get(elementIndex);
			}
			Vertex word = words.get(index);
			boolean required = true;
			if (element != null) {
				boolean found = false;
				if (element == word || element.equals(word)) {
					found = true;
				}
				if (!found && (element.is(Primitive.WILDCARD) || element.is(Primitive.UNDERSCORE) || element.is(Primitive.HATWILDCARD) || element.is(Primitive.POUNDWILDCARD))) {
					wasWildcard = true;
					if (currentStar != null) {
						star.add(currentStar);
					}
					currentStar = new ArrayList<Vertex>();
					elementIndex++;
					// * must match at least one word.
					if (element.is(Primitive.WILDCARD) || element.is(Primitive.UNDERSCORE) || elementIndex >= elements.size()) {
						currentStar.add(word);
						continue;
					}
					if (elementIndex < elements.size()) {
						element = elements.get(elementIndex);
					}
					while (((elementIndex + 1) < elements.size()) && element.is(Primitive.HATWILDCARD) || element.is(Primitive.POUNDWILDCARD)) {
						elementIndex++;
						element = elements.get(elementIndex);
					}
				}
				if (!found) {
					if (element.instanceOf(Primitive.ARRAY)) {
						required = (element.hasRelationship(Primitive.TYPE, Primitive.REQUIRED));
						Collection<Relationship> values = element.getRelationships(Primitive.ELEMENT);
						if (values != null) {
							for (Relationship value : values) {
								if (value.getTarget().getData() instanceof String && word.getData() instanceof String) {
									if (((String)value.getTarget().getData()).equalsIgnoreCase((String)word.getData())) {
										found = true;
									}
								} else if (value.getTarget().hasRelationship(Primitive.WORD, word)) {
									found = true;
								}
								if (!found && value.getTarget().instanceOf(Primitive.FRAGMENT)) {
									int checkIndex = index;
									boolean allMatch = true;
									for (Vertex wordFragment : value.getTarget().orderedRelations(Primitive.WORD)) {
										if (wordFragment.getData() instanceof String && word.getData() instanceof String) {
											if (((String)wordFragment.getData()).equalsIgnoreCase((String)word.getData())) { 
												checkIndex++;
												if (checkIndex < words.size()) {
													word = words.get(checkIndex);
													if (word == null) {
														break;
													}
												}
											} else {
												allMatch = false;
												word = words.get(index);
												break;
											}
										}
									}
									if (allMatch) {
										found = true;
										index = checkIndex - 1;
									}
								}
								if (found) {
									break;
								}
							}
						}
					} else if (element.instanceOf(Primitive.LIST)) {
						required = (element.hasRelationship(Primitive.TYPE, Primitive.REQUIRED));
						Collection<Relationship> values = element.getRelationships(Primitive.SEQUENCE);
						if (values != null) {
							for (Relationship value : values) {
								if (value.getTarget().getData() instanceof String && word.getData() instanceof String) {
									if (((String)value.getTarget().getData()).equalsIgnoreCase((String)word.getData())) {
										found = true;
									}
								} else if (value.getTarget().hasRelationship(Primitive.WORD, word)) {
									found = true;
								}
								if (found) {
									break;
								}
							}
						}
					}
				}
				if (!found) {
					if (element.instanceOf(Primitive.EXPRESSION) || element.instanceOf(Primitive.EQUATION)) {
						element = SelfInterpreter.getInterpreter().evaluateExpression(element, variables, network, System.currentTimeMillis(), MAX_RESPONSE_PROCESS, 0);
					}
					if (element.instanceOf(Primitive.VARIABLE)
							&& !(element.is(Primitive.WILDCARD) || element.is(Primitive.UNDERSCORE)
									|| element.is(Primitive.HATWILDCARD) || element.is(Primitive.POUNDWILDCARD))) {
						// First check for compound words.
						Collection<Relationship> compoundWords = word.getRelationships(Primitive.COMPOUND_WORD);
						if (compoundWords != null) {
							for (Relationship compoundWord :  compoundWords) {
								int checkIndex = index;
								boolean allMatch = true;
								for (Vertex wordFragment : compoundWord.getTarget().orderedRelations(Primitive.WORD)) {
									if (wordFragment.getData() instanceof String && word.getData() instanceof String) {
										if (((String)wordFragment.getData()).equalsIgnoreCase((String)word.getData())) { 
											checkIndex++;
											if (checkIndex < words.size()) {
												word = words.get(checkIndex);
												if (word == null) {
													break;
												}
											}
										} else {
											allMatch = false;
											word = words.get(index);
											break;
										}
									}
								}
								if (allMatch) {
									found = true;
									index = checkIndex - 1;
									break; // What about checking others when rest of pattern does not match? order by word length?
								}
							}
						}
						if (!found) {
							found = element.matches(word, variables) == Boolean.TRUE;
						}
						currentStar = new ArrayList<Vertex>();
						currentStar.add(word);
						star.add(currentStar);
						currentStar = null;
						wasWildcard = false;
					}
				}
				boolean regexMatch = false;
				if (!found && (element == word || element.equals(word))) {
					found = true;
				}
				if (!found && element.getData() instanceof String && word.getData() instanceof String) {
					if (((String)element.getData()).equalsIgnoreCase((String)word.getData())) {
						found = true;
					}
				} else if (!found) {
					// Element is an object, check all words for match.
					if (element.hasRelationship(Primitive.WORD, word)) {
						found = true;
					}
					if (word.getData() instanceof String) {
						// If element is a regex, check the expression.
						Vertex regex = element.getRelationship(Primitive.REGEX);
						if (regex != null && regex.getData() instanceof String) {
							if (((String)word.getData()).matches(regex.getDataValue())) {
								found = true;
								regexMatch = true;
							}
						}
					}
				}
				if (!found && getCheckSynonyms()) {
					Collection<Relationship> synonyms = element.getRelationships(Primitive.SYNONYM);
					if (synonyms != null) {
						for (Relationship synonym : synonyms) {
							if (synonym.getTarget() == word || synonym.getTarget().equals(word)) {
								found = true;
								break;
							}
							if (synonym.getTarget().getData() instanceof String && word.getData() instanceof String) {
								if (((String)synonym.getTarget().getData()).equalsIgnoreCase((String)word.getData())) {
									found = true;
								}
							}
						}
					}
				}
				if (found) {
					// Must skip matching words to check for other matches if the same word is in the phrase twice.
					if (wasWildcard && (wildcardSkips < wildcardSkip)) {
						wildcardSkips++;
						currentStar.add(word);
						continue;
					} else {
						hadMatchAfterWildcard = hadMatchAfterWildcard || (wasWildcard && elementIndex < elements.size());
						elementIndex++;
						wasWildcard = false;
						if (currentStar != null) {
							star.add(currentStar);
						}
						if (regexMatch) {
							currentStar = new ArrayList<Vertex>();
							currentStar.add(word);
							star.add(currentStar);
						}
						currentStar = null;
						continue;
					}
				}
			}
			if ("<".equals(word.getDataValue())) {
				boolean foundEndTag = false;
				int tagIndex = index;
				tagIndex++;
				// Ignore HTML tags
				while (tagIndex < words.size()) {
					Vertex next = words.get(tagIndex);
					if (">".equals(next.getDataValue())) {
						foundEndTag = true;
						break;
					}
					tagIndex++;
				}
				if (foundEndTag) {
					index = tagIndex;
					continue;
				}
			}
			if (word.instanceOf(Primitive.PUNCTUATION)) {
				continue;  // Only ignore real punctuation.
			}
			if (wasWildcard) {
				if (!required) {
					elementIndex++;
					index--;
					continue;
				}
				currentStar.add(word);
				continue;
			}
			if (isPunctuation(word)) {
				continue;
			}
			if (!required) {
				elementIndex++;
				index--;
				continue;
			}
			if (hadMatchAfterWildcard) {
				return evaluatePattern(pattern, sentence, variable, variables, network, elements, words, wildcardSkip + 1);
			}
			return false;
		}
		while (elementIndex < elements.size()) {
			Vertex element = elements.get(elementIndex);
			if (element.instanceOf(Primitive.ARRAY) && !element.hasRelationship(Primitive.TYPE, Primitive.REQUIRED)) {
				elementIndex++;
			} else if (element.is(Primitive.HATWILDCARD) || element.is(Primitive.POUNDWILDCARD)) {
				elementIndex++;
				if (currentStar != null) {
					star.add(currentStar);
					currentStar = null;
				} else {
					star.add(new ArrayList<Vertex>());
				}
			} else {
				break;
			}
		}
		if (elementIndex >= elements.size()) {
			if (currentStar != null) {
				star.add(currentStar);
			}
			recordStar(star, variable, variables, network);
			return true;
		}
		if (hadMatchAfterWildcard) {
			return evaluatePattern(pattern, sentence, variable, variables, network, elements, words, wildcardSkip + 1);
		}
		return false;
	}
	
	public static void recordStar(List<List<Vertex>> star, Primitive variable, Map<Vertex, Vertex> variables, Network network) {
		if (star.isEmpty()) {
			return;
		} else if (star.size() == 1) {
			variables.put(network.createVertex(variable), buildStar(star.get(0), variables, network));
			return;
		}
		Vertex starValue = network.createInstance(Primitive.FRAGMENT);
		int index = 0;
		for (List<Vertex> words : star) {
			starValue.addRelationship(Primitive.WORD, buildStar(words, variables, network), index);
			index++;
		}
		variables.put(network.createVertex(variable), starValue);
	}
	
	public static Vertex buildStar(List<Vertex> star, Map<Vertex, Vertex> variables, Network network) {
		if (star.isEmpty()) {
			return network.createWord("");
		} else if (star.size() == 1) {
			return star.get(0);
		}
		String starValue = "";
		boolean first = true;
		for (Vertex word : star) {
			if (first) {
				first = false;
			} else {
				starValue = starValue + " ";
			}
			starValue = starValue + word.printString();
		}
		return network.createFragment(starValue);
	}
	
	/**
	 * Associate the word to the current context selection.
	 */
	public Vertex processAssociation(Vertex text, Network network) {
		Vertex meaning = ((Context)getBot().awareness().getSense(Context.class)).top(network);
		if (meaning == null) {
			return null;
		}
		text.addRelationship(Primitive.MEANING, meaning);
		List<Relationship> words = text.orderedRelationships(Primitive.WORD);
		// Associate as single word or define as compound word.
		if (words.size() == 1) {
			Vertex word = words.get(0).getTarget();
			word.addRelationship(Primitive.MEANING, meaning);
			meaning.addRelationship(Primitive.WORD, word);
		} else if (words.size() > 1) {
			text.addRelationship(Primitive.INSTANTIATION, Primitive.COMPOUND_WORD);
			text.addRelationship(Primitive.INSTANTIATION, Primitive.WORD);
			meaning.addRelationship(Primitive.WORD, text);
			Vertex word = words.get(0).getTarget();
			word.addRelationship(Primitive.COMPOUND_WORD, text);
		}
		Vertex response = meaning.mostConscious(Primitive.WORD);
		return response;
	}
	
	/**
	 * Mark the sentence as a question.
	 */
	public void checkQuestion(Vertex sentence, Network network) {
		if (sentence.instanceOf(Primitive.QUESTION)) {
			log("Sentence is a question", Level.FINE, sentence);
			return;
		}
		Collection<Relationship> words = sentence.getRelationships(Primitive.WORD);
		if (words != null) {
			for (Relationship word : words) {
				boolean isQuestion = word.getTarget().instanceOf(Primitive.QUESTION);
				if (!isQuestion) {
					Vertex meaning = word.getTarget().mostConscious(Primitive.MEANING);
					if (meaning != null) {
						if (meaning.instanceOf(Primitive.QUESTION)) {
							isQuestion = true;
						}
					}
				}
				if (isQuestion) {
					sentence.addRelationship(Primitive.INSTANTIATION, Primitive.QUESTION);
					log("Sentence is a question", Level.FINE, sentence, word);
					break;
				}
			}
		}
	}
	
	/**
	 * Lookup any urls in the text.
	 */
	public void processHttp(Vertex sentence, Network network) {
		Collection<Relationship> words = sentence.getRelationships(Primitive.WORD);
		if (words != null) {
			Sense http = getBot().awareness().getSense(Http.class);
			for (Relationship word : words) {
				Vertex meaning = word.getTarget().mostConscious(Primitive.MEANING);
				if (meaning != null) {
					if (meaning.instanceOf(Primitive.URL)) {
						log("Prcoessing URL", Level.FINE, meaning);
						http.input(meaning.getData());
					}
				}
			}
		}
	}
	
	/**
	 * Process the correction to the last question.
	 */
	public Vertex processCorrection(Vertex input, Vertex correction, Vertex speaker, Vertex target, Vertex conversation, Network network) {
		if (!shouldCorrect(input, speaker)) {
			throw new BotException("You do not have permission to correct");
		}
		Vertex originalQuestion = null;
		Vertex originalQuestionInput = null;
		if (target != null) {
			// Get last input said by speaker.
			originalQuestionInput = getLastInputInConversation(conversation, speaker, 2);
			if (originalQuestionInput != null) {
				originalQuestion = originalQuestionInput.mostConscious(Primitive.INPUT);
			}
		}
		if (originalQuestion == null) {
			log("Correction missing question", Level.FINE, correction);
			return correction;
		}
		// Get last input said by target.
		Vertex wrongResponseInput = originalQuestionInput.mostConscious(Primitive.RESPONSE);
		Vertex previousQuestionInput = originalQuestionInput.getRelationship(Primitive.QUESTION);
		boolean wasCorrect = addCorrection(originalQuestionInput, originalQuestion, wrongResponseInput, correction, previousQuestionInput, network);
		originalQuestionInput.addRelationship(Primitive.RESPONSE, input);
		input.addRelationship(Primitive.QUESTION, originalQuestionInput);
		log("Correction question", Level.FINE, originalQuestion);
		log("Correction response", Level.FINE, correction);
		// Check if last response was from understanding and correct state machine.
		// Do not remove pinned quotients.
		Vertex quotient = getLastQuotient(network);
		if (!wasCorrect && (quotient != null) && !quotient.isPinned()) {
			Vertex state = getLastState(network);
			log("Correcting quotient", Level.FINE, quotient, state);
			Relationship relationship = state.getRelationship(Primitive.QUOTIENT, quotient);
			// Could be a wrong response, or just a wrong response in the context.
			// First mark wrong in context, then mark wrong in general.
			if ((relationship == null) || (relationship.getCorrectness() < 0.5)) {
				originalQuestion.removeRelationship(Primitive.RESPONSE, quotient);
				if (getReduceQuestions()) {
					network.checkReduction(originalQuestion);
					originalQuestion.inverseAssociateAll(Primitive.REDUCTION, quotient, Primitive.RESPONSE);
				}
			} else {
				relationship.setCorrectness(relationship.getCorrectness() / 2);
				// Dissociate previous question as meta info.
				removeSentencePreviousMeta(relationship, previousQuestionInput, network);
			}
		}
		return correction;
	}
	
	/**
	 * Process a offensive response.
	 * Remove the sentence.
	 */
	public void processOffensive(Vertex input, Vertex speaker, Vertex target, Vertex conversation, Network network) {
		Vertex originalQuestion = null;
		Vertex originalQuestionInput = null;
		if (target != null) {
			// Get last input said by speaker.
			originalQuestionInput = getLastInputInConversation(conversation, speaker, 2);
			if (originalQuestionInput != null) {
				originalQuestion = originalQuestionInput.mostConscious(Primitive.INPUT);
			}
		}
		if (originalQuestion == null) {
			log("Offensive missing question", Level.FINE);
			return;
		}
		// Get last input said by target.
		Vertex wrongResponseInput = originalQuestionInput.mostConscious(Primitive.RESPONSE);
		if (wrongResponseInput == null) {
			log("Offensive missing response", Level.FINE);
			return;
		}
		Vertex wrongResponse = wrongResponseInput.mostConscious(Primitive.INPUT);
		if (wrongResponse == null) {
			log("Offensive missing response input", Level.FINE);
			return;
		}
		if (wrongResponse.hasInverseRelationship(Primitive.ASSOCIATED, Primitive.OFFENSIVE)) {
			throw new BotException("The response has been marked as not offensive by the bot's admin, so cannot be flagged as offensive.");
		}
		log("Offensive response flagged", Bot.WARNING, wrongResponse);
		// Remove this as a response from all questions.
		Collection<Relationship> relationships = network.findAllRelationshipsTo(wrongResponse);
		for (Relationship relationship : relationships) {
			if (relationship.getType().is(Primitive.RESPONSE)) {
				relationship.getSource().removeRelationship(relationship.getType(), relationship.getTarget());
			}
		}
		wrongResponseInput.addRelationship(Primitive.ASSOCIATED, Primitive.OFFENSIVE);
		wrongResponse.addRelationship(Primitive.ASSOCIATED, Primitive.OFFENSIVE);
		if (getReduceQuestions()) {
			network.checkReduction(wrongResponse);
			wrongResponse.associateAll(Primitive.REDUCTION, network.createVertex(Primitive.OFFENSIVE), Primitive.ASSOCIATED);
		}
		// Check if last response was from understanding and correct state machine.
		// Do not remove pinned quotients.
		Vertex quotient = getLastQuotient(network);
		if ((quotient != null) && !quotient.isPinned()) {
			Vertex state = getLastState(network);
			log("Correcting quotient", Level.FINE, quotient, state);
			state.removeRelationship(Primitive.QUOTIENT, quotient);
		}
	}
	
	/**
	 * Process the discussion sentence.
	 * Only response if a question and understood,
	 * or has an exact known response.
	 */
	public Vertex processDiscussion(Vertex input, Vertex sentence, Vertex conversation, Map<Vertex, Vertex> variables, Network network) {
		Vertex response = null;
		// Check conversation flow next first.
		response = processNext(input, sentence, conversation, variables, network, this.discussionMatchPercentage);
		boolean hadNext = response != null;
		boolean checkUnderstanding = true;
		if (response == null && !this.checkExactMatchFirst) {
			// Try to understand first.
			response = processUnderstanding(input, sentence, this.discussionMatchPercentage, variables, network);
			checkUnderstanding = false;
			if (response != null) {
				if (sentence.instanceOf(Primitive.QUESTION)) {
					log("Discussion sentence understood", Level.FINE, sentence, response);
				} else {
					response = null;
					log("Discussion sentence understood, but not a question", Level.FINE, sentence, response);
				}
			}
		}
		boolean hadResponse = response != null;
		Relationship relationship = null;
		if (response == null) {
			// Check if the sentence has a known response.
			relationship = bestResponse(this.discussionMatchPercentage, input, sentence, null, null, variables, network);
			if (relationship != null) {
				response = relationship.getTarget();
			}
			if (response == null && this.checkExactMatchFirst) {
				// Try to understand first.
				response = processUnderstanding(input, sentence, this.discussionMatchPercentage, variables, network);
				checkUnderstanding = false;
				if (response != null) {
					if (sentence.instanceOf(Primitive.QUESTION)) {
						log("Discussion sentence understood", Level.FINE, sentence, response);
					} else {
						response = null;
						log("Discussion sentence understood, but not a question", Level.FINE, sentence, response);
					}
				}
			}
			hadResponse = response != null;
			if (response != null) {
				log("Question known response", Level.FINE, response);
			} else {
				Relationship mostConsciousRelationship = sentence.mostConsciousRelationship(Primitive.RESPONSE);
				hadResponse = mostConsciousRelationship != null;
				if (mostConsciousRelationship != null) {
					log("Question known response was not certain", Level.FINE, mostConsciousRelationship, this.discussionMatchPercentage);
					response = null;
				} else {
					log("No known response, checking question patterns", Level.FINE, sentence);
					// Try to find a pattern match.
					relationship = matchPattern(sentence, null, input, variables, network, this.discussionMatchPercentage);
					if (relationship != null) {
						response = relationship.getTarget();
						log("Question pattern match", Level.FINE, response);
					} else {
						log("No known response, checking similar questions", Level.FINE, sentence);
						// Try to find a good match.
						relationship = findResponseMatch(sentence, null, input, variables, network, this.discussionMatchPercentage);
						if (relationship != null) {
							response = relationship.getTarget();
							log("Discussion similar question match", Level.FINE, response);
						} else {
							// Find the best match for logging.
							relationship = findResponseMatch(sentence, null, input, variables, network, 0);
							if (relationship != null) {
								log("Discussion question match response was not valid", Level.FINE, relationship, this.discussionMatchPercentage);
								response = null;
							}
						}
					}
				}
			}
		}
		if (response == null) {
			return null;
		}
		// Pause to avoid rapid responses, if someone else responds, then don't
		Vertex lastInput = conversation.lastRelationship(Primitive.INPUT);
		if (lastInput != null) {
			Vertex lastSentence = lastInput.mostConscious(Primitive.INPUT);
			if (lastSentence != sentence) {
				log("Sentence was already responded to", Level.FINE, sentence, lastSentence);
				return null;
			}
			try {
				getBot().memory().wait(COVERSATIONAL_RESPONSE_DELAY);
			} catch (InterruptedException ignore) {}
			if (getBot().memory().getActiveMemory().size() > 1) {
				log("New active memory", Level.FINE, getBot().memory().getActiveMemory().size());
				// Use a new memory to access new input.
				Network tempMemory = getBot().memory().newMemory();
				Vertex tempConversation = tempMemory.createVertex(conversation);
				Vertex tempSentence = tempMemory.createVertex(sentence);
				lastInput = tempConversation.lastRelationship(Primitive.INPUT);
				lastSentence = lastInput.mostConscious(Primitive.INPUT);
				if (lastSentence != tempSentence) {
					log("Sentence was already responded to", Level.FINE, sentence, lastSentence);
					return null;
				}
			}
		}
		// Avoid responding the same way twice in a row.
		if (response != null && conversation != null) {
			Vertex newResponse = checkDuplicateOrOffensiveResponse(response, sentence, conversation, input, variables, network, !hadResponse, checkUnderstanding, !hadNext, false);
			if (response == newResponse) {
				checkResponseMeta(relationship, conversation, variables, network);
			}
			response = newResponse;
		}
		return response;
	}
	
	/**
	 * Check the response for meta data such as topics.
	 */
	public void checkResponseMeta(Relationship relationship, Vertex conversation, Map<Vertex, Vertex> variables, Network network) {
		if (relationship != null && relationship.hasMeta()) {
			Vertex meta = relationship.getMeta();
			this.lastResponseMetaId = meta.getId();
			Vertex topic = meta.getRelationship(Primitive.TOPIC);
			if (topic != null && !topic.instanceOf(Primitive.PATTERN)) {
				log("Conversation topic", Level.FINE, topic);
				conversation.setRelationship(Primitive.TOPIC, topic);
			}
			if (meta.hasRelationship(Primitive.NEXT)) {
				log("Conversation next", Level.FINE, meta);
				conversation.setRelationship(Primitive.NEXT, meta);
			}
			Vertex think = meta.getRelationship(Primitive.THINK);
			if (think != null && think.instanceOf(Primitive.FORMULA)) {
				log("Conversation think", Level.FINE, think);
				evaluateFormula(think, variables, network);
			}
			Vertex current = conversation.getRelationship(Primitive.NEXT);
			if (current != null) {
				Vertex previous = conversation.getRelationship(Primitive.CURRENT);
				conversation.setRelationship(Primitive.CURRENT, current);
				if (previous != null) {
					conversation.setRelationship(Primitive.PREVIOUS, previous);
				} else {
					conversation.internalRemoveRelationships(Primitive.PREVIOUS);
				}
			} else {
				conversation.internalRemoveRelationships(Primitive.CURRENT);
				conversation.internalRemoveRelationships(Primitive.PREVIOUS);
			}
		}
	}
	
	/**
	 * Check if the response has already been used, and if it has an ONREPEAT response.
	 */
	public Vertex checkOnRepeat(Vertex response, Vertex conversation, int depth) {
		if (!response.hasRelationship(Primitive.ONREPEAT) || (depth == 0 && !conversation.hasRelationship(Primitive.SENTENCE, response))) {
			return null;
		}
		if (depth > MAX_DEPTH) {
			return null;
		}
		Collection<Relationship> repeats = response.getRelationships(Primitive.ONREPEAT);
		// Find unused repeat.
		for (Relationship repeat : repeats) {
			if (!conversation.hasRelationship(Primitive.SENTENCE, repeat.getTarget())) {
				return repeat.getTarget();
			}
		}
		// Find chained repeat.
		for (Relationship repeat : repeats) {
			Vertex newResponse = checkOnRepeat(repeat.getTarget(), conversation, depth++);
			if (newResponse != null) {
				return newResponse;
			}
		}
		// Find repeat that allows repeats.
		for (Relationship repeat : repeats) {
			if (!response.hasRelationship(Primitive.REQUIRE, Primitive.NOREPEAT)) {
				return repeat.getTarget();
			}
		}
		return null;
	}
	
	/**
	 * Check if the previous response was the same and try to find a new one.
	 */
	public Vertex checkDuplicateOrOffensiveResponse(Vertex response, Vertex sentence, Vertex conversation, Vertex input, Map<Vertex, Vertex> variables, Network network,
				boolean allowMatch, boolean checkUnderstanding, boolean checkDuplicate, boolean force) {
		boolean duplicate = false;
		Vertex previousResponse = null;
		Vertex newResponse = checkOnRepeat(response, conversation, 0);
		if (newResponse == null) {
			newResponse = response;
		}
		if (newResponse == response && checkDuplicate && !force) {
			Vertex self = network.createVertex(Primitive.SELF);
			Vertex previousOutput = getLastInputInConversation(conversation, self, 1);
			if (previousOutput != null) {
				previousResponse = previousOutput.mostConscious(Primitive.INPUT);
			}
			duplicate = previousResponse == newResponse;
		}
		boolean offensive = newResponse.hasRelationship(Primitive.ASSOCIATED, Primitive.OFFENSIVE);
		boolean repeat = newResponse.hasRelationship(Primitive.REQUIRE, Primitive.NOREPEAT) && conversation.hasRelationship(Primitive.SENTENCE, newResponse);
		Relationship relationship = null;
		if (force || duplicate || offensive || repeat) {
			if (offensive) {
				log("Response was offensive", Level.FINE, response);
			} else if (repeat) {
				log("Response was repeat", Level.FINE, response);
			} else {
				log("Response was same as previous", Level.FINE, previousResponse);
			}
			newResponse = null;
			if (checkUnderstanding) {
				// Try to understand first.
				newResponse = processUnderstanding(input, sentence, this.conversationMatchPercentage, variables, network);
			}
			if (newResponse == null) {
				// Try to find another response.
				relationship = bestResponse(this.conversationMatchPercentage, input, sentence, null, response, variables, network);
				if (relationship != null) {
					newResponse = relationship.getTarget();
				}
				// If the response was an exact match or script result, then use it anyway if a duplicate instead of a worse matching result.
				// But if a no-repeat, or offensive, then search for another match.
				if ((newResponse == null) && (offensive || repeat || allowMatch)) {
					// Try to find a good match.
					relationship = matchPattern(sentence, response, input, variables, network, this.conversationMatchPercentage);
					if (relationship != null) {
						newResponse = relationship.getTarget();
					} else {
						// Try to find a good match.
						relationship = findResponseMatch(sentence, response, input, variables, network, this.conversationMatchPercentage);
						if (relationship != null) {
							newResponse = relationship.getTarget();
						}
					}
				}
			}
			if (newResponse == null) {
				if (offensive) {
					log("Response was offensive, no other response available", Level.FINE, response);
					return null;
				}
				log("Response is duplicate, but no other response available", Level.FINE, previousResponse);
				// No other response, go with previous one.
				return response;
			} else {
				if (newResponse.hasRelationship(Primitive.ASSOCIATED, Primitive.OFFENSIVE)) {
					log("New response was also offensive", Level.FINE, newResponse);
					return null;
				}
				if (newResponse.hasRelationship(Primitive.REQUIRE, Primitive.NOREPEAT) && conversation.hasRelationship(Primitive.SENTENCE, newResponse)) {
					log("Alternative response was no repeat, no other response available", Level.FINE, newResponse);
					return response;
				}
				Vertex onRepeat = checkOnRepeat(newResponse, conversation, 0);
				if (onRepeat != null) {
					newResponse = onRepeat;
				}
				log("Response was duplicate, found another response", Level.FINE, newResponse);
				checkResponseMeta(relationship, conversation, variables, network);
			}
		}
		return newResponse;
	}
	
	/**
	 * Return the best response to the question, taking into account the input history.
	 */
	public Relationship bestResponse(float percentage, Vertex input, Vertex sentence, Vertex question, Vertex previousResponse, Map<Vertex, Vertex> variables, Network network) {
		return bestResponse(percentage, input, sentence, question, previousResponse, true, true, null, null, null, variables, network);
	}
	
	/**
	 * Return the best response to the question, taking into account the input history.
	 */
	public Relationship bestResponse(float percentage, Vertex input, Vertex sentence, Vertex question, Vertex previousResponse, boolean cascade,
				boolean init, Vertex previousQuestion, Set<String> questionWords, Vertex currentTopic, Map<Vertex, Vertex> variables, Network network) {
		Collection<Relationship> responses = sentence.getRelationships(Primitive.RESPONSE);
		Relationship bestResponse = null;
		if (responses != null) {
			if (init) {
				Vertex previousQuestionInput = input.getRelationship(Primitive.QUESTION);
				if (previousQuestionInput != null) {
					previousQuestion = previousQuestionInput.getRelationship(Primitive.INPUT);
				}
				Vertex conversation = input.getRelationship(Primitive.CONVERSATION);
				if (conversation != null) {
					currentTopic = conversation.mostConscious(Primitive.TOPIC);
				}
				// If the question is null, this means it was an exact match, so do not need to check keywords.
				if (question != null) {
					questionWords = new HashSet<String>();
					Collection<Relationship> relationships = question.getRelationships(Primitive.WORD);
					if (relationships != null) {
						for (Relationship relationship : relationships) {
							questionWords.add(relationship.getTarget().getDataValue().toLowerCase());
						}
					}
				}
			}
			List<Relationship> best = new ArrayList<Relationship>();
			List<Relationship> bestWithCondition = new ArrayList<Relationship>();
			List<Relationship> bestWithTopic = new ArrayList<Relationship>();
			List<Relationship> bestWithPrevious = new ArrayList<Relationship>();
			List<Relationship> bestWithTopicPrevious = new ArrayList<Relationship>();
			for (Relationship response : responses) {
				if ((response.getCorrectness() >= percentage) && (response.getTarget() != previousResponse)) {
					if (response.getTarget().hasRelationship(Primitive.ASSOCIATED, Primitive.OFFENSIVE)) {
						continue;
					}
					Vertex meta = response.getMeta();
					if (meta == null && currentTopic != null && currentTopic.hasRelationship(Primitive.ASSOCIATED, Primitive.EXCLUSIVE)) {
						continue;
					}
					if (meta != null) {
						if ((previousQuestion == null) && meta.hasRelationship(Primitive.REQUIRE, Primitive.PREVIOUS)) {
							continue;
						}
						Vertex topic = meta.getRelationship(Primitive.TOPIC);
						if (topic == null) {
							if (!bestWithTopic.isEmpty()) {
								continue;
							}
							if (currentTopic != null && currentTopic.hasRelationship(Primitive.ASSOCIATED, Primitive.EXCLUSIVE)) {
								continue;
							}
						}
						Collection<Relationship> required = meta.getRelationships(Primitive.REQUIRED);
						if ((required != null) && (!required.isEmpty()) && (question != null)) {
							// Ensure all required words are in the matched question.
							boolean found = false;
							for (Relationship word : required) {
								found = false;
								if (word.getTarget().instanceOf(Primitive.WORD)) {
									if (questionWords.contains(word.getTarget().getDataValue().toLowerCase())) {
										found = true;
									} else {
										if (getCheckSynonyms()) {
											Collection<Relationship> synonyms = word.getTarget().getRelationships(Primitive.SYNONYM);
											if (synonyms != null) {
												for (Relationship synonym : synonyms) {
													if (questionWords.contains(synonym.getTarget().getDataValue().toLowerCase())) {
														found = true;
														break;
													}
													if (synonym.getTarget().instanceOf(Primitive.COMPOUND_WORD)) {
														if (question.hasAll(Primitive.WORD, synonym.getTarget().orderedRelations(Primitive.WORD), true)) {
															found = true;
															break;
														}
													}
												}
											}
										}
									}
								}
								if (!found && word.getTarget().instanceOf(Primitive.FRAGMENT)) {
									if (question.hasAll(Primitive.WORD, word.getTarget().orderedRelations(Primitive.WORD), true)) {
										found = true;
									} else {
										if (getCheckSynonyms()) {
											Collection<Relationship> synonyms = word.getTarget().getRelationships(Primitive.SYNONYM);
											if (synonyms != null) {
												for (Relationship synonym : synonyms) {
													if (questionWords.contains(synonym.getTarget().getDataValue().toLowerCase())) {
														found = true;
														break;
													}
													if (synonym.getTarget().instanceOf(Primitive.COMPOUND_WORD)) {
														if (question.hasAll(Primitive.WORD, synonym.getTarget().orderedRelations(Primitive.WORD), true)) {
															found = true;
															break;
														}
													}
												}
											}
										}
									}
								} else if (!found && word.getTarget().instanceOf(Primitive.PATTERN)) {
									if (evaluatePattern(word.getTarget(), question, Primitive.WILDCARD, new HashMap<Vertex, Vertex>(), network)) {
										found = true;
									}
								}
								if (!found) {
									log("Missing required word", Level.FINER, word.getTarget());
									break;
								}
							}
							if (!found) {
								continue;
							}
						} else {
							Collection<Relationship> keywords = meta.getRelationships(Primitive.KEYWORD);
							if ((keywords != null) && (!keywords.isEmpty()) && (question != null)) {
								// Ensure any keywords are in the matched question.
								boolean found = false;
								for (Relationship keyword : keywords) {
									if (questionWords.contains(keyword.getTarget().getDataValue().toLowerCase())) {
										found = true;
										break;
									}
									if (keyword.getTarget().instanceOf(Primitive.COMPOUND_WORD)) {
										if (question.hasAll(Primitive.WORD, keyword.getTarget().orderedRelations(Primitive.WORD), true)) {
											found = true;
											break;
										}
									}
									if (getCheckSynonyms()) {
										boolean foundSynonym = false;
										Collection<Relationship> synonyms = keyword.getTarget().getRelationships(Primitive.SYNONYM);
										if (synonyms != null) {
											for (Relationship synonym : synonyms) {
												if (questionWords.contains(synonym.getTarget().getDataValue().toLowerCase())) {
													foundSynonym = true;
													break;
												}
												if (synonym.getTarget().instanceOf(Primitive.COMPOUND_WORD)) {
													if (question.hasAll(Primitive.WORD, synonym.getTarget().orderedRelations(Primitive.WORD), true)) {
														found = true;
														break;
													}
												}
											}
										}
										if (foundSynonym) {
											found = true;
											break;
										}
									}
								}
								if (!found) {
									log("Missing keyword", Level.FINER, keywords);
									continue;
								}
							}
						}
						if (topic != null) {
							boolean requireTopic = meta.hasRelationship(Primitive.REQUIRE, Primitive.TOPIC);
							if (currentTopic == null && requireTopic) {
								continue;
							}
							if (currentTopic != null) {
								boolean match = topic == currentTopic;
								if (!match && topic.instanceOf(Primitive.PATTERN)) {
									match = evaluatePattern(topic, currentTopic, Primitive.TOPICWILDCARD, new HashMap<Vertex, Vertex>(), network);
								}
								if (!match && requireTopic) {
									continue;
								}
								if (match && (bestWithTopic.isEmpty() || (response.getCorrectness() >= bestWithTopic.get(0).getCorrectness()))) {
									if (previousQuestion != null) {
										Vertex label = previousQuestion.getRelationship(Primitive.LABEL);
										if (meta.hasRelationship(Primitive.PREVIOUS, previousQuestion)
													|| (label != null && meta.hasRelationship(Primitive.PREVIOUS, label))) {
											if (checkCondition(response, variables, network) != Boolean.FALSE) {
												if (!bestWithTopicPrevious.isEmpty() && (response.getCorrectness() > bestWithTopicPrevious.get(0).getCorrectness())) {
													bestWithTopicPrevious.clear();
												}
												bestWithTopicPrevious.add(response);
											}
										} else {
											if (meta.hasInverseRelationship(Primitive.PREVIOUS, previousQuestion)
													|| (label != null && meta.hasInverseRelationship(Primitive.PREVIOUS, label))) {
												continue;
											}
											Collection<Relationship> previous = meta.getRelationships(Primitive.PREVIOUS);
											if (previous != null) {
												for (Relationship relationship : previous) {
													match = evaluatePattern(relationship.getTarget(), previousQuestion, Primitive.THATWILDCARD, new HashMap<Vertex, Vertex>(), network);
													if (match) {
														if (checkCondition(response, variables, network) != Boolean.FALSE) {
															if (!bestWithTopicPrevious.isEmpty() && (response.getCorrectness() > bestWithTopicPrevious.get(0).getCorrectness())) {
																bestWithTopicPrevious.clear();
															}
															bestWithTopicPrevious.add(response);
														}
													}
												}
											}
										}
									}
									if (meta.hasRelationship(Primitive.REQUIRE, Primitive.PREVIOUS)) {
										continue;
									}
									if (checkCondition(response, variables, network) != Boolean.FALSE) {
										if (!bestWithTopic.isEmpty() && (response.getCorrectness() > bestWithTopic.get(0).getCorrectness())) {
											bestWithTopic.clear();
										}
										bestWithTopic.add(response);
									}
								}
							}
						}
						if ((bestWithTopic.isEmpty()) && (previousQuestion != null) && ((bestWithPrevious.isEmpty()) || (response.getCorrectness() >= bestWithPrevious.get(0).getCorrectness()))) {
							boolean match = false;
							Vertex label = previousQuestion.getRelationship(Primitive.LABEL);
							if (meta.hasRelationship(Primitive.PREVIOUS, previousQuestion)
										|| (label != null && meta.hasRelationship(Primitive.PREVIOUS, label))) {
								if (checkCondition(response, variables, network) != Boolean.FALSE) {
									if (!bestWithPrevious.isEmpty() && (response.getCorrectness() > bestWithPrevious.get(0).getCorrectness())) {
										bestWithPrevious.clear();
									}
									bestWithPrevious.add(response);
								}
							} else {
								if (meta.hasInverseRelationship(Primitive.PREVIOUS, previousQuestion)
											|| (label != null && meta.hasInverseRelationship(Primitive.PREVIOUS, label))) {
									continue;
								}
								Collection<Relationship> previous = meta.getRelationships(Primitive.PREVIOUS);
								if (previous != null) {
									for (Relationship relationship : previous) {
										match = evaluatePattern(relationship.getTarget(), previousQuestion, Primitive.THATWILDCARD, new HashMap<Vertex, Vertex>(), network);
										if (match) {
											if (checkCondition(response, variables, network) != Boolean.FALSE) {
												if (!bestWithPrevious.isEmpty() && (response.getCorrectness() > bestWithPrevious.get(0).getCorrectness())) {
													bestWithPrevious.clear();
												}
												bestWithPrevious.add(response);
												break;
											}
										}
									}
								}
							}
						}
						if (meta.hasRelationship(Primitive.REQUIRE, Primitive.PREVIOUS)) {
							continue;
						}
					}
					if ((best.isEmpty()) || (response.getCorrectness() >= best.get(0).getCorrectness())) {
						Boolean condition = checkCondition(response, variables, network);
						if (condition != Boolean.FALSE) {
							if (condition == Boolean.TRUE) {
								if (!bestWithCondition.isEmpty() && (response.getCorrectness() > bestWithCondition.get(0).getCorrectness())) {
									bestWithCondition.clear();
								}
								bestWithCondition.add(response);
							}
							if (!best.isEmpty() && (response.getCorrectness() > best.get(0).getCorrectness())) {
								best.clear();
							}
							best.add(response);
						}
					}
				}
			}
			if (!bestWithTopicPrevious.isEmpty()) {
				bestResponse = Utils.random(bestWithTopicPrevious);
			} else if (!bestWithTopic.isEmpty()) {
				bestResponse = Utils.random(bestWithTopic);
			} else if (!bestWithPrevious.isEmpty()) {
				bestResponse = Utils.random(bestWithPrevious);
			} else if (!bestWithCondition.isEmpty()) {
				bestResponse = Utils.random(bestWithCondition);
			} else if (!best.isEmpty()) {
				bestResponse = Utils.random(best);
			}
			if (bestResponse != null) {
				if (bestResponse.hasMeta()) {
					this.lastResponseMetaId = bestResponse.getMeta().getId();
				}
				return bestResponse;
			}
		}
		if (cascade) {
			if (getReduceQuestions()) {
				network.checkReduction(sentence);
				Collection<Relationship> reductions = sentence.getRelationships(Primitive.REDUCTION);
				if (reductions != null) {
					for (Relationship reduction : reductions) {
						bestResponse = bestResponse(percentage, input, reduction.getTarget(), question, previousResponse, false,
								init, previousQuestion, questionWords, currentTopic, variables, network);
						if (bestResponse != null) {
							return bestResponse;
						}
					}
				}
			}
		}
		return bestResponse;
	}
	
	/**
	 * Process the start of a new conversation and output the greeting.
	 */
	public Vertex processGreeting(Vertex input, Vertex conversation, Network network, LanguageState state, Map<Vertex, Vertex> variables) {
		Vertex language = network.createVertex(getPrimitive());
		Collection<Relationship> greetings = language.getRelationships(Primitive.GREETING);
		if (greetings == null) {
			log("No greeting", Level.FINE);
			return null;
		}
		Relationship relationship = null;
		Vertex greeting = null;
		// Check conditions.
		List<Relationship> invalid = new ArrayList<>();
		for (Relationship value : greetings) {
			Boolean condition = checkCondition(value, variables, network);
			if (condition == Boolean.TRUE) {
				greeting = value.getTarget();
				relationship = value;
				log("Greeting condition", Level.FINE, greeting);
			} else if (condition == Boolean.FALSE) {
				invalid.add(value);
			}
		}
		greetings.removeAll(invalid);
		if (greetings.isEmpty()) {
			log("No valid greeting", Level.FINE);
			return null;
		}
		if (greeting == null) {
			relationship = Utils.random(greetings);
			greeting = relationship.getTarget();
			log("Greeting", Level.FINE, greeting);
		}
		// Check for label and formula, and evaluate
		if ((greeting != null) && greeting.instanceOf(Primitive.LABEL)) {
			greeting = greeting.mostConscious(Primitive.RESPONSE);
		}
		if ((greeting != null) && greeting.instanceOf(Primitive.FORMULA)) {
			log("Greeting is template formula", Level.FINE, greeting);
			Vertex result = evaluateFormula(greeting, variables, network);
			if (result == null) {
				log("Template formula cannot be evaluated", Level.FINE, greeting);
				greeting = null;
				// Find non formula.
				for (Relationship value : greetings) {
					if (!value.getTarget().instanceOf(Primitive.FORMULA)) {
						relationship = value;
						greeting = value.getTarget();
						break;
					}
				}
			} else {
				greeting = getWord(result, network);
			}
		}
		checkResponseMeta(relationship, conversation, variables, network);
		return greeting;
	}
	
	/**
	 * Process the conversational sentence.
	 * Try to understand, otherwise,
	 * determine the best know response, if there is no known
	 * response, then resort to mimicry.
	 */
	public Vertex processConversational(Vertex input, Vertex sentence, Vertex conversation, Map<Vertex, Vertex> variables, Network network, LanguageState state) {
		Vertex response = null;
		// Check conversation flow next first.
		response = processNext(input, sentence, conversation, variables, network, this.conversationMatchPercentage);
		boolean hadNext = response != null;
		boolean checkUnderstanding = true;
		if (response == null && !this.checkExactMatchFirst) {
			// Try to understand first.
			response = processUnderstanding(input, sentence, this.conversationMatchPercentage, variables, network);
			checkUnderstanding = false;
		}
		Relationship relationship = null;
		if (response == null) {
			// Check if the sentence has a known response.
			relationship = bestResponse(0.1f, input, sentence, null, null, variables, network);
			if (relationship != null) {
				response = relationship.getTarget();
			}
		}
		if ((response == null) && this.checkExactMatchFirst) {
			// Try to understand first.
			response = processUnderstanding(input, sentence, this.conversationMatchPercentage, variables, network);
			checkUnderstanding = false;
		}
		boolean hadResponse = response != null;
		if (response != null) {
			log("Question known response", Level.FINE, response);
		} else {
			log("No known response, checking question patterns", Level.FINE, sentence);
			// Try to find a pattern that matches.
			relationship = matchPattern(sentence, null, input, variables, network, this.conversationMatchPercentage);
			if (relationship != null) {
				response = relationship.getTarget();
				log("Question pattern match", Level.FINE, response);
			} else {
				log("No known response, checking similar questions", Level.FINE, sentence);
				// Try to find a good match.
				relationship = findResponseMatch(sentence, null, input, variables, network, this.conversationMatchPercentage);
				if (relationship != null) {
					response = relationship.getTarget();
					log("Conversation similar question match response", Level.FINE, response);
				} else  if ((state == LanguageState.Answering) || (state == LanguageState.Discussion)) {
					Vertex language = network.createVertex(getPrimitive());
					List<Relationship> defaultResponses = language.orderedRelationships(Primitive.RESPONSE);
					if (defaultResponses == null) {
						if (this.synthesizeResponse) {
							response = synthesizeResponse(input, sentence, conversation, false, variables, network);
						}
						if (response == null) {
							this.wasMimic = true;
							// Mimic.
							log("Conversation mimic", Level.FINE, sentence);
							response = sentence;
						}
					} else {
						this.wasMimic = true;
						response = getDefaultResponse(defaultResponses, input, sentence, conversation, variables, network);
						this.defaultResponses++;
						this.confidence = 0;
					}
				}
			}
		}
		// Avoid responding the same way twice in a row.
		if ((response != null) && (conversation != null)) {
			Vertex newResponse = checkDuplicateOrOffensiveResponse(response, sentence, conversation, input, variables, network, !hadResponse, checkUnderstanding, !hadNext, false);
			if (response == newResponse) {
				checkResponseMeta(relationship, conversation, variables, network);
			}
			response = newResponse;
		}
		return response;
	}
	
	/**
	 * Return a synthesized response from linguistic patterns.
	 */
	public Vertex synthesizeResponse(Vertex input, Vertex sentence, Vertex conversation, boolean random, Map<Vertex, Vertex> variables, Network network) {
		// Find sentence topic.
		Vertex topic = null; //conversation.getRelationship(Primitive.TOPIC);
		if (topic == null && sentence != null) {
			if (sentence.instanceOf(Primitive.WORD)) {
				topic = sentence;
			} else {
				Collection<Relationship> words = sentence.getRelationships(Primitive.WORD);
				if (words != null && !words.isEmpty()) {
					for (Relationship relationship : words) {
						Vertex word = relationship.getTarget();
						Vertex meaning = word.mostConscious(Primitive.MEANING);
						if (meaning != null && meaning.instanceOf(Primitive.THING)) {
							if (topic == null || (meaning.getConsciousnessLevel() > topic.getConsciousnessLevel())) {
								topic = word;
							}
						}
					}
					if (topic == null) {
						for (Relationship relationship : words) {
							Vertex word = relationship.getTarget();
							Vertex meaning = word.mostConscious(Primitive.MEANING);
							if (meaning != null && meaning.instanceOf(Primitive.DESCRIPTION)) {
								if (topic == null || (meaning.getConsciousnessLevel() > topic.getConsciousnessLevel())) {
									topic = word;
								}
							}
						}
					}
					if (topic == null) {
						for (Relationship relationship : words) {
							Vertex word = relationship.getTarget();
							Vertex meaning = word.mostConscious(Primitive.MEANING);
							if (meaning != null && meaning.instanceOf(Primitive.ACTION)) {
								if (topic == null || (meaning.getConsciousnessLevel() > topic.getConsciousnessLevel())) {
									topic = word;
								}
							}
						}
					}
					if (topic == null) {
						for (Relationship relationship : words) {
							Vertex word = relationship.getTarget();
							if (topic == null || (word.getConsciousnessLevel() > topic.getConsciousnessLevel())) {
								topic = word;
							}
						}
					}
				}
			}
		}
		List<Vertex> words = new ArrayList<Vertex>();
		Set<Vertex> usedWords = new HashSet<Vertex>();
		if (topic != null && !topic.instanceOf(Primitive.WORD)) {
			topic = topic.mostConscious(Primitive.WORD);
		}
		boolean loop = false;
		if (topic != null) {
			// Generate sentence start from topic.
			int count = 0;
			words.add(topic);
			usedWords.add(topic);
			Vertex current = topic;
			while (count < 5) {
				Vertex previous = null;
				if (random) {
					Collection<Relationship> relationships = current.getRelationships(Primitive.PREVIOUS);
					if (relationships != null) {
						previous = Utils.random(relationships).getTarget();
						if (loop && usedWords.contains(previous)) {
							previous = Utils.random(relationships).getTarget();
						}
					}
				} else {
					if (loop) {
						previous = current.nextMostConscious(Primitive.PREVIOUS, usedWords);
					} else {
						previous = current.mostConscious(Primitive.PREVIOUS);
					}
				}
				if (previous == null || previous.is(Primitive.NULL)) {
					break;
				}
				if (usedWords.contains(previous)) {
					loop = true;
				} else {
					usedWords.add(previous);
				}
				words.add(0, previous);
				current = previous;
				count++;
			}
			if (count == 5) {
				count = 0;
				while (count < 5) {
					if (current.hasRelationship(Primitive.PREVIOUS, Primitive.NULL)) {
						break;
					}
					Vertex previous = null;
					if (random) {
						Collection<Relationship> relationships = current.getRelationships(Primitive.PREVIOUS);
						if (relationships != null) {
							previous = Utils.random(relationships).getTarget();
							if (loop && usedWords.contains(previous)) {
								previous = Utils.random(relationships).getTarget();
							}
						}
					} else {
						previous = current.nextMostConscious(Primitive.PREVIOUS, usedWords);
					}
					if (previous == null || previous.is(Primitive.NULL)) {
						break;
					}
					words.add(0, previous);
					usedWords.add(previous);
					current = previous;
					count++;
				}
			}
			
		}
		if (topic == null) {
			topic = network.createVertex(Primitive.NULL);
		}
		// Generate sentence end from topic.
		int count = 0;
		Vertex current = topic;
		while (count < 5) {
			Vertex next = null;
			if (random) {
				Collection<Relationship> relationships = current.getRelationships(Primitive.NEXT);
				if (relationships != null) {
					next = Utils.random(relationships).getTarget();
					if (loop && usedWords.contains(next)) {
						next = Utils.random(relationships).getTarget();
					}
				}
			} else {
				if (loop) {
					next = current.nextMostConscious(Primitive.NEXT, usedWords);
				} else {
					next = current.mostConscious(Primitive.NEXT);
				}
			}
			if (next == null || next.is(Primitive.NULL)) {
				break;
			}
			if (usedWords.contains(next)) {
				loop = true;
			} else {
				usedWords.add(next);
			}
			words.add(next);
			current = next;
			count++;
		}
		if (count == 5) {
			count = 0;
			while (count < 5) {
				if (current.hasRelationship(Primitive.PREVIOUS, Primitive.NULL)) {
					break;
				}
				Vertex next = null;
				if (random) {
					Collection<Relationship> relationships = current.getRelationships(Primitive.NEXT);
					if (relationships != null) {
						next = Utils.random(relationships).getTarget();
						if (loop && usedWords.contains(next)) {
							next = Utils.random(relationships).getTarget();
						}
					}
				} else {
					next = current.nextMostConscious(Primitive.NEXT, usedWords);
				}
				if (next == null || next.is(Primitive.NULL)) {
					break;
				}
				words.add(next);
				usedWords.add(next);
				current = next;
				count++;
			}
		}
		if (words.isEmpty()) {
			return null;
		}
		Vertex response = network.createInstance(Primitive.SENTENCE);
		int index = 0;
		for (Vertex word: words) {
			response.addRelationship(Primitive.WORD, word, index);
			index++;
		}
		return response;
	}
	
	/**
	 * Check if the response has a condition, and if it evaluates to true.
	 */
	public Boolean checkCondition(Relationship relationship, Map<Vertex, Vertex> variables, Network network) {
		if (!relationship.hasMeta()) {
			return null;
		}
		Vertex condition = relationship.getMeta().getRelationship(Primitive.CONDITION);
		if (condition == null || !condition.instanceOf(Primitive.FORMULA)) {
			return null;
		}
		Vertex result = evaluateFormula(condition, variables, network);
		if (result != null && result.printString().toLowerCase().equals("true")) {
			return Boolean.TRUE;
		}
		return Boolean.FALSE;
	}
	
	/**
	 * Return a random default response for the topic or context.
	 */
	public Vertex getDefaultResponse(List<Relationship> defaultResponses, Vertex input, Vertex sentence, Vertex conversation, Map<Vertex, Vertex> variables, Network network) {
		Vertex response = null;
		Relationship relationship = null;
		variables.put(network.createVertex(Primitive.WILDCARD), sentence);
		Vertex previousQuestionInput = input.getRelationship(Primitive.QUESTION);
		Vertex previousQuestion = null;
		if (previousQuestionInput != null) {
			previousQuestion = previousQuestionInput.getRelationship(Primitive.INPUT);
		}
		List<Relationship> previousCandidates = new ArrayList<Relationship>();
		List<Relationship> topicCandidates = new ArrayList<Relationship>();
		// Check topic
		if (conversation != null) {
			Vertex topic = conversation.getRelationship(Primitive.TOPIC);
			if (topic != null) {
				Vertex topicMatch = null;
				Vertex previousMatch = null;
				Relationship topicMatchRelationship = null;
				Relationship previousMatchRelationship = null;
				for (Relationship defaultResponse : defaultResponses) {
					Vertex meta = defaultResponse.getMeta();
					if (meta != null) {
						Vertex defaultTopic = meta.getRelationship(Primitive.TOPIC);
						if (defaultTopic != null) {
							boolean match = topic == defaultTopic;
							if (!match && defaultTopic.instanceOf(Primitive.PATTERN)) {
								match = evaluatePattern(defaultTopic, topic, Primitive.TOPIC, new HashMap<Vertex, Vertex>(), network);
							}
							if (match) {
								if (meta.hasRelationship(Primitive.PREVIOUS)) {
									boolean previousMatches = meta.hasRelationship(Primitive.PREVIOUS, previousQuestion);
									if (!previousMatches) {
										Vertex label = previousQuestion.getRelationship(Primitive.LABEL);
										if (label != null && meta.hasRelationship(Primitive.PREVIOUS, label)) {
											previousMatches = true;
										}
									}
									if (!previousMatches) {
										// Check for patterns.
										Collection<Relationship> previousResponses = meta.getRelationships(Primitive.PREVIOUS);
										if (previousResponses != null) {
											for (Relationship previousResponse : previousResponses) {
												if (!previousResponse.isInverse() && previousResponse.getTarget().instanceOf(Primitive.PATTERN)) {
													previousMatches = evaluatePattern(previousResponse.getTarget(), previousQuestion, Primitive.PREVIOUS, new HashMap<Vertex, Vertex>(), network);
													if (previousMatches) {
														if (checkCondition(defaultResponse, variables, network) != Boolean.FALSE) {
															previousMatch = defaultResponse.getTarget();
															previousMatchRelationship = defaultResponse;
															log("Conversation topic and previous default response", Level.FINE, defaultTopic, previousMatch);
															previousCandidates.add(previousMatchRelationship);
															break;
														}
													}
												} 
											}
										}
									} else {
										if (checkCondition(defaultResponse, variables, network) != Boolean.FALSE) {
											previousMatch = defaultResponse.getTarget();
											previousMatchRelationship = defaultResponse;
											log("Conversation topic and previous default response", Level.FINE, defaultTopic, previousMatch);
											previousCandidates.add(previousMatchRelationship);
										}
									}
								} else {
									if (checkCondition(defaultResponse, variables, network) != Boolean.FALSE) {
										topicMatch = defaultResponse.getTarget();
										topicMatchRelationship = defaultResponse;
										log("Conversation topic default response", Level.FINE, defaultTopic, topicMatch);
										topicCandidates.add(topicMatchRelationship);
									}
								}
							}
						}
					}
				}
				if (!previousCandidates.isEmpty()) {
					Object[] result = randomDefaultResponse(previousCandidates, input, conversation, network, variables);
					if (result != null) {
						relationship = (Relationship)result[0];
						response = (Vertex)result[1];
					}
				} else if (!topicCandidates.isEmpty()) {
					Object[] result = randomDefaultResponse(topicCandidates, input, conversation, network, variables);
					if (result != null) {
						relationship = (Relationship)result[0];
						response = (Vertex)result[1];
					}
				}
			}
		}
		List<Relationship> candidates = new ArrayList<Relationship>();
		if (response == null) {
			// Check previous.
			if (previousQuestion != null) {
				for (Relationship defaultResponse : defaultResponses) {
					Vertex meta = defaultResponse.getMeta();
					if (meta != null && meta.hasRelationship(Primitive.PREVIOUS) && !meta.hasRelationship(Primitive.TOPIC)) {
						boolean match = meta.hasRelationship(Primitive.PREVIOUS, previousQuestion);
						if (!match) {
							// Check for patterns.
							Collection<Relationship> previousResponses = meta.getRelationships(Primitive.PREVIOUS);
							if (previousResponses != null) {
								for (Relationship previousResponse : previousResponses) {
									if (!previousResponse.isInverse() && previousResponse.getTarget().instanceOf(Primitive.PATTERN)) {
										match = evaluatePattern(previousResponse.getTarget(), previousQuestion, Primitive.PREVIOUS, new HashMap<Vertex, Vertex>(), network);
										if (match) {
											break;
										}
									}
								}
							}
						}
						if (match) {
							boolean valid = true;
							if (defaultResponse.hasMeta()) {
								if (meta.hasRelationship(Primitive.TOPIC) && meta.hasRelationship(Primitive.REQUIRE, Primitive.TOPIC)) {
									valid = false;
								} else if (checkCondition(defaultResponse, variables, network) == Boolean.FALSE) {
									valid = false;
								}
							}
							if (valid) {
								response = defaultResponse.getTarget();
								relationship = defaultResponse;
								log("Conversation previous default response", Level.FINE, previousQuestion, response);
								candidates.add(defaultResponse);
							}
						}
					}
				}
			}
		}
		if (!candidates.isEmpty()) {
			Object[] result = randomDefaultResponse(candidates, input, conversation, network, variables);
			if (result != null) {
				relationship = (Relationship)result[0];
				response = (Vertex)result[1];
			}
		}
		if (response == null) {
			// Check conditions.
			for (Relationship defaultResponse : defaultResponses) {
				if (checkCondition(defaultResponse, variables, network) == Boolean.TRUE) {
					boolean valid = true;
					if (defaultResponse.hasMeta()) {
						Vertex meta = defaultResponse.getMeta();
						if (meta.hasRelationship(Primitive.TOPIC) && meta.hasRelationship(Primitive.REQUIRE, Primitive.TOPIC)) {
							valid = false;
						} else if (meta.hasRelationship(Primitive.PREVIOUS) && meta.hasRelationship(Primitive.REQUIRE, Primitive.PREVIOUS)) {
							valid = false;
						}
					}
					if (valid) {
						response = defaultResponse.getTarget();
						relationship = defaultResponse;
						log("Conversation condition default response", Level.FINE, response);
						candidates.add(defaultResponse);
					}
				}
			}
		}
		if (!candidates.isEmpty()) {
			Object[] result = randomDefaultResponse(candidates, input, conversation, network, variables);
			if (result != null) {
				relationship = (Relationship)result[0];
				response = (Vertex)result[1];
			}
		}
		if (response == null) {
			// Find valid matches that have not been used in this conversation.
			for (Relationship defaultResponse : defaultResponses) {
				boolean valid = true;
				if (defaultResponse.hasMeta()) {
					Vertex meta = defaultResponse.getMeta();
					if (meta.hasRelationship(Primitive.TOPIC) && meta.hasRelationship(Primitive.REQUIRE, Primitive.TOPIC)) {
						valid = false;
					} else if (meta.hasRelationship(Primitive.PREVIOUS) && meta.hasRelationship(Primitive.REQUIRE, Primitive.PREVIOUS)) {
						valid = false;
					}
				}
				if (valid && !conversation.hasRelationship(Primitive.SENTENCE, defaultResponse.getTarget())) {
					if (checkCondition(defaultResponse, variables, network) != Boolean.FALSE) {
						candidates.add(defaultResponse);
					}
				}
			}
			if (candidates.isEmpty()) {
				// Find any valid matches.
				for (Relationship defaultResponse : defaultResponses) {
					boolean valid = true;
					if (defaultResponse.hasMeta()) {
						Vertex meta = defaultResponse.getMeta();
						if (meta.hasRelationship(Primitive.TOPIC) && meta.hasRelationship(Primitive.REQUIRE, Primitive.TOPIC)) {
							valid = false;
						} else if (meta.hasRelationship(Primitive.PREVIOUS) && meta.hasRelationship(Primitive.REQUIRE, Primitive.PREVIOUS)) {
							valid = false;
						}
					}
					if (valid && (!defaultResponse.getTarget().hasRelationship(Primitive.REQUIRE, Primitive.NOREPEAT)
								|| !conversation.hasRelationship(Primitive.SENTENCE, defaultResponse.getTarget()))) {
						if (checkCondition(defaultResponse, variables, network) != Boolean.FALSE) {
							candidates.add(defaultResponse);
						}
					}
				}
			}
			if (!candidates.isEmpty()) {
				Object[] result = randomDefaultResponse(candidates, input, conversation, network, variables);
				if (result != null) {
					relationship = (Relationship)result[0];
					response = (Vertex)result[1];
				}
			}
			if (response == null) {
				// Mimic.
				log("Conversation mimic, failed to find default response", Level.FINE, sentence);
				response = sentence;
				relationship = null;
			} else {
				response = getWord(response, network);
			}
		}
		checkResponseMeta(relationship, conversation, variables, network);
		return response;
	}
	
	public Object[] randomDefaultResponse(List<Relationship> responses, Vertex input, Vertex conversation, Network network, Map<Vertex, Vertex> variables) {
		int count = 0;
		Object[] result = new Object[2];
		while (!responses.isEmpty()) {
			Relationship relationship = Utils.random(responses);
			Vertex response = relationship.getTarget();
			if ((response != null) && response.instanceOf(Primitive.LABEL)) {
				response = response.mostConscious(Primitive.RESPONSE);
			}
			if (response.hasRelationship(Primitive.REQUIRE, Primitive.NOREPEAT)
					&& conversation.hasRelationship(Primitive.SENTENCE, response)) {
				response = null;
			}
			if ((response != null) && response.instanceOf(Primitive.FORMULA)) {
				log("Default response is template formula", Level.FINE, response);
				response = evaluateFormula(response, variables, network);
				if (response == null) {
					log("Template formula cannot be evaluated", Level.FINE, response);
					responses.remove(relationship);
				} else {
					// Track template was used in conversation.
					conversation.addRelationship(Primitive.SENTENCE, relationship.getTarget());
				}
			}
			if (response != null) {
				result[0] = relationship;
				result[1] = response;
				return result;
			}
			count++;
			// Check at most 10 responses.
			if (count > 10) {
				return null;
			}
		}
		return null;
	}
	
	/**
	 * Attempt to import and learn new responses from a website.
	 */
	public List<Vertex> importHTML(String url, String xpath, String learningMode, Network network) {
		log("IMPORT HTML", Level.INFO, url, xpath, learningMode);
		List<Vertex> results = new ArrayList<Vertex>();
		try {
			if (learningMode.equals("Reflexive")) {
				// For each "well formed" paragraph, make the paragraph a response and its own question (to match similar questions).
				if (xpath.isEmpty()) {
					xpath = "//p";
				}
				Vertex result = getBot().awareness().getSense(Http.class).requestHTML(url, xpath, "#array", "#text", network);
				if (result != null) {
					List<Vertex> elements = result.orderedRelations(Primitive.ELEMENT);
					if (elements != null) {
						for (Vertex element : elements) {
							// Learn each sentence if it looks like something worth learning.
							// Only learn proper sentences.
							String text = element.printString().trim();
							if ((text.length() > 10) && text.endsWith(".")) {
								Vertex sentence = network.createSentence(text);
								Collection<Relationship> words = sentence.getRelationships(Primitive.WORD);
								if (words != null && words.size() > 3) {
									Language.addResponse(sentence, sentence, network);
									results.add(sentence);
								}
							}
						}
					}
				}
			} else if (learningMode.equals("Headers")) {
				// For each header, make the header the question, and the header's first paragraph the response.
				String html = Utils.httpGET(url);
				TextStream stream = new TextStream(html);
				while (!stream.atEnd()) {
					stream.skipToAll("<h", true);
					if (stream.atEnd() || !"123456789".contains(stream.next(1))) {
						// Ignore non h1... tags
						continue;
					}
					stream.skipToAll(">", true);
					if (stream.atEnd()) {
						break;
					}
					String header = stream.upToAll("</h").trim();
					header = Utils.stripTags(header).trim();
					stream.skipToAll(">", true);
					if (stream.atEnd()) {
						break;
					}
					stream.skipToAll("<p");
					stream.skipToAll(">", true);
					if (stream.atEnd()) {
						break;
					}
					String p = stream.upToAll("</p>").trim();
					if ((p.length() > 10) && p.endsWith(".")) {
						Vertex question = network.createSentence(header);
						Vertex response = network.createSentence(p);
						Language.addResponse(question, response, network);
						results.add(question);
					}
				}
			} else if (learningMode.equals("Text")) {
				if (xpath.isEmpty()) {
					xpath = "//p";
				}
				Vertex result = getBot().awareness().getSense(Http.class).requestHTML(url, xpath, "#array", "#text", network);
				if (result != null) {
					List<Vertex> elements = result.orderedRelations(Primitive.ELEMENT);
					if (elements != null) {
						for (Vertex element : elements) {
							results.add(element);
						}
					}
				}
			} else if (learningMode.equals("HTML")) {
				if (xpath.isEmpty()) {
					xpath = "//p";
				}
				Vertex result = getBot().awareness().getSense(Http.class).requestHTML(url, xpath, "#array", "#html", network);
				if (result != null) {
					List<Vertex> elements = result.orderedRelations(Primitive.ELEMENT);
					if (elements != null) {
						for (Vertex element : elements) {
							results.add(element);
						}
					}
				}
			} else {
				if (xpath.isEmpty()) {
					xpath = "//p";
				}
				Vertex result = getBot().awareness().getSense(Http.class).requestHTML(url, xpath, "#array", null, network);
				if (result != null) {
					List<Vertex> elements = result.orderedRelations(Primitive.ELEMENT);
					if (elements != null) {
						for (Vertex element : elements) {
							results.add(element);
						}
					}
				}
			}
			network.save();
			return results;
		} catch (BotException exception) {
			throw (BotException)exception;
		} catch (Exception exception) {
			throw new BotException(exception);
		}
	}
	
	/**
	 * Return the last thing the speaker said in the conversation.
	 */
	public Vertex getLastInputInConversation(Vertex conversation, Vertex speaker, int last) {
		if (conversation == null) {
			return null;
		}
		List<Vertex> allInput = conversation.orderedRelations(Primitive.INPUT);
		if (allInput != null) {
			int count = 0;
			for (int index  = allInput.size() - 1; index >= 0; index--) {
				Vertex previousInput = allInput.get(index);
				if (previousInput.mostConscious(Primitive.SPEAKER) == speaker) {
					count++;
					if (count >= last) {
						return previousInput;
					}
				}
			}
		}
		return null;		
	}

	/**
	 * Return if learning should be used for the input.
	 */
	public boolean shouldLearn(Vertex input, Vertex speaker) {
		if (this.allowLearning == Boolean.TRUE) {
			return true;
		} else if (this.allowLearning == Boolean.FALSE) {
			return false;
		}
		boolean isAdmin = false;
		boolean isAnonymous = true;
		if (speaker != null) {
			isAdmin = speaker.hasRelationship(Primitive.ASSOCIATED, Primitive.ADMINISTRATOR);
			isAnonymous = speaker.hasRelationship(Primitive.ASSOCIATED, Primitive.ANONYMOUS);
		}
		if (this.learningMode == LearningMode.Disabled) {
			return false;
		} else if (!isAdmin && (this.learningMode == LearningMode.Administrators)) {
			if (speaker.is(Primitive.SELF)) {
				return true;
			}
			return false;					
		} else if (isAnonymous && (this.learningMode == LearningMode.Users)) {
			return false;
		}
		return true;
	}

	/**
	 * Return if learning should be used for the input.
	 */
	public boolean shouldCorrect(Vertex input, Vertex speaker) {
		boolean isAdmin = false;
		boolean isAnonymous = true;
		if (speaker != null) {
			isAdmin = speaker.hasRelationship(Primitive.ASSOCIATED, Primitive.ADMINISTRATOR);
			isAnonymous = speaker.hasRelationship(Primitive.ASSOCIATED, Primitive.ANONYMOUS);
		}
		if (this.correctionMode == CorrectionMode.Disabled) {
			return false;
		} else if (!isAdmin && (this.correctionMode == CorrectionMode.Administrators)) {
			return false;					
		} else if (isAnonymous && (this.correctionMode == CorrectionMode.Users)) {
			return false;
		}
		return true;
	}

	/**
	 * Self API for checking is a user is trusted and should allow correction.
	 */
	public Vertex allowCorrection(Vertex source, Vertex user) {
		if (shouldCorrect(null, user)) {
			return source.getNetwork().createVertex(Primitive.TRUE);
		}
		return source.getNetwork().createVertex(Primitive.FALSE);
	}

	/**
	 * Self API for learning a new response.
	 */
	public Vertex learn(Vertex source, Vertex question, Vertex response) {
		log("learn", Level.FINE, question, response);
		Network network = source.getNetwork();
		if (question.is(Primitive.NULL) || response.is(Primitive.NULL)) {
			return null;
		}
		if (!question.instanceOf(Primitive.PATTERN) && question.instanceOf(Primitive.SENTENCE)) {
			question = network.createSentence(question.printString());
		}
		addResponse(question, response, network);
		return source;
	}

	/**
	 * Self API for learning a new response.
	 */
	public Vertex learn(Vertex source, Vertex question, Vertex response, Vertex topic) {
		log("learn", Level.FINE, question, response);
		addResponse(question, response, topic.printString(), null, null, source.getNetwork());
		return source;
	}

	/**
	 * Self API for synthesizing a new response from a phrase.
	 */
	public Vertex synthesize(Vertex source, Vertex phrase) {
		log("synthesize", Level.FINE, phrase);
		return synthesizeResponse(null, phrase, null, false, null, phrase.getNetwork());
	}

	/**
	 * Self API for synthesizing a new response.
	 */
	public Vertex synthesize(Vertex source) {
		log("synthesize", Level.FINE);
		return synthesizeResponse(null, null, null, false, null, source.getNetwork());
	}

	/**
	 * Self API for synthesizing a new response from a phrase.
	 */
	public Vertex randomSynthesize(Vertex source, Vertex phrase) {
		log("random synthesize", Level.FINE, phrase);
		return synthesizeResponse(null, phrase, null, true, null, phrase.getNetwork());
	}

	/**
	 * Self API for synthesizing a new response.
	 */
	public Vertex randomSynthesize(Vertex source) {
		log("random synthesize", Level.FINE);
		return synthesizeResponse(null, null, null, true, null, source.getNetwork());
	}

	/**
	 * Self API to return an array of word indexes.
	 */
	public Vertex keywordIndexes(Vertex source, Vertex text, Vertex keywords) {
		Network network = source.getNetwork();
		Vertex array = network.createInstance(Primitive.ARRAY);
		Vertex paragraph = network.createSentence(text.printString());
		List<String> keywordList = new TextStream(keywords.printString()).allWords();
		if (keywordList.isEmpty()) {
			return null;
		}
		List<Vertex> words = paragraph.orderedRelations(Primitive.WORD);
		if (words != null) {
			int index = 0;
			for (Vertex word : words) {
				for (String keyword : keywordList) {
					if ((word.getData() instanceof String)
							&& (keyword.equalsIgnoreCase((String)word.getData())
									|| (((String)word.getData()).toLowerCase().startsWith(keyword.toLowerCase()))
										&&  ((String)word.getData()).length() <= (keyword.length() + 2))) {
						array.appendRelationship(Primitive.ELEMENT, network.createVertex(index));
					}
				}
				index++;
			}
		}
		return array;
	}

	/**
	 * Self API to print the details of an object.
	 */
	public Vertex details(Vertex source, Vertex object) {
		Network network = source.getNetwork();
		Vertex paragraph = network.createInstance(Primitive.PARAGRAPH);
		Vertex text = network.createInstance(Primitive.FRAGMENT);
		Vertex cr = network.createVertex("\n");
		Vertex bold = network.createVertex("<b>");
		Vertex boldEnd = network.createVertex("</b>");
		Vertex ul = network.createVertex("<ul>");
		Vertex ulEnd = network.createVertex("</ul>");
		Vertex li = network.createVertex("<li>");
		Vertex liEnd = network.createVertex("</li>");
		Vertex dash = network.createVertex("-");
		int index = 0;
		int paraIndex = 0;
		text.addRelationship(Primitive.WORD, bold, index++);
		text.addRelationship(Primitive.WORD, getWord(object, network), index++);
		text.addRelationship(Primitive.WORD, boldEnd, index++);
		text.addRelationship(Primitive.WORD, cr, index++);
		paragraph.addRelationship(Primitive.SENTENCE, text, paraIndex++);
		text = network.createInstance(Primitive.FRAGMENT);
		index = 0;
		text.addRelationship(Primitive.WORD, ul, index++);
		text.addRelationship(Primitive.WORD, cr, index++);
		paragraph.addRelationship(Primitive.SENTENCE, text, paraIndex++);
		for (Iterator<Relationship> iterator = object.orderedAllRelationships(); iterator.hasNext(); ) {
			if (paraIndex > 100) {
				break;
			}
			Relationship relationship = iterator.next();
			if (relationship.isInverse()) {
				continue;
			}
			Vertex type = getWord(relationship.getType(), network);
			Vertex value = getWord(relationship.getTarget(), network);
			if (!(value.getData() instanceof String) || ((String)value.getData()).isEmpty()) {
				//continue;
			}
			text = network.createInstance(Primitive.FRAGMENT);
			index = 0;
			text.addRelationship(Primitive.WORD, li, index++);
			text.addRelationship(Primitive.WORD, type, index++);
			text.addRelationship(Primitive.WORD, dash, index++);
			text.addRelationship(Primitive.WORD, value, index++);
			text.addRelationship(Primitive.WORD, liEnd, index++);
			text.addRelationship(Primitive.WORD, cr, index++);
			paragraph.addRelationship(Primitive.SENTENCE, text, paraIndex++);
		}
		text = network.createInstance(Primitive.FRAGMENT);
		index = 0;
		text.addRelationship(Primitive.WORD, ulEnd, index++);
		paragraph.addRelationship(Primitive.SENTENCE, text, paraIndex++);
		return paragraph;
	}

	/**
	 * Self API to create a compound word.
	 * Language.word("very", "nice")
	 * Create a compound word from the arguments.
	 */
	public Vertex word(Vertex source, Vertex[] arguments) {
		Network network = source.getNetwork();
		Vertex nil = network.createVertex(Primitive.NULL);
		if (arguments.length == 0) {
			return nil;
		}
		StringWriter writer = new StringWriter();
		List<Vertex> words = new ArrayList<Vertex>();
		for (Vertex argument : arguments) {
			if (argument.instanceOf(Primitive.ARRAY)) {
				List<Vertex> elements = argument.orderedRelations(Primitive.ELEMENT);
				if (elements != null) {
					words.addAll(elements);
				}
			} else if (argument.instanceOf(Primitive.LIST)) {
				List<Vertex> elements = argument.orderedRelations(Primitive.SEQUENCE);
				if (elements != null) {
					words.addAll(elements);
				}
			} else {
				words.add(argument);
			}
		}
		Vertex previousWord = nil;
		for (int index = 0; index < words.size(); index++) {
			Vertex word = words.get(index);
			Vertex nextWord = nil;
			if (words.size() > (index + 1)) {
				nextWord = words.get(index + 1);
			}
			word = Language.getWordFollowing(word, previousWord, nextWord, network);
			writer.write(String.valueOf(word.getData()));
			if ((index + 1) < words.size()) {
				writer.write(" ");
			}
			previousWord = word;
		}
		return network.createWord(writer.toString());
	}

	/**
	 * Self API to define a word.
	 * Language.define("hello", #hello)
	 */
	public Vertex define(Vertex source, Vertex word, Vertex meaning) {
		word.addRelationship(Primitive.MEANING, meaning);
		meaning.addRelationship(Primitive.WORD, word);
		source.getNetwork().associateCaseInsensitivity((String)word.getData(), meaning);
		return word;
	}

	/**
	 * Self API to create a sentence.
	 * Language.sentence("hello", "world")
	 * Create a sentence from the arguments.
	 */
	public Vertex sentence(Vertex source, Vertex[] arguments) {
		Network network = source.getNetwork();
		Vertex result = null;
		Vertex nil = network.createVertex(Primitive.NULL);
		if (arguments.length == 0) {
			result = nil;
		} else if (arguments.length == 1) {
			result = arguments[0];
			if (!(result.getData() instanceof String)) {
				StringWriter writer = new StringWriter();
				Vertex text = Language.getWordFollowing(result, nil, nil, network);
				writer.write(text.getDataValue());
				result = network.createSentence(writer.toString());
			} else {
				result = network.createSentence((String)result.getData());
			}
		} else {
			StringWriter writer = new StringWriter();
			List<Vertex> words = new ArrayList<Vertex>();
			for (Vertex argument : arguments) {
				words.add(argument);
			}
			Vertex previousWord = nil;
			for (int index = 0; index < words.size(); index++) {
				Vertex word = words.get(index);
				Vertex nextWord = nil;
				if (words.size() > (index + 1)) {
					nextWord = words.get(index + 1);
				}
				word = Language.getWordFollowing(word, previousWord, nextWord, network);
				writer.write(String.valueOf(word.getData()));
				if ((index + 1) < words.size()) {
					writer.write(" ");
				}
				previousWord = word;
			}
			result = network.createSentence(writer.toString());
		}
		return result;
	}

	/**
	 * Self API to create a paragraph.
	 * Language.paragraph("hello. How are you?")
	 * Parse the text into a paragraph object.
	 */
	public Vertex paragraph(Vertex source, Vertex text) {
		Network network = source.getNetwork();
		return network.createParagraph(text);
	}
	
	/**
	 * Self API to access last input of a speaker.
	 * Language.getLastInput(speaker)
	 * Get the last input from the conversation for the speaker.
	 */
	public Vertex getLastInput(Vertex source, Vertex conversation, Vertex speaker) {
		return getLastInput(source, conversation, speaker, null, null);
	}
	
	/**
	 * Self API to access last input of a speaker.
	 * Language.getLastInput(speaker, 1)
	 * Get the last input from the conversation for the speaker.
	 */
	public Vertex getLastInput(Vertex source, Vertex conversation, Vertex speaker, Vertex index) {
		return getLastInput(source, conversation, speaker, index, null);
	}
	
	/**
	 * Self API to access last input of a speaker.
	 * Language.getLastInput(speaker, 1, 2)
	 * Get the last input from the conversation for the speaker.
	 */
	public Vertex getLastInput(Vertex source, Vertex conversation, Vertex speaker, Vertex index, Vertex part) {
		Network network = source.getNetwork();
		int partValue = 1;
		if (part != null) {
			try {
				partValue = Integer.valueOf(String.valueOf(part.getData()));
			} catch (Exception exception) {
				// Ignore, use 1;
			}
		}
		Vertex input = network.createVertex(Primitive.INPUT_VARIABLE);
		if (conversation == null) {
			return network.createVertex(Primitive.NULL);
		}
		int count = 0;
		int value = 1;
		if (index != null) {
			try {
				value = Integer.valueOf(String.valueOf(index.getData()));
			} catch (Exception exception) {
				// Ignore, use 1;
			}
		}
		List<Vertex> inputs = conversation.orderedRelations(Primitive.INPUT);
		int element = inputs.size() - 1;
		while (count < value && element >= 0) {
			input = inputs.get(element);
			if (input.hasRelationship(Primitive.SPEAKER, speaker)) {
				count++;
				if (count == value) {
					Vertex sentence = input.getRelationship(Primitive.INPUT);
					if (part == null) {
						return sentence;
					}
					if (!sentence.instanceOf(Primitive.PARAGRAPH)) {
						if (partValue == 1) {
							return sentence;
						}
						return network.createVertex(Primitive.NULL);
					}
					List<Vertex> sentences = sentence.orderedRelations(Primitive.SENTENCE);
					if (partValue > sentences.size()) {
						return network.createVertex(Primitive.NULL);
					}
					return sentences.get(partValue - 1);
				}
			}
			element--;
		}
		return network.createVertex(Primitive.NULL);
	}
	
	/**
	 * Associate the response, attempt to understand.
	 */
	public Vertex processListening(Vertex input, Vertex sentence, Vertex speaker, Vertex conversation, List<Relationship> targets, Network network, LanguageState state) {
		if (targets != null) {
			for (int index = 0; index < targets.size(); index++) {
				Vertex target = targets.get(index).getTarget();
				// Get last input said by target.
				Vertex lastInput = getLastInputInConversation(conversation, target, 1);
				// Abort if is first question and talking to self.
				if (input == lastInput) {
					return sentence;
				}
				if (lastInput == null) {
					continue;
				}
				Vertex lastSentence = lastInput.mostConscious(Primitive.INPUT);
				Vertex mimic = lastInput.getRelationship(Primitive.MIMIC);
				if (mimic != null) {
					lastSentence = mimic.mostConscious(Primitive.INPUT);
				}
				if (lastSentence == null) {
					continue;
				}
				float value = 1.0f/(index + (1/this.learningRate));
				if (!shouldLearn(input, speaker)) {
					// Still maintain input state.
					lastInput.addWeakRelationship(Primitive.RESPONSE, input, value);
					input.addWeakRelationship(Primitive.QUESTION, lastInput, value);
				} else {
					// Associate response.
					// Associate previous question as meta info.
					if (getReduceQuestions() && !lastSentence.hasRelationship(Primitive.RESPONSE) && lastSentence.getDataValue() != null) {
						lastSentence = network.createSentence(Utils.reduce(lastSentence.getDataValue()));
					}
					Vertex previousQuestionInput = lastInput.getRelationship(Primitive.QUESTION);
					sentence.addWeakRelationship(Primitive.RESPONSE_QUESTION, lastSentence, value);
					lastInput.addWeakRelationship(Primitive.RESPONSE, input, value);
					lastSentence.associateAll(Primitive.WORD, lastSentence, Primitive.QUESTION);
					input.addWeakRelationship(Primitive.QUESTION, lastInput, value);
					Relationship relationship = null;
					if (index == 0) {
						relationship = getBot().mind().getThought(Comprehension.class).checkTemplate(input, network);
						if (relationship == null) {
							relationship = lastSentence.addWeakRelationship(Primitive.RESPONSE, sentence, value);
							if (getReduceQuestions()) {
								network.checkReduction(lastSentence);
								lastSentence.weakAssociateAll(Primitive.REDUCTION, sentence, Primitive.RESPONSE, value);
							}
						}
					} else {
						relationship = lastSentence.addWeakRelationship(Primitive.RESPONSE, sentence, value);
					}
					addSentencePreviousMeta(relationship, previousQuestionInput, network);
				}
				log("Listening sentence", Level.FINE, lastSentence);
			}
		}
		if (speaker != null) {
			Vertex lastInput = getLastInputInConversation(speaker, conversation, 1);
			if (lastInput != null) {
				Vertex lastSentence = lastInput.mostConscious(Primitive.INPUT);
				if (lastSentence != null) {
					lastSentence.addRelationship(Primitive.NEXT, sentence);
					sentence.addRelationship(Primitive.PREVIOUS, lastSentence);
				}
			}
		}
		return sentence;
	}

	/**
	 * Self API to create a fragment.
	 * Language.sentence("hello", "world")
	 * Create a sentence from the arguments.
	 */
	public Vertex fragment(Vertex source, Vertex[] arguments) {
		Network network = source.getNetwork();
		Vertex nil = network.createVertex(Primitive.NULL);
		if (arguments.length == 0) {
			return nil;
		}
		StringWriter writer = new StringWriter();
		List<Vertex> words = new ArrayList<Vertex>();
		for (Vertex argument : arguments) {
			if (argument.instanceOf(Primitive.ARRAY)) {
				List<Vertex> elements = argument.orderedRelations(Primitive.ELEMENT);
				if (elements != null) {
					words.addAll(elements);
				}
			} else if (argument.instanceOf(Primitive.LIST)) {
				List<Vertex> elements = argument.orderedRelations(Primitive.SEQUENCE);
				if (elements != null) {
					words.addAll(elements);
				}
			} else {
				words.add(argument);
			}
		}
		Vertex previousWord = nil;
		for (int index = 0; index < words.size(); index++) {
			Vertex word = words.get(index);
			Vertex nextWord = nil;
			if (words.size() > (index + 1)) {
				nextWord = words.get(index + 1);
			}
			word = Language.getWordFollowing(word, previousWord, nextWord, network);
			writer.write(String.valueOf(word.getData()));
			if ((index + 1) < words.size()) {
				writer.write(" ");
			}
			previousWord = word;
		}
		return network.createFragment(writer.toString());
	}
	
	/**
	 * Compute the real value of the sentence.
	 */
	public int computeMaxSentenceValue(Vertex match, Vertex original, Network network, Map<Vertex, Integer> wordScores) {
		Collection<Relationship> words = match.getRelationships(Primitive.WORD);
		int max = 0;
		if (words != null) {
			for (Relationship word : words) {
				boolean found  = false;
				Vertex lowercase = null;
				if (getTrackCase()) {
					// Old case over sensitivity code.
					lowercase = invertWordCase(word.getTarget(), network);
				}
				if (original.hasRelationship(Primitive.WORD, word.getTarget())) {
					found = true;
				} else {
					if (getTrackCase()) {
						if ((lowercase != null) && (lowercase != word)) {
							if (original.hasRelationship(Primitive.WORD, lowercase)) {
								found = true;
							}
						}
					}
					if (getCheckSynonyms()) {
						Collection<Relationship> synonyms = word.getTarget().getRelationships(Primitive.SYNONYM);
						if (synonyms != null) {
							for (Relationship synonym : synonyms) {
								if (original.hasRelationship(Primitive.WORD, synonym.getTarget())) {
									found = true;
								}
							}
						}
					}
				}
				if (found) {
					int value = computeWordValue(word.getTarget(), wordScores);
					if (getTrackCase()) {
						if ((lowercase != null) && (lowercase != word)) {
							value = Math.max(value, computeWordValue(lowercase, wordScores));
						}
					}
					max = max + value;
				}
			}
		}
		return max;
	}
	
	/**
	 * Compute the real value of the sentence.
	 */
	public int computeMaxSentenceFragmentsValue(Vertex match, Vertex original, Network network) {
		Collection<Relationship> fragments = match.getRelationships(Primitive.FRAGMENT);
		Map<Vertex, Integer> fragmentScores = computeInputFragmentValues(match);
		int max = 0;
		if (fragments != null) {
			for (Relationship fragment : fragments) {
				boolean found  = false;
				if (original.hasRelationship(Primitive.FRAGMENT, fragment.getTarget())) {
					found = true;
				} else {
					if (getCheckSynonyms()) {
						Collection<Relationship> synonyms = fragment.getTarget().getRelationships(Primitive.SYNONYM);
						if (synonyms != null) {
							for (Relationship synonym : synonyms) {
								if (original.hasRelationship(Primitive.FRAGMENT, synonym.getTarget())) {
									found = true;
								}
							}
						}
					}
				}
				if (found) {
					int value = fragmentScores.get(fragment.getTarget());
					max = max + value;
				}
			}
		}
		return max;
	}
	
	/**
	 * Return the matching value for the word, some word types are worth more than others.
	 */
	public int computeWordValue(Vertex word, Map<Vertex, Integer> wordScores) {
		int value = 2;
		
		if (this.nlp >= 3) {
			if (wordScores == null) {
			} else {
				if (wordScores.get(word) != null) {
					value = wordScores.get(word);
				}
				if (word.instanceOf(Primitive.PUNCTUATION) || (word.instanceOf(Primitive.ARTICLE))) {
					value = 1;
				}
				if (word.instanceOf(Primitive.KEYWORD)) {
					value += 25;
				}
			}
		} else {
			int count = 0;
			
			if (word.instanceOf(Primitive.NOUN)) {
				value = value + 12;
				count++;
			}
			if (word.instanceOf(Primitive.ADJECTIVE)) {
				value = value + 6;
				count++;
			}
			if (word.instanceOf(Primitive.INTERJECTION)) {
				value = value + 6;
				count++;
			}
			if (word.instanceOf(Primitive.VERB)) {
				value = value + 4;
				count++;
			}
			if (word.instanceOf(Primitive.QUESTION)) {
				value = value + 3;
				count++;
			}
			if (word.instanceOf(Primitive.ADVERB)) {
				value = value + 2;
				count++;
			}
			if (count == 0) {
				// Check for meaning.
				Collection<Relationship> meanings = word.getRelationships(Primitive.MEANING);
				if (meanings != null) {
					for (Relationship relation : meanings) {
						Vertex meaning = relation.getTarget();
						// Value nouns and adjective over other words.
						if (meaning.instanceOf(Primitive.THING)) {
							value = value + 12;
						} else if (meaning.instanceOf(Primitive.DESCRIPTION)) {
							value = value + 6;
						} else if (meaning.instanceOf(Primitive.INTERJECTION)) {
							value = value + 6;
						} else if (meaning.instanceOf(Primitive.ACTION)) {
							value = value + 4;
						} else if (meaning.instanceOf(Primitive.QUESTION)) {
							value = value + 3;
						} else {
							value = value + 2;
						}
					}
					value = value / meanings.size();
				} else {
					// Unknown word, may not have discovered it yet, so give it a +4.
					value = value + 4;
				}
			} else {
				value = value / count;
			}
			if (word.instanceOf(Primitive.PUNCTUATION) || (word.instanceOf(Primitive.ARTICLE))) {
				value = 1;
			}
			if (word.instanceOf(Primitive.KEYWORD)) {
				value = 25;
			}
		}
		
		return value;
	}
	
	/**
	 * Add all of the sentences for the word with its value.
	 */
	public void recordSetenceValues(Vertex word, Vertex originalWord, Collection<Relationship> relationships, int value, Vertex sentence,
					Map<Vertex, Integer> matches, Map<Vertex, Set<Vertex>> processed, Network network, List<Vertex> defer) {
		if (relationships != null) {
			if ((defer != null) && relationships.size() > 100) {
				log("Deferring word", Level.FINER, word, relationships.size());
				defer.add(word);
				return;
			}
			for (Relationship sentenceRelation : relationships) {
				Vertex otherSentence = sentenceRelation.getTarget();
				if (sentence != otherSentence) {
					// Only index sentences with responses.
					if (otherSentence.hasAnyResponseRelationship()) {
						Set<Vertex> processedWords = processed.get(otherSentence);
						if (processedWords == null) {
							processedWords = new HashSet<Vertex>(4);
							processed.put(otherSentence, processedWords);
						}
						if (processedWords.contains(originalWord)) {
							log("Already processed word for sentence", Level.FINEST, word, otherSentence);
						} else {
							processedWords.add(originalWord);
							Integer count = matches.get(otherSentence);
							if (count == null) {
								count = 0;
							}
							matches.put(otherSentence, count + value);
							log("Increasing question match value (question, word, value, word value)", Level.FINER, otherSentence, word, count + value, value);
						}
					} else {
						log("Sentence has no responses", Level.FINEST, otherSentence);
					}
				}
			}
		}
	}
	
	/**
	 * Add all of the patterns for the word with its value.
	 */
	public void recordPatternValues(Vertex word, Vertex sentence, Map<Vertex, Integer> matches, Network network, List<Vertex> defer, Map<Vertex, Integer> wordValues) {
		Collection<Relationship> sentenceRelations = word.getRelationships(Primitive.PATTERN);
		if (sentenceRelations != null) {
			if ((defer != null) && sentenceRelations.size() > 100) {
				defer.add(word);
				return;
			}
			int value = computeWordValue(word, wordValues);
			for (Relationship sentenceRelation : sentenceRelations) {
				Vertex otherSentence = sentenceRelation.getTarget();
				if (sentence != otherSentence) {
					// Only index sentences with responses.
					if (otherSentence.hasAnyResponseRelationship()) {
						Integer count = matches.get(otherSentence);
						if (count == null) {
							count = 0;
						}
						matches.put(otherSentence, count + value);
						log("Increasing pattern match value (pattern, word, value, word value)", Level.FINER, otherSentence, word, count + value, value);
					}
				}
			}
			// Record max value.
			Integer count = matches.get(sentence);
			if (count == null) {
				count = 0;
			}
			matches.put(sentence, count + value);
		}
	}
	
	public static Vertex invertWordCase(Vertex word, Network network) {
		if (!(word.getData() instanceof String)) {
			return null;
		}
		String text = (String)word.getData();
		if (Utils.isCaps(text) || Utils.isCapitalized(text)) {
			return network.findByData(((String)word.getData()).toLowerCase());
		} else {
			return network.findByData(Utils.capitalize((String)word.getData()));
		}
	}

	/**
	 * Add all of the questions for all of the words to the matching map.
	 */
	public void addQuestionMatches(Vertex sentence, Network network, long startTime, long processTime, List<Vertex> words,
					Map<Vertex, Integer> matches, Map<Vertex, Set<Vertex>> processed, Primitive key, boolean keywords, boolean compoundWords, Map<Vertex, Integer> wordScores) {
		if (words == null) {
			return;
		}
		List<Vertex> deferred = new ArrayList<Vertex>();
		for (Vertex word : words) {
			if (!(word.getData() instanceof String)) {
				continue;
			}
			if (compoundWords && (!word.instanceOf(Primitive.COMPOUND_WORD))) {
				continue;
			}
			Vertex lowercase = null;
			Vertex uppercase = null;
			if (getTrackCase()) {
				// Old case over sensitivity code.
				lowercase = invertWordCase(word, network);
				uppercase = network.findByData(((String)word.getData()).toUpperCase());
			}
			if (keywords && (!word.instanceOf(Primitive.KEYWORD)
							&& (lowercase == null || !lowercase.instanceOf(Primitive.KEYWORD))
							&& (uppercase == null || !uppercase.instanceOf(Primitive.KEYWORD)))) {
				boolean foundSynonym = false;
				if (getCheckSynonyms()) {
					Collection<Relationship> synonyms = word.getRelationships(Primitive.SYNONYM);
					if (synonyms != null) {
						for (Relationship synonym : synonyms) {
							if (synonym.getTarget().instanceOf(Primitive.KEYWORD)) {
								foundSynonym = true;
								break;
							}
						}
					}
					if (getTrackCase()) {
						if (lowercase != null && lowercase != word) {
							synonyms = lowercase.getRelationships(Primitive.SYNONYM);
							if (synonyms != null) {
								for (Relationship synonym : synonyms) {
									if (synonym.getTarget().instanceOf(Primitive.KEYWORD)) {
										foundSynonym = true;
										break;
									}
								}
							}
						}
					}
				}
				if (!foundSynonym) {
					continue;
				}
			}
			long currentTime = System.currentTimeMillis();
			if ((currentTime - startTime) > processTime) {
				log("Search time limit reached (time, matches)", Level.INFO, processTime, matches.size());
				break;
			}
			int value = computeWordValue(word, wordScores);
			if (getTrackCase()) {
				if ((lowercase != null) && (lowercase != word)) {
					value = Math.max(value, computeWordValue(lowercase, wordScores));
				}
				if ((uppercase != null) && (uppercase != word)) {
					value = Math.max(value, computeWordValue(uppercase, wordScores));
				}
			}
			if (key.equals(Primitive.KEYQUESTION)) {
				value = value + 4; // Weight keyquestion more.
			}
			Collection<Relationship> questions = word.getRelationships(key);
			if (questions != null) {
				log("Finding similar questions for word (word, value, questions, keyword)", Level.FINER, word.getData(), value, questions.size(), keywords);
				recordSetenceValues(word, word, questions, value, sentence, matches, processed, network, deferred);
			}
			if (getTrackCase()) {
				if ((lowercase != null) && (lowercase != word)) {
					questions = lowercase.getRelationships(key);
					if (questions != null) {
						log("Finding similar questions for word lowercase (word, value, questions, keyword)", Level.FINER, lowercase.getData(), value, questions.size(), keywords);
						recordSetenceValues(lowercase, word, questions, value, sentence, matches, processed, network, deferred);
					}
				}
				if ((uppercase != null) && (uppercase != word)) {
					questions = uppercase.getRelationships(key);
					if (questions != null) {
						log("Finding similar questions for word uppercase (word, value, questions, keyword)", Level.FINER, uppercase.getData(), value, questions.size(), keywords);
						recordSetenceValues(uppercase, word, questions, value, sentence, matches, processed, network, deferred);
					}
				}
			}
			if (getCheckSynonyms()) {
				Collection<Relationship> synonyms = word.getRelationships(Primitive.SYNONYM);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						questions = synonym.getTarget().getRelationships(key);
						if (questions != null) {
							log("Finding similar questions for word synonym (synonym, word, value, questions, keyword)", Level.FINER,
										synonym.getTarget().getData(), word, synonym, value, questions.size(), keywords);
							recordSetenceValues(synonym.getTarget(), word, questions, value, sentence, matches, processed, network, deferred);
						}
					}
				}
				if (getTrackCase()) {
					if ((lowercase != null) && (lowercase != word)) {
						synonyms = lowercase.getRelationships(Primitive.SYNONYM);
						if (synonyms != null) {
							for (Relationship synonym : synonyms) {
								questions = synonym.getTarget().getRelationships(key);
								if (questions != null) {
									log("Finding similar questions for word synonym (synonym, word, value, questions, keyword)", Level.FINER,
												synonym.getTarget().getData(), word, synonym, value, questions.size(), keywords);
									recordSetenceValues(synonym.getTarget(), word, questions, value, sentence, matches, processed, network, deferred);
								}
							}
						}
					}
				}
			}
			// TODO plurals, conjegations
		}
		// Process keywords with lots of sentences last.
		for (Vertex word : deferred) {
			long currentTime = System.currentTimeMillis();
			if ((currentTime - startTime) > processTime) {
				log("Search time limit reached (time, matches)", Level.INFO, processTime, matches.size());
				break;
			}
			int value = computeWordValue(word, wordScores);
			if (getTrackCase()) {
				Vertex lowercase = invertWordCase(word, network);
				if ((lowercase != null) && (lowercase != word)) {
					value = Math.max(value, computeWordValue(lowercase, wordScores));
				}
				Vertex uppercase = network.findByData(((String)word.getData()).toUpperCase());
				if ((uppercase != null) && (uppercase != word)) {
					value = Math.max(value, computeWordValue(uppercase, wordScores));
				}
			}
			Collection<Relationship> questions = word.getRelationships(key);
			if (questions != null) {
				log("Checking deferred word (word, value, questions)", Level.FINER, word.getData(), value, questions.size());
				recordSetenceValues(word, word, questions, value, sentence, matches, processed, network, null);
			}
		}
	}
	
	/**
	 * Find the best match for the sentence.
	 * Traverse its words to find other sentences they are used in,
	 * and pick other sentence with the most words in common.
	 */
	@SuppressWarnings("unchecked")
	public Relationship findResponseMatch(Vertex sentence, Vertex previousResponse, Vertex input, Map<Vertex, Vertex> variables, Network network, float percentage) {
		if (!this.enableResponseMatch) {
			return null;
		}
		if (getReduceQuestions()) {
			// Reduce the sentence to lower case.
			network.checkReduction(sentence);
			Vertex reduction = sentence.getRelationship(Primitive.REDUCTION);
			if (reduction != null) {
				sentence = reduction;
			}
		}
		List<Vertex> wordRelations = sentence.orderedRelations(Primitive.WORD);
		if (wordRelations == null) {
			return null;
		}
		long startTime = System.currentTimeMillis();
		log("Searching for similar questions", Level.FINE);
		Map<Vertex, Integer> matches = new HashMap<Vertex, Integer>();
		Map<Vertex, Set<Vertex>> processed = new HashMap<Vertex, Set<Vertex>>();
		Map<Vertex, Integer> wordScores = null;
		long processTime = Math.min(MAX_PROCCESS_TIME, this.maxResponseMatchProcess);
		if (getBot().isDebugFine()) {
			log("Increasing processing time to allow debugging", Level.INFO, getBot().getDebugLevel());
			processTime = processTime * 20;
		}
		if (this.nlp >= 3) {
			wordScores = computeInputWordValues(sentence);
		}
		List<Vertex> compoundWords = Language.processCompoundWords(sentence.orderedRelationships(Primitive.WORD));
		// Record all keyword matches.
		addQuestionMatches(sentence, network, startTime, processTime, wordRelations, matches, processed, Primitive.KEYQUESTION, true, false, wordScores);
		addQuestionMatches(sentence, network, startTime, processTime, compoundWords, matches, processed, Primitive.KEYQUESTION, true, true, wordScores);
		Map<Vertex, Integer> keyWordsMatches = new HashMap<Vertex, Integer>(matches);
		addQuestionMatches(sentence, network, startTime, processTime, wordRelations, matches, processed, Primitive.QUESTION, true, false, wordScores);
		addQuestionMatches(sentence, network, startTime, processTime, compoundWords, matches, processed, Primitive.QUESTION, true, true, wordScores);
		addQuestionMatches(sentence, network, startTime, processTime, wordRelations, matches, processed, Primitive.QUESTION, false, false, wordScores);
		addQuestionMatches(sentence, network, startTime, processTime, compoundWords, matches, processed, Primitive.QUESTION, false, true, wordScores);
		if (this.learnGrammar) {
			addQuestionMatches(sentence, network, startTime, processTime, wordRelations, matches, processed, Primitive.SENTENCE, false, false, wordScores);
		}
		
		// Calculate fragment values.
		if (nlp >= 4) {
			recordFragmentValues(sentence, matches, network);
		}
		
		// Find the best match.
		int wordCount = wordRelations.size();
		double multiplier = (1.0 - percentage) * 15;
		int tooBig = (int) (wordCount * multiplier) + 2;
		int tooSmall = 0;
		Map.Entry<Vertex, Integer> bestMatch = null;
		int bestAbs = 0;
		Relationship bestResponse = null;
		Object[] best = new Object[3];
		best[0] = bestMatch;
		best[1] = bestAbs;
		best[2] = bestResponse;
		startTime = System.currentTimeMillis();
		log("Searching for best question match (min words, max words, match size)", Level.FINE, tooSmall, tooBig, matches.size());
		if (!matches.isEmpty()) {
			// Pre-compute data
			Vertex previousQuestionInput = input.getRelationship(Primitive.QUESTION);
			Vertex previousQuestion = null;
			if (previousQuestionInput != null) {
				previousQuestion = previousQuestionInput.getRelationship(Primitive.INPUT);
			}
			Vertex conversation = input.getRelationship(Primitive.CONVERSATION);
			Vertex currentTopic = null;
			if (conversation != null) {
				currentTopic = conversation.mostConscious(Primitive.TOPIC);
			}
			Set<String> questionWords = new HashSet<String>();
			Collection<Relationship> wordRelationships = sentence.getRelationships(Primitive.WORD);
			if (wordRelationships != null) {
				for (Relationship relationship : wordRelationships) {
					questionWords.add(relationship.getTarget().getDataValue().toLowerCase());
				}
			}
			// Search for best response
			// First check the best value match
			int bestValue = 0;
			int secondBestValue = 0;
			Map.Entry<Vertex, Integer> bestEntry = null;
			Map.Entry<Vertex, Integer> secondBestEntry = null;
			for (Map.Entry<Vertex, Integer> entry : matches.entrySet()) {
				if (entry.getValue() > bestValue && (sentence != entry.getKey())) {
					secondBestEntry = bestEntry;
					secondBestValue = bestValue;
					bestValue = entry.getValue();
					bestEntry = entry;
				} else if (entry.getValue() > secondBestValue && (sentence != entry.getKey())) {
					secondBestValue = entry.getValue();
					secondBestEntry = entry;
				}
			}
			if (bestEntry != null) {
				// First the best response.
				checkBetterMatch(bestEntry, keyWordsMatches, best, tooBig, tooSmall, wordCount,
						percentage, input, bestEntry.getKey(), sentence, previousResponse, true, false, previousQuestion, questionWords, currentTopic, variables, network);
			}
			if (best[0] == null && secondBestEntry != null) {
				// If no response, find the 2nd best response.
				checkBetterMatch(secondBestEntry, keyWordsMatches, best, tooBig, tooSmall, wordCount,
						percentage, input, secondBestEntry.getKey(), sentence, previousResponse, true, false, previousQuestion, questionWords, currentTopic, variables, network);
			}
			if (best[0] == null) {
				// If no response, find the best response checking each match.
				int count = 0;
				for (Map.Entry<Vertex, Integer> entry : matches.entrySet()) {
					if (entry == bestEntry || bestEntry == secondBestEntry) {
						// Don't check twice.
						continue;
					}
					long currentTime = System.currentTimeMillis();
					if ((currentTime - startTime) > processTime) {
						log("Process time limit reached (time, matches, processed)", Level.INFO, processTime, matches.size(), count);
						break;
					}
					count++;
					checkBetterMatch(entry, keyWordsMatches, best, tooBig, tooSmall, wordCount,
							percentage, input, entry.getKey(), sentence, previousResponse, true, false, previousQuestion, questionWords, currentTopic, variables, network);
				}
			}
		}
		bestMatch = (Map.Entry<Vertex, Integer>) best[0];
		bestAbs = (Integer) best[1];
		bestResponse= (Relationship) best[2];
		if (bestResponse == null) {
			log("No valid question match", Level.FINE);
			return null;
		}
		
		Map<Vertex, Integer> matchWordScores = null;
		if (this.nlp >= 3) {
			matchWordScores = computeInputWordValues(bestMatch.getKey());
		}
		
		int max = computeMaxSentenceValue(bestMatch.getKey(), bestMatch.getKey(), network, matchWordScores);
		// If % then ok.
		double required = (this.nlp >= 3) ? max * percentage * 0.9 : max * percentage * 0.8;
		// Recompute value using all words, as some words may not store relation to sentence.
		int matchValue = computeMaxSentenceValue(bestMatch.getKey(), sentence, network, matchWordScores);
		
		if (this.penalizeExtraneousWords) {
			matchValue -= Math.max(0, this.penalizeExtraneousWordsAmount * (sentence.getRelationships(Primitive.WORD).size() - bestMatch.getKey().getRelationships(Primitive.WORD).size()));
		}
		
		if (keyWordsMatches.containsKey(bestMatch.getKey())) {
			log("Question keyword match", Level.FINE);
		} else {
			log("Question best match (score, max score, required score, question)", Level.FINE, matchValue, max, required, bestMatch.getKey());
			if (matchValue < required) {
				log("Question bad match, insufficient score (score, required score, question)", Level.FINE, matchValue, required, bestMatch.getKey());
				this.lastResponseMetaId = null;
				return null;
			}
			if (matchValue * multiplier < max) {
				log("Question bad match, too generic (score, multiplier, value, match max, question)", Level.FINE, matchValue, multiplier, matchValue * multiplier, max, bestMatch.getKey());
				this.lastResponseMetaId = null;
				return null;
			}
		}
		log("Question match response", Level.FINE, bestResponse);
		if (max != 0) {
			this.confidence = Math.min(100, ((matchValue * 100) / max));
		}
		return bestResponse;
	}
	
	/**
	 * Check if this response match is better than the current best, and if it has a valid response.
	 */
	@SuppressWarnings("unchecked")
	public void checkBetterMatch(Map.Entry<Vertex, Integer> entry, Map<Vertex, Integer> keyWordsMatches, Object[] best, int tooBig, int tooSmall, int wordCount,
				float percentage, Vertex input, Vertex sentence, Vertex question, Vertex previousResponse, boolean cascade,
				boolean init, Vertex previousQuestion, Set<String> questionWords, Vertex currentTopic, Map<Vertex, Vertex> variables, Network network) {
		Map.Entry<Vertex, Integer> bestMatch = (Map.Entry<Vertex, Integer>) best[0];
		int bestAbs = (Integer) best[1];
		if (bestMatch != null && (bestMatch == entry || (entry.getValue() < bestMatch.getValue()))) {
			return;
		}
		if (question == entry.getKey() || entry.getKey().instanceOf(Primitive.PATTERN)) {
			return;
		}
		log("Processing (value, question)", Level.FINER, entry.getValue(), entry.getKey());
		Collection<Relationship> relationships = entry.getKey().getRelationships(Primitive.WORD);
		if (relationships == null) {
			return;
		}
		int entryWordCount = relationships.size();
		boolean hasKeyword = keyWordsMatches.containsKey(entry.getKey());
		// Ignore if too big or too small.
		if (hasKeyword || ((entryWordCount <= tooBig) && (entryWordCount >= tooSmall))) {
			int entryAbs = Math.abs(wordCount - entryWordCount);
			if (bestMatch == null || (entry.getValue() > bestMatch.getValue()) || (entryAbs < bestAbs)) {
				Relationship response = null;
				response = bestResponse(percentage, input, entry.getKey(), question, previousResponse,
						false, false, previousQuestion, questionWords, currentTopic, variables, network);
				if (response != null) {
					log("Better question match (value, question)", Level.FINE, entry.getValue(), entry.getKey());
					best[0] = entry;
					best[1] = entryAbs;
					best[2] = response;
				}
			}
		}
	}
	
	/**
	 * Find the best pattern that matches the sentence.
	 */
	public Relationship matchPattern(Vertex sentence, Vertex previousResponse, Vertex input, Map<Vertex, Vertex> variables, Network network, float percentage) {
		Vertex reduction = null;
		if (getReduceQuestions()) {
			// Reduce the sentence to lower case.
			network.checkReduction(sentence);
			reduction = sentence.getRelationship(Primitive.REDUCTION);
		}
		if (reduction == null) {
			reduction = sentence;
		}
		List<Relationship> wordRelations = reduction.orderedRelationships(Primitive.WORD);
		if (wordRelations == null) {
			return null;
		}
		long startTime = System.currentTimeMillis();
		Map<Vertex, Integer> matches = new HashMap<Vertex, Integer>();
		Map<Vertex, Integer> wordValues = null;
		long processTime = Math.min(MAX_PROCCESS_TIME, this.maxResponseMatchProcess);
		// Record all of the matches.
		List<Vertex> deferred = new ArrayList<Vertex>();
		if (this.nlp >= 3) {
			wordValues = computeInputWordValues(input);
		}
		for (Relationship wordRelation : wordRelations) {
			long currentTime = System.currentTimeMillis();
			if ((currentTime - startTime) > processTime) {
				log("Pattern search time limit reached", Level.INFO, processTime, matches.size());
				break;
			}
			Vertex word = wordRelation.getTarget();
			recordPatternValues(word, reduction, matches, network, deferred, wordValues);
			Vertex lowercase = null;
			if (!(word.getData() instanceof String)) {
				return null;
			}
			if (getTrackCase()) {
				// Old case over sensitivity code.
				String text = (String)word.getData();
				if (Utils.isCaps(text) || Utils.isCapitalized(text)) {
					lowercase = network.findByData(((String)word.getData()).toLowerCase());
				} else {
					lowercase = network.findByData(Utils.capitalize((String)word.getData()));
				}
				if ((lowercase != null) && (lowercase != word)) {
					recordPatternValues(lowercase, sentence, matches, network, deferred, wordValues);
					if (getCheckSynonyms()) {
						Collection<Relationship> synonyms = lowercase.getRelationships(Primitive.SYNONYM);
						if (synonyms != null) {
							for (Relationship synonym : synonyms) {
								recordPatternValues(synonym.getTarget(), sentence, matches, network, deferred, wordValues);
							}
						}
					}
				}
			}
			if (getCheckSynonyms()) {
				Collection<Relationship> synonyms = word.getRelationships(Primitive.SYNONYM);
				if (synonyms != null) {
					for (Relationship synonym : synonyms) {
						recordPatternValues(synonym.getTarget(), reduction, matches, network, deferred, wordValues);
					}
				}
			}
		}
		// Process words with lots of sentences last.
		for (Vertex word : deferred) {
			long currentTime = System.currentTimeMillis();
			if ((currentTime - startTime) > processTime) {
				log("Pattern search time limit reached", Level.INFO, processTime, matches.size());
				break;
			}
			recordPatternValues(word, reduction, matches, network, null, wordValues);
		}
		// Find the best match.
		Map.Entry<Vertex, Integer> bestMatch = null;
		Relationship bestResponse = null;
		boolean bestHasUnderscore = false;
		startTime = System.currentTimeMillis();
		log("Found possible patterns", Level.FINE, matches.size());
		if (!matches.isEmpty()) {
			// Pre-compute data
			Vertex previousQuestionInput = input.getRelationship(Primitive.QUESTION);
			Vertex previousQuestion = null;
			if (previousQuestionInput != null) {
				previousQuestion = previousQuestionInput.getRelationship(Primitive.INPUT);
			}
			Vertex conversation = input.getRelationship(Primitive.CONVERSATION);
			Vertex currentTopic = null;
			if (conversation != null) {
				currentTopic = conversation.mostConscious(Primitive.TOPIC);
			}
			Set<String> questionWords = new HashSet<String>();
			Collection<Relationship> wordRelationships = reduction.getRelationships(Primitive.WORD);
			if (wordRelationships != null) {
				for (Relationship relationship : wordRelationships) {
					questionWords.add(relationship.getTarget().getDataValue().toLowerCase());
				}
			}
			// Search for best pattern
			// First check the best value match to avoid extra pattern checks.
			int bestValue = 0;
			Map.Entry<Vertex, Integer> bestEntry = null;
			for (Map.Entry<Vertex, Integer> entry : matches.entrySet()) {
				if (entry.getValue() > bestValue && (sentence != entry.getKey())) {
					bestValue = entry.getValue();
					bestEntry = entry;
				}
			}
			if (bestEntry != null) {
				if ((sentence != bestEntry.getKey()) && (reduction !=  bestEntry.getKey())) {
					if (!evaluatePattern(bestEntry.getKey(), sentence, Primitive.WILDCARD, variables, network)) {
						log("Pattern does not match", Level.FINER, bestEntry.getKey(), bestEntry.getValue());
					} else {
						bestResponse = bestResponse(percentage, input, bestEntry.getKey(), sentence, previousResponse,
								false, false, previousQuestion, questionWords, currentTopic, variables, network);
						if (bestResponse != null) {
							log("Pattern match", Level.FINER, bestEntry.getKey(), bestEntry.getValue());
							bestMatch = bestEntry;
							bestHasUnderscore = bestEntry.getKey().hasRelationship(Primitive.WORD, Primitive.UNDERSCORE)
										|| bestEntry.getKey().hasRelationship(Primitive.WORD, Primitive.POUNDWILDCARD)
										|| bestEntry.getKey().hasRelationship(Primitive.TYPE, Primitive.PRECEDENCE);
						} else {
							log("Pattern response is not valid", Level.FINER, bestEntry.getKey());
						}
					}
				}
			}
			for (Map.Entry<Vertex, Integer> entry : matches.entrySet()) {
				long currentTime = System.currentTimeMillis();
				if ((currentTime - startTime) > processTime) {
					log("Pattern process time limit reached", Level.INFO, processTime, matches.size());
					break;
				}
				if ((sentence == entry.getKey()) || (reduction ==  entry.getKey()) || entry == bestEntry) {
					continue;
				}
				if (bestMatch == null) {
					if (!evaluatePattern(entry.getKey(), sentence, Primitive.WILDCARD, variables, network)) {
						log("Pattern does not match", Level.FINER, entry.getKey(), entry.getValue());
						continue;
					}
					bestResponse = bestResponse(percentage, input, entry.getKey(), sentence, previousResponse,
							false, false, previousQuestion, questionWords, currentTopic, variables, network);
					if (bestResponse != null) {
						log("Pattern match", Level.FINER, entry.getKey(), entry.getValue());
						bestMatch = entry;
						bestHasUnderscore = entry.getKey().hasRelationship(Primitive.WORD, Primitive.UNDERSCORE)
									|| entry.getKey().hasRelationship(Primitive.WORD, Primitive.POUNDWILDCARD)
									|| entry.getKey().hasRelationship(Primitive.TYPE, Primitive.PRECEDENCE);
					} else {
						log("Pattern response is not valid", Level.FINER, entry.getKey());
					}
				} else {
					boolean hasUnderscore = entry.getKey().hasRelationship(Primitive.WORD, Primitive.UNDERSCORE)
								|| entry.getKey().hasRelationship(Primitive.WORD, Primitive.POUNDWILDCARD)
								|| entry.getKey().hasRelationship(Primitive.TYPE, Primitive.PRECEDENCE);
					if (entry.getValue() > bestMatch.getValue() || (hasUnderscore && !bestHasUnderscore)) {
						if (!evaluatePattern(entry.getKey(), sentence, Primitive.WILDCARD, variables, network)) {
							log("Pattern does not match", Level.FINER, entry.getKey(), entry.getValue());
							continue;
						}
						Relationship response = bestResponse(percentage, input, entry.getKey(), sentence, previousResponse,
								false, false, previousQuestion, questionWords, currentTopic, variables, network);
						if (response != null) {
							log("Pattern match", Level.FINER, entry.getKey(), entry.getValue());
							bestResponse = response;
							bestMatch = entry;
							bestHasUnderscore = hasUnderscore;
						} else {
							log("Pattern response is not valid", Level.FINER, entry.getKey());
						}
					} else {
						log("Lower score", Level.FINER, entry.getValue(), bestMatch.getValue(), entry.getKey());
					}
				}
			}
		}
		if (bestResponse == null) {
			log("No valid pattern", Level.FINE);
			return null;
		}
		log("Pattern match", Level.FINE, bestMatch.getKey(), bestResponse);
		return bestResponse;
	}
	
	/**
	 * Attempt to understand the sentence using state machines.
	 */
	public Vertex processUnderstanding(Vertex input, Vertex sentence, float correctnessRequired, Map<Vertex, Vertex> variables, Network network) {
		//List<Vertex> compoundWords = null;
		boolean hasCompoundWords = false;
		Vertex compoundSentence = sentence;
		// Lookup and apply language rules.
		// Use read-only states to improve performance, as they will not be modified.
		Network readOnlyMemory = getBot().memory().getLongTermMemory();
		Vertex language = readOnlyMemory.createVertex(getPrimitive());
		List<Vertex> states = language.orderedRelations(Primitive.STATE);
		if (sentence.hasRelationship(Primitive.WORD)) {
			if (states == null || states.isEmpty()) {
				return null;
			}
			// Dead code: compound words now handled inside checkState()
			/*compoundWords = processCompoundWords(sentence.orderedRelationships(Primitive.WORD));
			// Check if the input has compound words.
			if (compoundWords != null) {
				// Sentence had compound words, so need to create new sentence and switch input.
				hasCompoundWords = true;
				compoundSentence = network.createInstance(Primitive.SENTENCE);
				for (int index = 0; index < compoundWords.size(); index++) {
					Vertex word = compoundWords.get(index);
					compoundSentence.addRelationship(Primitive.WORD, word, index);
				}
			}*/
		}
		List<Vertex> inputs = new ArrayList<Vertex>(1);
		inputs.add(input);
		//if (hasCompoundWords) {
		//	log("Sentence has compond words, processing compound words", Level.FINE, sentence);
		//}
		Vertex response = checkState(null, input, compoundSentence, hasCompoundWords, states, 0, 0, inputs, variables, new ArrayList<Vertex>(), correctnessRequired, network);
		/*if ((response == null) && hasCompoundWords) {
			// If had compound words, also check without them.
			variables = new HashMap<Vertex, Vertex>();
			SelfCompiler.addGlobalVariables(input, sentence, network, variables);
			log("Processing as non compound words", Level.FINE, sentence);
			response = checkState(null, input, sentence, false, states, 0, 0, inputs, variables, new ArrayList<Vertex>(), correctnessRequired, network);
		}*/
		if (response != null) {
			response = getWord(response, network);
			if (response != null) {
				log("Sentence understood", Level.FINE, sentence, response);
			} else {
				log("Sentence understood but no words", Level.FINE, sentence);
				response = null;
			}
		} else {
			log("Sentence not understood", Level.FINE, sentence);
		}
		return response;
	}
	
	/**
	 * Check for an active conversation flow and exclusively process the best next response.
	 */
	public Vertex processNext(Vertex input, Vertex sentence, Vertex conversation, Map<Vertex, Vertex> variables, Network network, float percentage) {
		if (conversation == null) {
			return null;
		}
		Vertex next = conversation.getRelationship(Primitive.NEXT);
		if (next == null) {
			return null;
		}
		log("Processing next", Level.FINE, next);
		return processNext(next, 0, input, sentence, conversation, variables, network, percentage);
	}
	
	/**
	 * Check for an active conversation flow and exclusively process the best next response.
	 */
	public Vertex processNext(Vertex next, int index, Vertex input, Vertex sentence, Vertex conversation, Map<Vertex, Vertex> variables, Network network, float percentage) {
		// Check exact match.
		Relationship relationship = next.getRelationship(Primitive.NEXT, sentence);
		if (relationship == null && getReduceQuestions()) {
			network.checkReduction(sentence);
			Collection<Relationship> reductions = sentence.getRelationships(Primitive.REDUCTION);
			if (reductions != null) {
				for (Relationship reduction : reductions) {
					relationship = next.getRelationship(Primitive.NEXT, reduction.getTarget());
					if (relationship != null) {
						break;
					}
				}
			}
		}
		Relationship response = null;
		if (relationship != null) {
			Vertex meta = relationship.getMeta();
			if (meta != null) {
				response = bestResponse(0.1f, input, meta, null, null, variables, network);
				if (response != null) {
					log("Next response exact match", Level.FINE, relationship.getTarget());
				}
			}
		}
		
		if (response == null) {
			// Check for pattern match.
			List<Relationship> relationships = next.orderedRelationships(Primitive.NEXT);
			if (relationships != null) {
				for (Relationship nextRelationship : relationships) {
					if (nextRelationship.getTarget().instanceOf(Primitive.PATTERN)) {
						if (evaluatePattern(nextRelationship.getTarget(), sentence, Primitive.WILDCARD, variables, network)) {
							response = bestResponse(0.1f, input, nextRelationship.getMeta(), sentence, null, variables, network);
							if (response != null) {
								log("Next response pattern match", Level.FINE, nextRelationship.getTarget());
								break;
							}
						}
					}
				}
			}
		}

		if (response == null) {
			// Check for best heuristic match.
			List<Relationship> relationships = next.orderedRelationships(Primitive.NEXT);
			if (relationships != null) {
				double multiplier = (1.0 - percentage) * 15;
				int bestMatch = 0;
				int bestMax = 0;
				Relationship bestNext = null;
				for (Relationship nextRelationship : relationships) {
					if (!nextRelationship.getTarget().instanceOf(Primitive.PATTERN)) {
						Vertex nextQuestion = nextRelationship.getTarget();
						Map<Vertex, Integer> matchWordScores = null;
						if (this.nlp >= 3) {
							matchWordScores = computeInputWordValues(nextQuestion);
						}
						int matchValue = computeMaxSentenceValue(nextQuestion, sentence, network, matchWordScores);
						if (matchValue > bestMatch) {
							int max = computeMaxSentenceValue(nextQuestion, nextQuestion, network, matchWordScores);
							double required = max * percentage * 0.9;
							if (matchValue >= required && (matchValue * multiplier >= max)) {
								bestMatch = matchValue;
								bestMax = max;
								bestNext = nextRelationship;
							}
						}
					}
				}
				if (bestNext != null) {
					response = bestResponse(0.1f, input, bestNext.getMeta(), sentence, null, variables, network);
					if (response != null) {
						log("Next response match (match, required)", Level.FINE, bestNext.getTarget(), bestMatch, bestMax);
						if (bestMax != 0) {
							this.confidence = Math.min(100, ((bestMatch * 100) / bestMax));
						}
					}
				}
			}
		}

		// Check for keyword match.
		if (response == null) {
			// Check for pattern match.
			List<Relationship> relationships = next.orderedRelationships(Primitive.NEXT);
			if (relationships != null) {
				for (Relationship nextRelationship : relationships) {
					if (nextRelationship.hasMeta()) {
						Collection<Relationship> responseRelationships = nextRelationship.getMeta().getRelationships(Primitive.RESPONSE);
						if (responseRelationships != null) {
							for (Relationship responseRelationship : responseRelationships) {
								if (responseRelationship.hasMeta() && responseRelationship.getMeta().hasRelationship(Primitive.KEYWORD)) {
									response = bestResponse(0.1f, input, nextRelationship.getMeta(), sentence, null, variables, network);
									if (response != null) {
										log("Next keyword response", Level.FINE, nextRelationship.getTarget());
										break;
									}
								}
							}
						}
					}
				}
			}
		}
		
		if (response != null) {
			// Check for next next, or clear conversation flow.
			if (response.getMeta() != null) {
				if (!response.getMeta().hasRelationship(Primitive.NEXT)) {
					log("Clearing next", Level.FINE, sentence);
					conversation.internalRemoveRelationships(Primitive.NEXT);
				}
			} else {
				log("Clearing next", Level.FINE, sentence);
				conversation.internalRemoveRelationships(Primitive.NEXT);
			}
			checkResponseMeta(response, conversation, variables, network);
			return response.getTarget();
		}
	
		// Check for default.
		relationship = next.getRelationship(Primitive.NEXT, Primitive.DEFAULT);
		if (relationship != null) {
			Vertex meta = relationship.getMeta();
			if (meta != null) {
				response = bestResponse(0.1f, input, meta, null, null, variables, network);
				if (response != null) {
					log("Next default response", Level.FINE, response);
					checkResponseMeta(response, conversation, variables, network);
					return response.getTarget();
				}
			}
		}

		// Check parent.
		Vertex parent = next.getRelationship(Primitive.PARENT);
		if (parent != null && index < MAX_DEPTH) {
			log("Processing next parent", Level.FINE, parent);
			index++;
			Vertex parentResponse = processNext(parent, index, input, sentence, conversation, variables, network, percentage);
			if (parentResponse != null) {
				return parentResponse;
			}
		}
		
		log("Missing next", Level.FINE, sentence);
		return null;
	}
	
	/**
	 * Transform the list of words, into a list of compound words.
	 */
	public static List<Vertex> processCompoundWords(List<Relationship> words) {
		List<Vertex> compoundedWords = new ArrayList<Vertex>(words.size());
		for (int index = 0; index < words.size(); index++) {
			Vertex word = words.get(index).getTarget();
			// Get the compound words for each word.
			Collection<Relationship> compoundWords = word.getRelationships(Primitive.COMPOUND_WORD);
			// Also check lowercase.
			if (compoundWords == null && (word.getData() instanceof String)) {
				Vertex lowercase = word.getNetwork().createVertex(((String)word.getData()).toLowerCase());
				if (word != lowercase) {
					compoundWords = lowercase.getRelationships(Primitive.COMPOUND_WORD);
				}
			}
			if (compoundWords == null) {
				// No compound words, so just add the single word.
				compoundedWords.add(word);
			} else {
				boolean found = false;
				// Check each compound word, to see if it follows the word.
				for (Relationship compundWord : compoundWords) {
					int compoundIndex = 1;
					List<Relationship> wordParts = compundWord.getTarget().orderedRelationships(Primitive.WORD);
					if ((wordParts == null) || (wordParts.size() <= 1)) {
						// Avoid corrupt data (from forgetfullness).
						break;
					}
					// Check that all the compound words are contained.
					while (((index + compoundIndex) < words.size()) && (compoundIndex < wordParts.size())) {
						Vertex wordPart = wordParts.get(compoundIndex).getTarget();
						Vertex nextWord = words.get(index + compoundIndex).getTarget();
						// If the words don't match, then stop checking and check the next compound word.
						if (!wordPart.equalsIgnoreCase(nextWord)) {
							break;
						}
						compoundIndex++;
					}
					// If the compound word is a match, the add it.
					// TODO: What if multiple compound words match?  Take longest, or most conscious?
					if (compoundIndex == wordParts.size()) {
						found = true;
						compoundedWords.add(compundWord.getTarget());
						index = index + compoundIndex - 1;
						break;
					}
				}
				/*if (!found) {
					for (Relationship compundWord : compoundWords) {
						int compoundIndex = 1;
						List<Relationship> wordParts = compundWord.getTarget().orderedRelationships(Primitive.WORD);
						if ((wordParts == null) || (wordParts.size() <= 1)) {
							// Avoid corrupt data (from forgetfullness).
							break;
						}
						// Check that all the compound words are contained.
						while (((index + compoundIndex) < words.size()) && (compoundIndex < wordParts.size())) {
							Vertex wordPart = wordParts.get(compoundIndex).getTarget();
							Vertex nextWord = words.get(index + compoundIndex).getTarget();
							// If the words don't match, then stop checking and check the next compound word.
							if (!wordPart.equalsIgnoreCase(nextWord)) {
								break;
							}
							compoundIndex++;
						}
						// If the compound word is a match, the add it.
						// TODO: What if multiple compound words match?  Take longest, or most conscious?
						if (compoundIndex == wordParts.size()) {
							found = true;
							compoundedWords.add(compundWord.getTarget());
							index = index + compoundIndex - 1;
							break;
						}
					}
				}*/
				if (!found) {
					compoundedWords.add(word);
				}
			}
		}
		if (words.size() == compoundedWords.size()) {
			return null;
		}
		return compoundedWords;
	}

	/**
	 * Apply each state machine vertex to the sentence of words.
	 * If the state machine finds a match, it will record the real vertices mapped to the state machine variables.
	 */
	public Vertex checkState(Vertex root, Vertex input, Vertex sentence, boolean hasCompoundWords, List<Vertex> states, int index, int recurse, List<Vertex> inputs, Map<Vertex, Vertex> variables, List<Vertex> stateStack, float correctnessRequired, Network network) {
		if (states == null || this.abort) {
			return null;
		}
		
		// Dead code: Compound words are now handled inside checkState
		//if (hasCompoundWords && root != null && root.hasRelationship(Primitive.LANGUAGE, Primitive.AIML)) {
			// AIML does not support compound words... AIML now requires compound words for sets
			//return null;
		//}
		if (this.startTime == 0) {
			this.startTime = System.currentTimeMillis();
		}
		long processTime = Math.min(this.maxStateProcess, MAX_PROCCESS_TIME);
		if (getBot().isDebugFiner()) {
			processTime = processTime * 10;
		}
		Vertex state = null;
		try {
			while (index <= inputs.size()) {
				Vertex currentInput = null;
				if (index < inputs.size()) {
					currentInput = inputs.get(index);
				}
				// Check each state machine for a match.
				for (ListIterator<Vertex> iterator = states.listIterator(); iterator.hasNext(); ) {
					if (this.abort) {
						return null;
					}
					if ((System.currentTimeMillis() - this.startTime) > processTime) {
						log("State processing time limit reached", Level.WARNING, processTime, root, state);
						this.abort = true;
						return null;
					}
					if (stateStack.size() > MAX_STACK) {
						log("State stack overflow", Level.WARNING, MAX_STACK, root, state);
						this.abort = true;
						return null;
					}
					// Record local variables so they can be discarded if there is no match.
					Map<Vertex, Vertex> localVariables = new HashMap<Vertex, Vertex>(variables);
					state = iterator.next();
					Vertex lastState = null;
					if (!stateStack.isEmpty()) {
						lastState = stateStack.get(stateStack.size() - 1);
					}
					stateStack.add(state);
					Vertex newRoot = root;
					Vertex decompiled = SelfDecompiler.getDecompiler().decompileState(state, state.getNetwork());
					if (root == null) {
						newRoot = state;
						log("SCRIPT", Level.FINE, state, currentInput);
						if (!this.loadedStates.contains(state.getId()) && (stateStack.size() == 1) && this.loadedStates.size() < 20) {
							this.loadedStates.add(state.getId());
							SelfCompiler.getCompiler().fastLoadChildren(state);
						}
						// Record root state variable.
						localVariables.put(network.createVertex(Primitive.STATE), decompiled);
					} else {
						log("STATE", Level.FINER, state, currentInput);
					}
					Collection<Relationship> equations = decompiled.orderedRelationships(Primitive.DO);
					Vertex response = null;
					if (equations != null) {
						for (Relationship equationRelationship : equations) {
							if (this.abort) {
								return null;
							}
							if ((System.currentTimeMillis() - this.startTime) > processTime) {
								log("State processing time limit reached", Level.WARNING, processTime, root, state);
								this.abort = true;
								return null;
							}
							Vertex equation = equationRelationship.getTarget();
							if (equation.instanceOf(Primitive.CASE) || equation.hasRelationship(Primitive.PATTERN)) {

								List<Relationship> fors = equation.orderedRelationships(Primitive.FOR);
								if (fors != null) {
									fors = null;
								}
								Vertex pattern = equation.getRelationship(Primitive.PATTERN);
								Vertex caseVariable = null;
								Boolean match = false;
								boolean anyOrNone = false;
								boolean emptyMatch = false;
								boolean poundstar = false;
								if (pattern != null) {
									log("PATTERN", Level.FINER, pattern, currentInput);
									caseVariable = pattern;
									// Avoid checking pattern again if in recursive state.
									if (lastState != state) {
										match = evaluatePattern(pattern, sentence, Primitive.WILDCARD, localVariables, network);
									}
								} else {
									caseVariable = equation.getRelationship(Primitive.CASE);
									if (caseVariable != null && (!caseVariable.isVariable())) {
										if ((caseVariable.instanceOf(Primitive.EXPRESSION) || caseVariable.instanceOf(Primitive.EQUATION))) {
											caseVariable = SelfInterpreter.getInterpreter().evaluateExpression(caseVariable, localVariables, network, this.startTime, processTime, 0);
										} else if (caseVariable.instanceOf(Primitive.FUNCTION)) {
											caseVariable = SelfInterpreter.getInterpreter().evaluateFunction(caseVariable, localVariables, network, this.startTime, processTime, 0);
										}
									}
									if (caseVariable != null) {
										poundstar = caseVariable.getName() != null && (caseVariable.getName().equals("poundstar"));
										anyOrNone = (caseVariable.getName() != null && (caseVariable.getName().equals("poundstar") || caseVariable.getName().equals("hatstar")))
												|| ((caseVariable.instanceOf(Primitive.ARRAY) || caseVariable.instanceOf(Primitive.LIST))
															&& !caseVariable.hasRelationship(Primitive.TYPE, Primitive.REQUIRED));
									}
									if (poundstar && !equation.hasRelationship(Primitive.GOTO, state)) {
										emptyMatch = true;
										match = Boolean.TRUE;
										log("EMPTY CASE", Level.FINER, caseVariable, currentInput);
									} else if (index >= inputs.size()) {
										if (anyOrNone) {
											emptyMatch = true;
											match = Boolean.TRUE;
											log("EMPTY CASE", Level.FINER, caseVariable, currentInput);
										} else {
											continue;
										}
									} else {
										currentInput = inputs.get(index);
										log("CASE", Level.FINER, caseVariable, currentInput);
										if (caseVariable != null) {
											// First check for compound words.
											Collection<Relationship> compoundWords = currentInput.getRelationships(Primitive.COMPOUND_WORD);
											if (compoundWords == null && (currentInput.getData() instanceof String)) {
												// Check case.
												Vertex lowercase = network.createVertex(((String)currentInput.getData()).toLowerCase());
												compoundWords = lowercase.getRelationships(Primitive.COMPOUND_WORD);
											}
											if (compoundWords != null) {
												for (Relationship compoundWord :  compoundWords) {
													int checkIndex = index;
													boolean allMatch = true;
													Vertex currentWord = currentInput;
													for (Vertex wordFragment : compoundWord.getTarget().orderedRelations(Primitive.WORD)) {
														if (wordFragment.getData() instanceof String && currentWord.getData() instanceof String) {
															if (((String)wordFragment.getData()).equalsIgnoreCase((String)currentWord.getData())) { 
																checkIndex++;
																if (checkIndex < inputs.size()) {
																	currentWord = inputs.get(checkIndex);
																	if (currentWord == null) {
																		break;
																	}
																}
															} else {
																allMatch = false;
																currentWord = currentInput;
																break;
															}
														}
													}
													if (allMatch) {
														match = caseVariable.matches(compoundWord.getTarget(), localVariables);
														if (match == Boolean.TRUE) {
															currentInput = compoundWord.getTarget();
															index = checkIndex - 1;
															break; // What about checking others when rest of states do not match? order by word length?
														}
													}
												}
											}
											if (match != Boolean.TRUE) {
												match = caseVariable.matches(currentInput, localVariables);
											}
											if (match != Boolean.TRUE && currentInput.instanceOf(Primitive.WORD) && currentInput.getData() instanceof String) {
												// Also check lower case word.
												Vertex lowercase = network.createVertex(((String)currentInput.getData()).toLowerCase());
												Boolean lowerMatch = null;
												if (lowercase != currentInput) {
													lowerMatch = caseVariable.matches(lowercase, localVariables);
												}
												if (lowerMatch != null) {
													match = lowerMatch;
												}
											}
											if (match != Boolean.TRUE && caseVariable.isPrimitive() && currentInput != null && currentInput.instanceOf(Primitive.WORD)) {
												// Also allow primitive to match words meaning.
												if (currentInput.hasRelationship(Primitive.MEANING, caseVariable)) {
													match = true;
												}
											}
											if (currentInput == input) {
												Vertex sentenceVariable = caseVariable.getRelationship(Primitive.INPUT);
												if (sentenceVariable != null) {
													localVariables.put(sentenceVariable, sentence);
												}
											}
										}
										if (match != Boolean.TRUE && anyOrNone) {
											emptyMatch = true;
											match = Boolean.TRUE;
										}
									}
								}
								if (match == Boolean.TRUE) {
									Vertex topic = equation.getRelationship(Primitive.TOPIC);
									if (topic != null) {
										match = false;
										Vertex conversation = input.getRelationship(Primitive.CONVERSATION);
										Vertex currentTopic = null;
										if (conversation != null) {
											currentTopic = conversation.getRelationship(Primitive.TOPIC);
										}
										log("Checking topic", Level.FINER, topic, currentTopic);
										if (currentTopic != null) {
											match = evaluatePattern(topic, currentTopic, Primitive.TOPICWILDCARD, localVariables, network);
										}
									}
									Vertex that = equation.getRelationship(Primitive.THAT);
									if (match && (that != null)) {
										match = false;
										Vertex questionInput = input.getRelationship(Primitive.QUESTION);
										log("Checking that", Level.FINER, that, questionInput);
										if (questionInput != null) {
											Vertex question = questionInput.getRelationship(Primitive.INPUT);
											if (question != null) {
												match = evaluatePattern(that, question, Primitive.THATWILDCARD, localVariables, network);
												if (!match) {
													// Check if the question was a paragraph, then need to check eat part.
													network.createParagraph(question);
													if (question.instanceOf(Primitive.PARAGRAPH)) {
														Collection<Relationship> relationships = question.getRelationships(Primitive.SENTENCE);
														if (relationships != null) {
															for (Relationship relationship : relationships) {
																match = evaluatePattern(that, relationship.getTarget(), Primitive.THATWILDCARD, localVariables, network);
																if (match) {
																	break;
																}
															}
														}
													}
												}
											}
										}
									}
								}
								// Check if the word matches the state machine variable.
								if (match == Boolean.TRUE) {
									if (pattern != null) {
										log("PATTERN MATCH", Level.FINE, pattern, currentInput);
									} else {
										log("CASE MATCH", Level.FINER, caseVariable, currentInput);
									}
									// Check next state/word recursively.
									Vertex template = equation.getRelationship(Primitive.TEMPLATE);
									if (template != null) {
										if (template.instanceOf(Primitive.EQUATION) || template.instanceOf(Primitive.EXPRESSION)
													|| template.instanceOf(Primitive.FUNCTION)) {
											response = evaluateAnswerResponse(template, state, localVariables, network);
											if (response != null) {
												return response;
											}
										} else {
											if (template.getNetwork() != network) {
												template = network.createVertex(template);
											}
											if ((template != null) && template.instanceOf(Primitive.LABEL)) {
												template = template.mostConscious(Primitive.RESPONSE);
											}
											if (template.instanceOf(Primitive.FORMULA)) {
												log("Template is template formula", Level.FINE, template);
												response = evaluateFormula(template, localVariables, network);
												if (response == null) {
													log("Template formula cannot be evaluated", Level.FINE, template);
												} else {
													return response;
												}
											} else if (template.instanceOf(Primitive.EQUATION) || template.instanceOf(Primitive.EXPRESSION)
													|| template.instanceOf(Primitive.FUNCTION)) {
												response = evaluateAnswerResponse(template, state, localVariables, network);
												if (response != null) {
													return response;
												}
											} else {
												return template;
											}
										}
									}
									// Check for AS, maps case variable into target variable.
									Vertex as = equation.getRelationship(Primitive.AS);
									if (as != null) {
										localVariables.put(as, localVariables.get(caseVariable));
									}
									List<Vertex> gotoStates = equation.orderedRelations(Primitive.GOTO);
									if (gotoStates != null) {
										if ((gotoStates.size() == 1) && gotoStates.get(0).is(Primitive.RETURN)) {
											log("CASE RETURN", Level.FINER);
											return null;
										}
										List<Relationship> arguments = equation.orderedRelationships(Primitive.FOR);
										List<Vertex> newInputs = inputs;
										int newIndex = index + 1;
										if (arguments != null && arguments.size() >= 2) {
											Vertex variable = arguments.get(1).getTarget();
											Vertex value = arguments.get(0).getTarget();
											newInputs = new ArrayList<Vertex>();
											Vertex variableValue = SelfInterpreter.getInterpreter().evaluateExpression(variable, localVariables, network, this.startTime, processTime, 0);
											List<Relationship> relationships = variableValue.orderedRelationships(value);
											if (relationships != null) {
												for (Relationship result : relationships) {
													newInputs.add(result.getTarget());
												}
											}
											newIndex = 0;
										}
										log("CASE GOTO STATE", Level.FINER, gotoStates);
										if (!emptyMatch) {
											response = checkState(newRoot, input, sentence, hasCompoundWords, gotoStates, newIndex, recurse, newInputs, localVariables, stateStack, correctnessRequired, network);
										}
										if (response != null) {
											return response;
										}
										if (anyOrNone && (newIndex == index + 1) &&  !gotoStates.contains(state)) {
											// Also check matching nothing.
											response = checkState(newRoot, input, sentence, hasCompoundWords, gotoStates, index, recurse, newInputs, localVariables, stateStack, correctnessRequired, network);
										}
										if (response != null) {
											return response;
										}
									}
								} else {
									log("Case not matched", Level.FINER, caseVariable, currentInput);
								}
							} else if (equation.instanceOf(Primitive.DO)) {
								log("DO", Level.FINER, state, currentInput);
								SelfInterpreter.getInterpreter().evaluateExpression(equation.getRelationship(Primitive.DO), localVariables, network, this.startTime, processTime, 0);
								localVariables.remove(network.createVertex(Primitive.RETURN));
							} else if (equation.instanceOf(Primitive.GOTO)) {
								// May require terminal state.
								if (!equation.hasRelationship(Primitive.FINALLY) || (index >= inputs.size())) {
									List<Vertex> gotoStates = equation.orderedRelations(Primitive.GOTO);
									log("GOTO", Level.FINER, state, gotoStates);
									List<Vertex> arguments = equation.orderedRelations(Primitive.ARGUMENT);
									if (arguments == null || arguments.isEmpty()) {
										response = checkState(newRoot, input, sentence, hasCompoundWords, gotoStates, index, recurse, inputs, localVariables, stateStack, correctnessRequired, network);
									} else {
										List<Vertex> newInputs = new ArrayList<Vertex>();
										for (Vertex argument : arguments) {
											newInputs.add(SelfInterpreter.getInterpreter().evaluateExpression(argument, localVariables, network, this.startTime, processTime, 0));
										}
										response = checkState(newRoot, input, sentence, hasCompoundWords, gotoStates, 0, recurse, newInputs, localVariables, stateStack, correctnessRequired, network);
									}
									if (response != null) {
										return response;
									}
								}
							} else if (equation.instanceOf(Primitive.PUSH)) {
								Vertex argument = equation.getRelationship(Primitive.ARGUMENT);
								argument = SelfInterpreter.getInterpreter().evaluateExpression(argument, localVariables, network, this.startTime, processTime, 0);
								log("PUSH", Level.FINER, state, argument);
								inputs.add(index, argument);
							} else if (equation.instanceOf(Primitive.RETURN)) {
								log("RETURN", Level.FINER, state, currentInput);
								List<Vertex> lastStates = new ArrayList<Vertex>(1);
								Vertex current = stateStack.remove(stateStack.size() - 1);
								Vertex last = stateStack.remove(stateStack.size() - 1);
								lastStates.add(last);
								Map<Vertex, Vertex> newVariables = localVariables;
								// Allow the return to clear the variable stack, or keep specific variables.
								Collection<Relationship> arguments = equation.getRelationships(Primitive.ARGUMENT);
								if (arguments != null) {
									newVariables = new HashMap<Vertex, Vertex>(variables);
									for (Relationship variable : arguments) {
										newVariables.put(variable.getTarget(), localVariables.get(variable.getTarget()));
									}
								}
								// A return value is used as the next state value, (reruns the previous state with the new value).
								Vertex value = equation.getRelationship(Primitive.RETURN);
								if (value != null && value.isVariable()) {
									value = localVariables.get(value);
									if (value == null) {
										value = network.createVertex(Primitive.NULL);
									}
								}
								if (value != null) {
									List<Vertex> newInputs = new ArrayList<Vertex>(inputs);
									if (currentInput == null) {
										newInputs.add(value);
									} else {
										index++;
										newInputs.add(index, value);
									}
									recurse++;
									if (recurse > MAX_DEPTH) {
										stateStack.add(last);
										stateStack.add(current);
										throw new SelfExecutionException(current, "Max recursive state execution");
									}
									response = checkState(newRoot, input, sentence, hasCompoundWords, lastStates, index++, recurse, newInputs, newVariables, stateStack, correctnessRequired, network);
								} else {
									response = checkState(newRoot, input, sentence, hasCompoundWords, lastStates, index++, recurse, inputs, newVariables, stateStack, correctnessRequired, network);
								}
								if (response != null) {
									return response;
								}
								stateStack.add(last);
								stateStack.add(current);
							}
						}
					}
					// Ignore punctuation.
					if (currentInput != null && currentInput.instanceOf(Primitive.PUNCTUATION)) {
						response = checkState(newRoot, input, sentence, hasCompoundWords, states, index+1, recurse, inputs, localVariables, stateStack, correctnessRequired, network);
						if (response != null) {
							return response;
						}
					}
					
					// Check if the entire sentence was matched.
					if ((index >= inputs.size())) {
						Vertex[] pair = bestAnswer(correctnessRequired, decompiled, localVariables, input, sentence, network);
						if (pair != null) {
							response = pair[1];
							log("ANSWER", Level.FINER, state, response);
							Vertex quotient = pair[0];
							setLastStateMachine(root);
							setLastState(state);
							setLastQuotient(quotient);
							return response;
						}
					}
					stateStack.remove(stateStack.size() - 1);
					log("STATE DONE", Level.FINER, state, currentInput);
				}
				index++;
				return null;
			}
		} catch (Throwable failure) {
			log("Error occured in processing state", Level.WARNING, root, state);
			log(failure);
			return null;
		} finally {
			if (root == null) {
				log("State processing time", Level.FINE, System.currentTimeMillis() - startTime);
			}
		}
		return null;
	}
	
	/**
	 * Return the best response to the question, taking into account the input history.
	 */
	public Vertex[] bestAnswer(float percentage, Vertex state, Map<Vertex, Vertex> localVariables, Vertex input, Vertex sentence, Network network) {
		// Check if any response has the same previous question.
		Vertex previousQuestionInput = input.getRelationship(Primitive.QUESTION);
		Vertex previousQuestion = null;
		if (previousQuestionInput != null) {
			previousQuestion = previousQuestionInput.getRelationship(Primitive.INPUT);
			if (previousQuestion != null) {
				Collection<Relationship> quotients = state.getRelationships(Primitive.QUOTIENT);
				if (quotients != null) {
					Vertex bestResponse = null;
					Relationship best = null;
					Relationship bestWithPrevious = null;
					for (Relationship quotient : quotients) {
						if (quotient.getCorrectness() >= percentage) {
							Vertex meta = quotient.getMeta();
							if (meta != null) {
								if (hasPrevious(meta, previousQuestion, state, localVariables, network)) {
									if ((bestWithPrevious == null) || (quotient.getCorrectness() > bestWithPrevious.getCorrectness())) {
										Vertex response = evaluateAnswerResponse(quotient.getTarget(), state, localVariables, network);
										if ((response == null) || sentence.hasInverseRelationship(Primitive.RESPONSE, response)
													|| sentence.hasAnyAssociatedInverseRelationship(Primitive.REDUCTION, response, Primitive.RESPONSE)
													|| sentence.hasAnyAssociatedInverseRelationship(Primitive.SYNONYM, response, Primitive.RESPONSE)) {
											continue;
										} else {
											bestWithPrevious = quotient;
											bestResponse = response;
										}
									}
								}
								Vertex label = previousQuestion.getRelationship(Primitive.LABEL);
								if (meta.hasInverseRelationship(Primitive.PREVIOUS, previousQuestion)
											|| (label != null && meta.hasInverseRelationship(Primitive.PREVIOUS, label))
											|| meta.hasRelationship(Primitive.REQUIRE, Primitive.PREVIOUS)) {
									continue;
								}
							}
							if ((bestWithPrevious == null) && ((best == null) || (quotient.getCorrectness() > best.getCorrectness()))) {
								Vertex response = evaluateAnswerResponse(quotient.getTarget(), state, localVariables, network);
								if ((response == null) || sentence.hasInverseRelationship(Primitive.RESPONSE, response)
											|| sentence.hasAnyAssociatedInverseRelationship(Primitive.REDUCTION, response, Primitive.RESPONSE)
											|| sentence.hasAnyAssociatedInverseRelationship(Primitive.SYNONYM, response, Primitive.RESPONSE)) {
									continue;
								} else {
									best = quotient;
									bestResponse = response;
								}
							}
						}
					}
					if (bestWithPrevious != null) {
						Vertex[] pair = new Vertex[2];
						pair[0] = bestWithPrevious.getTarget();
						pair[1] = bestResponse;
						return pair;
					}
					if (bestResponse == null) {
						return null;
					}
					Vertex[] pair = new Vertex[2];
					pair[0] = best.getTarget();
					pair[1] = bestResponse;
					return pair;
				}
			}
		}
		Vertex quotient = state.mostConscious(Primitive.QUOTIENT, percentage);
		if (quotient == null) {
			return null;
		}
		Vertex response = evaluateAnswerResponse(quotient, state, localVariables, network);
		if ((response == null) || sentence.hasInverseRelationship(Primitive.RESPONSE, response)
				|| sentence.hasAnyAssociatedInverseRelationship(Primitive.REDUCTION, response, Primitive.RESPONSE)
				|| sentence.hasAnyAssociatedInverseRelationship(Primitive.SYNONYM, response, Primitive.RESPONSE)) {
			quotient = state.nextMostConscious(Primitive.QUOTIENT, quotient, percentage);
			response = evaluateAnswerResponse(quotient, state, localVariables, network);
			if ((response == null) || sentence.hasInverseRelationship(Primitive.RESPONSE, response)
						|| sentence.hasAnyAssociatedInverseRelationship(Primitive.REDUCTION, response, Primitive.RESPONSE)
						|| sentence.hasAnyAssociatedInverseRelationship(Primitive.SYNONYM, response, Primitive.RESPONSE)) {
				return null;
			}
		}
		Vertex[] pair = new Vertex[2];
		pair[0] = quotient;
		pair[1] = response;
		return pair;
	}
	
	/**
	 * Return the best response to the question, taking into account the input history.
	 */
	public boolean hasPrevious(Vertex meta, Vertex sentence, Vertex state, Map<Vertex, Vertex> localVariables, Network network) {
		Collection<Relationship> relationship = meta.getRelationships(Primitive.PREVIOUS);
		if (relationship == null) {
			return false;
		}
		if (meta.hasRelationship(Primitive.PREVIOUS, sentence)) {
			return true;
		}
		Vertex label = sentence.getRelationship(Primitive.LABEL);
		if (label != null && meta.hasRelationship(Primitive.PREVIOUS, label)) {
			return true;
		}
		for (Relationship previous : relationship) {
			if (previous.getTarget().instanceOf(Primitive.FORMULA)) {
				log("Previous is template formula", Level.FINE, previous.getTarget());
				Vertex result = evaluateFormula(previous.getTarget(), localVariables, network);
				if (result == null) {
					log("Template formula cannot be evaluated", Level.FINE, previous.getTarget());					
				} else if (result.equals(sentence)) {
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * Evaluate the quotient and possible formula response.
	 */
	public Vertex evaluateAnswerResponse(Vertex answer, Vertex state, Map<Vertex, Vertex> localVariables, Network network) {		
		Vertex response = null;
		if (answer != null) {
			long processTime = Math.min(this.maxStateProcess, MAX_PROCCESS_TIME);
			if (getBot().isDebugFiner()) {
				processTime = processTime * 10;
			}
			log("Evaluating answer", Level.FINE, answer, state);
			response = SelfInterpreter.getInterpreter().evaluateExpression(answer, localVariables, network, this.startTime, processTime, 0);
			localVariables.remove(network.createVertex(Primitive.RETURN));
			log("Answer result", Level.FINE, response);
			// Check for formula and transpose
			if ((response != null) && response.instanceOf(Primitive.LABEL)) {
				response = response.mostConscious(Primitive.RESPONSE);
			}
			if ((response != null) && response.instanceOf(Primitive.FORMULA)) {
				log("Answer is template formula", Level.FINE, response);
				response = evaluateFormula(response, localVariables, network);
				if (response == null) {
					log("Template formula cannot be evaluated", Level.FINE, response);
				}
			} else if ((response != null) && !response.hasData() && (response.instanceOf(Primitive.SENTENCE) || response.instanceOf(Primitive.FRAGMENT))
						&& !response.instanceOf(Primitive.PARAGRAPH)) {
				response = createSentenceText(response, network);
			}
			if ((response != null) && response.is(Primitive.NULL)) {
				response = null;
			}
		}
		return response;
	}
	
	/**
	 * Create the text for the sentence.
	 */
	public Vertex createSentenceText(Vertex vertex, Network network) {
		StringWriter writer = new StringWriter();
		List<Relationship> relationships = vertex.orderedRelationships(Primitive.WORD);
		Vertex previous = network.createVertex(Primitive.NULL);
		boolean inferWhitespace = !vertex.hasRelationship(Primitive.TYPE, Primitive.SPACE) && !vertex.hasRelationship(Primitive.WORD, Primitive.SPACE);
		boolean caseSensitive = !this.fixFormulaCase || vertex.hasRelationship(Primitive.TYPE, Primitive.CASESENSITVE);
		if (relationships != null) {
			boolean first = true;
			String last = null;
			for (int index = 0; index < relationships.size(); index++) {
				Relationship relationship = relationships.get(index);
				Vertex next = network.createVertex(Primitive.NULL);
				if (relationships.size() > (index + 1)) {
					next = relationships.get(index + 1).getTarget();
				}
				Vertex word = relationship.getTarget();
				if (word.is(Primitive.SPACE)) {
					writer.write(" ");
					continue;
				}
				if (!(word.getData() instanceof String)) {
					word = getWordFollowing(word, relationship, previous, next, network);
				}
				String text = word.printString();
				if (!caseSensitive) {
					if (first) {
						text = Utils.capitalize(text);
					} else if (text.equals("i") || text.equals("I")) {
						text = text.toUpperCase();
					} else {
						boolean isName = word.instanceOf(Primitive.NAME);
						boolean isNoun = word.instanceOf(Primitive.NOUN);
						boolean isVerb = word.instanceOf(Primitive.VERB) || word.instanceOf(Primitive.ARTICLE)
								|| word.instanceOf(Primitive.PRONOUN) || word.instanceOf(Primitive.QUESTION);
						if (isName && !isVerb) {
							text = Utils.capitalize(text);
						} else if (isVerb && !isName && !isNoun) {
							text = text.toLowerCase();
						}
					}
				}
				if (inferWhitespace) {
					if (!first && !text.equals("'") && !text.equals(")") && !"(".equals(last) && !"'".equals(last) && !text.equals("^") && !"^".equals(last) && (!(word.instanceOf(Primitive.PUNCTUATION)))) {
						writer.write(" ");
					}
				}
				writer.write(text);
				first = text.equals(".");
				previous = word;
				last = text;
			}
		}
		String text = writer.toString();
		Vertex sentence = network.createSentence(text.trim(), true);
		if (vertex.hasRelationship(Primitive.TYPE, Primitive.CASESENSITVE)) {
			sentence.addRelationship(Primitive.TYPE, Primitive.CASESENSITVE);
		}
		return sentence;
	}
	
	/**
	 * Return the sentence or word for the vertex.
	 */
	public Vertex getWord(Vertex vertex, Network network) {
		if (vertex.instanceOf(Primitive.SENTENCE) || vertex.instanceOf(Primitive.WORD)) {
			if (!vertex.hasData()) {
				// If it was a self created sentence it may not have text.
				vertex = createSentenceText(vertex, network);
			}
			if (!vertex.instanceOf(Primitive.SENTENCE)) {
				vertex.addRelationship(Primitive.INSTANTIATION, Primitive.SENTENCE);
			}
			return vertex;
		}
		// May be a string that needs to be converted to a sentence.
		if (vertex.hasData() && (vertex.getData() instanceof String)) {
			return network.createSentence((String)vertex.getData());
		}
		if (vertex.instanceOf(Primitive.PARAGRAPH)) {
			return vertex;
		}
		Vertex previous = network.createVertex(Primitive.NULL);
		return getWordFollowing(vertex, previous, previous, network);
	}
	
	/**
	 * Return the sentence or word for the vertex.
	 */
	public static Vertex getWordFollowing(Vertex vertex, Vertex previousWord, Vertex nextWord, Network network) {
		return getWordFollowing(vertex, null, previousWord, nextWord, network);
	}
	
	/**
	 * Return the sentence or word for the vertex.
	 */
	public static Vertex getWordFollowing(Vertex vertex, Relationship relationship, Vertex previousWord, Vertex nextWord, Network network) {
		if (vertex.instanceOf(Primitive.WORD)) {
			if (!vertex.hasData()) {
				// Check if it is a newly create compound word that needs the text.
				if (vertex.instanceOf(Primitive.COMPOUND_WORD)) {
					Collection<Relationship> wordParts = vertex.orderedRelationships(Primitive.WORD);
					if (wordParts != null) {
						StringWriter writer = new StringWriter();
						boolean first = true;
						for (Relationship wordPart : wordParts) {
							if (!first) {
								writer.write(" ");
							} else {
								first = false;
							}
							writer.write(wordPart.getTarget().getDataValue());
						}
						return network.createWord(writer.toString());
					}
				}
			}
			return vertex;
		}
		if (vertex.instanceOf(Primitive.FRAGMENT)) {
			if (!vertex.hasData()) {
				return network.createFragment(printFragment(vertex, previousWord, nextWord, network));
			}
			return vertex;
		}
		if (vertex.instanceOf(Primitive.SENTENCE)) {
			if (!vertex.hasData()) {
				return network.createSentence(printFragment(vertex, previousWord, nextWord, network));
			}
			return vertex;
		}
		if (vertex.instanceOf(Primitive.PARAGRAPH)) {
			return vertex;
		}
		if (vertex.instanceOf(Primitive.ARRAY)) {
			if (!vertex.hasData()) {
				// Print the list.
				Collection<Relationship> values = vertex.orderedRelationships(Primitive.ELEMENT);
				if (values != null) {
					StringWriter writer = new StringWriter();
					int index = 0;
					for (Relationship value : values) {
						if (!value.isInverse()) {
							if (index != 0) {
								writer.write(", ");
								if (index == (values.size() - 1)) {
									writer.write("and ");
								}
							}
							writer.write(getWordFollowing(value.getTarget(), previousWord, nextWord, network).getDataValue());
							index++;
						}
					}
					return network.createFragment(writer.toString());
				} else {
					return network.createWord("empty");					
				}
			}
		} else if (vertex.instanceOf(Primitive.LIST)) {
			if (!vertex.hasData()) {
				// Print the list.
				Collection<Relationship> values = vertex.orderedRelationships(Primitive.SEQUENCE);
				if (values != null) {
					StringWriter writer = new StringWriter();
					int index = 0;
					for (Relationship value : values) {
						if (!value.isInverse()) {
							if (index != 0) {
								writer.write(", ");
								if (index == (values.size() - 1)) {
									writer.write("and ");
								}
							}
							writer.write(getWordFollowing(value.getTarget(), previousWord, nextWord, network).getDataValue());
							index++;
						}
					}
					return network.createFragment(writer.toString());
				} else {
					return network.createWord("empty");					
				}
			}
		}
		// Verb conjugation
		Vertex tense = null;
		if (relationship != null && relationship.hasMeta()) {
			tense = relationship.getMeta().getRelationship(Primitive.TENSE);
		}
		Vertex word = null;
		if (vertex.instanceOf(Primitive.ACTION)) {
			word = vertex.getAssoiate(network.createVertex(Primitive.WORD),
				previousWord, network.createVertex(Primitive.CONJUGATION),
				tense, network.createVertex(Primitive.TENSE),
				previousWord, network.createVertex(Primitive.PREVIOUS));
		}
		if (word == null) {
			// Pronoun subjuction
			Collection<Relationship> types = null;
			if (relationship != null && relationship.hasMeta()) {
				types = relationship.getMeta().getRelationships(Primitive.TYPE);
			}
			if (types == null) {
				word = vertex.mostConscious(Primitive.NAME);
			}
			if (word == null) {
				// What sounds right
				if (nextWord != null && !nextWord.instanceOf(Primitive.WORD)) {
					word = vertex.getAssoiate(network.createVertex(Primitive.WORD),
							previousWord, network.createVertex(Primitive.PREVIOUS),
							nextWord.getRelationships(Primitive.WORD), network.createVertex(Primitive.NEXT),
							types, network.createVertex(Primitive.TYPE), null);
					nextWord = nextWord.getRelationship(Primitive.WORD);
				} else {
					word = vertex.getAssoiate(network.createVertex(Primitive.WORD),
							previousWord, network.createVertex(Primitive.PREVIOUS),
							nextWord, network.createVertex(Primitive.NEXT),
							types, network.createVertex(Primitive.TYPE), null);
				}
			}
		}
		if (word == null) {
			word = vertex.mostConscious(Primitive.WORD);
		}
		if ((word == null) && (vertex.instanceOf(Primitive.SEQUENCE))) {
			// TODO assume digits for now, should probably change to sequence
			List<Relationship> digits = vertex.orderedRelationships(Primitive.DIGIT);
			if (digits != null) {
				StringWriter writer = new StringWriter();
				for (int index = digits.size() - 1; index >= 0; index--) {
					Relationship digit = digits.get(index);
					writer.write(String.valueOf(digit.getTarget().getData()));
				}
				word = network.createWord(writer.toString());
			}
		}
		if (word == null) {
			if (!vertex.hasData()) {
				word = vertex.mostConscious(Primitive.SENTENCE);
				if (word != null) {
					return word;
				}
				// If no word, call it what it is.
				Collection<Relationship> classifications = vertex.getRelationships(Primitive.INSTANTIATION);
				if (classifications != null) {
					Vertex mostSpecialized = null;
					for (Relationship classification : classifications) {
						if (mostSpecialized == null) {
							mostSpecialized = classification.getTarget();
						} else if (classification.getTarget().hasRelationship(Primitive.SPECIALIZATION, mostSpecialized)) {
							mostSpecialized = classification.getTarget();
						}
					}
					word = mostSpecialized.mostConscious(Primitive.WORD);
				}
				if (word == null) {
					word = network.createVertex("");
				}
			} else if (vertex.isPrimitive()) {
				word = network.createWord(vertex.getDataValue());
			} else if (vertex.getData() instanceof Time) {				
				word = network.createWord(Utils.printTime((Time)vertex.getData(), "h:mm:ss a z"));
			} else {
				word = network.createFragment(vertex.getDataValue());
			}
		}
		return word;
	}
	
	public static String printFragment(Vertex fragment, Vertex previousWord, Vertex nextWord, Network network) {
		List<Relationship> values = fragment.orderedRelationships(Primitive.WORD);
		StringWriter writer = new StringWriter();
		int index = 0;
		if (values != null) {
			for (Relationship value : values) {
				if (index != 0) {
					writer.write(" ");
				}
				Vertex next = nextWord;
				if (index < values.size()) {
					next = values.get(index).getTarget();
				}
				Vertex word = getWordFollowing(value.getTarget(), previousWord, next, network);
				writer.write(word.printString());
				index++;
				previousWord = word;
			}
		}
		return writer.toString();
	}

	public Vertex getLastStateMachine(Network network) {
		if (this.lastStateMachineId == null) {
			return null;
		}
		return network.findById(this.lastStateMachineId);
	}

	public void setLastStateMachine(Vertex lastStateMachine) {
		if (lastStateMachine == null) {
			this.lastStateMachineId = null;
		} else {
			this.lastStateMachineId = lastStateMachine.getId();
		}
	}

	public Vertex getLastState(Network network) {
		if (this.lastStateId == null) {
			return null;
		}
		return network.findById(this.lastStateId);
	}

	public void setLastState(Vertex lastState) {
		if (lastState == null) {
			this.lastStateId = null;
		} else {
			this.lastStateId = lastState.getId();
		}
	}

	public Vertex getLastQuotient(Network network) {
		if (this.lastQuotientId == null) {
			return null;
		}
		return network.findById(this.lastQuotientId);
	}

	public void setLastQuotient(Vertex lastQuotient) {
		if (lastQuotient == null) {
			this.lastQuotientId = null;
		} else {
			this.lastQuotientId = lastQuotient.getId();
		}
	}

	public boolean getEnableEmote() {
		return enableEmote;
	}

	public void setEnableEmote(boolean enableEmote) {
		this.enableEmote = enableEmote;
	}

	public String getLanguage() {
		return language;
	}

	public void setLanguage(String language) {
		this.language = language;
		if (getBot() != null) {
			// Update Wiktionary/WikiData language.
			Wiktionary wiktionary = getBot().awareness().getSense(Wiktionary.class);
			if (wiktionary != null) {
				wiktionary.setLanguage(language);
			}
			Wikidata wikidata = getBot().awareness().getSense(Wikidata.class);
			if (wikidata != null) {
				wikidata.setLanguage(language);
			}
		}
	}

	/**
	 * Load, compile, and add the state machine from the .self file.
	 */
	public void loadSelfFile(File file, String encoding, boolean debug) {
		try {
			loadSelfFile(new FileInputStream(file), encoding, MAX_FILE_SIZE, debug, true);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}
	
	/**
	 * Load, compile, and add the state machine from the .self file.
	 */
	public void loadSelfFile(URL url, String encoding, boolean debug) {
		try {
			loadSelfFile(Utils.openStream(url), encoding, MAX_FILE_SIZE, debug, true);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}
	
	/**
	 * Load, compile, and add the state machine from the Self source code.
	 */
	public void loadSelf(String text, boolean debug, boolean optimize) {
		Network network = getBot().memory().newMemory();
		Vertex language = network.createVertex(getPrimitive());
		SelfCompiler compiler = SelfCompiler.getCompiler();
		if (!optimize) {
			compiler = new SelfCompiler();
		}
		Vertex stateMachine = compiler.parseStateMachine(text, debug, network);
		SelfCompiler.getCompiler().pin(stateMachine);
		language.addRelationship(Primitive.STATE, stateMachine);
		network.save();
	}
	
	/**
	 * Load, compile, and add the state machine from the .self file stream.
	 */
	public void loadSelfFile(InputStream stream, String encoding, int maxSize, boolean debug, boolean optimize) {
		Network network = getBot().memory().newMemory();
		Vertex language = network.createVertex(getPrimitive());
		SelfCompiler compiler = SelfCompiler.getCompiler();
		if (!optimize) {
			compiler = new SelfCompiler();
		}
		Vertex stateMachine = compiler.parseStateMachine(stream, debug, network, encoding, maxSize);
		SelfCompiler.getCompiler().pin(stateMachine);
		language.addRelationship(Primitive.STATE, stateMachine);
		network.save();
	}

	/**
	 * Load, parse, the aiml file as a chat log.
	 */
	public void loadAIMLFileAsLog(File file, String encoding, boolean pin) {
		try {
			loadAIMLFileAsLog(new FileInputStream(file), encoding, MAX_FILE_SIZE, pin);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}

	/**
	 * Load, parse, the aiml file as a script.
	 */
	public void loadAIMLFile(File file, boolean createStates, boolean indexStatic, String encoding) {
		try {
			loadAIMLFile(new FileInputStream(file), file.getName(), createStates, false, indexStatic, encoding, MAX_FILE_SIZE);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}
	
	/**
	 * Load, parse, the aiml file as a chat log.
	 */
	public void loadAIMLFileAsLog(InputStream stream, String encoding, int maxSize, boolean pin) {
		String text = Utils.loadTextFile(stream, encoding, maxSize);
		loadAIMLAsLog(text, pin);
	}
	
	/**
	 * Load, parse, the aiml as a chat log.
	 */
	public void loadAIMLAsLog(String text, boolean pin) {
		long start = System.currentTimeMillis();
		Network network = getBot().memory().newMemory();
		AIMLParser.parser().parseAIML(text, false, false, pin, false, null, network);
		network.save();
		log("AIML parsing time", Level.INFO, System.currentTimeMillis() - start);
	}
	
	/**
	 * Load, parse, the aiml file as a state machine.
	 */
	public void loadAIMLFile(InputStream stream, String name, boolean createStates, boolean mergeState, boolean indexStatic, String encoding, int maxSize) {
		String text = Utils.loadTextFile(stream, encoding, maxSize);
		loadAIML(text, name, createStates, mergeState, indexStatic);
	}

	/**
	 * Load, parse, the aiml .set file.
	 */
	public void loadAIMLSETFile(File file, String name, String encoding) {
		try {
			loadAIMLSETFile(new FileInputStream(file), name, encoding, MAX_FILE_SIZE);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}
	
	/**
	 * Load, parse, the aiml .set file.
	 */
	public void loadAIMLSETFile(InputStream stream, String name, String encoding, int maxSize) {
		String text = Utils.loadTextFile(stream, encoding, maxSize);
		Network network = getBot().memory().newMemory();
		AIMLParser.parser().parseSET(text, name, true, network);
		network.save();
	}

	/**
	 * Load, parse, the aiml .set file.
	 */
	public void loadAIMLMAPFile(File file, String name, String encoding) {
		try {
			loadAIMLMAPFile(new FileInputStream(file), name, encoding, MAX_FILE_SIZE);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}
	
	/**
	 * Load, parse, the aiml .set file.
	 */
	public void loadAIMLMAPFile(InputStream stream, String name, String encoding, int maxSize) {
		String text = Utils.loadTextFile(stream, encoding, maxSize);
		Network network = getBot().memory().newMemory();
		AIMLParser.parser().parseMAP(text, name, true, network);
		network.save();
	}

	/**
	 * Load, parse, the aiml .set file.
	 */
	public void loadAIMLPropertiesFile(File file, String encoding) {
		try {
			loadAIMLPropertiesFile(new FileInputStream(file), encoding, MAX_FILE_SIZE);
		} catch (IOException exception) {
			throw new SelfParseException("Parsing error occurred", exception);
		}
	}
	
	/**
	 * Load, parse, the aiml .set file.
	 */
	public void loadAIMLPropertiesFile(InputStream stream, String encoding, int maxSize) {
		String text = Utils.loadTextFile(stream, encoding, maxSize);
		Network network = getBot().memory().newMemory();
		AIMLParser.parser().parseProperties(text, true, network);
		network.save();
	}
	
	/**
	 * Load, parse, the aiml file as a state machine.
	 */
	public void loadAIML(String text, String name, boolean createStates, boolean mergeState, boolean indexStatic) {
		long start = System.currentTimeMillis();
		Network network = getBot().memory().newMemory();
		Vertex stateMachine = null;
		Vertex language = network.createVertex(getPrimitive());
		if (mergeState) {
			stateMachine = language.lastRelationship(Primitive.STATE);
		} 
		if (stateMachine == null) {
			stateMachine = network.createInstance(Primitive.STATE);
			stateMachine.addRelationship(Primitive.LANGUAGE, network.createVertex(Primitive.AIML));
			stateMachine.addRelationship(Primitive.LANGUAGE, network.createVertex(Primitive.SELF4));
			stateMachine.setName(name);
			language.addRelationship(Primitive.STATE, stateMachine);
			TextData data = new TextData();
			data.setText(text);
			stateMachine.addRelationship(Primitive.SOURCECODE, network.createVertex(data));
			Vertex sourceCode = stateMachine.getRelationship(Primitive.SOURCECODE);
			if (sourceCode != null) {
				sourceCode.setPinned(true);
			}
		}
		stateMachine = AIMLParser.parser().parseAIML(text, true, createStates, false, indexStatic, stateMachine, network);
		SelfCompiler.getCompiler().pin(stateMachine);
		network.save();
		log("AIML parsing time", Level.INFO, System.currentTimeMillis() - start);
	}
	
	public Map<Vertex, Integer> computeInputWordValues(Vertex input) {
		HashMap<Vertex, Integer> wordScores = new HashMap<Vertex, Integer>();
		Map<Vertex, Integer> sortedWordScores = new LinkedHashMap<Vertex, Integer>();
		
		if (input != null && input.hasRelationship(Primitive.WORD)) {
			for (Relationship word : input.getRelationships(Primitive.WORD)) {
				int score = 0;
				if (word.getTarget().getRelationships(Primitive.QUESTION) != null) {
					score = word.getTarget().getRelationships(Primitive.QUESTION).size();
				}
				wordScores.put(word.getTarget(), score);
			}
		}
		
		List<Object> list = new LinkedList<Object>(wordScores.entrySet());
		Collections.sort(list, new Comparator<Object>() {
			@SuppressWarnings("unchecked")
			public int compare(Object o1, Object o2) {
				return ((int)((Map.Entry<Vertex, Integer>)o2).getValue()) - ((int)((Map.Entry<Vertex, Integer>)o1).getValue());
			}
		});
		
		for (Iterator<Object> iterator = list.iterator(); iterator.hasNext();) {
			@SuppressWarnings("unchecked")
			Map.Entry<Vertex, Integer> entry = (Map.Entry<Vertex, Integer>)iterator.next();
			sortedWordScores.put(entry.getKey(), entry.getValue());
		}
		int score = 2;
		int position = 1;
		int lastScore = Integer.MAX_VALUE;
		for (Vertex word : sortedWordScores.keySet()) {
			if(sortedWordScores.get(word) < lastScore) {
				score = position * 2;
			}
			wordScores.put(word, score);
			lastScore = sortedWordScores.get(word);
			position++;	
		}

		return wordScores;
	}
	
	public Map<Vertex, Integer> computeInputFragmentValues(Vertex input) {
		HashMap<Vertex, Integer> fragmentScores = new HashMap<Vertex, Integer>();
		Map<Vertex, Integer> sortedFragmentScores = new LinkedHashMap<Vertex, Integer>();
		
		if (input != null && input.hasRelationship(Primitive.FRAGMENT)) {
			for (Relationship word : input.getRelationships(Primitive.FRAGMENT)) {
				int score = 0;
				if (word.getTarget().getRelationships(Primitive.QUESTION) != null) {
					score = word.getTarget().getRelationships(Primitive.QUESTION).size();
				}
				fragmentScores.put(word.getTarget(), score);
			}
		}
		
		List<Object> list = new LinkedList<Object>(fragmentScores.entrySet());
		Collections.sort(list, new Comparator<Object>() {
			@SuppressWarnings("unchecked")
			public int compare(Object o1, Object o2) {
				return ((int)((Map.Entry<Vertex, Integer>)o2).getValue()) - ((int)((Map.Entry<Vertex, Integer>)o1).getValue());
			}
		});
		
		for (Iterator<Object> it = list.iterator(); it.hasNext();) {
			@SuppressWarnings("unchecked")
			Map.Entry<Vertex, Integer> entry = (Map.Entry<Vertex, Integer>)it.next();
			sortedFragmentScores.put(entry.getKey(), entry.getValue());
		}
		int score = 2;
		int position = 1;
		int lastScore = Integer.MAX_VALUE;
		for (Vertex word : sortedFragmentScores.keySet()) {
			if (sortedFragmentScores.get(word) < lastScore) {
				score = position * 2;
			}
			fragmentScores.put(word, score);
			lastScore = sortedFragmentScores.get(word);
			position++;
		}

		return fragmentScores;
	}
	
	public void recordFragmentValues(Vertex sentence, Map<Vertex, Integer> matches, Network network) {
		Collection<Relationship> words = sentence.orderedRelationships(Primitive.WORD);
		for (int i = 0; i < words.size() - 1; i++) {
			String fragmentText = ((Relationship)words.toArray()[i]).getTarget().getDataValue() + " " + ((Relationship)words.toArray()[i + 1]).getTarget().getDataValue();
			Vertex fragment = network.createFragment(fragmentText);
			// TODO These should not be persisted on the input sentence, pass in-memory list.
			//fragment.addRelationship(Primitive.QUESTION, sentence);
			sentence.addRelationship(Primitive.FRAGMENT, fragment);
		}
		
		for (Map.Entry<Vertex, Integer> entry : matches.entrySet()) { 
			int fragmentValueMatch = computeMaxSentenceFragmentsValue(entry.getKey(), entry.getKey(), network);
			int fragmentValueInput = computeMaxSentenceFragmentsValue(entry.getKey(), sentence, network);
			
			Integer count = matches.get(entry.getKey());
			if (count == null) {
				count = 0;
			}
			if (fragmentValueMatch == 0) {
				fragmentValueInput = fragmentValueMatch = 1;
			}
			int fragmentAmount = (int)((fragmentValueInput / (float)fragmentValueMatch) * count);
			
			matches.put(entry.getKey(), Math.round((Math.round(count * (1.0f - fragmentMatchPercent) * 10) + Math.round(fragmentAmount * fragmentMatchPercent * 10)) / 10.0f));
		}
	}
}