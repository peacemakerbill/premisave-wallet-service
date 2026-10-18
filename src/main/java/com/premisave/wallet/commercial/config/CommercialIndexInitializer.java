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
import org.springframework.data.mongodb.core.mapping.Document;
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
 * Only documents (classes annotated with @Document) in the commercial package are touched; the rest of the wallet
 * service keeps whatever index handling it has today. A failure on one index
 * (for example duplicates already stored under a unique index, or an older index
 * of the same name with different options) is logged as one warning and does not
 * stop the other indexes, the other documents or the service from starting.
 * Creating an index that already exists with the same definition does nothing.
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
        int failed = 0;
        for (MongoPersistentEntity<?> entity : mapping.getPersistentEntities()) {
            Class<?> type = entity.getType();
            if (!type.getName().startsWith(PACKAGE)) continue;
            // Embedded value classes (results, limits, timeline entries) are mapped too but are not collections.
            if (!type.isAnnotationPresent(Document.class)) continue;
            try {
                IndexOperations ops = mongo.indexOps(type);
                for (IndexDefinition definition : resolver.resolveIndexFor(type)) {
                    try {
                        ops.createIndex(definition);
                    } catch (RuntimeException e) {
                        failed++;
                        String collection = mongo.getCollectionName(type);
                        log.warn("Could not create an index on {} ({}): {}. If an older index with the same name exists, list it with "
                                + "db.{}.getIndexes() in mongosh, drop it with db.{}.dropIndex(\"<name>\") and restart.",
                                type.getSimpleName(), collection, rootMessage(e), collection, collection);
                    }
                }
                types++;
            } catch (RuntimeException e) {
                failed++;
                log.error("Could not work out the indexes for {}", type.getSimpleName(), e);
            }
        }
        log.info("Commercial indexes checked for {} document types, {} index(es) could not be created", types, failed);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}