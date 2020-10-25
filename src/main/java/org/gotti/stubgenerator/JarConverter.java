package org.gotti.stubgenerator;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;

public class JarConverter {

	public static void main(String[] args) {
		try {

			Options options = new Options();

			options.addOption(Option.builder("h").longOpt("help").desc("Print help message").build());
			options.addOption(Option.builder("s").longOpt("source").argName("jar or folder").hasArg().required().desc("Source JAR or folder").build());
			options.addOption(Option.builder("t").longOpt("target").argName("jar or folder").hasArg().required().desc("Target JAR or folder").build());

			CommandLineParser parser = new DefaultParser();
			CommandLine cmd = parser.parse(options, args);
			
			if (cmd.hasOption("help")) {
				new HelpFormatter().printHelp("JarConverter", "Create stubs for classes in a jar file\n\n", options, null, true);
				System.exit(0);
			}
			
			new JarConverter().convert(Paths.get(cmd.getOptionValue("source")), Paths.get(cmd.getOptionValue("target")));

		} catch (Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	private static FileSystem openFileSystem(Path path, boolean create) throws IOException {
		if (Files.isDirectory(path)) {
			return null;
		}
		for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
			try {
				Map<String, ?> env = create ? Collections.singletonMap("create", "true") : Collections.emptyMap();
				return provider.newFileSystem(path, env);
			} catch (UnsupportedOperationException uoe) {
			}
		}
		throw new ProviderNotFoundException("Provider not found");
	}

	public JarConverter() {
	}

	public void convert(Path source, Path target) throws NotFoundException, CannotCompileException, IOException {

		ClassPool classPool = new ClassPool(true);
		classPool.appendClassPath(source.toString());
		StubGenerator stubGenerator = new StubGenerator(classPool);

		try (FileSystem fs1 = openFileSystem(source, false); FileSystem fs2 = openFileSystem(target, true)) {

			Path sourceRoot = fs1 == null ? source : fs1.getPath("/");
			Path targetRoot = fs2 == null ? target : fs2.getPath("/");

			Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (file.getFileName().toString().endsWith(".class")) {

						try (InputStream inputStream = Files.newInputStream(file)) {

							ClassPool stubClassPool = new ClassPool(classPool);
							CtClass ctClass = stubClassPool.makeClass(inputStream, false);
							System.out.println(ctClass.getName());

							CtClass stubClass = stubGenerator.makeStubClass(ctClass);

							String filename = stubClass.getName().replace('.', '/') + ".class";
							Path targetPath = targetRoot.resolve(filename);
							Files.createDirectories(targetPath.getParent());
							try (OutputStream outputStream = Files.newOutputStream(targetPath, StandardOpenOption.CREATE)) {
								stubClass.toBytecode(new DataOutputStream(new BufferedOutputStream(outputStream)));
							}
							;
						} catch (NotFoundException | CannotCompileException e) {
							throw new RuntimeException(e);
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}
}
