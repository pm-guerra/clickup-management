package io.chronohealth.clickup.mention;

import io.chronohealth.clickup.client.ClickUpClient;
import io.chronohealth.clickup.client.ClickUpClientFactory;
import io.chronohealth.clickup.client.dto.ChatMessage;
import io.chronohealth.clickup.config.ClickUpProperties;
import io.chronohealth.clickup.mention.MentionRepository.NewMention;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds mentions of the bot in the watched chat channels. ClickUp has no webhook for chat messages, so this reads the
 * newest messages of each channel on every run (once a minute), plus the replies of recent threads whose reply count
 * went up. Messages posted before a channel was first polled, and the bot's own messages, are ignored.
 */
@Service
public class ChatMentionPoller {

    static final String SOURCE = "chat";

    private static final Logger log = LoggerFactory.getLogger(ChatMentionPoller.class);

    private final ClickUpClientFactory clientFactory;
    private final MentionRepository repository;
    private final MentionProperties properties;
    private final ClickUpProperties clickUpProperties;
    private final Clock clock;

    public ChatMentionPoller(ClickUpClientFactory clientFactory, MentionRepository repository,
                             MentionProperties properties, ClickUpProperties clickUpProperties, Clock clock) {
        this.clientFactory = clientFactory;
        this.repository = repository;
        this.properties = properties;
        this.clickUpProperties = clickUpProperties;
        this.clock = clock;
    }

    /**
     * @return new mentions recorded
     */
    public int poll() {
        if (!properties.enabled() || properties.botUserId() == null || properties.channelsOrEmpty().isEmpty()) {
            return 0;
        }
        String workspaceId = clickUpProperties.workspaceId();
        ClickUpClient client = clientFactory.forWorkspace(workspaceId);
        int found = 0;
        for (String channelId : properties.channelsOrEmpty()) {
            try {
                found += pollChannel(client, workspaceId, channelId);
            } catch (RuntimeException e) {
                // One unreachable channel (e.g. the bot isn't a member) shouldn't stop the others.
                log.warn("Couldn't read chat channel {}: {}", channelId, e.getMessage());
            }
        }
        if (found > 0) {
            log.info("Recorded {} new chat mention(s) of the bot", found);
        }
        return found;
    }

    private int pollChannel(ClickUpClient client, String workspaceId, String channelId) {
        Instant startedAt = repository.channelStartedAt(channelId);
        Instant threadCutoff = clock.instant().minus(properties.threadLookback());
        int found = 0;
        for (ChatMessage message : client.getChatMessages(workspaceId, channelId, properties.pageSize())) {
            if (mentionsBot(message, startedAt)) {
                found += record(message, channelId, null);
            }
            int replies = message.repliesCountOrZero();
            if (replies > 0 && message.postedAt().isAfter(threadCutoff)
                    && replies > repository.knownReplyCount(message.id())) {
                for (ChatMessage reply : client.getChatReplies(workspaceId, message.id(), 100)) {
                    if (mentionsBot(reply, startedAt)) {
                        found += record(reply, channelId, message.id());
                    }
                }
                repository.saveReplyCount(message.id(), channelId, replies);
            }
        }
        return found;
    }

    private boolean mentionsBot(ChatMessage message, Instant startedAt) {
        return message.postedAt().isAfter(startedAt)
                && !Objects.equals(message.authorId(), properties.botUserId())
                && Mentions.inMarkdown(message.content()).contains(properties.botUserId());
    }

    private int record(ChatMessage message, String channelId, String parentMessageId) {
        boolean recorded = repository.record(new NewMention(SOURCE, message.id(), null, channelId, parentMessageId,
                message.authorId(), message.postedAt()));
        if (recorded) {
            log.info("Bot mentioned in chat channel {} (message {}, by user {})", channelId, message.id(),
                    message.authorId());
        }
        return recorded ? 1 : 0;
    }
}
