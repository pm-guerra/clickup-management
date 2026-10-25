package io.chronohealth.clickup.mention;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled         detect mentions at all
 * @param botUserId       ClickUp user id whose mentions are recorded (the app's own user, Octo bot)
 * @param channels        chat channels polled for mentions (the bot must be a member)
 * @param pageSize        newest top-level messages read per channel and poll
 * @param threadLookback  thread starters younger than this are checked for new replies
 * @param reaction        emoji (lower-case name) the bot adds to a chat message once its mention reached the agent;
 *                        empty for none
 * @param commentReply    text the bot replies in a task comment's thread once its mention reached the agent (the
 *                        API can't add reactions to comments); empty for none
 */
@Validated
@ConfigurationProperties("app.mentions")
public record MentionProperties(
        boolean enabled,
        Long botUserId,
        List<String> channels,
        @Min(1) int pageSize,
        @NotNull Duration threadLookback,
        String reaction,
        String commentReply
) {

    public List<String> channelsOrEmpty() {
        return channels == null ? List.of() : channels;
    }
}
