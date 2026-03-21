package ai.labs.eddi.engine.schedule.mongo;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.result.UpdateResult;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * MongoDB implementation of {@link IScheduleStore}.
 * <p>
 * Uses {@code findOneAndUpdate} for atomic CAS (compare-and-swap) claiming,
 * ensuring exactly-one-instance execution in clustered deployments.
 * <p>
 * Annotated {@code @DefaultBean} so PostgreSQL can override.
 * <p>
 * All {@link Instant} values are stored as epoch-millisecond {@code Long}
 * for consistent BSON comparison in filter queries.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class MongoScheduleStore implements IScheduleStore {

    private static final Logger LOGGER = Logger.getLogger(MongoScheduleStore.class);

    private static final String COLLECTION_SCHEDULES = "eddi_schedules";
    private static final String COLLECTION_FIRE_LOGS = "eddi_schedule_fire_logs";

    private static final String ID = "_id";
    private static final String ENABLED = "enabled";
    private static final String NEXT_FIRE = "nextFire";
    private static final String FIRE_STATUS = "fireStatus";
    private static final String CLAIMED_BY = "claimedBy";
    private static final String CLAIMED_AT = "claimedAt";
    private static final String FIRE_ID = "fireId";
    private static final String FAIL_COUNT = "failCount";
    private static final String NEXT_RETRY_AT = "nextRetryAt";
    private static final String LAST_FIRED = "lastFired";
    private static final String UPDATED_AT = "updatedAt";
    private static final String AGENT_ID = "agentId";
    private static final String TENANT_ID = "tenantId";
    private static final String SCHEDULE_ID = "scheduleId";
    private static final String STARTED_AT = "startedAt";
    private static final String STATUS = "status";

    private final MongoCollection<Document> scheduleCollection;
    private final MongoCollection<Document> fireLogCollection;
    private final IDocumentBuilder documentBuilder;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public MongoScheduleStore(MongoDatabase database,
                              IJsonSerialization jsonSerialization,
                              IDocumentBuilder documentBuilder) {
        this.jsonSerialization = jsonSerialization;
        this.documentBuilder = documentBuilder;
        this.scheduleCollection = database.getCollection(COLLECTION_SCHEDULES);
        this.fireLogCollection = database.getCollection(COLLECTION_FIRE_LOGS);

        // Indexes for efficient polling
        scheduleCollection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending(ENABLED),
                        Indexes.ascending(NEXT_FIRE),
                        Indexes.ascending(FIRE_STATUS)),
                new IndexOptions().name("idx_schedules_due")
        );
        scheduleCollection.createIndex(
                Indexes.ascending(AGENT_ID),
                new IndexOptions().name("idx_schedules_botId")
        );
        scheduleCollection.createIndex(
                Indexes.ascending(TENANT_ID),
                new IndexOptions().name("idx_schedules_tenantId")
        );

        // Fire log indexes
        fireLogCollection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending(SCHEDULE_ID),
                        Indexes.descending(STARTED_AT)),
                new IndexOptions().name("idx_fire_logs_schedule")
        );
        // Fix #14: index on status for readFailedFireLogs()
        fireLogCollection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending(STATUS),
                        Indexes.descending(STARTED_AT)),
                new IndexOptions().name("idx_fire_logs_status")
        );
    }

    // ========================= CRUD =========================

    @Override
    public String createSchedule(ScheduleConfiguration schedule) throws IResourceStore.ResourceStoreException {
        try {
            String id = UUID.randomUUID().toString();
            schedule.setId(id);
            Instant now = Instant.now();
            schedule.setCreatedAt(now);
            schedule.setUpdatedAt(now);

            Document doc = toDocument(schedule);
            doc.put(ID, id);
            doc.remove("id"); // use _id instead
            // Fix #6: Store Instants as epoch-millis for consistent BSON comparison
            storeInstantsAsLong(doc);
            scheduleCollection.insertOne(doc);
            LOGGER.infof("Created schedule '%s' (id=%s, type=%s) for Agent %s",
                    schedule.getName(), id, schedule.getTriggerType(), schedule.getAgentId());
            return id;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to create schedule", e);
        }
    }

    @Override
    public ScheduleConfiguration readSchedule(String scheduleId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        try {
            Document doc = scheduleCollection.find(eq(ID, scheduleId)).first();
            if (doc == null) {
                throw new IResourceStore.ResourceNotFoundException(
                        "Schedule with id=" + scheduleId + " not found");
            }
            return fromDocument(doc);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to read schedule " + scheduleId, e);
        }
    }

    @Override
    public void updateSchedule(String scheduleId, ScheduleConfiguration schedule)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        try {
            schedule.setUpdatedAt(Instant.now());
            Document doc = toDocument(schedule);
            doc.remove("id");
            doc.remove(ID);
            storeInstantsAsLong(doc);

            var result = scheduleCollection.replaceOne(eq(ID, scheduleId), doc);
            if (result.getMatchedCount() == 0) {
                throw new IResourceStore.ResourceNotFoundException(
                        "Schedule with id=" + scheduleId + " not found");
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to update schedule " + scheduleId, e);
        }
    }

    // Fix #3: Atomic field-level updates instead of replaceOne() for targeted changes
    @Override
    public void setScheduleEnabled(String scheduleId, boolean enabled, Instant nextFire)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        try {
            Instant now = Instant.now();
            var updates = new ArrayList<Bson>();
            updates.add(set(ENABLED, enabled));
            updates.add(set(UPDATED_AT, epochMillis(now)));

            if (enabled && nextFire != null) {
                updates.add(set(NEXT_FIRE, epochMillis(nextFire)));
                updates.add(set(FIRE_STATUS, FireStatus.PENDING.name()));
                updates.add(set(FAIL_COUNT, 0));
            }

            UpdateResult result = scheduleCollection.updateOne(eq(ID, scheduleId), combine(updates));
            if (result.getMatchedCount() == 0) {
                throw new IResourceStore.ResourceNotFoundException(
                        "Schedule with id=" + scheduleId + " not found");
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to set enabled for " + scheduleId, e);
        }
    }

    @Override
    public void deleteSchedule(String scheduleId) throws IResourceStore.ResourceStoreException {
        try {
            scheduleCollection.deleteOne(eq(ID, scheduleId));
            LOGGER.infof("Deleted schedule id=%s", scheduleId);
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete schedule " + scheduleId, e);
        }
    }

    @Override
    public int deleteSchedulesByBotId(String agentId) throws IResourceStore.ResourceStoreException {
        try {
            var result = scheduleCollection.deleteMany(eq(AGENT_ID, agentId));
            int count = (int) result.getDeletedCount();
            if (count > 0) {
                LOGGER.infof("Cascade-deleted %d schedule(s) for Agent %s", count, agentId);
            }
            return count;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete schedules for Agent " + agentId, e);
        }
    }

    @Override
    public List<ScheduleConfiguration> readAllSchedules(int limit) throws IResourceStore.ResourceStoreException {
        // Fix #12: bounded result set
        return readSchedulesWithFilter(new Document(), limit);
    }

    @Override
    public List<ScheduleConfiguration> readSchedulesByBotId(String agentId) throws IResourceStore.ResourceStoreException {
        return readSchedulesWithFilter(new Document(AGENT_ID, agentId), 500);
    }

    // ========================= Polling & Claiming =========================

    @Override
    public List<ScheduleConfiguration> findDueSchedules(Instant now, Instant leaseExpiry, int maxRetries)
            throws IResourceStore.ResourceStoreException {
        try {
            long nowMs = epochMillis(now);
            long leaseMs = epochMillis(leaseExpiry);

            // Due = enabled AND nextFire <= now AND
            //   (PENDING OR (CLAIMED + lease expired) OR (FAILED + retry due + under max retries))
            Bson pendingFilter = eq(FIRE_STATUS, FireStatus.PENDING.name());
            Bson leaseExpiredFilter = and(
                    eq(FIRE_STATUS, FireStatus.CLAIMED.name()),
                    lte(CLAIMED_AT, leaseMs)
            );
            Bson retryDueFilter = and(
                    eq(FIRE_STATUS, FireStatus.FAILED.name()),
                    lte(NEXT_RETRY_AT, nowMs),
                    lt(FAIL_COUNT, maxRetries)
            );

            Bson filter = and(
                    eq(ENABLED, true),
                    lte(NEXT_FIRE, nowMs),
                    or(pendingFilter, leaseExpiredFilter, retryDueFilter)
            );

            return readSchedulesWithFilter(filter, 100);
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to find due schedules", e);
        }
    }

    @Override
    public boolean tryClaim(String scheduleId, String instanceId, Instant now)
            throws IResourceStore.ResourceStoreException {
        try {
            long nowMs = epochMillis(now);

            // Fix #2: Atomic CAS with complete guards
            // Only claim if PENDING, or FAILED with retry due + under max retries
            Bson filter = and(
                    eq(ID, scheduleId),
                    or(
                            eq(FIRE_STATUS, FireStatus.PENDING.name()),
                            and(
                                    eq(FIRE_STATUS, FireStatus.FAILED.name()),
                                    lte(NEXT_RETRY_AT, nowMs)
                                    // Note: maxRetries guard is in findDueSchedules —
                                    // we trust the poller already filtered, but adding
                                    // the retryAt guard prevents premature claiming
                            )
                    )
            );

            Bson update = combine(
                    set(FIRE_STATUS, FireStatus.CLAIMED.name()),
                    set(CLAIMED_BY, instanceId),
                    set(CLAIMED_AT, nowMs),
                    set(FIRE_ID, scheduleId + "_" + now.toString()),
                    set(UPDATED_AT, nowMs)
            );

            Document result = scheduleCollection.findOneAndUpdate(filter, update);
            if (result != null) {
                LOGGER.debugf("Claimed schedule %s on instance %s", scheduleId, instanceId);
                return true;
            }
            return false;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to claim schedule " + scheduleId, e);
        }
    }

    @Override
    public void markCompleted(String scheduleId, Instant nextFire) throws IResourceStore.ResourceStoreException {
        try {
            long nowMs = epochMillis(Instant.now());
            var updates = new ArrayList<Bson>();
            updates.add(set(FIRE_STATUS, FireStatus.PENDING.name()));
            updates.add(set(LAST_FIRED, nowMs));
            updates.add(set(FAIL_COUNT, 0));
            updates.add(set(CLAIMED_BY, null));
            updates.add(set(CLAIMED_AT, null));
            updates.add(set(FIRE_ID, null));
            updates.add(set(NEXT_RETRY_AT, null));
            updates.add(set(UPDATED_AT, nowMs));

            // Fix #5: If nextFire is null (one-shot), disable the schedule
            if (nextFire != null) {
                updates.add(set(NEXT_FIRE, epochMillis(nextFire)));
            } else {
                updates.add(set(ENABLED, false));
                updates.add(set(NEXT_FIRE, null));
            }

            scheduleCollection.updateOne(eq(ID, scheduleId), combine(updates));
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to mark completed: " + scheduleId, e);
        }
    }

    @Override
    public void markFailed(String scheduleId, Instant nextRetryAt) throws IResourceStore.ResourceStoreException {
        try {
            long nowMs = epochMillis(Instant.now());
            Bson update = combine(
                    set(FIRE_STATUS, FireStatus.FAILED.name()),
                    set(NEXT_RETRY_AT, epochMillis(nextRetryAt)),
                    set(CLAIMED_BY, null),
                    set(CLAIMED_AT, null),
                    inc(FAIL_COUNT, 1),
                    set(UPDATED_AT, nowMs)
            );
            scheduleCollection.updateOne(eq(ID, scheduleId), update);
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to mark failed: " + scheduleId, e);
        }
    }

    @Override
    public void markDeadLettered(String scheduleId) throws IResourceStore.ResourceStoreException {
        try {
            long nowMs = epochMillis(Instant.now());
            Bson update = combine(
                    set(FIRE_STATUS, FireStatus.DEAD_LETTERED.name()),
                    set(CLAIMED_BY, null),
                    set(CLAIMED_AT, null),
                    set(UPDATED_AT, nowMs)
            );
            scheduleCollection.updateOne(eq(ID, scheduleId), update);
            LOGGER.warnf("Schedule %s dead-lettered after max retries", scheduleId);
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to dead-letter: " + scheduleId, e);
        }
    }

    @Override
    public void requeueDeadLetter(String scheduleId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        try {
            long nowMs = epochMillis(Instant.now());
            Bson filter = and(
                    eq(ID, scheduleId),
                    eq(FIRE_STATUS, FireStatus.DEAD_LETTERED.name())
            );
            Bson update = combine(
                    set(FIRE_STATUS, FireStatus.PENDING.name()),
                    set(FAIL_COUNT, 0),
                    set(NEXT_RETRY_AT, null),
                    set(CLAIMED_BY, null),
                    set(CLAIMED_AT, null),
                    set(NEXT_FIRE, nowMs), // fire immediately on next poll
                    set(UPDATED_AT, nowMs)
            );
            UpdateResult result = scheduleCollection.updateOne(filter, update);
            if (result.getMatchedCount() == 0) {
                throw new IResourceStore.ResourceNotFoundException(
                        "Schedule " + scheduleId + " not found or not in DEAD_LETTERED state");
            }
            LOGGER.infof("Requeued dead-lettered schedule %s", scheduleId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to requeue: " + scheduleId, e);
        }
    }

    // ========================= Fire Log =========================

    @Override
    public void logFire(ScheduleFireLog fireLog) throws IResourceStore.ResourceStoreException {
        try {
            Document doc = toDocument(fireLog);
            doc.put(ID, fireLog.id());
            storeInstantsAsLong(doc);
            fireLogCollection.insertOne(doc);
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to log fire", e);
        }
    }

    @Override
    public List<ScheduleFireLog> readFireLogs(String scheduleId, int limit) throws IResourceStore.ResourceStoreException {
        try {
            List<ScheduleFireLog> logs = new ArrayList<>();
            for (var doc : fireLogCollection
                    .find(eq(SCHEDULE_ID, scheduleId))
                    .sort(new Document(STARTED_AT, -1))
                    .limit(limit)) {
                logs.add(fireLogFromDocument(doc));
            }
            return logs;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to read fire logs", e);
        }
    }

    @Override
    public List<ScheduleFireLog> readFailedFireLogs(int limit) throws IResourceStore.ResourceStoreException {
        try {
            List<ScheduleFireLog> logs = new ArrayList<>();
            Bson filter = or(
                    eq(STATUS, FireStatus.FAILED.name()),
                    eq(STATUS, FireStatus.DEAD_LETTERED.name())
            );
            for (var doc : fireLogCollection
                    .find(filter)
                    .sort(new Document(STARTED_AT, -1))
                    .limit(limit)) {
                logs.add(fireLogFromDocument(doc));
            }
            return logs;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to read failed fire logs", e);
        }
    }

    // ========================= Helpers =========================

    private List<ScheduleConfiguration> readSchedulesWithFilter(Bson filter, int limit)
            throws IResourceStore.ResourceStoreException {
        try {
            List<ScheduleConfiguration> result = new ArrayList<>();
            for (var doc : scheduleCollection.find(filter).limit(limit)) {
                result.add(fromDocument(doc));
            }
            return result;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to read schedules", e);
        }
    }

    /**
     * Fix #6: Convert known Instant fields to epoch-millis Long for BSON comparison consistency.
     * Jackson with write-dates-as-timestamps=true serializes Instants as longs, but we
     * must ensure the filter queries also use longs consistently.
     */
    private static void storeInstantsAsLong(Document doc) {
        convertInstantField(doc, "nextFire");
        convertInstantField(doc, "lastFired");
        convertInstantField(doc, "claimedAt");
        convertInstantField(doc, "nextRetryAt");
        convertInstantField(doc, "createdAt");
        convertInstantField(doc, "updatedAt");
        // fireLog fields
        convertInstantField(doc, "fireTime");
        convertInstantField(doc, "startedAt");
        convertInstantField(doc, "completedAt");
    }

    private static void convertInstantField(Document doc, String field) {
        Object val = doc.get(field);
        if (val instanceof Number num) {
            doc.put(field, num.longValue()); // already epoch-millis, normalize to Long
        } else if (val instanceof Instant inst) {
            doc.put(field, inst.toEpochMilli());
        } else if (val instanceof Date date) {
            doc.put(field, date.getTime());
        }
        // null or other types left as-is
    }

    private static long epochMillis(Instant instant) {
        return instant.toEpochMilli();
    }

    private Document toDocument(Object obj) throws IResourceStore.ResourceStoreException {
        try {
            return jsonSerialization.deserialize(jsonSerialization.serialize(obj), Document.class);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Serialization failed", e);
        }
    }

    private ScheduleConfiguration fromDocument(Document doc) throws IResourceStore.ResourceStoreException {
        try {
            if (doc.containsKey(ID)) {
                doc.put("id", doc.get(ID));
            }
            return documentBuilder.build(doc, ScheduleConfiguration.class);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Deserialization failed", e);
        }
    }

    private ScheduleFireLog fireLogFromDocument(Document doc) throws IResourceStore.ResourceStoreException {
        try {
            if (doc.containsKey(ID)) {
                doc.put("id", doc.get(ID));
            }
            return documentBuilder.build(doc, ScheduleFireLog.class);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Deserialization failed", e);
        }
    }
}
