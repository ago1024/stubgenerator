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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.NotFoundException;

public class JarConverter {

	private static final Logger LOGGER = Logger.getLogger(JarConverter.class.getName());
	
	private final List<String> includes = new ArrayList<>();
	private final List<String> excludes = new ArrayList<>();

	public static void main(String[] args) {
		try {

			Options options = new Options();

			options.addOption(Option.builder("h").longOpt("help").desc("Print help message").build());
			options.addOption(Option.builder("s").longOpt("source").argName("jar or folder").hasArg().required().desc("Source JAR or folder").build());
			options.addOption(Option.builder("t").longOpt("target").argName("jar or folder").hasArg().required().desc("Target JAR or folder").build());
			options.addOption(Option.builder("i").longOpt("include").argName("package").hasArg().desc("Include package and subpackages").build());
			options.addOption(Option.builder("x").longOpt("exclude").argName("package").hasArg().desc("Exclude package and subpacakges").build());

			CommandLineParser parser = new DefaultParser();
			CommandLine cmd = parser.parse(options, args);
			
			if (cmd.hasOption("help")) {
				new HelpFormatter().printHelp("JarConverter", "Create stubs for classes in a jar file\n\n", options, null, true);
				System.exit(0);
			}
			
			
			String source = cmd.getOptionValue("source");
			String target = cmd.getOptionValue("target");
			System.out.printf("Converting %s to %s\n", source, target);
			JarConverter jarConverter = new JarConverter();
			
			if (cmd.hasOption("include")) {
				String[] includes = cmd.getOptionValues("include");
				jarConverter.setIncludes(Arrays.asList(includes));
			}
			
			if (cmd.hasOption("exclude")) {
				String[] excludes = cmd.getOptionValues("exclude");
				jarConverter.setExcludes(Arrays.asList(excludes));
			}
			
			jarConverter.convert(Paths.get(source), Paths.get(target));

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
	
	public List<String> getIncludes() {
		return Collections.unmodifiableList(includes);
	}
	
	public void setIncludes(List<String> includes) {
		this.includes.clear();
		this.includes.addAll(includes);
	}

	public List<String> getExcludes() {
		return Collections.unmodifiableList(excludes);
	}
	
	public void setExcludes(List<String> excludes) {
		this.excludes.clear();
		this.excludes.addAll(excludes);
	}
	
	private static boolean packageMatch(String packageName, String filter) {
		return packageName.equals(filter) || packageName.startsWith(filter + ".");
	}
	
	private boolean checkInclude(String packageName) {
		if (this.includes.isEmpty()) {
			return true;
		}
		
		return this.includes.stream().anyMatch(filter -> packageMatch(packageName, filter));
	}
	
	private boolean checkExclude(String packageName) {
		return this.excludes.stream().anyMatch(filter -> packageMatch(packageName, filter));
	}
	
	private boolean checkFilter(String packageName) {
		return checkInclude(packageName) && !checkExclude(packageName);
	}
	
	public void convert(Path source, Path target) throws NotFoundException, CannotCompileException, IOException, ClassNotFoundException {

		ClassPool classPool = new ClassPool();
		classPool.appendSystemPath();
		classPool.appendClassPath(new LoaderClassPath(this.getClass().getClassLoader()));
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
							
							if (!checkFilter(ctClass.getPackageName())) {
								LOGGER.fine("Excluding " + ctClass.getName());
								return FileVisitResult.CONTINUE;
							}
							
							LOGGER.fine(ctClass.getName());

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
