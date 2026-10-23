package io.chronohealth.clickup.mention;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Finds @-mentioned user ids in ClickUp content.
 */
public final class Mentions {

    /**
     * Chat messages (markdown): {@code [@Name](#user_mention#123)}.
     */
    private static final Pattern MARKDOWN_MENTION = Pattern.compile("#user_mention#(\\d+)");

    private Mentions() {
    }

    public static List<Long> inMarkdown(String markdown) {
        List<Long> ids = new ArrayList<>();
        if (markdown == null) {
            return ids;
        }
        Matcher matcher = MARKDOWN_MENTION.matcher(markdown);
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        return ids;
    }

    /**
     * Task comments (rich text): parts like {@code {"type": "tag", "user": {"id": 123}}}, at any depth; markdown-style
     * mentions inside text are found too.
     */
    public static List<Long> inComment(JsonNode comment) {
        List<Long> ids = new ArrayList<>();
        collect(comment, ids);
        return ids.stream().distinct().toList();
    }

    private static void collect(JsonNode node, List<Long> ids) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            String type = node.path("type").asString("");
            JsonNode userId = node.path("user").path("id");
            if (("tag".equals(type) || "mention".equals(type) || "user_mention".equals(type))
                    && (userId.isNumber() || userId.isString()) && userId.asString().matches("\\d+")) {
                ids.add(Long.parseLong(userId.asString()));
            }
            node.properties().forEach(e -> collect(e.getValue(), ids));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, ids));
        } else if (node.isString()) {
            ids.addAll(inMarkdown(node.asString()));
        }
    }
}
