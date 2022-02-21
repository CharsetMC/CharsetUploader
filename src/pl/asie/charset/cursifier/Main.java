package pl.asie.charset.cursifier;

import java.io.File;

public class Main {
	public static final boolean SIMULATE = true;
	public static final boolean UPLOAD_LIB = true;
	public static final boolean UPLOAD_TABLET = false;

	public static void main(String[] args) throws Exception {
		// DO NOT ADD -full TO THE FILENAME
		ModuleUploader verifier = new ModuleUploader(new File("/home/asie/Charset-0.5.6.6.jar"), new File("/home/asie/result"), new File("/home/asie/defs"));
		verifier.analyze();
		verifier.packageModules();
		System.out.println("Packaging successful - can upload!");
		verifier.uploadModules();
	}
}
