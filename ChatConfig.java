package robjam1990.sdk.config;

import java.io.StringWriter;

import robjam1990.sdk.util.Utils;

/**
 * DTO for XML chat config.
 */
public class ChatConfig extends Config {	
	public String conversation;
	public boolean correction;
	public boolean offensive;
	public boolean disconnect;
	public String emote;
	public String action;
	public String message;
	public boolean speak;
	public boolean includeQuestion;
	public boolean avatarHD;
	public String avatarFormat;
	public String avatar;
	public String language;
	public String voice;
	
	public String toXML() {
		StringWriter writer = new StringWriter();
		writer.write("<chat");
		writeCredentials(writer);
		if (this.conversation != null) {
			writer.write(" conversation=\"" + this.conversation + "\"");
		}
		if (this.emote != null) {
			writer.write(" emote=\"" + this.emote + "\"");
		}
		if (this.action != null) {
			writer.write(" action=\"" + this.action + "\"");
		}
		if (this.correction) {
			writer.write(" correction=\"" + this.correction + "\"");
		}
		if (this.offensive) {
			writer.write(" offensive=\"" + this.offensive + "\"");
		}
		if (this.speak) {
			writer.write(" speak=\"" + this.speak + "\"");
		}
		if (this.avatar != null) {
			writer.write(" avatar=\"" + this.avatar + "\"");
		}
		if (this.avatarHD) {
			writer.write(" avatarHD=\"" + this.avatarHD + "\"");
		}
		if (this.avatarFormat != null) {
			writer.write(" avatarFormat=\"" + this.avatarFormat + "\"");
		}
		if (this.language != null) {
			writer.write(" language=\"" + this.language + "\"");
		}
		if (this.voice != null) {
			writer.write(" voice=\"" + this.voice + "\"");
		}
		if (this.includeQuestion) {
			writer.write(" includeQuestion=\"" + this.includeQuestion + "\"");
		}
		if (this.disconnect) {
			writer.write(" disconnect=\"" + this.disconnect + "\"");
		}
		writer.write(">");
		
		if (this.message != null) {
			writer.write("<message>");
			writer.write(Utils.escapeHTML(this.message));
			writer.write("</message>");
		}
		writer.write("</chat>");
		return writer.toString();
	}
}