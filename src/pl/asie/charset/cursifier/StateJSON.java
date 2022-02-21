package pl.asie.charset.cursifier;

import com.google.common.base.Charsets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class StateJSON {
	public Map<String, String> uploadedHashes = new HashMap<>();

	public boolean add(File out, boolean simulate) throws IOException {
		List<String> hashes = new ArrayList<>();

		Hasher hasher = Hashing.sha256().newHasher();
		ZipInputStream stream = new ZipInputStream(new FileInputStream(out));
		ZipEntry entry;
		while ((entry = stream.getNextEntry()) != null) {
			hashes.add(Hashing.sha256().hashBytes(ByteStreams.toByteArray(stream)).toString());
		}
		hashes.sort(String::compareTo);
		for (String s : hashes) {
			hasher.putString(s, Charsets.US_ASCII);
		}

		HashCode code = hasher.hash();
		if (uploadedHashes.containsKey(code.toString())) {
			return false;
		} else {
			if (!simulate) {
				uploadedHashes.put(code.toString(), out.getName());
			}
			return true;
		}
	}
}
