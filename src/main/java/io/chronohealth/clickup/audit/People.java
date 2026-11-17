package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.AuditProperties.Person;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Who is who: roles and streams of the people configured in {@code app.audit.people}, by ClickUp user id.
 * Anyone not configured has no permissions.
 */
@Component
public class People {

    public enum Role { ADMIN, TESTER, DEV }

    private final Map<String, Person> byId;
    private final List<List<String>> sharedStreams;

    public People(AuditProperties properties) {
        this.byId = properties.peopleOrEmpty().stream()
                .collect(Collectors.toMap(Person::id, Function.identity(), (a, _) -> a));
        this.sharedStreams = properties.sharedStreamsOrEmpty().stream()
                .map(group -> group.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList())
                .toList();
    }

    public Optional<Role> role(Long userId) {
        return person(userId).map(p -> Role.valueOf(p.role().trim().toUpperCase(Locale.ROOT)));
    }

    public Optional<String> stream(Long userId) {
        return person(userId).map(Person::stream).map(s -> s.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the person's devs work on {@code stream}: their own, or one sharing a group with it (web and mobile).
     */
    public boolean worksOn(Long userId, String stream) {
        String wanted = stream.trim().toLowerCase(Locale.ROOT);
        return stream(userId).filter(own -> own.equals(wanted)
                || sharedStreams.stream().anyMatch(group -> group.contains(own) && group.contains(wanted))).isPresent();
    }

    /**
     * The streams whose tasks the devs of {@code stream} may move: their own plus those sharing a group with it.
     */
    public List<String> streamsSharing(String stream) {
        String own = stream.trim().toLowerCase(Locale.ROOT);
        return sharedStreams.stream().filter(group -> group.contains(own)).findFirst().orElse(List.of(own));
    }

    public boolean isEnforced(Long userId) {
        return person(userId).map(Person::isEnforced).orElse(false);
    }

    /**
     * The status rules (placement, parents ahead of subtasks) don't apply to them: Pedro and the app itself.
     */
    public boolean isUnrestricted(Long userId) {
        return person(userId).map(Person::isUnrestricted).orElse(false);
    }

    public Optional<String> name(Long userId) {
        return person(userId).map(Person::name);
    }

    public String describe(Long userId) {
        return person(userId)
                .map(p -> p.name() + " (" + (p.stream() == null || p.stream().isBlank()
                        ? p.role().toLowerCase(Locale.ROOT)
                        : p.stream().toLowerCase(Locale.ROOT) + " dev") + ")")
                .orElse(userId == null ? "Someone" : "User " + userId + " (no role)");
    }

    private Optional<Person> person(Long userId) {
        return userId == null ? Optional.empty() : Optional.ofNullable(byId.get(String.valueOf(userId)));
    }
}
