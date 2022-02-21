package pl.asie.charset.cursifier;

import java.util.*;

public class SettingsJSON {
	public static class ComboModule {
		public List<String> contains = new ArrayList<>();
		public List<String> depends = new ArrayList<>();
		public int stability;
	}
	public int[] curseGameVersions;
	public String curseToken;
	public Map<String, Integer> curseFriendlyModules;

	public Map<String, ComboModule> comboModules;
}
