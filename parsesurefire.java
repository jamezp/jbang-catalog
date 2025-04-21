///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DEPS info.picocli:picocli:4.7.7
//DEPS org.jsoup:jsoup:1.19.1

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "parse-surefire-report", description = "Parses a surefire report and reports information.",
        showDefaultValues = true, subcommands = AutoComplete.GenerateCompletion.class)
public class parsesurefire implements Callable<Integer> {

    /**
     * The regular expression for format strings. Ain't regex grand?
     */
    private static final Pattern FORMAT_PATTERN = Pattern.compile(
            // greedily match all non-format characters
            "([^%]++)" +
                    // match a format string...
                    "|(?:%" +
                    // optional minimum width plus justify flag
                    "(?:(-)?(\\d+))?" +
                    // optional maximum width
                    "(?:\\.(-)?(\\d+))?" +
                    // the actual format character
                    "(.)" +
                    // an optional argument string
                    "(?:\\{([^}]*)\\})?" +
                    // end format string
                    ")");

    @Parameters(arity = "1", description = "A surefire XML report or a directory which contains reports.", defaultValue = ".")
    private Path file;

    @Option(names = {"-B", "--batch"}, description = "Batch mode disables any colorization of the output.")
    private boolean batch;

    @Option(names = {"-f", "--format"}, description = {
            "The format pattern to use for the output.",
            "The following is are the options for the summary output:",
            "%%t - the total number of tests",
            "%%p - the total number of passed tests",
            "%%f - the total number of failed tests",
            "%%e - the total number of errored tests",
            "%%s - the total number of skipped tests",
            "%%n - new line",
    }, showDefaultValue = CommandLine.Help.Visibility.NEVER, defaultValue =
            "@|bold,white Total: %t|@ - @|green PASSED: %p|@ - @|red FAILED: %f|@ - @|bold,red ERRORS: %e|@ - @|yellow SKIPPED: %s|@")
    private String format;

    @Option(names = {"-g", "--group"}, description = "Groups the results by the directory the files were found in.", defaultValue = "false")
    private boolean group;

    @Option(names = {"-o", "--output"}, description = "A path to a file used of the output.")
    private String output;

    @Option(names = {"-r", "--report-type"}, description = "The type of output for the report. The options are ${COMPLETION-CANDIDATES}", defaultValue = "total")
    private ReportType reportType;

    @Option(names = {"-s", "--status"}, description = "Print only the specific status. This can be a comma delimited list. The options are ${COMPLETION-CANDIDATES}.",
            split = ",")
    private List<Status> statuses;

    @Option(names = {"--show-path"}, description = "Shows the path to the file parsed")
    private boolean showPath;

    @Option(names = {"--sort-by"}, description = "The order to sort the results. The options are ${COMPLETION-CANDIDATES}", defaultValue = "status")
    private SortBy sortBy;

    @Option(names = {"-t", "--total-time"}, description = "Prints the total time all tests took.")
    private boolean printTotalTime;

    @SuppressWarnings("unused")
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Display this help message")
    private boolean usageHelpRequested;

    @Option(names = {"-v", "--verbose"}, description = "Prints verbose output.")
    private boolean verbose;

    @Spec
    private CommandSpec spec;

    private PrintWriter writer;
    private CommandLine.Help.Ansi ansi;

    public static void main(String... args) {
        final CommandLine commandLine = new CommandLine(new parsesurefire());
        commandLine.setCaseInsensitiveEnumValuesAllowed(true);
        final var gen = commandLine.getSubcommands().get("generate-completion");
        gen.getCommandSpec().usageMessage().hidden(true);
        final int exitStatus = commandLine.execute(args);
        System.exit(exitStatus);
    }

    @Override
    public Integer call() throws Exception {
        final Instant start = Instant.now();
        try {
            final Map<Path, EnumMap<Status, Set<TestResult>>> grouped = parseFile(file);
            final EnumMap<Status, Set<TestResult>> totals = createResultMap();
            for (var group : grouped.entrySet()) {
                final var results = group.getValue();
                if (this.group) {
                    print("@|bold,cyan %s|@", group.getKey());
                    for (Status status : results.keySet()) {
                        totals.get(status).addAll(results.get(status));
                    }
                }
                printTotals(results, reportType.printDetail());
            }
            if (group) {
                print("@|bold All Tests|@");
                printTotals(totals, false);
            }
            return 0;
        } finally {
            if (verbose) {
                spec.commandLine()
                        .getOut()
                        .println(format("@|bold Completed in %s|@", toHumanReadable(Duration.between(start, Instant.now()))));
            }
            if (writer != null && output != null) {
                writer.close();
            }
        }
    }

    private Map<Path, EnumMap<Status, Set<TestResult>>> parseFile(final Path file) throws IOException, InterruptedException {
        // If this is a directory, it cannot be a ZIP file
        if (Files.isDirectory(file)) {
            return parseFile(FileSystems.getDefault(), file);
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
                    final URI uri = URI.create("jar:" + file.toUri());
                    FileSystem zipFs;
                    try {
                        zipFs = FileSystems.getFileSystem(uri);
                    } catch (FileSystemNotFoundException ignore) {
                        zipFs = FileSystems.newFileSystem(uri, Map.of());
                    }
                    return parseFile(zipFs, zipFs.getPath("/"));
                }
            }
        }
        return parseFile(FileSystems.getDefault(), file);
    }

    private Map<Path, EnumMap<Status, Set<TestResult>>> parseFile(final FileSystem fs, final Path file) throws IOException, InterruptedException {
        final Map<Path, EnumMap<Status, Set<TestResult>>> grouped = new TreeMap<>();
        final Lock lock = new ReentrantLock();
        if (Files.isDirectory(file)) {
            final ExecutorService executor = Executors.newWorkStealingPool();
            final var pattern = fs.getPathMatcher("glob:**/TEST-*.xml");
            final var globalResults = createResultMap();
            try (Stream<Path> files = Files.walk(file)) {
                files.filter(pattern::matches)
                        .forEach(p -> executor.submit(() -> {
                            if (verbose) {
                                print(format("@|cyan Processing file %s|@", p));
                            }
                            final EnumMap<Status, Set<TestResult>> results;
                            lock.lock();
                            try {
                                if (group) {
                                    results = grouped.computeIfAbsent(p.getParent(), (current) -> createResultMap());
                                } else {
                                    results = grouped.computeIfAbsent(file, (current) -> globalResults);
                                }
                            } finally {
                                lock.unlock();
                            }
                            parseResults(p, results);
                        }));
            }
            executor.shutdown();
            if (!executor.awaitTermination(60L, TimeUnit.MINUTES)) {
                throw new CommandLine.ExecutionException(spec.commandLine(), "Parsing did not complete within 60 minutes.");
            }
        } else {
            final var results = createResultMap();
            parseResults(file, results);
            grouped.put(file, results);
        }
        return grouped;
    }

    private EnumMap<Status, Set<TestResult>> createResultMap() {
        final EnumMap<Status, Set<TestResult>> map = new EnumMap<>(Status.class);
        map.put(Status.PASSED, ConcurrentHashMap.newKeySet());
        map.put(Status.FAILED, ConcurrentHashMap.newKeySet());
        map.put(Status.ERROR, ConcurrentHashMap.newKeySet());
        map.put(Status.SKIPPED, ConcurrentHashMap.newKeySet());
        return map;
    }

    private void printTotals(final EnumMap<Status, Set<TestResult>> results, final boolean printDetail) {
        BigDecimal totalTime = new BigDecimal("0.000");
        final Set<TestResult> passedResults = new TreeSet<>(results.get(Status.PASSED));
        final Set<TestResult> failedResults = new TreeSet<>(results.get(Status.FAILED));
        final Set<TestResult> errorResults = new TreeSet<>(results.get(Status.ERROR));
        final Set<TestResult> skippedResults = new TreeSet<>(results.get(Status.SKIPPED));

        // Prepare the summary first to validate the format pattern
        final var success = passedResults.size();
        final var failures = failedResults.size();
        final var errors = errorResults.size();
        final var skipped = skippedResults.size();
        final var totalSummary = formatTotal((success + failures + errors + skipped), success, failures, errors, skipped);

        if (printDetail) {
            if (sortBy == SortBy.status) {
                final Map<Status, Set<TestResult>> toPrint = new LinkedHashMap<>(4);
                if (statuses == null || statuses.isEmpty()) {
                    toPrint.put(Status.PASSED, passedResults);
                    toPrint.put(Status.FAILED, failedResults);
                    toPrint.put(Status.ERROR, errorResults);
                    toPrint.put(Status.SKIPPED, skippedResults);
                } else {
                    if (statuses.contains(Status.PASSED)) {
                        toPrint.put(Status.PASSED, passedResults);
                    }
                    if (statuses.contains(Status.FAILED)) {
                        toPrint.put(Status.FAILED, failedResults);
                    }
                    if (statuses.contains(Status.ERROR)) {
                        toPrint.put(Status.ERROR, errorResults);
                    }
                    if (statuses.contains(Status.SKIPPED)) {
                        toPrint.put(Status.SKIPPED, skippedResults);
                    }
                }
                final Collection<Map.Entry<Status, Set<TestResult>>> entries = List.copyOf(toPrint.entrySet());
                for (var entry : entries) {
                    if (entry.getValue().isEmpty()) {
                        print("No %s tests found.", entry.getKey().name());
                    } else {
                        totalTime = totalTime.add(printResult(entry.getKey(), entry.getValue()));
                    }
                    print();
                }
            } else {
                final List<TestResult> combined = new ArrayList<>();
                if (statuses == null || statuses.isEmpty()) {
                    combined.addAll(passedResults);
                    combined.addAll(failedResults);
                    combined.addAll(errorResults);
                    combined.addAll(skippedResults);
                } else {
                    if (statuses.contains(Status.PASSED)) {
                        combined.addAll(passedResults);
                    }
                    if (statuses.contains(Status.FAILED)) {
                        combined.addAll(failedResults);
                    }
                    if (statuses.contains(Status.ERROR)) {
                        combined.addAll(errorResults);
                    }
                    if (statuses.contains(Status.SKIPPED)) {
                        combined.addAll(skippedResults);
                    }
                }
                final Comparator<TestResult> comparator;
                if (sortBy == SortBy.name) {
                    combined.addAll(skippedResults);
                    comparator = Comparator.comparing(testResult -> testResult.className);
                } else {
                    comparator = Comparator.comparing(testResult -> testResult.time);
                }
                combined.sort(comparator);
                totalTime = totalTime.add(printSortedResult(sortBy, combined));
            }
            print();
        } else {
            totalTime = totalTime.add(calculateTotalTime(passedResults));
            totalTime = totalTime.add(calculateTotalTime(failedResults));
            totalTime = totalTime.add(calculateTotalTime(errorResults));
            totalTime = totalTime.add(calculateTotalTime(skippedResults));
        }
        // Print a summary
        // Determine the output length
        final var len = format(CommandLine.Help.Ansi.OFF, totalSummary).length() + 2;
        print("*".repeat(len));
        print(totalSummary);
        print("*".repeat(len));
        if (printTotalTime || verbose) {
            final BigDecimal millis = totalTime.multiply(new BigDecimal(1000));
            print("@|bold,white Total Test Time: %s|@", toHumanReadable(Duration.ofMillis(millis.longValue())));
        }
    }

    private BigDecimal printResult(final Status status, final Set<TestResult> results) {
        final var prefix = status.name().charAt(0) + status.name().substring(1).toLowerCase(Locale.ROOT);
        BigDecimal totalTime = new BigDecimal("0.000");
        print("@|bold,white %s Tests:|@", prefix);
        String currentTest = null;
        int totalCount = 0;
        int count = 0;
        final var countFormat = "@|" + getStatusColor(status) + " Total " + status.toString()
                .toLowerCase(Locale.ROOT) + " for %s: %d|@";
        for (TestResult result : results) {
            if (!result.className.equals(currentTest)) {
                if (count > 0) {
                    print(countFormat, currentTest, count);
                }
                count = 0;
                if (reportType != ReportType.summary) {
                    if (result.title.equals(result.className)) {
                        print("@|cyan %s|@", result.className);
                    } else {
                        print("@|cyan %s: %s|@", result.title, result.className);
                    }
                }
                if (verbose || showPath) {
                    print(4, "@|red file: %s|@", result.file);
                }
            }
            count++;
            totalCount++;
            currentTest = result.className;
            printDetail(result);
            totalTime = totalTime.add(result.time);
        }
        if (count > 0) {
            print(countFormat, currentTest, count);
        }
        if (totalCount > 0) {
            print("@|bold,%s Total %s: %d|@", getStatusColor(status), status.toString().toLowerCase(Locale.ROOT), totalCount);
        }
        return totalTime;
    }

    private BigDecimal printSortedResult(final SortBy sortBy, final List<TestResult> results) {
        String currentTest = null;
        BigDecimal totalTime = new BigDecimal("0.000");
        for (TestResult result : results) {
            if (sortBy == SortBy.name) {
                if (!result.className.equals(currentTest)) {
                    print("@|cyan %s|@", result.className);
                    if (verbose || showPath) {
                        print(4, "@|red file: %s|@", result.file);
                    }
                }
                currentTest = result.className;
                printDetail(result);
            } else {
                totalTime = totalTime.add(result.time);
                print("@|bold,cyan %s.%s|@ @|%s [%s]|@ @|bold,white - Time elapsed: %s|@", result.className, result.testName,
                        getStatusColor(result.status), result.status, result.time);
            }
        }
        if (sortBy == SortBy.time) {
            print("@|bold,white Total Time: %s|@", totalTime);
        }
        return totalTime;
    }

    private void printDetail(final TestResult result) {
        if (reportType != ReportType.summary) {
            print(4, "%s - Time elapsed: %s @|%s [%s]|@", result.testName, result.time,
                    getStatusColor(result.status), result.status);
        }
        if (reportType != ReportType.summary && !result.message.isBlank()) {
            print(8, "Reason: %s", result.message);
        }
        if (verbose && !result.detailMessage.isBlank()) {
            print(8, "Detailed:");
            result.detailMessage.lines()
                    .forEach(line -> print(8, line));
        }
    }

    private String getStatusColor(final Status status) {
        switch (status) {
            case FAILED:
            case ERROR:
                return "red";
            case PASSED:
                return "green";
            case SKIPPED:
                return "yellow";
        }
        return "white";
    }

    private void parseResults(final Path file, final Map<Status, Set<TestResult>> results) {
        try {
            final String xml = Files.readString(file);
            final Document document = Jsoup.parse(xml, Parser.xmlParser());

            final Elements testsuites = document.select("testsuite");
            if (testsuites.isEmpty()) {
                print("No testsuite found in %s", file);
                return;
            }
            parseResults(file, document, results);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void parseResults(final Path file, final Document document, final Map<Status, Set<TestResult>> results) {
        final Set<TestResult> passedResults = results.get(Status.PASSED);
        final Set<TestResult> failedResults = results.get(Status.FAILED);
        final Set<TestResult> errorResults = results.get(Status.ERROR);
        final Set<TestResult> skippedResults = results.get(Status.SKIPPED);

        final Elements testsuites = document.select("testsuite");
        if (testsuites.isEmpty()) {
            return;
        }

        final Elements tests = testsuites.select("testcase");
        for (Element test : tests) {
            final Elements skipped = test.select("skipped");
            if (!skipped.isEmpty()) {
                addTestResult(parseSkipped(file, test, skipped), skippedResults);
                continue;
            }
            final Elements failure = test.select("failure");
            if (!failure.isEmpty()) {
                addTestResult(parseFailed(file, test, failure), failedResults);
                continue;
            }
            final Elements errors = test.select("error");
            if (!errors.isEmpty()) {
                addTestResult(parseError(file, test, errors), errorResults);
                continue;
            }
            addTestResult(TestResult.passed(file, test.attr("classname"), test.attr("name"), createTime(test.attr("time"))),
                    passedResults);
        }
    }

    private BigDecimal calculateTotalTime(final Set<TestResult> results) {
        BigDecimal totalTime = new BigDecimal("0.000");
        for (TestResult result : results) {
            totalTime = totalTime.add(result.time);
        }
        return totalTime;
    }

    private void addTestResult(final TestResult result, final Set<TestResult> results) {
        if (!results.add(result) && verbose) {
            print("@|red Did not add result: %s|@%n@|green Duplicate        : %s|@", result, results.stream()
                    .filter(result::equals)
                    .findFirst()
                    .orElse(null));
        }
    }

    private TestResult parseSkipped(final Path file, final Element test, final Elements skipped) {
        final Element e = skipped.first();
        String message = "";
        if (e != null) {
            message = e.attr("message");
        }
        return TestResult.skipped(file, test.attr("classname"), test.attr("name"), createTime(test.attr("time")), message);
    }

    private TestResult parseFailed(final Path file, final Element test, final Elements failed) {
        final Element failure = failed.first();
        String message = "";
        String detailMessage = "";
        if (failure != null) {
            message = failure.attr("message");
            detailMessage = failure.hasText() ? failure.text() : "";
        }
        return TestResult.failed(file, test.attr("classname"), test.attr("name"), createTime(test.attr("time")), message, detailMessage);
    }

    private TestResult parseError(final Path file, final Element test, final Elements failed) {
        @SuppressWarnings("DuplicatedCode")
        final Element error = failed.first();
        String message = "";
        String detailMessage = "";
        if (error != null) {
            message = error.attr("message");
            detailMessage = error.hasText() ? error.text() : "";
        }
        return TestResult.error(file, test.attr("classname"), test.attr("name"), createTime(test.attr("time")), message, detailMessage);
    }

    private void print() {
        getWriter().println();
    }

    private void print(final String fmt, final Object... args) {
        print(0, fmt, args);
    }

    @SuppressWarnings("SameParameterValue")
    private void print(final int padding, final Object message) {
        final PrintWriter writer = getWriter();
        if (padding > 0) {
            writer.printf("%1$" + padding + "s", " ");
        }
        writer.println(message);
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
            if (output == null) {
                writer = spec.commandLine().getOut();
            } else {
                try {
                    final Path path = Path.of(output);
                    final Path parent = path.getParent();
                    if (parent != null && Files.notExists(parent)) {
                        Files.createDirectories(parent);
                    }
                    Files.deleteIfExists(path);
                    writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return writer;
    }

    private String format(final String fmt, final Object... args) {
        if (ansi == null) {
            ansi = batch || output != null ? CommandLine.Help.Ansi.OFF : spec.commandLine().getColorScheme().ansi();
        }
        return format(ansi, String.format(fmt, args));
    }

    private String format(final CommandLine.Help.Ansi ansi, final String value) {
        return ansi.string(value);
    }

    private String formatTotal(final int totalTests, final int passedTests, final int failedTests, final int erroredTests, final int skippedTests) {
        final Matcher matcher = FORMAT_PATTERN.matcher(format);
        final StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            final String otherText = matcher.group(1);
            if (otherText != null) {
                result.append(otherText);
            } else {
                final String hyphen = matcher.group(2);
                final String minWidthString = matcher.group(3);
                final String widthHyphen = matcher.group(4);
                final String maxWidthString = matcher.group(5);
                final String formatCharString = matcher.group(6);
                final String argument = matcher.group(7);
                final int minimumWidth = minWidthString == null ? 0 : Integer.parseInt(minWidthString);
                final boolean leftJustify = hyphen != null;
                final boolean truncateBeginning = widthHyphen != null;
                final int maximumWidth = maxWidthString == null ? 0 : Integer.parseInt(maxWidthString);
                final char formatChar = formatCharString.charAt(0);
                switch (formatChar) {
                    case 'e': {
                        result.append(erroredTests);
                        break;
                    }
                    case 'f': {
                        result.append(failedTests);
                        break;
                    }
                    case 'n': {
                        result.append(System.lineSeparator());
                        break;
                    }
                    case 'p': {
                        result.append(passedTests);
                        break;
                    }
                    case 's': {
                        result.append(skippedTests);
                        break;
                    }
                    case 't': {
                        result.append(totalTests);
                        break;
                    }
                    default: {
                        throw new CommandLine.ParameterException(spec.commandLine(), "Invalid format character " + formatChar + " in format pattern " + format + ".");
                    }
                }
            }
        }
        return result.toString();
    }

    private BigDecimal createTime(final String time) {
        if (time.isBlank()) {
            return new BigDecimal("0.000");
        }
        try {
            final var n = NumberFormat.getNumberInstance().parse(time);
            return new BigDecimal(n.toString()).setScale(3, RoundingMode.HALF_UP);
        } catch (Exception e) {
            spec.commandLine().getErr().printf("Failed to parse the time %s. Default to 0.000.%n", time);
            return new BigDecimal("0.000");
        }
    }

    private static String toHumanReadable(final Duration duration) {
        final long days = duration.toDaysPart();
        final long hours = duration.toHoursPart();
        final long minutes = duration.toMinutesPart();
        final long seconds = duration.toSecondsPart();
        final long millis = duration.toMillisPart();

        if (days > 0) {
            return String.format("%dd, %02dh, %02dm, %02ds, %03dms", days, hours, minutes, seconds, millis);
        }

        if (hours > 0) {
            return String.format("%02dh, %02dm, %02ds, %03dms", hours, minutes, seconds, millis);
        }
        return String.format("%02dm, %02ds, %03dms", minutes, seconds, millis);
    }

    private enum ReportType {
        detail,
        summary,
        total() {
            @Override
            boolean printDetail() {
                return false;
            }
        };

        boolean printDetail() {
            return true;
        }
    }

    private enum Status {
        PASSED,
        FAILED,
        ERROR,
        SKIPPED
    }

    private enum SortBy {
        status,
        name,
        time
    }

    private static class TestResult implements Comparable<TestResult> {
        private final Path file;
        private final Status status;
        private final String title;
        private final String className;
        private final String testName;
        private final BigDecimal time;
        private final String message;
        private final String detailMessage;

        private TestResult(final Path file, final Status status, final String className, final String testName,
                           final BigDecimal time, final String message, final String detailMessage) {
            this.file = file;
            this.status = status;
            this.title = className;
            this.className = parseTestClassName(file, className);
            this.testName = testName;
            this.time = time;
            this.message = message == null ? "" : message;
            this.detailMessage = detailMessage == null ? "" : detailMessage;
        }

        static TestResult passed(final Path file, final String className, final String testName,
                                 final BigDecimal time) {
            return new TestResult(file, Status.PASSED, className, testName, time, null, null);
        }

        static TestResult skipped(final Path file, final String className, final String testName, final BigDecimal time,
                                  final String message) {
            return new TestResult(file, Status.SKIPPED, className, testName, time, message, null);
        }

        static TestResult failed(final Path file, final String className, final String testName, final BigDecimal time,
                                 final String message, final String detailMessage) {
            return new TestResult(file, Status.FAILED, className, testName, time, message, detailMessage);
        }

        static TestResult error(final Path file, final String className, final String testName, final BigDecimal time,
                                final String message, final String detailMessage) {
            return new TestResult(file, Status.ERROR, className, testName, time, message, detailMessage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(className, testName, status);
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof TestResult)) {
                return false;
            }
            final TestResult other = (TestResult) obj;
            return Objects.equals(className, other.className)
                    && Objects.equals(testName, other.testName)
                    && Objects.equals(status, other.status);
        }

        @Override
        public String toString() {
            return className + "#" + testName + ": " + status;
        }

        @Override
        public int compareTo(final TestResult o) {
            int result = className.compareTo(o.className);
            if (result != 0) {
                return result;
            }
            result = testName.compareTo(o.testName);
            if (result != 0) {
                return result;
            }
            return status.compareTo(o.status);
        }

        private static String parseTestClassName(final Path file, final String className) {
            final var fileName = file.getFileName().toString();
            if (fileName.contains(className) || fileName.equalsIgnoreCase("TEST-TestSuite.xml")) {
                return className;
            }
            // The file name should be TEST-${testClassName}.xml
            final int start = fileName.indexOf("TEST-");
            final int end = fileName.indexOf(".xml");
            if (start != -1 && end != -1) {
                return fileName.substring(start + 5, end).trim();
            }
            return className;
        }
    }
}
