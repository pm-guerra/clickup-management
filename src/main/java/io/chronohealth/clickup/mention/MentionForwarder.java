package io.chronohealth.clickup.mention;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.ChatMessage;
import io.chronohealth.clickup.client.dto.TaskComment;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.mention.MentionRepository.StoredMention;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends recorded mentions to the agent's routine, once each. The message text isn't stored with the mention, so it's
 * read from ClickUp right before sending and only passed on. A failed send is retried on the next runs (once a minute)
 * up to {@code max-attempts}.
 */
@Service
public class MentionForwarder {

    private static final Logger log = LoggerFactory.getLogger(MentionForwarder.class);
    private static final int BATCH_SIZE = 20;
    private static final int REPLIES_LIMIT = 100;

    private final MentionRepository repository;
    private final MentionForwardProperties properties;
    private final MentionProperties mentionProperties;
    private final ClickUpClientFactory clientFactory;
    private final ClickUpProperties clickUpProperties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final RestClient agent;

    public MentionForwarder(MentionRepository repository, MentionForwardProperties properties,
                            MentionProperties mentionProperties, ClickUpClientFactory clientFactory,
                            ClickUpProperties clickUpProperties, JsonMapper jsonMapper, Clock clock,
                            RestClient.Builder restClientBuilder) {
        this.repository = repository;
        this.properties = properties;
        this.mentionProperties = mentionProperties;
        this.clientFactory = clientFactory;
        this.clickUpProperties = clickUpProperties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(properties.timeout());
        this.agent = restClientBuilder.requestFactory(requestFactory).build();
    }

    /**
     * @return mentions sent
     */
    public int forwardPending() {
        if (!properties.enabled()) {
            return 0;
        }
        List<StoredMention> pending = repository.findToForward(properties.maxAttempts(),
                clock.instant().minus(properties.maxAge()), BATCH_SIZE);
        if (pending.isEmpty()) {
            return 0;
        }
        String workspaceId = clickUpProperties.workspaceId();
        Lookup lookup = new Lookup(clientFactory.forWorkspace(workspaceId), workspaceId);
        int sent = 0;
        for (StoredMention mention : pending) {
            try {
                send(payload(mention, lookup));
                repository.markForwarded(mention.id());
                sent++;
                log.info("Forwarded mention {} ({}) to the agent", mention.id(), mention.source());
            } catch (RuntimeException e) {
                repository.recordForwardFailure(mention.id());
                log.warn("Couldn't forward mention {} ({}): {}", mention.id(), mention.source(), describe(e));
            }
        }
        return sent;
    }

    private AgentMention payload(StoredMention mention, Lookup lookup) {
        long date = mention.mentionedAt().toInstant().toEpochMilli();
        if (CommentMentionHandler.SOURCE.equals(mention.source())) {
            TaskComment comment = lookup.comment(mention.taskId(), mention.itemId());
            String username = comment != null && comment.user() != null && comment.user().username() != null
                    ? comment.user().username()
                    : lookup.username(mention.authorUserId());
            return new AgentMention(mention.source(), null, null, null, null, mention.taskId(), mention.itemId(),
                    new Author(mention.authorUserId(), username),
                    comment == null ? null : comment.commentText(), date,
                    "https://app.clickup.com/t/" + mention.taskId() + "?comment=" + mention.itemId());
        }
        ChatMessage message = lookup.chatMessage(mention.channelId(), mention.parentMessageId(), mention.itemId());
        return new AgentMention(mention.source(), mention.channelId(), lookup.channelName(mention.channelId()),
                mention.itemId(), mention.parentMessageId(), null, null,
                new Author(mention.authorUserId(), lookup.username(mention.authorUserId())),
                message == null ? null : Mentions.toPlainText(message.content()), date,
                "https://app.clickup.com/" + lookup.workspaceId + "/chat/r/" + mention.channelId());
    }

    private void send(AgentMention payload) {
        // The routine takes a single "text" field; the mention goes in it as JSON.
        Map<String, String> body = Map.of("text", jsonMapper.writeValueAsString(payload));
        agent.post()
                .uri(properties.url())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", properties.betaHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Error class and HTTP status only: messages and response bodies may echo the mention's text.
     */
    private static String describe(RuntimeException e) {
        return e instanceof RestClientResponseException response
                ? "HTTP " + response.getStatusCode().value()
                : e.getClass().getSimpleName();
    }

    /**
     * What the agent receives (snake_case JSON). Fields that don't apply to the source are left out.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AgentMention(String source, String channelId, String channelName, String messageId,
                        String parentMessageId, String taskId, String commentId, Author author, String text,
                        long date, String url) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Author(Long id, String username) {
    }

    /**
     * ClickUp reads for one run, each done at most once.
     */
    private final class Lookup {

        private final ClickUpClient client;
        private final String workspaceId;
        private final Map<String, List<TaskComment>> comments = new HashMap<>();
        private final Map<String, List<ChatMessage>> chatMessages = new HashMap<>();
        private final Map<String, String> channelNames = new HashMap<>();
        private Map<Long, String> usernames;

        Lookup(ClickUpClient client, String workspaceId) {
            this.client = client;
            this.workspaceId = workspaceId;
        }

        TaskComment comment(String taskId, String commentId) {
            return comments.computeIfAbsent(taskId, client::getTaskComments).stream()
                    .filter(c -> Objects.equals(c.id(), commentId))
                    .findFirst().orElse(null);
        }

        ChatMessage chatMessage(String channelId, String parentMessageId, String messageId) {
            List<ChatMessage> candidates = parentMessageId == null
                    ? chatMessages.computeIfAbsent("channel:" + channelId,
                            _ -> client.getChatMessages(workspaceId, channelId, mentionProperties.pageSize()))
                    : chatMessages.computeIfAbsent("thread:" + parentMessageId,
                            _ -> client.getChatReplies(workspaceId, parentMessageId, REPLIES_LIMIT));
            return candidates.stream().filter(m -> Objects.equals(m.id(), messageId)).findFirst().orElse(null);
        }

        String channelName(String channelId) {
            return channelNames.computeIfAbsent(channelId, id -> {
                try {
                    return client.getChatChannelName(workspaceId, id);
                } catch (RuntimeException e) {
                    return null;
                }
            });
        }

        String username(Long userId) {
            if (userId == null) {
                return null;
            }
            if (usernames == null) {
                try {
                    usernames = client.getMemberNames(workspaceId);
                } catch (RuntimeException e) {
                    usernames = Map.of();
                }
            }
            return usernames.get(userId);
        }
    }
}
