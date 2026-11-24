import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Writes a Windows .ico holding the given PNG files unchanged, one entry each.
 * Run as a source file: {@code java packaging/MakeIco.java out.ico a.png b.png ...}.
 * Windows Vista and later read PNG-compressed icon entries directly, so no
 * conversion is needed and nothing beyond the JDK is required.
 */
public final class MakeIco {

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("Usage: java MakeIco.java out.ico image.png...");
			System.exit(2);
		}
		List<byte[]> images = new ArrayList<>();
		List<int[]> sizes = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			Path png = Path.of(args[i]);
			var image = ImageIO.read(png.toFile());
			if (image == null) throw new IOException("Not an image: " + png);
			if (image.getWidth() > 256 || image.getHeight() > 256) throw new IOException("Larger than 256 pixels: " + png);
			images.add(Files.readAllBytes(png));
			sizes.add(new int[] { image.getWidth(), image.getHeight() });
		}

		int count = images.size();
		ByteBuffer header = ByteBuffer.allocate(6 + 16 * count).order(ByteOrder.LITTLE_ENDIAN);
		header.putShort((short) 0); // reserved
		header.putShort((short) 1); // type: icon
		header.putShort((short) count);
		int offset = 6 + 16 * count;
		for (int i = 0; i < count; i++) {
			int[] size = sizes.get(i);
			header.put((byte) (size[0] == 256 ? 0 : size[0])); // 0 means 256
			header.put((byte) (size[1] == 256 ? 0 : size[1]));
			header.put((byte) 0); // no palette
			header.put((byte) 0); // reserved
			header.putShort((short) 1); // colour planes
			header.putShort((short) 32); // bits per pixel
			header.putInt(images.get(i).length);
			header.putInt(offset);
			offset += images.get(i).length;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(header.array());
		for (byte[] image : images) out.write(image);
		Files.write(Path.of(args[0]), out.toByteArray());
	}
}
