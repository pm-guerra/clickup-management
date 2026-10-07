package io.chronohealth.clickup.audit;

import io.chronohealth.clickup.audit.AuditProperties.Person;
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

    public People(AuditProperties properties) {
        this.byId = properties.peopleOrEmpty().stream()
                .collect(Collectors.toMap(Person::id, Function.identity(), (a, _) -> a));
    }

    public Optional<Role> role(Long userId) {
        return person(userId).map(p -> Role.valueOf(p.role().trim().toUpperCase(Locale.ROOT)));
    }

    public Optional<String> stream(Long userId) {
        return person(userId).map(Person::stream).map(s -> s.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isEnforced(Long userId) {
        return person(userId).map(Person::isEnforced).orElse(false);
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
