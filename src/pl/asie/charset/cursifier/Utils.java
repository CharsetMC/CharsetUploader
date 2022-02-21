package pl.asie.charset.cursifier;

import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Utils {
	private Utils() {
	}

	static String stripInnerClass(String s) {
		return s.replaceFirst("\\$[a-zA-Z0-9\\$]+\\.class$", "\\.class");
	}

	public static void copy(File zipFile, JarOutputStream stream, Set<String> filesLeft, Predicate<ZipEntry> pass, BiFunction<ZipEntry, ZipInputStream, byte[]> patcher) throws IOException {
		ZipInputStream inStream = new ZipInputStream(new FileInputStream(zipFile));
		ZipEntry entry;

		while ((entry = inStream.getNextEntry()) != null) {
			if (!entry.getName().equals("META-INF/MANIFEST.MF") && pass.test(entry)) {
				filesLeft.remove(entry.getName());
				JarEntry entryNew = new JarEntry(entry.getName());
				entryNew.setTime(entry.getTime());
				stream.putNextEntry(entryNew);
				if (patcher != null) {
					byte[] patch = patcher.apply(entry, inStream);
					if (patch != null) {
						stream.write(patch);
						stream.closeEntry();
						continue;
					}
				}
				ByteStreams.copy(inStream, stream);
				stream.closeEntry();
			}
		}

		inStream.close();
	}
}
