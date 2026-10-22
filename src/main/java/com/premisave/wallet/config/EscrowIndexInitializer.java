package com.premisave.wallet.config;

import com.premisave.wallet.entity.EscrowHold;
import com.premisave.wallet.entity.EscrowPayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.stereotype.Component;

/**
 * Makes sure the indexes of the escrow collections exist. The unique index on each reference is what stops two
 * simultaneous requests with the same reference from both creating a hold or a payout, so it has to exist. Spring Data
 * creates indexes by itself only when spring.data.mongodb.auto-index-creation is true, and this service does not set
 * it, so without this class they would silently not exist. Creating an index that already exists does nothing.
 *
 * A failure is logged loudly (as an error, naming the collection) and does not stop the service from starting.
 */
@Component
public class EscrowIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(EscrowIndexInitializer.class);

    private final MongoTemplate mongo;

    public EscrowIndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mapping =
                mongo.getConverter().getMappingContext();
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mapping);
        for (Class<?> type : new Class<?>[] {EscrowHold.class, EscrowPayout.class}) {
            String collection = mongo.getCollectionName(type);
            try {
                IndexOperations ops = mongo.indexOps(type);
                for (IndexDefinition definition : resolver.resolveIndexFor(type)) {
                    ops.createIndex(definition);
                }
                log.info("Escrow indexes checked on {}", collection);
            } catch (RuntimeException e) {
                log.error("Could not create the escrow indexes on {}. Without the unique index on reference, two simultaneous "
                        + "requests with the same reference could both succeed. If an older index with the same name exists, drop it "
                        + "(db.{}.getIndexes(), db.{}.dropIndex(\"<name>\")) and restart.", collection, collection, collection, e);
            }
        }
    }
}
