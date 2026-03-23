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
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.stream.JsonGenerator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "jira-from-pr", mixinStandardHelpOptions = true, version = "jira-from-pr 0.1",
        description = "Create a JIRA based on a GitHub PR. Note this is very specific to Red Hat JIRA.",
        showDefaultValues = true, subcommands = AutoComplete.GenerateCompletion.class)
class JiraFromPullRequest implements Callable<Integer> {

    private static final Pattern MARKDOWN_PATTERN = Pattern.compile("\\[(?<linkText>[^]]+)]\\((?<url>[^)]+)\\)|`(?<code>[^`]+)`");

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
            final String baseJiraUri = "%s/rest/api/3".formatted(jiraConfig.getProperty("baseUri", "https://redhat.atlassian.net"));

            try (HttpClient client = HttpClient.newHttpClient()) {
                // Validate the issue type
                if (!validateIssueType(client, baseJiraUri, username, token)) {
                    throw new ValidationException("Issue type \"%s\" is not valid for project %s. Please provide a valid --issue-type with a valid name.", issueType, project);
                }
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
                            final JsonObject jiraPayload = createJiraPayload(username, title, body);
                            print("Creating JIRA for pull request %s", pullRequestId);
                            final JsonObject v;
                            if (dryRun) {
                                print("JIRA would be created with the following payload: %n%s", toString(jiraPayload));
                                // Create a fake response result
                                v = Json.createObjectBuilder()
                                        .add("key", project + "-XXXX")
                                        .build();
                            } else {
                                final HttpRequest jiraRequest = createJiraRequest(createUri(baseJiraUri, "issue"), username, token)
                                        .setHeader("content-type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(jiraPayload.toString()))
                                        .build();
                                final HttpResponse<InputStream> jiraResponse = client.send(jiraRequest, HttpResponse.BodyHandlers.ofInputStream());
                                if (jiraResponse.statusCode() != 201) {
                                    throw new ValidationException("JIRA issue not created for PR %s. The HTTP status is %d: %s", pullRequestId, jiraResponse.statusCode(), toString(jiraResponse.body()));
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
                                .add("body", String.format("Issue: https://redhat.atlassian.net/browse/%s\n\n%s", jiraId, body))
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
                                .add("customfield_10875", createPrLinkList(pullRequestLinks)))
                        .build();
                // Git Pull Request field, hard-coding for now
                if (dryRun) {
                    print("Attempting to transition the %s to the status %s", jiraId, transitionPayload);
                } else {
                    print("Transitioning JIRA %s to Pull Request Sent and linking pull request %s", jiraId, String.join(",", pullRequestLinks));
                    // Finally transition the PR to Pull Request Sent
                    final HttpRequest transitionRequest = createJiraRequest(createUri(baseJiraUri, "issue", jiraId, "transitions"), username, token)
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

    private HttpRequest.Builder createJiraRequest(final URI uri, final String username, final String token) {
        final String encoded = Base64.getEncoder()
                .encodeToString("%s:%s".formatted(username, token).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri)
                .setHeader("authorization", "Basic %s".formatted(encoded))
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

    private JsonObject createJiraPayload(final String username, final String title, final String body) {

        final var jsonBuilder = Json.createObjectBuilder();

        final var fields = Json.createObjectBuilder();

        fields.add("project", Json.createObjectBuilder().add("key", project));

        fields.add("summary", title);

        final JsonObjectBuilder content = Json.createObjectBuilder();
        content.add("type", "doc");
        content.add("version", 1);
        addDescription(content, body);
        fields.add("description", content);

        fields.add("issuetype", Json.createObjectBuilder().add("name", issueType));
        fields.add("assignee", Json.createObjectBuilder().add("name", username));

        jsonBuilder.add("fields", fields);

        return jsonBuilder.build();
    }

    private boolean validateIssueType(final HttpClient client, final String uri, final String username, final String token) throws IOException, InterruptedException {
        final HttpRequest request = createJiraRequest(createUri(uri, "project", project), username, token)
                .GET()
                .build();
        final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (JsonReader reader = Json.createReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            final JsonObject object = reader.readObject();
            if (response.statusCode() != 200) {
                throw new ValidationException("Could not resolve issue types for %s: Status=%d%n%s", project, response.statusCode(), object);
            }
            final var issueTypes = object.getJsonArray("issueTypes");
            for (final var type : issueTypes) {
                final var name = type.asJsonObject().getString("name");
                if (issueType.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addDescription(final JsonObjectBuilder jsonDescription, final String description) {
        final var mainContent = Json.createArrayBuilder();
        final StringBuilder htmlBuffer = new StringBuilder();
        boolean inDetails = false;

        for (String line : description.lines().toList()) {
            String trimmedLine = line.trim();

            // Skip noise
            if (trimmedLine.isEmpty() || trimmedLine.matches("(?i)<br\\s*/?>")) continue;
            if (line.contains("@dependabot") || line.contains("Dependabot compatibility score")) break;

            // Horizontal Rule Support
            if (trimmedLine.equals("---")) {
                mainContent.add(Json.createObjectBuilder().add("type", "rule"));
                continue;
            }

            // HTML Details Toggle
            if ("<details>".equalsIgnoreCase(trimmedLine)) {
                inDetails = true;
                continue;
            }
            if ("</details>".equalsIgnoreCase(trimmedLine)) {
                inDetails = false;
                final Document document = Jsoup.parseBodyFragment(htmlBuffer.toString());
                htmlBuffer.setLength(0);
                appendHtmlAsAdf(document.body(), mainContent, false);
                continue;
            }

            if (inDetails) {
                htmlBuffer.append(line).append(" ");
                continue;
            }

            // Markdown Paragraph Parser
            final var paragraphContent = Json.createArrayBuilder();
            final Matcher matcher = MARKDOWN_PATTERN.matcher(line);
            int lastEnd = 0;

            while (matcher.find()) {
                if (matcher.start() > lastEnd) {
                    paragraphContent.add(createTextContent(line.substring(lastEnd, matcher.start())));
                }

                if (matcher.group("linkText") != null) {
                    paragraphContent.add(createLink(matcher.group("linkText"), matcher.group("url")));
                } else if (matcher.group("code") != null) {
                    paragraphContent.add(createTextContent(matcher.group("code"), "code", null));
                }
                lastEnd = matcher.end();
            }

            if (lastEnd < line.length()) {
                paragraphContent.add(createTextContent(line.substring(lastEnd)));
            }

            mainContent.add(Json.createObjectBuilder()
                    .add("type", "paragraph")
                    .add("content", paragraphContent));
        }
        jsonDescription.add("content", mainContent);
    }

    private static void appendHtmlAsAdf(final Node rootNode, final JsonArrayBuilder mainContent, final boolean isInBlockquote) {
        for (Node child : rootNode.childNodes()) {
            if (child instanceof Element el) {
                String tagName = el.tagName().toLowerCase();
                switch (tagName) {
                    case "h1", "h2", "h3", "h4" -> {
                        // ADF Rule: blockquote cannot contain headings. Convert to bold paragraph if nested.
                        if (isInBlockquote) {
                            mainContent.add(Json.createObjectBuilder()
                                    .add("type", "paragraph")
                                    .add("content", Json.createArrayBuilder()
                                            .add(createTextContent(el.text(), "strong", null))));
                        } else {
                            mainContent.add(createHeading(el.text(), Integer.parseInt(tagName.substring(1))));
                        }
                    }
                    case "blockquote" -> {
                        final var quoteContent = Json.createArrayBuilder();
                        // Recurse with isInBlockquote = true
                        appendHtmlAsAdf(el, quoteContent, true);
                        mainContent.add(Json.createObjectBuilder()
                                .add("type", "blockquote")
                                .add("content", quoteContent));
                    }
                    case "ul", "ol" -> {
                        final var listContent = Json.createArrayBuilder();
                        for (Element li : el.select("> li")) {
                            final var liParaContent = Json.createArrayBuilder();
                            processInlines(li, liParaContent);
                            listContent.add(createListItem(liParaContent.build()));
                        }
                        mainContent.add(Json.createObjectBuilder()
                                .add("type", tagName.equals("ul") ? "bulletList" : "orderedList")
                                .add("content", listContent));
                    }
                    case "p" -> {
                        final var pInlines = Json.createArrayBuilder();
                        processInlines(el, pInlines);
                        var builtInlines = pInlines.build();
                        if (!builtInlines.isEmpty()) {
                            mainContent.add(Json.createObjectBuilder()
                                    .add("type", "paragraph")
                                    .add("content", builtInlines));
                        }
                    }
                    case "summary" -> mainContent.add(Json.createObjectBuilder()
                            .add("type", "paragraph")
                            .add("content", Json.createArrayBuilder()
                                    .add(createTextContent(el.text(), "strong", null))));
                    case "hr" -> mainContent.add(Json.createObjectBuilder().add("type", "rule"));
                    default -> appendHtmlAsAdf(el, mainContent, isInBlockquote);
                }
            } else if (child instanceof TextNode tn) {
                String text = tn.getWholeText();
                if (!text.isBlank()) {
                    mainContent.add(Json.createObjectBuilder()
                            .add("type", "paragraph")
                            .add("content", Json.createArrayBuilder().add(createTextContent(text))));
                }
            }
        }
    }

    private static void processInlines(final Node node, final JsonArrayBuilder contentBuilder) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode tn) {
                final String text = tn.getWholeText();
                if (!text.isEmpty()) {
                    contentBuilder.add(createTextContent(text));
                }
            } else if (child instanceof Element el) {
                final String tagName = el.tagName().toLowerCase();
                // Map HTML tags to ADF Marks
                switch (tagName) {
                    case "a" -> contentBuilder.add(createLink(el.text(), el.attr("href")));
                    case "code" -> contentBuilder.add(createTextContent(el.text(), "code", null));
                    case "strong", "b" -> contentBuilder.add(createTextContent(el.text(), "strong", null));
                    case "em", "i" -> contentBuilder.add(createTextContent(el.text(), "em", null));
                    // If it's a span or something else, just keep digging for text
                    default -> processInlines(el, contentBuilder);
                }
            }
        }
    }

    private static JsonObjectBuilder createHeading(final String text, final int level) {
        return Json.createObjectBuilder()
                .add("type", "heading")
                .add("attrs", Json.createObjectBuilder().add("level", level))
                .add("content", Json.createArrayBuilder().add(createTextContent(text)));
    }

    private static JsonObject createListItem(final JsonArray listParagraphContent) {
        return Json.createObjectBuilder()
                .add("type", "listItem")
                .add("content", Json.createArrayBuilder()
                        .add(Json.createObjectBuilder()
                                .add("type", "paragraph")
                                .add("content", listParagraphContent)))
                .build();
    }

    private static JsonObjectBuilder createTextContent(final String text) {
        return Json.createObjectBuilder().add("type", "text").add("text", text);
    }

    private static JsonObjectBuilder createTextContent(final String text, final String markType, final Map<String, String> attrs) {
        final var node = Json.createObjectBuilder().add("type", "text").add("text", text);
        final var mark = Json.createObjectBuilder().add("type", markType);
        if (attrs != null && !attrs.isEmpty()) {
            var attrObj = Json.createObjectBuilder();
            attrs.forEach(attrObj::add);
            mark.add("attrs", attrObj);
        }
        node.add("marks", Json.createArrayBuilder().add(mark));
        return node;
    }

    private static JsonObjectBuilder createLink(final String text, final String url) {
        return createTextContent(text, "link", Map.of("href", url));
    }

    private static URI createUri(final String baseUri, final String... paths) {
        return createUri(baseUri, Map.of(), paths);
    }

    private static URI createUri(final String baseUri, final Map<String, String> queryParams, final String... paths) {
        final StringBuilder uriBuilder = new StringBuilder();
        uriBuilder.append(baseUri);
        for (String path : paths) {
            if (uriBuilder.charAt(uriBuilder.length() - 1) != '/' && path.charAt(0) != '/') {
                uriBuilder.append('/');
            }
            uriBuilder.append(path);
        }
        final AtomicBoolean first = new AtomicBoolean(true);
        queryParams.forEach((key, value) -> {
            if (first.compareAndSet(true, false)) {
                uriBuilder.append('?');
            } else {
                uriBuilder.append('&');
            }
            uriBuilder.append(key).append('=').append(value);
        });
        return URI.create(uriBuilder.toString());
    }

    private static JsonObject createPrLinkList(final Collection<String> prUrls) {
        final var bulletListContent = Json.createArrayBuilder();

        for (String url : prUrls) {
            if (url == null || url.isBlank()) continue;

            // Create the link mark with the href attribute
            final var linkMark = Json.createObjectBuilder()
                    .add("type", "link")
                    .add("attrs", Json.createObjectBuilder()
                            .add("href", url.trim()));

            // Create the text node and attach the mark
            final var textNode = Json.createObjectBuilder()
                    .add("type", "text")
                    .add("text", url.trim())
                    .add("marks", Json.createArrayBuilder().add(linkMark));

            // Wrap in Paragraph -> ListItem
            final var listItem = Json.createObjectBuilder()
                    .add("type", "listItem")
                    .add("content", Json.createArrayBuilder()
                            .add(Json.createObjectBuilder()
                                    .add("type", "paragraph")
                                    .add("content", Json.createArrayBuilder().add(textNode))));

            bulletListContent.add(listItem);
        }

        // Build the bulletList object
        final var bulletList = Json.createObjectBuilder()
                .add("type", "bulletList")
                .add("content", bulletListContent);

        // Wrap in the top-level "doc" array (the missing piece from last time!)
        return Json.createObjectBuilder()
                .add("version", 1)
                .add("type", "doc")
                .add("content", Json.createArrayBuilder().add(bulletList))
                .build();
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
