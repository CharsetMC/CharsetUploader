package pl.asie.charset.cursifier;

import com.google.common.base.Charsets;
import com.google.common.collect.*;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClients;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModuleUploader {
	private static final Set<String> ALWAYS_INCLUDE = ImmutableSet.of(
			"pack.mcmeta",
			"licenses/LICENSE"
	);

	private static final Set<String> MODULE_ANNOTATIONS = ImmutableSet.of("Lpl/asie/charset/lib/loader/CharsetModule;");

	public static class ListingRemapper extends Remapper {
		private final Set<String> refClasses = new HashSet<>();

		@Override
		public String mapMethodName(String owner, String name, String desc) {
			refClasses.add(owner);
			return name;
		}

		@Override
		public String mapFieldName(String owner, String name, String desc) {
			refClasses.add(owner);
			return name;
		}

		@Override
		public String map(String var1) {
			refClasses.add(var1);
			return var1;
		}
	}

	public class ArrayToListVisitor extends AnnotationVisitor {
		private final List list;

		public ArrayToListVisitor(int api, AnnotationVisitor av, List list) {
			super(api, av);
			this.list = list;
		}

		@Override
		public void visit(String name, Object value) {
			this.list.add(value);
			super.visit(name, value);
		}
	}

	public class ModuleFinderAnnotation extends AnnotationVisitor {
		private final ModuleDefinition def = new ModuleDefinition();
		private final String path;

		public ModuleFinderAnnotation(int api, AnnotationVisitor av, String path) {
			super(api, av);
			this.path = path;
		}

		@Override
		public void visit(String name, Object value) {
			if ("name".equals(name)) {
				def.name = (String) value;
				def.contains.add(def.name);
			}
			super.visit(name, value);
		}

		@Override
		public void visitEnum(String name, String desc, String value) {
			if ("profile".equals(name)) {
				if ("STABLE".equals(value)) {
					def.stability = 0;
				} else if ("TESTING".equals(value)) {
					def.stability = 1;
				} else if ("EXPERIMENTAL".equals(value)) {
					def.stability = 2;
				} else if ("INDEV".equals(value)) {
					def.stability = 3;
				}
			}
			super.visitEnum(name, desc, value);
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			if ("dependencies".equals(name)) {
				return new ArrayToListVisitor(api, super.visitArray(name), def.dependencies);
			}
			if ("antidependencies".equals(name)) {
				return new ArrayToListVisitor(api, super.visitArray(name), def.conflicts);
			}
			return super.visitArray(name);
		}

		@Override
		public void visitEnd() {
			if (def.name != null) {
				modules.put(def.name, def);
				String p = path.replaceFirst("pl/asie/charset/module/", "");
				p = p.replaceFirst("pl/asie/simplelogic/", "");
				p = p.substring(0, p.lastIndexOf('/'));

				if (!modulesCollidingPaths.contains(p)) {
					if (modulesByPath.containsKey(p)) {
						modulesByPath.remove(p);
						modulesCollidingPaths.add(p);
					} else {
						modulesByPath.put(p, def);
					}
				}
			}
			super.visitEnd();
		}
	}
	public class ModuleForcerAnnotation extends AnnotationVisitor {
		public ModuleForcerAnnotation(int api, AnnotationVisitor av) {
			super(api, av);
		}

		@Override
		public void visitEnum(String name, String desc, String value) {
			if ("profile".equals(name) && !("COMPAT".equals(value))) {
				super.visitEnum(name, desc, "FORCED");
			} else {
				super.visitEnum(name, desc, value);
			}
		}
	}

	public class ModuleForcer extends ClassVisitor {
		public ModuleForcer(int api, ClassVisitor cv) {
			super(api, cv);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			if (MODULE_ANNOTATIONS.contains(desc)) {
				return new ModuleForcerAnnotation(api, super.visitAnnotation(desc, visible));
			} else {
				return super.visitAnnotation(desc, visible);
			}
		}
	}

	public class ModuleFinder extends ClassVisitor {
		public boolean provides = false;
		public ModuleDefinition definition;
		private final String path;

		public ModuleFinder(int api, String path) {
			super(api);
			this.path = path;
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			if (MODULE_ANNOTATIONS.contains(desc)) {
				provides = true;
				ModuleFinderAnnotation annotation = new ModuleFinderAnnotation(api, super.visitAnnotation(desc, visible), path);
				definition = annotation.def;
				return annotation;
			}
			return super.visitAnnotation(desc, visible);
		}
	}

	private final Map<String, byte[]> modifiedFiles;
	private final Map<String, JsonObject> jsonFiles;
	private final BiMap<String, ModuleDefinition> moduleProviders;
	private final Map<String, ModuleDefinition> modules;
	private final Map<ModuleDefinition, File> moduleFiles;
	private final BiMap<String, ModuleDefinition> modulesByPath;
	private final Set<String> modulesCollidingPaths;
	private final File file, outPath, defPath;
	private final Multimap<String, String> errorsByModule;
	private final Gson gson;
	private final SettingsJSON settings;
	private Set<String> files;

	public ModuleUploader(File file, File outPath, File defsPath) throws IOException {
		this.file = file;
		this.outPath = outPath;
		this.defPath = defsPath;
		this.gson = new Gson();
		this.settings = gson.fromJson(new InputStreamReader(new FileInputStream(new File(defsPath, "settings.json"))), SettingsJSON.class);
		this.files = new HashSet<>();
		this.jsonFiles = new HashMap<>();
		this.errorsByModule = LinkedListMultimap.create();
		this.modules = new HashMap<>();
		this.modulesCollidingPaths = new HashSet<>();
		this.moduleFiles = new HashMap<>();
		this.modulesByPath = HashBiMap.create();
		this.moduleProviders = HashBiMap.create();
		this.modifiedFiles = new HashMap<>();
	}

	private static String reformatModuleName(String name) {
		String[] s = name.split("\\.");
		StringBuilder builder = new StringBuilder();
		for (String ss : s) {
			builder.append(ss.substring(0, 1).toUpperCase() + ss.substring(1, ss.length()));
		}
		return builder.toString();
	}

	public void uploadModules() throws IOException {
		File stateFile = new File(defPath, "state.json");
		StateJSON state = stateFile.exists() ? gson.fromJson(new InputStreamReader(new FileInputStream(stateFile)), StateJSON.class) : new StateJSON();
		for (Map.Entry<ModuleDefinition, File> entry : moduleFiles.entrySet()) {
			for (String s : entry.getKey().contains) {
				if (errorsByModule.containsKey(s)) {
					String n = s;
					System.err.println("Cannot upload module " + n + ":");
					for (String ss : errorsByModule.get(n)) {
						System.err.println(ss);
					}
				}
			}

			if (settings.curseFriendlyModules.containsKey(entry.getKey().name)) {
				if (entry.getKey().name.equals("tablet") && !Main.UPLOAD_TABLET) {
					continue;
				}

				if (entry.getKey().name.equals("lib") && !Main.UPLOAD_LIB) {
					continue;
				}

				if (state.add(entry.getValue(), true)) {
					String version = entry.getValue().getName();
					String[] vDashes = version.split("-");
					version = vDashes[vDashes.length - 1].startsWith("full") ? vDashes[vDashes.length - 2] : vDashes[vDashes.length - 1];
					version = version.replaceFirst("\\.jar", "");

					System.out.println("Uploading " + entry.getValue() + " (version " + version + ")");

					CurseMetadataJSON metadataJSON = new CurseMetadataJSON();
					entry.getKey().fill(metadataJSON, version);
					metadataJSON.gameVersions = settings.curseGameVersions;

					if (Main.SIMULATE) {
						System.out.println(gson.toJson(metadataJSON));
					} else {
						try {
							HttpClient client = HttpClients.createDefault();
							HttpPost post = new HttpPost(new URI("https://minecraft.curseforge.com/api/projects/" + settings.curseFriendlyModules.get(entry.getKey().name) + "/upload-file"));

							post.addHeader("X-Api-Token", settings.curseToken);
							post.setEntity(MultipartEntityBuilder.create()
									.addBinaryBody("file", entry.getValue())
									.addTextBody("metadata", gson.toJson(metadataJSON))
									.build()
							);

							HttpResponse response = client.execute(post);
							if (response.getStatusLine().getStatusCode() == 200) {
								state.add(entry.getValue(), false);
								Files.write(gson.toJson(state).getBytes(Charsets.UTF_8), stateFile);
								System.out.println("*** ADDED!");
							}
							System.out.println(new String(ByteStreams.toByteArray(response.getEntity().getContent()), Charsets.UTF_8));
						} catch (URISyntaxException e) {
							e.printStackTrace();
						}
					}
				} else {
					System.out.println("Skipping " + entry.getValue() + " - no changes");
				}
			}
		}
	}

	private boolean shouldPackage(String name, Collection<String> prefixes) {
		if (ALWAYS_INCLUDE.contains(name)) {
			return true;
		}

		if (jsonFiles.containsKey(name)) {
			return true;
		}

		String siPath = Utils.stripInnerClass(name);
		for (String fPrefix : prefixes) {
			if (siPath.startsWith(fPrefix)) {
				return true;
			}
		}
		return false;
	}

	public void packageModules() throws IOException {
		Set<ModuleDefinition> definitions = new HashSet<>();
		definitions.addAll(modules.values());

		Set<String> toAdd = new HashSet<>();
		toAdd.addAll(files);
		toAdd.remove("META-INF/MANIFEST.MF");

		for (Map.Entry<String, SettingsJSON.ComboModule> comboModule : settings.comboModules.entrySet()) {
			ModuleDefinition def = new ModuleDefinition();
			def.name = comboModule.getKey();
			def.contains.addAll(comboModule.getValue().contains);
			def.dependencies.addAll(comboModule.getValue().depends);
			def.stability = comboModule.getValue().stability;
			for (String s : def.contains) {
				if (!modules.containsKey(s)) {
					throw new RuntimeException("Could not find comboModule-contained module " + s + "!");
				}
			}
			modules.put(def.name, def);
			definitions.add(def);
		}

		for (ModuleDefinition def : definitions) {
			if (def.stability >= 0 && def.stability <= 2) {
				if (def.name.startsWith("lib")) {
					// Libraries are packaged separately
					continue;
				}

				final List<String> prefixes = new ArrayList<>();
				for (String s : def.contains) {
					String prefix = "pl/asie/charset/module/" + s.replaceAll("\\.", "/");
					if (s.startsWith("simplelogic")) {
						prefix = "pl/asie/" + s.replaceAll("\\.", "/");
					}
					if (!moduleProviders.inverse().get(modules.get(s)).startsWith(prefix)) {
						prefix = moduleProviders.inverse().get(modules.get(s));
					}
					System.out.println(prefix);
					prefixes.add(prefix);
					// prefixes.add(prefix.replace("pl/asie/charset/module", "assets/charset/recipes"));
				}

				String fn = file.getName().replaceFirst("Charset", "Charset-" + reformatModuleName(def.name));
				if (fn.startsWith("Charset-Simplelogic")) {
					fn = fn.replaceFirst("Charset-Simplelogic", "SimpleLogic-");
				}
				File outFile = new File(outPath, fn);
				moduleFiles.put(def, outFile);
				System.out.println("Building " + outFile.getName());

				Manifest manifest = new Manifest();
				manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
				JarOutputStream stream = new JarOutputStream(new FileOutputStream(outFile), manifest);
				Utils.copy(file, stream, toAdd, (entry) -> shouldPackage(entry.getName(), prefixes), (entry, inStream) -> {
					if (modifiedFiles.containsKey(entry.getName())) {
						System.out.println("Using modified " + entry.getName());
						return modifiedFiles.get(entry.getName());
					}

					if (jsonFiles.containsKey(entry.getName())) {
						System.out.println("Patching " + entry.getName() + " to split annotation data...");
						JsonObject oldObject = jsonFiles.get(entry.getName());
						JsonObject newObject = new JsonObject();
						for (Map.Entry<String, JsonElement> entries : oldObject.entrySet()) {
							if (shouldPackage(entries.getKey() + ".class", prefixes)) {
								System.out.println("- " + entries.getKey());
								newObject.add(entries.getKey(), entries.getValue());
							}
						}
						return newObject.toString().getBytes(Charsets.UTF_8);
					}

					if (moduleProviders.containsKey(entry.getName())) {
						System.out.println("Patching " + entry.getName() + " to force module...");
						try {
							ClassWriter writer = new ClassWriter(Opcodes.ASM6);
							ModuleForcer moduleForcer = new ModuleForcer(Opcodes.ASM6, writer);
							ClassReader reader = new ClassReader(inStream);
							reader.accept(moduleForcer, 0);
							return writer.toByteArray();
						} catch (IOException e) {
							e.printStackTrace();
							return null;
						}
					}
					return null;
				});
				stream.close();
			}
		}

		for (String s : files) {
			if (!s.startsWith("pl/") && !s.startsWith("assets/")) {
				toAdd.add(s);
			}
		}

		File outFile = new File(outPath, file.getName().replaceFirst("Charset", "Charset-Lib"));
		moduleFiles.put(modules.get("lib"), outFile);
		System.out.println("Building " + outFile.getName());

		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		JarOutputStream stream = new JarOutputStream(new FileOutputStream(outFile), manifest);
		Utils.copy(file, stream, toAdd, (entry) -> {
			return !entry.getName().startsWith("pl/asie/charset/module/")
					&& !entry.getName().startsWith("pl/asie/simplelogic/")
					&& !entry.getName().endsWith(".sh")
					&& toAdd.contains(entry.getName());
		}, (entry, inStream) -> {
			if (modifiedFiles.containsKey(entry.getName())) {
				System.out.println("Using modified " + entry.getName());
				return modifiedFiles.get(entry.getName());
			}

			return null;
		});
		stream.close();

		for (String s : toAdd) {
			if (!s.endsWith("/")) {
				System.out.println("Found stray file without home: " + s);
			}
		}
	}

	private ModuleDefinition getModuleFromPath(String s, boolean accountForProviders) {
		s = Utils.stripInnerClass(s);

		if (accountForProviders) {
			if (moduleProviders.containsKey(s)) {
				return moduleProviders.get(s);
			}
		}

		String p = null;
		if (s.startsWith("pl/asie/charset/lib/")) {
			p = s.substring("pl/asie/charset/".length());
		} else if (s.startsWith("pl/asie/charset/module/")) {
			p = s.substring("pl/asie/charset/module/".length());
		} else if (s.startsWith("pl/asie/simplelogic/")) {
			p = s.substring("pl/asie/simplelogic/".length());
		}

		if (p != null) {
			while (!modulesByPath.containsKey(p) && p.lastIndexOf('/') > 0) {
				p = p.substring(0, p.lastIndexOf('/'));
			}
			if (modulesByPath.containsKey(p)) {
				return modulesByPath.get(p);
			}
		}

		return null;
	}

	public void analyze() throws IOException {
		// Phase 1: Gather all module names
		ZipInputStream stream = new ZipInputStream(new FileInputStream(file));

		ZipEntry entry;
		while ((entry = stream.getNextEntry()) != null) {
			files.add(entry.getName());
			if (entry.getName().endsWith(".class")) {
				ClassReader reader = new ClassReader(stream);
				ModuleFinder visitor = new ModuleFinder(Opcodes.ASM6, entry.getName());
				reader.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				if (visitor.provides) {
					moduleProviders.put(entry.getName(), visitor.definition);
				}
			}
		}

		files = Collections.unmodifiableSet(files);
		stream.close();

		stream = new ZipInputStream(new FileInputStream(file));
		while ((entry = stream.getNextEntry()) != null) {
			if (entry.getName().endsWith("png")) {
				System.out.println("Optimizing " + entry.getName());

				byte[] in = ByteStreams.toByteArray(stream);
				File tmpIn = new File("/tmp/charset.png");

				if (tmpIn.exists()) tmpIn.delete();

				Files.write(in, tmpIn);
				Runtime runtime = Runtime.getRuntime();
				Process process = runtime.exec("optipng -o7 --strip=all --fix /tmp/charset.png");
				try {
					process.waitFor();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				byte[] out = ByteStreams.toByteArray(new FileInputStream(tmpIn));
				if (in.length > out.length) {
					System.out.println(in.length + " -> " + out.length);
					modifiedFiles.put(entry.getName(), out);
				}
			}

			if (entry.getName().startsWith("META-INF/fml") && entry.getName().endsWith("json")) {
				try {
					JsonParser parser = new JsonParser();
					jsonFiles.put(entry.getName(), parser.parse(new String(ByteStreams.toByteArray(stream), Charsets.UTF_8)).getAsJsonObject());
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			if (entry.getName().endsWith("class")) {
				if (!(entry.getName().startsWith("pl/asie/charset/module")) && !(entry.getName().startsWith("pl/asie/simplelogic"))) {
					continue;
				}

				ModuleDefinition myModule = getModuleFromPath(entry.getName(), true);
				if (myModule == null) {
					System.out.println(entry.getName() + " has no module?");
					continue;
				}
				ModuleDefinition myModulePath = getModuleFromPath(entry.getName(), false);

				Multimap<String, String> depModules = HashMultimap.create();
				ListingRemapper remapper = new ListingRemapper();
				ClassReader reader = new ClassReader(stream);
				// We need the ClassWriter as the Remapper will not create MethodRemappers unless there is
				// /something/ in there.
				ClassVisitor visitor = new ClassRemapper(new ClassWriter(0), remapper);
				reader.accept(visitor, 0);

				for (String s : remapper.refClasses) {
					String p = null;
					if (s.startsWith("pl/asie/charset/lib/")) {
						p = s.substring("pl/asie/charset/".length());
					} else if (s.startsWith("pl/asie/charset/module/")) {
						p = s.substring("pl/asie/charset/module/".length());
					} else if (s.startsWith("pl/asie/simplelogic/")) {
						p = s.substring("pl/asie/simplelogic/".length());
					}

					if (p != null) {
						while (!modulesByPath.containsKey(p) && p.lastIndexOf('/') > 0) {
							p = p.substring(0, p.lastIndexOf('/'));
						}
						if (modulesByPath.containsKey(p)) {
							depModules.put(modulesByPath.get(p).name, s);
						}
					}
				}

				for (String dep : depModules.keySet()) {
					if (!"lib".equals(dep) && !myModule.name.equals(dep) && (myModulePath == null || !myModulePath.name.equals(dep))) {
						// dep check
						List<String> deps = myModule.dependencies;
						List<String> depsChecked = Lists.newArrayList(myModule.name);
						while (!deps.isEmpty()) {
							if (deps.contains(dep)) {
								break;
							}
							depsChecked.addAll(deps);
							List<String> depsNew = new ArrayList<>();
							for (String ddep : deps) {
								ModuleDefinition md = modules.get(ddep);
								if (md != null) {
									depsNew.addAll(md.dependencies);
								}
							}
							depsNew.removeAll(depsChecked);
							deps = depsNew;
						}

						if (!deps.contains(dep)) {
							errorsByModule.put(myModulePath.name, "Cross-class dependency found: " + myModule.name + " depends on " + dep + ":\n< " + entry.getName());
							for (String s : depModules.get(dep)) {
								errorsByModule.put(myModulePath.name, "> " + s);
							}
						}
					}
				}
			}
		}
	}
}
