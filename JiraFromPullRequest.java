///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.7
//DEPS jakarta.json:jakarta.json-api:2.1.3
//DEPS org.eclipse.parsson:parsson:1.1.7
//DEPS org.jsoup:jsoup:1.21.2


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.stream.JsonGenerator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "jira-from-pr", mixinStandardHelpOptions = true, version = "jira-from-pr 0.1",
        description = "Create a JIRA based on a GitHub PR. Note this is very specific to Red Hat JIRA.",
        showDefaultValues = true, subcommands = AutoComplete.GenerateCompletion.class)
class JiraFromPullRequest implements Callable<Integer> {

    @Option(names = "--dry-run", description = "Indicates this should be a dry run and no updates will be performed.")
    private boolean dryRun;

    @Option(names = {"--issue-type"}, description = "The name of the issue type, e.g. \"Component Upgrade\", \"Bug\", etc.", required = true, defaultValue = "Component Upgrade")
    private String issueType;

    @Option(names = {"-p", "--project"}, description = "The JIRA project id", required = true)
    private String project;

    @Option(names = {"-o", "--organization"}, description = "The GitHub organization to look for the PR on.", required = true)
    private String organization;

    @Option(names = {"-r", "--repository"}, description = "The GitHub repository to look for the PR on.", required = true)
    private String repository;

    @Option(names = {"-v", "--verbose"}, description = "Turns on more verbose output.", defaultValue = "false")
    private boolean verbose;

    @Parameters(arity = "1..*", description = {"Pull request numbers to update with the newly created JIRA.",
            "Only one JIRA will be created and each pull request will be updated with links to the JIRA.",
            "The first pull request is used to get the data for the JIRA."
    })
    private String[] pullRequestIds;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    private PrintWriter writer;
    private CommandLine.Help.Ansi ansi;

    public static void main(String... args) {
        final int exitCode = new CommandLine(new JiraFromPullRequest()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try {
            final var githubConfigFile = resolveConfigFile(".github");
            final var jiraConfigFile = resolveConfigFile(".jira");
            if (Files.notExists(githubConfigFile)) {
                throw new ValidationException("File %s does not exist.", githubConfigFile);
            }
            if (Files.notExists(jiraConfigFile)) {
                throw new ValidationException("File %s does not exist.", jiraConfigFile);
            }
            // login and oauth
            final Properties githubConfig = loadProperties(githubConfigFile);
            // token
            final Properties jiraConfig = loadProperties(jiraConfigFile);

            // Validate the github configuration
            final String oauth = githubConfig.getProperty("oauth");
            if (isNullOrEmpty(oauth)) {
                throw new ValidationException("Missing the login and/or oauth configuration properties in %s", githubConfigFile);
            }

            final String username = jiraConfig.getProperty("username");
            final String token = jiraConfig.getProperty("token");
            if (isNullOrEmpty(token) || isNullOrEmpty(username)) {
                throw new ValidationException("Missing the token and/or username configuration properties in %s", jiraConfigFile);
            }

            if (pullRequestIds == null || pullRequestIds.length == 0) {
                throw new CommandLine.ParameterException(spec.commandLine(), "At least one pull request must be provided as an argument.");
            }

            // Get the PR information
            final String githubBaseUri = githubConfig.getProperty("endpoint", "https://api.github.com");

            final String issueJiraUri = "https://issues.redhat.com/rest/api/2/issue";

            try (HttpClient client = HttpClient.newHttpClient()) {
                final Collection<String> pullRequestLinks = new ArrayList<>();
                String jiraId = null;
                for (String pullRequestId : pullRequestIds) {
                    final URI prUri = createUri(githubBaseUri, "repos", organization, repository, "pulls", pullRequestId);
                    final HttpRequest request = createGitHubRequest(prUri, oauth).build();
                    final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() != 200) {
                        throw new ValidationException("Request to %s ended with a status code of %d: %s", githubBaseUri, response.statusCode(), toString(response.body()));
                    }
                    try (
                            InputStream inputStream = response.body();
                            JsonReader reader = Json.createReader(inputStream)
                    ) {
                        final var json = reader.readObject();
                        if (!json.getString("state").equals("open")) {
                            // Only throw an exception if the first PR is not open. Print an error, but ignore for
                            // additional PR's.
                            if (jiraId == null) {
                                throw new ValidationException("Pull request %s is not open. The state is %s", pullRequestId, json.getString("state"));
                            } else {
                                printError("Pull request %s is not open and will not be updated. The state is %s", pullRequestId, json.getString("state"));
                                continue;
                            }
                        }
                        final String title = json.getString("title");
                        final String body = json.getString("body");
                        pullRequestLinks.add(json.getString("html_url"));

                        // Only create the JIRA if it was not previously created
                        if (jiraId == null) {

                            // We need to parse the body to get only the parts we need
                            final String description = attemptDescriptionCleanUp(body);
                            final JsonObject jiraPayload = createJiraPayload(username, title, description, json);
                            print("Creating JIRA for pull request %s", pullRequestId);
                            final JsonObject v;
                            if (dryRun) {
                                print("JIRA would be created with the following payload: %n%s", toString(jiraPayload));
                                // Create a fake response result
                                v = Json.createObjectBuilder()
                                        .add("key", project + "-XXXX")
                                        .build();
                            } else {
                                final HttpRequest jiraRequest = createJiraRequest(URI.create(issueJiraUri), token)
                                        .setHeader("content-type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(jiraPayload.toString()))
                                        .build();
                                final HttpResponse<InputStream> jiraResponse = client.send(jiraRequest, HttpResponse.BodyHandlers.ofInputStream());
                                if (jiraResponse.statusCode() != 201) {
                                    throw new ValidationException("JIRA issue not created for PR %s. The HTTP status is %d: %s", pullRequestId, jiraResponse.statusCode(), toString(response.body()));
                                }
                                try (JsonReader responseReader = Json.createReader(jiraResponse.body())) {
                                    v = responseReader.readObject();
                                }
                            }
                            // Get the JIRA id
                            jiraId = v.getString("key");
                        }
                        final var newTitle = String.format("[%s] %s", jiraId, title);
                        // Create a patch update
                        final var patchBody = Json.createObjectBuilder()
                                .add("title", newTitle)
                                .add("body", String.format("Issue: https://issues.redhat.com/browse/%s\n\n%s", jiraId, body))
                                .build();
                        print("Updating pull request %s with new title: %s", pullRequestId, newTitle);
                        if (dryRun) {
                            print("Patch to update PR %s%n%s", pullRequestId, toString(patchBody));
                        } else {
                            final HttpRequest patchRequest = createGitHubRequest(prUri, oauth)
                                    .method("PATCH", HttpRequest.BodyPublishers.ofString(patchBody.toString()))
                                    .build();
                            final HttpResponse<String> patchResponse = client.send(patchRequest, HttpResponse.BodyHandlers.ofString());
                            // Check the status of the update
                            if (patchResponse.statusCode() != 200) {
                                throw new ValidationException("Failed to update GitHub PR title for PR %s. HTTP status %d: %s", pullRequestId, patchResponse.statusCode(), patchResponse.body());
                            }
                        }
                    }
                }

                // Transition the JIRA
                final var transitionPayload = Json.createObjectBuilder()
                        // Link Pull Request -> Pull Request Sent
                        .add("transition", Json.createObjectBuilder().add("id", "711"))
                        .add("fields", Json.createObjectBuilder()
                                .add("customfield_12310220", String.join(",", pullRequestLinks)))
                        .build();
                // Git Pull Request field, hard-coding for now
                if (dryRun) {
                    print("Attempting to transition the %s to the status %s", jiraId, transitionPayload);
                } else {
                    print("Transitioning JIRA %s to Pull Request Sent and linking pull request %s", jiraId, String.join(",", pullRequestLinks));
                    // Finally transition the PR to Pull Request Sent
                    final HttpRequest transitionRequest = createJiraRequest(createUri(issueJiraUri, jiraId, "transitions"), token)
                            .setHeader("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(transitionPayload.toString()))
                            .build();
                    final HttpResponse<String> transitionResponse = client.send(transitionRequest, HttpResponse.BodyHandlers.ofString());
                    if (transitionResponse.statusCode() != 204) {
                        throw new ValidationException("Failed to transition JIRA to Pull Request Sent.  HTTP status %d: %s", transitionResponse.statusCode(), transitionResponse.body());
                    }
                }
            }
        } catch (Exception e) {
            printError(e.getMessage());
            if (verbose) {
                try (
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw)
                ) {
                    e.printStackTrace(pw);
                    pw.flush();
                    printError(sw.toString());
                }
            }
            return spec.exitCodeOnExecutionException();
        }
        return spec.exitCodeOnSuccess();
    }

    private HttpRequest.Builder createGitHubRequest(final URI uri, final String oauth) {
        return HttpRequest.newBuilder(uri)
                .setHeader("accept", "application/vnd.github+json")
                .setHeader("authorization", "Bearer " + oauth);
    }

    private HttpRequest.Builder createJiraRequest(final URI uri, final String token) {
        return HttpRequest.newBuilder(uri)
                .setHeader("authorization", "Bearer " + token)
                .setHeader("accept", "application/json");
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

    private void printError(final String fmt, final Object... args) {
        final var errorFmt = "@|red " + fmt + "|@";
        spec.commandLine().getErr().println(format(errorFmt, args));
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

    private JsonObject createJiraPayload(final String username, final String title, final String description, final JsonObject json) {

        final var jsonBuilder = Json.createObjectBuilder();

        final var fields = Json.createObjectBuilder();

        fields.add("project", Json.createObjectBuilder().add("key", project));

        fields.add("summary", title);
        fields.add("description", description);
        fields.add("issuetype", Json.createObjectBuilder().add("name", issueType));
        fields.add("assignee", Json.createObjectBuilder().add("name", username));

        jsonBuilder.add("fields", fields);

        return jsonBuilder.build();
    }

    private static String attemptDescriptionCleanUp(final String description) {
        final Pattern mdLinkPattern = Pattern.compile("(\\[.*)]\\((http.*)\\)(.*)");
        final Pattern inlineCodePattern = Pattern.compile("`(.*)`");
        final StringBuilder builder = new StringBuilder();

        boolean inDetails = false;

        final StringBuilder html = new StringBuilder();

        for (String line : description.lines().toList()) {
            if (line.isEmpty()) {
                builder.append('\n');
                continue;
            }
            // If the line includes @dependabot, we're at instructions we can ignore
            if (line.contains("@dependabot")) {
                break;
            }
            final var inLineCodeMatcher = inlineCodePattern.matcher(line);
            line = inLineCodeMatcher.replaceAll("{{$1}}");
            final var matcher = mdLinkPattern.matcher(line);
            if (matcher.matches()) {
                builder.append(matcher.replaceFirst("$1|$2]$3"));
            } else {
                // If this line is a <detail> tag, we're inside HTML so we will process until we find the ending tag
                if ("<details>".equals(line)) {
                    inDetails = true;
                    continue;
                }
                if ("</details>".equals(line)) {
                    inDetails = false;
                    final Document document = Jsoup.parseBodyFragment(html.toString());
                    html.setLength(0);
                    appendDetails(document, builder);
                    continue;
                }
                if (inDetails) {
                    html.append(line);
                } else {
                    builder.append(line).append("\n");
                }
            }
        }
        // Clear any HTML breaks before returning
        final Pattern htmlBreakPattern = Pattern.compile("<br\\s*/?>");
        return htmlBreakPattern.matcher(builder).replaceAll("");
    }

    private static void appendDetails(final Document document, final StringBuilder builder) {
        final var summary = document.selectFirst("summary");
        if (summary != null && summary.text().contains("Dependabot commands and options")) {
            return;
        }
        // Get all the details
        final var listItems = document.select("li");
        final var iterator = listItems.iterator();
        while (iterator.hasNext()) {
            final var listItem = iterator.next();
            builder.append("* ");
            final var a = listItem.select("a").first();
            if (a != null) {
                final var code = a.selectFirst("code");
                if (code == null) {
                    builder.append(listItem.nodeValue());
                    builder.append('[').append(a.nodeValue()).append('|').append(a.attr("href")).append(']');
                } else {
                    builder.append("[{{").append(code.text()).append("}}|").append(a.attr("href")).append(']');
                    builder.append(' ').append(listItem.nodeValue());
                }
            } else {
                builder.append(listItem.text());
            }
            if (iterator.hasNext()) {
                builder.append('\n');
            }
        }
    }

    private static URI createUri(final String baseUri, final String... paths) {
        final StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(baseUri);
        for (String path : paths) {
            if (uriBuilder.charAt(uriBuilder.length() - 1) != '/' && path.charAt(0) != '/') {
                uriBuilder.append('/');
            }
            uriBuilder.append(path);
        }
        return URI.create(uriBuilder.toString());
    }

    private static boolean isNullOrEmpty(final String value) {
        return value == null || value.isEmpty();
    }

    private static Properties loadProperties(final Path config) throws IOException {
        final Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String toString(final JsonObject json) {
        final var factory = Json.createGeneratorFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true));
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = factory.createGenerator(writer)) {
            generator.write(json);
        }
        return writer.toString();
    }

    private static String toString(final InputStream inputStream) throws IOException {
        try (
                inputStream;
                final JsonReader reader = Json.createReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
        ) {
            return toString(reader.readObject());
        }
    }

    private static Path resolveConfigFile(final String filename) {
        return Path.of(System.getProperty("user.home"), filename);
    }

    private static class ValidationException extends RuntimeException {

        public ValidationException(final String fmt, final Object... args) {
            super(String.format(fmt, args));
        }
    }
}
