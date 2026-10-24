package io.chronohealth.clickup.mention;

import static org.assertj.core.api.Assertions.assertThat;

import io.chronohealth.clickup.mention.MentionForwarder.AgentMention;
import io.chronohealth.clickup.mention.MentionForwarder.Author;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class MentionsTest {

    @Test
    void findsMentionsInChatMarkdown() {
        assertThat(Mentions.inMarkdown("[@Octo bot](#user_mention#106723346) ping [@Pedro](#user_mention#1)"))
                .containsExactly(106723346L, 1L);
    }

    @Test
    void showsMentionLinksAsPlainNames() {
        assertThat(Mentions.toPlainText("[@Octo bot](#user_mention#106723346) ping")).isEqualTo("@Octo bot ping");
        assertThat(Mentions.toPlainText(null)).isNull();
    }

    @Test
    void sendsSnakeCaseJsonWithoutFieldsThatDontApply() {
        JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        AgentMention chat = new AgentMention("chat", "6-1", "Status report", "m1", null, null, null,
                new Author(5L, "Pedro"), "@Octo bot ping", 0, "https://app.clickup.com/1/chat/r/6-1");

        assertThat(mapper.writeValueAsString(chat)).isEqualTo("{\"source\":\"chat\",\"channel_id\":\"6-1\","
                + "\"channel_name\":\"Status report\",\"message_id\":\"m1\",\"author\":{\"id\":5,\"username\":\"Pedro\"},"
                + "\"text\":\"@Octo bot ping\",\"date\":0,\"url\":\"https://app.clickup.com/1/chat/r/6-1\"}");
    }
}
