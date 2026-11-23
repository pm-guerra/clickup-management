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
 * Who is who: roles, teams and streams of the people configured in {@code app.audit.people}, by ClickUp user id.
 * Anyone not configured has no permissions.
 */
@Component
public class People {

    public enum Role { ADMIN, TESTER, DEV, MEMBER }

    public static final String PRODUCT = "product";
    public static final String PLANNING = "planning";
    public static final String TECH = "tech";
    public static final String TESTERS = "testers";

    private final Map<String, Person> byId;
    private final List<Person> all;

    public People(AuditProperties properties) {
        this.all = properties.peopleOrEmpty();
        this.byId = all.stream().collect(Collectors.toMap(Person::id, Function.identity(), (a, _) -> a));
    }

    public Optional<Role> role(Long userId) {
        return person(userId).map(p -> Role.valueOf(p.role().trim().toUpperCase(Locale.ROOT)));
    }

    public Optional<String> stream(Long userId) {
        return person(userId).map(Person::stream).map(s -> s.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isAdmin(Long userId) {
        return role(userId).filter(r -> r == Role.ADMIN).isPresent();
    }

    /**
     * Whether the person is in {@code team} (product, planning, tech, testers). The old roles still count: a tester
     * is in the testers team, a dev in the tech team.
     */
    public boolean inTeam(Long userId, String team) {
        return person(userId).map(p -> teams(p).contains(team)).orElse(false);
    }

    /**
     * ClickUp user ids of everyone in {@code team}, in the configured order.
     */
    public List<Long> membersOf(String team) {
        return all.stream().filter(p -> teams(p).contains(team)).map(p -> Long.valueOf(p.id())).toList();
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

    private static List<String> teams(Person person) {
        List<String> teams = new java.util.ArrayList<>(person.teamsOrEmpty().stream()
                .map(t -> t.trim().toLowerCase(Locale.ROOT)).toList());
        String role = person.role().trim().toLowerCase(Locale.ROOT);
        if (role.equals("tester")) {
            teams.add(TESTERS);
        } else if (role.equals("dev")) {
            teams.add(TECH);
        }
        return teams;
    }

    private Optional<Person> person(Long userId) {
        return userId == null ? Optional.empty() : Optional.ofNullable(byId.get(String.valueOf(userId)));
    }
}
