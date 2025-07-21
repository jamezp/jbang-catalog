/// usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.7

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */

@Command(name = "zip-util", description = "Similar to the unzip or jar command, but allows recursive listings or extractions.",
        showDefaultValues = true, subcommands = AutoComplete.GenerateCompletion.class)
public class ziputil implements Callable<Integer> {

    @Parameters(arity = "1", description = "The archived file to process.", defaultValue = ".")
    private Path file;

    // TODO (jrp) add this back when we enable extract
    //@Option(names = {"-d", "--extract-to"}, description = "The directory where to extract the contents of the archive to.", defaultValue = ".")
    private Path extractTo;

    @Option(names = {"-e", "--exclude"}, description = "A glob pattern for the files to exclude.")
    private String exclude;

    @Option(names = {"-i", "--include"}, description = "A glob pattern for the files to include.")
    private String include;

    @Option(names = {"-p", "--print"}, description = "Prints the contents of a file to the console.")
    private boolean print;

    @Option(names = {"-r", "--recursive"}, description = "Recursively processes the file.")
    private boolean recursive;

    @Option(names = {"--reversed"}, description = "Reverses the sort order of the results.")
    private boolean reversed;

    @Option(names = {"-s", "--sort-by"}, description = "The order to sort the results. The options are ${COMPLETION-CANDIDATES}", defaultValue = "name")
    private SortBy sortBy;

    @SuppressWarnings("unused")
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help message")
    private boolean usageHelpRequested;

    @Option(names = {"-v", "--verbose"}, description = "Prints verbose output.")
    private boolean verbose;

    // TODO (jrp) we should probably support this
    //@Option(names = {"-x", "--extract"}, description = "Extracts the contents of the file")
    private boolean extract = false;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    private PrintWriter writer;
    private CommandLine.Help.Ansi ansi;

    public static void main(String... args) {
        final CommandLine commandLine = new CommandLine(new ziputil());
        // Generate the completion command
        final var gen = commandLine.getSubcommands().get("generate-completion");
        gen.getCommandSpec().usageMessage().hidden(true);
        final int exitStatus = commandLine.execute(args);
        System.exit(exitStatus);
    }

    @Override
    public Integer call() throws Exception {
        if (!isZip(file)) {
            throw new CommandLine.ExecutionException(spec.commandLine(), "The file must be a ZIP, JAR, EAR, WAR, etc.");
        }

        // Process the file
        try (FileSystem fs = zipFs(file)) {
            final var comparator = (reversed ? sortBy.comparator.reversed() : sortBy.comparator);
            final Set<PathDescription> files = new TreeSet<>(comparator);
            collectContents(fs, file.getFileName().toString(), fs.getSeparator(), createFilter(), files);

            String archiveName = "";
            for (PathDescription f : files) {
                if (!archiveName.equals(f.archiveName())) {
                    print("@|bold,green %s|@", f.archiveName());
                    archiveName = f.archiveName();
                }
                if (verbose) {
                    print(4, "%10d %tc %s", f.attributes().size(), f.attributes()
                            .creationTime()
                            .toMillis(), f.path());
                } else {
                    print(4, "%s", f.path());
                }
            }
            ;
        }
        return 0;
    }

    private void collectContents(final FileSystem fs, final String archivePath, final String path, final PathFilter filter, final Set<PathDescription> entries) throws IOException {
        final var baseDir = fs.getPath(path);
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(baseDir, filter)) {
            for (final Path childPath : dirStream) {
                // Check if the archive path is excluded or the child path is excluded
                if (!filter.isExcluded(Path.of(archivePath)) && filter.accept(childPath, false)) {
                    // If print was enabled and this is a file, print the content
                    if (print && Files.isRegularFile(childPath)) {
                        print("@|bold,green %s%s|@", archivePath, childPath);
                        Files.copy(childPath, System.out);
                        print("@|bold ===== EOF =====|@");
                    } else {
                        final BasicFileAttributes attrs = Files.readAttributes(childPath, BasicFileAttributes.class);
                        final Map<String, Object> allFileAttributes = new LinkedHashMap<>();
                        for (var name : fs.supportedFileAttributeViews()) {
                            allFileAttributes.putAll(Files.readAttributes(childPath, name + ":*"));
                        }
                        entries.add(new PathDescription(archivePath, childPath.toString(), attrs, Files.readAttributes(childPath, PosixFileAttributes.class), allFileAttributes));
                    }
                }
                if (Files.isDirectory(childPath)) {
                    // Recursively process files
                    collectContents(fs, archivePath, childPath.toString(), filter, entries);
                } else {
                    if (recursive && isZip(childPath)) {
                        if (!extract) {
                            final var tempDir = Files.createTempDirectory("jarutil-" + childPath.getFileName());
                            try {
                                final var tempFile = tempDir.resolve(childPath.getFileName().toString());
                                Files.createDirectories(tempFile.getParent());
                                Files.copy(childPath, tempFile, StandardCopyOption.REPLACE_EXISTING);
                                try (var zipFs = zipFs(tempFile)) {
                                    collectContents(zipFs, archivePath + childPath, zipFs.getSeparator(), filter, entries);
                                }
                            } finally {
                                deleteDir(tempDir);
                            }
                        }
                    }
                }
            }
        }
    }

    private PathFilter createFilter() {
        final PathMatcher includeMatcher;
        if (include != null) {
            includeMatcher = FileSystems.getDefault().getPathMatcher("glob:" + include);
        } else {
            includeMatcher = null;
        }
        final PathMatcher excludeMatcher;
        if (exclude != null) {
            excludeMatcher = FileSystems.getDefault().getPathMatcher("glob:" + exclude);
        } else {
            excludeMatcher = null;
        }
        return new PathFilter(includeMatcher, excludeMatcher);
    }

    private void print(final String fmt, final Object... args) {
        print(0, fmt, args);
    }

    private void print(final int padding, final String fmt, final Object... args) {
        final PrintWriter writer = getWriter();
        if (padding > 0) {
            writer.printf("%1$" + padding + "s", " ");
        }
        writer.println(format(fmt, args));
    }

    private PrintWriter getWriter() {
        if (writer == null) {
            writer = spec.commandLine().getOut();
        }
        return writer;
    }

    private String format(final String fmt, final Object... args) {
        if (ansi == null) {
            ansi = spec.commandLine().getColorScheme().ansi();
        }
        return format(ansi, String.format(fmt, args));
    }

    private String format(final CommandLine.Help.Ansi ansi, final String value) {
        return ansi.string(value);
    }

    private static boolean isZip(final Path file) throws IOException {
        if (Files.isDirectory(file)) {
            return false;
        }
        // Check if this is a zip file
        try (InputStream in = Files.newInputStream(file)) {
            // Read the first 4 bytes
            final byte[] bytes = new byte[4];
            final int len = in.read(bytes);
            if (len == 4) {
                // The first 4 bytes are the header, we'll check to see if it's a zip file
                final int header = bytes[0] + (bytes[1] << 8) + (bytes[2] << 16) + (bytes[3] << 24);
                if (0x04034b50 == header) {
                    return true;
                }
            }
        }
        return false;
    }

    private static FileSystem zipFs(final Path path) throws IOException {
        final URI uri = URI.create("jar:" + path.toUri());
        try {
            return FileSystems.getFileSystem(uri);
        } catch (FileSystemNotFoundException ignore) {
        }
        return FileSystems.newFileSystem(uri, Map.of("enablePosixFileAttributes", "true"));
    }

    private static void deleteDir(final Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record PathFilter(PathMatcher includeMatcher,
                              PathMatcher excludeMatcher) implements DirectoryStream.Filter<Path> {

        @Override
        public boolean accept(final Path entry) throws IOException {
            return accept(entry, true);
        }

        boolean accept(final Path entry, final boolean includeDirsAndArchives) throws IOException {
            if (includeMatcher == null && excludeMatcher == null) {
                return true;
            }
            if (includeDirsAndArchives && (Files.isDirectory(entry) || isZip(entry))) {
                return true;
            }
            if (includeMatcher != null && excludeMatcher != null) {
                return !includeMatcher.matches(entry) && excludeMatcher.matches(entry);
            }
            if (includeMatcher != null) {
                return includeMatcher.matches(entry);
            }
            return !excludeMatcher.matches(entry);
        }

        boolean isExcluded(final Path entry) {
            if (excludeMatcher == null) {
                return false;
            }
            return excludeMatcher.matches(entry);
        }
    }

    private record PathDescription(String archiveName, String path,
                                   BasicFileAttributes attributes, PosixFileAttributes posixFileAttributes,
                                   Map<String, Object> allAttributes) {
    }

    private enum SortBy implements Comparator<PathDescription> {
        size(Comparator.comparing(PathDescription::archiveName)
                .thenComparingLong((pd) -> pd.attributes().size())
                .thenComparing(PathDescription::path)),
        name(Comparator.comparing(PathDescription::archiveName).thenComparing(PathDescription::path)),
        lastModifiedTime(Comparator.comparing(PathDescription::archiveName)
                .thenComparing((pd) -> pd.attributes().lastModifiedTime())
                .thenComparing(PathDescription::path)),;
        private final Comparator<PathDescription> comparator;

        SortBy(final Comparator<PathDescription> comparator) {
            this.comparator = comparator;
        }

        @Override
        public int compare(final PathDescription o1, final PathDescription o2) {
            return comparator.compare(o1, o2);
        }
    }
}
