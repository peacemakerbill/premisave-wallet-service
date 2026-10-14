package com.premisave.wallet.commercial.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Makes sure the indexes declared on the commercial documents exist. Several
 * guarantees depend on them: the unique idempotency key, the unique ledger
 * line per operation, and the one-registration-per-URL rules. Spring Data
 * creates indexes by itself only when spring.data.mongodb.auto-index-creation
 * is true, and this project does not set it, so without this class those
 * unique indexes would silently not exist.
 *
 * Only documents in the commercial package are touched; the rest of the wallet
 * service keeps whatever index handling it has today. A failure on one document
 * (for example duplicates already stored under a unique index) is logged and
 * does not stop the others or the service from starting. Creating an index that
 * already exists with the same definition does nothing.
 */
@Component("commercialIndexInitializer")
@ConditionalOnProperty(prefix = "commercial.indexes", name = "ensure", havingValue = "true", matchIfMissing = true)
public class CommercialIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(CommercialIndexInitializer.class);
    private static final String PACKAGE = "com.premisave.wallet.commercial";

    private final MongoTemplate mongo;

    public CommercialIndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mapping =
                mongo.getConverter().getMappingContext();
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mapping);
        int types = 0;
        for (MongoPersistentEntity<?> entity : mapping.getPersistentEntities()) {
            Class<?> type = entity.getType();
            if (!type.getName().startsWith(PACKAGE)) continue;
            try {
                IndexOperations ops = mongo.indexOps(type);
                for (IndexDefinition definition : resolver.resolveIndexFor(type)) {
                    ops.createIndex(definition);
                }
                types++;
            } catch (RuntimeException e) {
                log.error("Could not create the indexes for {}", type.getSimpleName(), e);
            }
        }
        log.info("Commercial indexes checked for {} document types", types);
    }
}
