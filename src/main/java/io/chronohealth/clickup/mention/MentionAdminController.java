package io.chronohealth.clickup.mention;

import io.chronohealth.clickup.mention.MentionRepository.StoredMention;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recorded mentions of the bot. Protected by {@code X-Admin-Key}.
 */
@RestController
@RequestMapping("/admin/mentions")
public class MentionAdminController {

    private final MentionRepository repository;
    private final ChatMentionPoller poller;

    public MentionAdminController(MentionRepository repository, ChatMentionPoller poller) {
        this.repository = repository;
        this.poller = poller;
    }

    @GetMapping
    public List<StoredMention> recent(@RequestParam(defaultValue = "50") int limit) {
        return repository.findRecent(Math.min(limit, 500));
    }

    /**
     * Polls the watched chat channels now instead of waiting for the next minute.
     */
    @PostMapping("/poll")
    public int poll() {
        return poller.poll();
    }
}
