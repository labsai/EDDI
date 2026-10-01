/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MongoDB implementation of {@link IUserDirectoryStore}: one document per
 * principal in {@code user_directory}, keyed by {@code _id = principal}.
 * <p>
 * Lower-cased copies of the searchable fields are stored alongside the
 * originals, so a case-insensitive prefix search is an anchored regex that can
 * use an index, rather than a {@code $regex} with the {@code i} option, which
 * cannot.
 */
@ApplicationScoped
@DefaultBean
public class MongoUserDirectoryStore implements IUserDirectoryStore {

    private static final Logger LOGGER = Logger.getLogger(MongoUserDirectoryStore.class);

    static final String COLLECTION = "user_directory";

    private static final String ID = "_id";
    private static final String SUBJECT = "subject";
    private static final String USERNAME = "username";
    private static final String EMAIL = "email";
    private static final String EMAIL_VERIFIED = "emailVerified";
    private static final String DISPLAY_NAME = "displayName";
    private static final String TEAMS = "teams";
    private static final String FIRST_SEEN = "firstSeen";
    private static final String LAST_SEEN = "lastSeen";
    private static final String PRINCIPAL_LOWER = "principalLower";
    private static final String USERNAME_LOWER = "usernameLower";
    private static final String EMAIL_LOWER = "emailLower";
    private static final String DISPLAY_NAME_LOWER = "displayNameLower";
    private static final String TEAMS_LOWER = "teamsLower";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoUserDirectoryStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        createIndexes();
    }

    private void createIndexes() {
        try {
            for (String field : List.of(USERNAME_LOWER, EMAIL_LOWER, PRINCIPAL_LOWER, DISPLAY_NAME_LOWER, TEAMS, TEAMS_LOWER)) {
                collection.createIndex(Indexes.ascending(field), new IndexOptions().background(true));
            }
            collection.createIndex(Indexes.descending(LAST_SEEN), new IndexOptions().background(true));
        } catch (RuntimeException e) {
            // An index is a performance aid here, not a constraint: lookups stay
            // correct without it, so a failure is worth a warning and not a boot failure.
            LOGGER.warnf("Could not create the %s indexes: %s", COLLECTION, e.getMessage());
        }
    }

    @Override
    public void upsert(DirectoryUser user) {
        Instant firstSeen = user.firstSeen() == null ? Instant.now() : user.firstSeen();
        Instant lastSeen = user.lastSeen() == null ? Instant.now() : user.lastSeen();
        List<Bson> sets = new ArrayList<>();
        sets.add(Updates.set(SUBJECT, user.subject()));
        sets.add(Updates.set(USERNAME, user.username()));
        sets.add(Updates.set(EMAIL, user.email()));
        sets.add(Updates.set(EMAIL_VERIFIED, user.emailVerified()));
        sets.add(Updates.set(DISPLAY_NAME, user.displayName()));
        sets.add(Updates.set(TEAMS, user.teams()));
        sets.add(Updates.set(TEAMS_LOWER, user.teams().stream().map(DirectoryUser::lower).toList()));
        sets.add(Updates.set(LAST_SEEN, Date.from(lastSeen)));
        sets.add(Updates.set(PRINCIPAL_LOWER, DirectoryUser.lower(user.principal())));
        sets.add(Updates.set(USERNAME_LOWER, DirectoryUser.lower(user.username())));
        sets.add(Updates.set(EMAIL_LOWER, DirectoryUser.lower(user.email())));
        sets.add(Updates.set(DISPLAY_NAME_LOWER, DirectoryUser.lower(user.displayName())));
        sets.add(Updates.setOnInsert(FIRST_SEEN, Date.from(firstSeen)));
        collection.updateOne(Filters.eq(ID, user.principal()), Updates.combine(sets), new UpdateOptions().upsert(true));
    }

    @Override
    public Optional<DirectoryUser> find(String principal) {
        if (principal == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(collection.find(Filters.eq(ID, principal)).first()).map(MongoUserDirectoryStore::toUser);
    }

    @Override
    public Map<String, DirectoryUser> findAll(Collection<String> principals) {
        Map<String, DirectoryUser> result = new LinkedHashMap<>();
        if (principals == null || principals.isEmpty()) {
            return result;
        }
        collection.find(Filters.in(ID, new LinkedHashSet<>(principals))).forEach(doc -> {
            DirectoryUser user = toUser(doc);
            result.put(user.principal(), user);
        });
        return result;
    }

    @Override
    public List<DirectoryUser> findByUsername(String username) {
        String lower = DirectoryUser.lower(username);
        if (lower == null || lower.isEmpty()) {
            return List.of();
        }
        return collect(Filters.eq(USERNAME_LOWER, lower), 10);
    }

    @Override
    public List<DirectoryUser> findByVerifiedEmail(String email) {
        String lower = DirectoryUser.lower(email);
        if (lower == null || lower.isEmpty()) {
            return List.of();
        }
        return collect(Filters.and(Filters.eq(EMAIL_LOWER, lower), Filters.eq(EMAIL_VERIFIED, true)), 10);
    }

    @Override
    public List<DirectoryUser> search(String prefix, int limit) {
        String lower = DirectoryUser.lower(prefix);
        if (lower == null || lower.isEmpty()) {
            return collect(new Document(), limit);
        }
        // Pattern.quote, because the prefix is whatever a person typed into a share
        // box — "a.b" must not match "axb", and "(" must not be a syntax error.
        String pattern = "^" + Pattern.quote(lower);
        return collect(Filters.or(Filters.regex(PRINCIPAL_LOWER, pattern), Filters.regex(USERNAME_LOWER, pattern),
                Filters.regex(EMAIL_LOWER, pattern), Filters.regex(DISPLAY_NAME_LOWER, pattern)), limit);
    }

    @Override
    public List<String> searchTeams(String prefix, int limit) {
        String lower = DirectoryUser.lower(prefix);
        String wanted = lower == null ? "" : lower;
        Set<String> teams = new LinkedHashSet<>();
        // distinct() returns every element of every matching array, not only the
        // element that matched, so the prefix is re-applied below.
        Bson filter = wanted.isEmpty() ? new Document() : Filters.regex(TEAMS_LOWER, "^team:" + Pattern.quote(wanted));
        for (String team : collection.distinct(TEAMS, filter, String.class)) {
            if (team != null && teamName(team).toLowerCase(Locale.ROOT).startsWith(wanted)) {
                teams.add(team);
                if (teams.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(teams);
    }

    @Override
    public boolean teamExists(String teamSubject) {
        return teamSubject != null && collection.find(Filters.eq(TEAMS, teamSubject)).limit(1).first() != null;
    }

    @Override
    public List<String> teamMembers(String teamSubject, int limit) {
        if (teamSubject == null) {
            return List.of();
        }
        return collect(Filters.eq(TEAMS, teamSubject), limit).stream().map(DirectoryUser::principal).toList();
    }

    @Override
    public boolean delete(String principal) {
        return principal != null && collection.deleteOne(Filters.eq(ID, principal)).getDeletedCount() > 0;
    }

    private List<DirectoryUser> collect(Bson filter, int limit) {
        List<DirectoryUser> users = new ArrayList<>();
        collection.find(filter).sort(Sorts.descending(LAST_SEEN)).limit(Math.max(1, limit)).forEach(doc -> users.add(toUser(doc)));
        return users;
    }

    private static String teamName(String teamSubject) {
        return teamSubject.startsWith("team:") ? teamSubject.substring("team:".length()) : teamSubject;
    }

    private static DirectoryUser toUser(Document doc) {
        Date first = doc.getDate(FIRST_SEEN);
        Date last = doc.getDate(LAST_SEEN);
        List<String> teams = doc.getList(TEAMS, String.class);
        return new DirectoryUser(doc.getString(ID), doc.getString(SUBJECT), doc.getString(USERNAME), doc.getString(EMAIL),
                Boolean.TRUE.equals(doc.getBoolean(EMAIL_VERIFIED)), doc.getString(DISPLAY_NAME), teams,
                first == null ? null : first.toInstant(), last == null ? null : last.toInstant());
    }
}
