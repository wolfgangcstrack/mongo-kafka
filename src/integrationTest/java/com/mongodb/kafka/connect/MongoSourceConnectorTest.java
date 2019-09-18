/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mongodb.kafka.connect;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.rangeClosed;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.bson.Document;

import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import com.mongodb.kafka.connect.mongodb.MongoKafkaTestCase;
import com.mongodb.kafka.connect.source.MongoSourceConfig;

public class MongoSourceConnectorTest extends MongoKafkaTestCase {

    @BeforeEach
    void setUp() {
        assumeTrue(isReplicaSetOrSharded());
    }

    @AfterEach
    void tearDown() {
        getMongoClient().listDatabaseNames().into(new ArrayList<>()).forEach(i -> {
            if (i.startsWith(getDatabaseName())) {
                getMongoClient().getDatabase(i).drop();
            }
        });
    }

    @Test
    @DisplayName("Ensure source loads data from MongoDB MongoClient")
    void testSourceLoadsDataFromMongoClient() {
        addSourceConnector();

        MongoCollection<Document> coll1 = getDatabase("1").getCollection("coll");
        MongoCollection<Document> coll2 = getDatabase("2").getCollection("coll");
        MongoCollection<Document> coll3 = getDatabase("3").getCollection("coll");
        MongoCollection<Document> coll4 = getDatabase("1").getCollection("db1Coll2");

        insertMany(rangeClosed(1, 50), coll1, coll2);

        assertAll(
                () -> assertProduced(50, coll1),
                () -> assertProduced(50, coll2),
                () -> assertProduced(0, coll3));


        getDatabase("1").drop();
        insertMany(rangeClosed(51, 60), coll2, coll4);
        insertMany(rangeClosed(1, 70), coll3);

        assertAll(
                () -> assertProduced(51, coll1),
                () -> assertProduced(60, coll2),
                () -> assertProduced(70, coll3),
                () -> assertProduced(10, coll4)
        );
    }

    @Test
    @DisplayName("Ensure source loads data from MongoDB database")
    void testSourceLoadsDataFromDatabase() {
        try (KafkaConsumer<?, ?> consumer = createConsumer()) {
            Pattern pattern = Pattern.compile(format("^%s.*", getDatabaseName()));
            consumer.subscribe(pattern);

            MongoDatabase db = getDatabase("4");

            Properties sourceProperties = new Properties();
            sourceProperties.put(MongoSourceConfig.DATABASE_CONFIG, db.getName());
            addSourceConnector(sourceProperties);

            MongoCollection<Document> coll1 = db.getCollection("coll1");
            MongoCollection<Document> coll2 = db.getCollection("coll2");
            MongoCollection<Document> coll3 = db.getCollection("coll3");

            insertMany(rangeClosed(1, 50), coll1, coll2);

            assertAll(
                    () -> assertProduced(50, coll1),
                    () -> assertProduced(50, coll2),
                    () -> assertProduced(0, coll3)
            );

            // Update some of the collections
            coll1.drop();
            coll2.drop();

            insertMany(rangeClosed(1, 20), coll3);

            String collName4 = "coll4";
            coll3.renameCollection(new MongoNamespace(getDatabaseName(), collName4));
            MongoCollection<Document> coll4 = db.getCollection(collName4);

            insertMany(rangeClosed(21, 30), coll4);

            assertAll(
                    () -> assertProduced(51, coll1),
                    () -> assertProduced(51, coll2),
                    () -> assertProduced(21, coll3),
                    () -> assertProduced(10, coll4)
            );
        }
    }

    @Test
    @DisplayName("Ensure source loads data from collection")
    void testSourceLoadsDataFromCollection() {
        MongoCollection<Document> coll = getDatabase("5").getCollection("coll");

        Properties sourceProperties = new Properties();
        sourceProperties.put(MongoSourceConfig.DATABASE_CONFIG, coll.getNamespace().getDatabaseName());
        sourceProperties.put(MongoSourceConfig.COLLECTION_CONFIG, coll.getNamespace().getCollectionName());
        addSourceConnector(sourceProperties);

        insertMany(rangeClosed(1, 100), coll);
        assertProduced(100, coll);

        coll.drop();
        assertProduced(100, coll);
    }

    @Test
    @DisplayName("Ensure source loads data from collection and outputs documents only")
    void testSourceLoadsDataFromCollectionDocumentOnly() {
        MongoCollection<Document> coll = getDatabase("6").getCollection("coll");

        Properties sourceProperties = new Properties();
        sourceProperties.put(MongoSourceConfig.DATABASE_CONFIG, coll.getNamespace().getDatabaseName());
        sourceProperties.put(MongoSourceConfig.COLLECTION_CONFIG, coll.getNamespace().getCollectionName());
        sourceProperties.put(MongoSourceConfig.PUBLISH_FULL_DOCUMENT_ONLY_CONFIG, "true");
        addSourceConnector(sourceProperties);

        List<Document> docs = insertMany(rangeClosed(1, 100), coll);
        assertProduced(docs, coll);

        coll.drop();
        assertProduced(docs, coll);
    }

    @Test
    @DisplayName("test uses resume token as source record key by default")
    void testUsesResumeTokenAsSourceRecordKeyByDefault() {
        MongoCollection<Document> coll = getDatabase("6").getCollection("coll");

        Properties sourceProperties = new Properties();
        sourceProperties.put(MongoSourceConfig.DATABASE_CONFIG, coll.getNamespace().getDatabaseName());
        sourceProperties.put(MongoSourceConfig.COLLECTION_CONFIG, coll.getNamespace().getCollectionName());
        addSourceConnector(sourceProperties);

        insertOne(coll);
        String producedKeyString = getProducedKeys(1, coll.getNamespace().getFullName()).get(0);
        BsonDocument producedKey = BsonDocument.parse(producedKeyString);

        assertNotNull(producedKey.getDocument("_id"));
    }

    @Test
    @DisplayName("test uses documentKey as source record key")
    void testUsesDocumentKeyAsSourceRecordKey() {
        MongoCollection<Document> coll = getDatabase("6").getCollection("coll");

        Properties sourceProperties = new Properties();
        sourceProperties.put(MongoSourceConfig.DATABASE_CONFIG, coll.getNamespace().getDatabaseName());
        sourceProperties.put(MongoSourceConfig.COLLECTION_CONFIG, coll.getNamespace().getCollectionName());
        sourceProperties.put(MongoSourceConfig.SOURCE_RECORD_KEY_CONFIG, "documentKey");
        addSourceConnector(sourceProperties);

        Document doc = insertOne(coll);
        String producedKeyString = getProducedKeys(1, coll.getNamespace().getFullName()).get(0);
        LOGGER.info("THIS IS THE FUCKING KEY {}", producedKeyString);
        BsonDocument producedKey = BsonDocument.parse(producedKeyString);

        assertEquals(doc.get("_id"), producedKey.get("documentKey"));
    }

    private MongoDatabase getDatabase(final String postfix) {
        return getMongoClient().getDatabase(format("%s%s", getDatabaseName(), postfix));
    }

    private Document insertOne(final MongoCollection<?>... collections) {
        return insertMany(rangeClosed(1, 1), collections).get(0);
    }

    private List<Document> insertMany(final IntStream stream, final MongoCollection<?>... collections) {
        List<Document> docs = stream.mapToObj(i -> Document.parse(format("{_id: %s}", i))).collect(toList());
        for (MongoCollection<?> c : collections) {
            LOGGER.debug("Inserting into {} ", c.getNamespace().getFullName());
            c.withDocumentClass(Document.class).insertMany(docs);
        }
        return docs;
    }

}
