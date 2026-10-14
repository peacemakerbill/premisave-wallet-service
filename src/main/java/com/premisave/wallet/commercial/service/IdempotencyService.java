package com.premisave.wallet.commercial.service;

import com.premisave.wallet.commercial.entity.IdempotencyRecord;
import com.premisave.wallet.commercial.enums.ErrorCode;
import com.premisave.wallet.commercial.exception.CommercialException;
import com.premisave.wallet.commercial.repository.IdempotencyRecordRepository;
import com.premisave.wallet.commercial.security.CommercialContext;
import com.premisave.wallet.commercial.util.Ids;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Makes money moving POSTs safe to retry. The first request with a key does the
 * work; a repeat with the same key and the same body gets the same resource
 * back; the same key with a different body is refused; a repeat while the first
 * is still running is told to wait. Keys are remembered for 24 hours.
 */
@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9._:\\-]{1,64}$");
    private static final Duration IN_PROGRESS_TTL = Duration.ofMinutes(5);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    public record Outcome<T>(T value, boolean replayed) {}

    private final IdempotencyRecordRepository records;
    private final MongoTemplate mongo;

    public IdempotencyService(IdempotencyRecordRepository records, MongoTemplate mongo) {
        this.records = records;
        this.mongo = mongo;
    }

    /**
     * @param action creates the resource and returns its id; must leave nothing half done if it throws
     * @param load   builds the response for a resource id (used for the first call and for replays)
     */
    public <T> Outcome<T> execute(CommercialContext ctx, String key, String bodyHash, String resourceType,
                                  Supplier<String> action, Function<String, T> load) {
        requireValidKey(key);
        Instant now = Instant.now();
        IdempotencyRecord record = new IdempotencyRecord(Ids.next("idem"), ctx.merchantId(), ctx.livemode(), key,
                bodyHash, now, now.plus(IN_PROGRESS_TTL));
        try {
            records.insert(record);
        } catch (DuplicateKeyException exists) {
            return replay(ctx, key, bodyHash, load);
        }

        String resourceId;
        try {
            resourceId = action.get();
        } catch (RuntimeException e) {
            records.deleteById(record.getId());
            throw e;
        }
        complete(record.getId(), resourceType, resourceId);
        return new Outcome<>(load.apply(resourceId), false);
    }

    public static void requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw CommercialException.validation("Idempotency-Key", "header is required for this request");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw CommercialException.validation("Idempotency-Key", "must be 1 to 64 characters of letters, digits and . _ : -");
        }
    }

    private <T> Outcome<T> replay(CommercialContext ctx, String key, String bodyHash, Function<String, T> load) {
        IdempotencyRecord existing = records.findByMerchantIdAndLivemodeAndKey(ctx.merchantId(), ctx.livemode(), key)
                .orElseThrow(() -> new CommercialException(ErrorCode.REQUEST_IN_PROGRESS,
                        "A request with this Idempotency-Key was just finished or removed; retry"));
        if (!existing.getRequestHash().equals(bodyHash)) {
            throw new CommercialException(ErrorCode.DUPLICATE_REQUEST,
                    "This Idempotency-Key was already used with a different request body");
        }
        if (!IdempotencyRecord.COMPLETED.equals(existing.getState())) {
            throw new CommercialException(ErrorCode.REQUEST_IN_PROGRESS,
                    "A request with this Idempotency-Key is still being processed");
        }
        return new Outcome<>(load.apply(existing.getResourceId()), true);
    }

    private void complete(String recordId, String resourceType, String resourceId) {
        Document set = new Document("state", IdempotencyRecord.COMPLETED)
                .append("resourceType", resourceType)
                .append("resourceId", resourceId)
                .append("expiresAt", Date.from(Instant.now().plus(COMPLETED_TTL)));
        mongo.findAndModify(Query.query(Criteria.where("id").is(recordId)),
                new BasicUpdate(new Document("$set", set)),
                FindAndModifyOptions.options().returnNew(true), IdempotencyRecord.class);
    }
}
