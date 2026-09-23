package io.chronohealth.clickup.client.dto;

import java.util.List;

public record AssigneesUpdate(List<Long> add, List<Long> rem) {
}
