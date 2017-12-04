package org.dcw.twitter.sanitise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SuppressWarnings("unchecked")
public class TweetSanitiser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String INDENT = "  ";

    /**
     * Constructs a nested map of the fields to retain in the stripped version
     * of the the Tweet's JSON.
     *
     * @param cleanFields The list of field names with implied structure (via '.' delimiters).
     * @return A nested map version of <code>cleanFields</code>.
     */
    public static Map<String, Object> buildFieldStructure(final List<String> cleanFields) {
        Map<String, Object> map = Maps.newTreeMap();

        for (String f : cleanFields) {
            if (! f.contains(".")) {
                map.put(f, null);
            } else {
                final String head = f.substring(0, f.indexOf('.'));
                final String tail = f.substring(f.indexOf('.') + 1);
                final Map<String, Object> subMap = buildFieldStructure(Collections.singletonList(tail));
                if (map.containsKey(head)) {
                    final Map<String, Object> existingMap = (Map<String, Object>) map.get(head);
                    existingMap.putAll(subMap);
                } else {
                    map.put(head, subMap);
                }
            }
        }
        return map;
    }


    /**
     * Strips sensitive elements from the Tweet's raw JSON.
     *
     * @param tweetJSON The Tweet's raw JSON.
     * @param fieldsToKeep Keep the fields in this nested map.
     * @return The desensitised JSON.
     */
    public static String sanitiseJSON(final String tweetJSON, final Map<String, Object> fieldsToKeep) {
        try {
            JsonNode root = JSON.readValue(tweetJSON, JsonNode.class);

            stripFields(root, fieldsToKeep);

            /* As of 2017-09-27, Twitter is progressively rolling out 280 character tweets,
             * referred to as "extended tweets", and "text" is replaced by "full_text". I am
             * using Twitter4J in extended mode, but as a courtesy to those still running on
             * standard mode, my "sanitised" objects will have "full_text" copied to "text", if
             * there is no content there already.
             *
             * TODO think of the myriad ways in which the full_text will be hidden and how to extract it
             * - full_text
             * - extended_tweet.full_text
             * - retweeted_status.extended_tweet.full_text
             * - retweeted_status.full_text
             */
            if (root.has("full_text")) {
                ((ObjectNode) root).set("text", root.get("full_text").deepCopy());
            }
            if (root.hasNonNull("truncated") &&
                root.get("truncated").asBoolean(false) &&
                has(root, "extended_tweet.full_text")) {
                ((ObjectNode) root).set("text", get(root, "extended_tweet.full_text").deepCopy());
            }
            // not quite right: missing "RT @originalPoster "
            if (root.hasNonNull("retweeted_status") && has(root, "retweeted_status.full_text")) {
                ((ObjectNode) root).set("text", get(root, "retweeted_status.full_text").deepCopy());
            }
            if (root.hasNonNull("retweeted_status") && has(root, "retweeted_status.extended_tweet.full_text")) {
                ((ObjectNode) root).set("text", get(root, "retweeted_status.extended_tweet.full_text").deepCopy());
            }

            return JSON.writeValueAsString(root);

        } catch (IOException e) {
            e.printStackTrace();
            StringWriter sw = new StringWriter();
            PrintWriter stacktrace = new PrintWriter(sw);
            e.printStackTrace(stacktrace);
            return "{\"error\":\"" + e.getMessage() + "\",\"stacktrace\":\"" + sw.toString() + "\"}";
        }
    }

    private static boolean has(final JsonNode n, final String path) {
        if (path.contains(".")) {
            final String head = path.substring(0, path.indexOf('.'));
            final String tail = path.substring(path.indexOf('.') + 1);
            return n.has(head) && has(n.get(head), tail);
        } else {
            return n.has(path);
        }
    }

    private static JsonNode get(final JsonNode n, final String path) {
        if (path.contains(".")) {
            final String head = path.substring(0, path.indexOf('.'));
            final String tail = path.substring(path.indexOf('.') + 1);
            if (n.has(head)) {
                return get(n.get(head), tail);
            } else {
                return JsonNodeFactory.instance.nullNode(); // shouldn't happen if you use "has()" first
            }
        } else {
            return n.get(path);
        }
    }

    /**
     * Strips unwanted fields directly from a {@link JsonNode} tree structure.
     *
     * @param root The root of the tree.
     * @param toKeep The fields to keep - i.e. remove the others.
     */
    private static void stripFields(final JsonNode root, final Map<String, Object> toKeep) {

        List<String> toRemove = Lists.newArrayList();

        final Iterator<String> fieldIterator = root.fieldNames();
        while (fieldIterator.hasNext()) {
            String field = fieldIterator.next();
            if (! toKeep.containsKey(field)) {
                toRemove.add(field);
            }
        }
        ((ObjectNode) root).remove(toRemove);

        for (String field: toKeep.keySet()) {
            Map<String, Object> value = (Map<String, Object>) toKeep.get(field);
            if (value != null && root.has(field)) {
                stripFields(root.get(field), value);
            }
        }
    }


    /**
     * Converts a nested map (keys only) to a formatted String. Ignores values
     * unless they're nested maps. Recursively called, making use of
     * <code>indentLevel</code> to structure the text.
     *
     * @param m The nested map to represent as a String.
     * @param indentLevel How deeply we've recursed.
     * @return A formatted String representation of a nested map.
     */
    public static String str(final Map<String, Object> m, final int indentLevel) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> {
            sb.append(leadingSpaces(indentLevel)).append("- ").append(k).append("\n");

            Map mapValue = (Map) v;
            if (v != null) {
                sb.append(str(mapValue, indentLevel + 1));
            }
        });
        return sb.toString();
    }

    /**
     * Creates the leading spaces used in formatting, based on {@link #INDENT}.
     *
     * @param tabs The number of times we've indented.
     * @return A String of spaces.
     */
    private static String leadingSpaces(final int tabs) {
        return IntStream.range(0, tabs).mapToObj(i -> INDENT).collect(Collectors.joining());
    }

}
