/*
 * Copyright 2017 Derek Weber
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dcw.twitter.sanitise;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import twitter4j.RateLimitStatus;
import twitter4j.RateLimitStatusEvent;
import twitter4j.RateLimitStatusListener;
import twitter4j.Twitter;
import twitter4j.TwitterFactory;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * <p>This app can sanitise the JSON for one or more tweets. It has a GUI mode, which
 * (for convenience) can be used to fetch one tweet at a time or the user can paste in
 * a Tweet's JSON for it to be sanitised, or alternatively in a commandline mode where
 * tweets can be pumped in via <code>stdin</code>, one JSON object per line, while
 * output is written to <code>stdout</code> (like a simple version of <code>jq</code>.
 * This tool's output is as fully fledged JSON objects and doesn't have a string formatting
 * feature like <code>jq</code>.
 * </p>
 *
 * <p>Twitter credentials are looked for in "./twitter.properties", and proxy info
 * is looked for in "./proxy.properties". Commandline options for the input file,
 * the output file, and the Twitter properties are provided, along with a verbose
 * mode.</p>
 *
 * @author <a href="mailto:weber.dc@gmail.com">Derek Weber</a>
 */
class TweetSanitiserCmdLine {

    private static final List<String> DEFAULT_FIELDS_TO_KEEP = Arrays.asList(
        "created_at", "text", "full_text", "extended_tweet.full_text", "user.screen_name", "coordinates", "place",
        "entities.media", "id", "id_str"
    );

    @Parameter(names = {"--keep-file"}, description = "File of properties to keep (comma separated or one per line)")
    private String propertiesToKeepFile = null;

    @Parameter(names = {"-k", "--keep"}, description = "Properties to keep (comma separated)")
    private String propertiesToKeep = null;

    @Parameter(names = {"-c", "--credentials"},
               description = "Properties file with Twitter OAuth credentials")
    private String credentialsFile = "./twitter.properties";

    @Parameter(names = {"-v", "--debug", "--verbose"}, description = "Debug mode")
    private boolean debug = false;

    @Parameter(names = {"-cmd", "--command-line" }, description = "Command line mode")
    private boolean commandLineMode = false;

    @Parameter(names = {"-h", "-?", "--help"}, description = "Help")
    private static boolean help = false;

    public static void main(String[] args) throws IOException {
        TweetSanitiserCmdLine theApp = new TweetSanitiserCmdLine();

        // JCommander instance parses args, populates fields of theApp
        JCommander argsParser = JCommander.newBuilder()
            .addObject(theApp)
            .programName("bin/tweet-sanitiser[.bat]")
            .build();
        try {
            argsParser.parse(args);
        } catch (ParameterException e) {
            help = true;
        }

        if (help) {
            StringBuilder sb = new StringBuilder();
            argsParser.usage(sb);
            System.out.println(sb.toString());
            System.exit(-1);
        }

        theApp.run();
    }

    private void run() throws IOException {

        // establish resources
        final Configuration twitterConfig = makeTwitterConfig(credentialsFile, debug);
        final Twitter twitter = new TwitterFactory(twitterConfig).getInstance();
        twitter.addRateLimitStatusListener(new RateLimitStatusListener() {
            @Override
            public void onRateLimitStatus(RateLimitStatusEvent event) {
                maybeDoze(event.getRateLimitStatus());
            }

            @Override
            public void onRateLimitReached(RateLimitStatusEvent event) {
                maybeDoze(event.getRateLimitStatus());
            }
        });

        final List<String> toKeep = loadFieldsToKeep();

        if (inGuiMode()) {
            // Create and set up the window
            final JFrame frame = new JFrame("Sanitise Tweet");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

            final JComponent gui = new TweetSanitiserUI(twitter, toKeep, debug);
            frame.setContentPane(gui);

            // Display the window
            frame.setSize(500, 700);
            frame.setVisible(true);

        } else {

            // a bit like a poor man's jq
            final Map<String, Object> keepMap = TweetSanitiser.buildFieldStructure(toKeep);

            final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            in.lines().forEach(tweetJson -> {
                String sanitisedJson = TweetSanitiser.sanitiseJSON(tweetJson, keepMap);
                System.out.println(sanitisedJson);
            });
        }
    }

    private List<String> loadFieldsToKeep() throws IOException {
        if (propertiesToKeepFile == null && propertiesToKeep == null) {
            return DEFAULT_FIELDS_TO_KEEP;

        } else if (propertiesToKeep != null) {
            return Arrays.asList(propertiesToKeep.split(","));
        } else {
            return Files.readAllLines(Paths.get(propertiesToKeepFile)).stream()
                .map(l -> l.contains("#") ? l.split("#")[0] : l) // strip comments
                .map(l -> l.contains(",") || l.contains(" ") ? Stream.of(l.split("[, ]")) : Stream.of(l)) // break up multiple properties
                .flatMap(x -> x) // typecast them back to Strings (I never understood this magic)
                .map(String::trim) // remove surrounding whitespace
                .filter(s -> s.length() > 0) // ditch empty lines
                .collect(Collectors.toList());
        }
    }

    /**
     * Says yes to GUI mode if no IDs are referred to on the commandline.
     *
     * @return True if the GUI should be launched.
     */
    private boolean inGuiMode() {
        return  ! commandLineMode;// && (infile == null && idStrs.isEmpty());
    }

    /**
     * If the provided {@link RateLimitStatus} indicates that we are about to exceed the rate
     * limit, in terms of number of calls or time window, then sleep for the rest of the period.
     *
     * @param status The current rate limit status of our calls to Twitter
     */
    private void maybeDoze(final RateLimitStatus status) {
        if (status == null) { return; }

        final int secondsUntilReset = status.getSecondsUntilReset();
        final int callsRemaining = status.getRemaining();
        if (secondsUntilReset < 10 || callsRemaining < 10) {
            final int untilReset = status.getSecondsUntilReset() + 5;
            System.out.printf("Rate limit reached. Waiting %d seconds starting at %s...\n", untilReset, new Date());
            try {
                Thread.sleep(untilReset * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("Resuming...");
        }
    }

    /**
     * Builds the {@link Configuration} object with which to connect to Twitter, including
     * credentials and proxy information if it's specified.
     *
     * @return a Twitter4j {@link Configuration} object
     * @throws IOException if there's an error loading the application's {@link #credentialsFile}.
     */
    private static Configuration makeTwitterConfig(
        final String credentialsFile,
        final boolean debug
    ) throws IOException {
        // TODO find a better name than credentials, given it might contain proxy info
        final Properties credentials = loadCredentials(credentialsFile);

        final ConfigurationBuilder conf = new ConfigurationBuilder();
        conf.setTweetModeExtended(true);
        conf.setJSONStoreEnabled(true)
            .setDebugEnabled(debug)
            .setOAuthConsumerKey(credentials.getProperty("oauth.consumerKey"))
            .setOAuthConsumerSecret(credentials.getProperty("oauth.consumerSecret"))
            .setOAuthAccessToken(credentials.getProperty("oauth.accessToken"))
            .setOAuthAccessTokenSecret(credentials.getProperty("oauth.accessTokenSecret"));

        final Properties proxies = loadProxyProperties();
        if (proxies.containsKey("http.proxyHost")) {
            conf.setHttpProxyHost(proxies.getProperty("http.proxyHost"))
                .setHttpProxyPort(Integer.parseInt(proxies.getProperty("http.proxyPort")))
                .setHttpProxyUser(proxies.getProperty("http.proxyUser"))
                .setHttpProxyPassword(proxies.getProperty("http.proxyPassword"));
        }

        return conf.build();
    }

    /**
     * Loads the given {@code credentialsFile} from disk.
     *
     * @param credentialsFile the properties file with the Twitter credentials in it
     * @return A {@link Properties} map with the contents of credentialsFile
     * @throws IOException if there's a problem reading the credentialsFile.
     */
    private static Properties loadCredentials(final String credentialsFile)
        throws IOException {
        final Properties properties = new Properties();
        properties.load(Files.newBufferedReader(Paths.get(credentialsFile)));
        return properties;
    }

    /**
     * Loads proxy information from <code>"./proxy.properties"</code> if it is
     * present. If a proxy host and username are specified by no password, the
     * user is asked to type it in via stdin.
     *
     * @return A {@link Properties} map with proxy credentials.
     */
    private static Properties loadProxyProperties() {
        final Properties properties = new Properties();
        final String proxyFile = "./proxy.properties";
        if (new File(proxyFile).exists()) {
            boolean success = true;
            try (Reader fileReader = Files.newBufferedReader(Paths.get(proxyFile))) {
                properties.load(fileReader);
            } catch (IOException e) {
                System.err.println("Attempted and failed to load " + proxyFile + ": " + e.getMessage());
                success = false;
            }
            if (success && !properties.containsKey("http.proxyPassword")) {
                char[] password = System.console().readPassword("Please type in your proxy password: ");
                properties.setProperty("http.proxyPassword", new String(password));
                properties.setProperty("https.proxyPassword", new String(password));
            }
            properties.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
        }
        return properties;
    }
}
