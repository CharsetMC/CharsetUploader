package pl.asie.charset.cursifier;

import java.util.ArrayList;
import java.util.List;

public class ModuleDefinition {
	public String name;
	public int stability = -1; // 0 - stable, 1 - testing, 2 - experimental
	public List<String> contains = new ArrayList<>();
	public List<String> dependencies = new ArrayList<>();
	public List<String> conflicts = new ArrayList<>();

	public void fill(CurseMetadataJSON json, String version) {
		StringBuilder displayName = new StringBuilder("Charset ");
		String[] entries = name.split("\\.");
		for (String s : entries) {
			displayName.append(s.substring(0, 1).toUpperCase() + s.substring(1) + " ");
		}
		displayName.append(version);

		json.displayName = displayName.toString();
		json.displayName = json.displayName.replaceFirst("Charset Simplelogic", "SimpleLogic");
		switch (stability) {
			case 0:
				json.releaseType = "release";
				break;
			case 1:
				json.releaseType = "beta";
				break;
			case 2:
			default:
				json.releaseType = "alpha";
				break;
		}
	}
}
