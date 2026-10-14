package com.erezer.admin.user;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves user ids to display names for audit fields ("created by", "changed by"). */
@Service
public class UserDirectory {

    private final UserRepository users;

    UserDirectory(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Map<Long, String> namesById(Collection<Long> ids) {
        var distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(distinct).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));
    }

    /** Name for display; ids of since-deleted users still render. */
    public static String nameOf(Map<Long, String> names, long id) {
        return names.getOrDefault(id, "Deleted user");
    }
}
