package com.clawai.gatedemo.game.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class MongoCityWorldStatePersistence implements CityWorldStatePersistence {

    private static final String COLLECTION = "city_world_state";

    private final MongoTemplate mongoTemplate;

    public MongoCityWorldStatePersistence(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    static String documentId(long regionId, long cityId) {
        return regionId + ":" + cityId;
    }

    @Override
    public void saveSnapshot(long regionId, long cityId, Map<String, Integer> tiles) {
        CityWorldStateDocument doc = new CityWorldStateDocument();
        doc.setId(documentId(regionId, cityId));
        doc.setRegionId(regionId);
        doc.setCityId(cityId);
        doc.setTiles(tiles != null ? new HashMap<>(tiles) : new HashMap<>());
        mongoTemplate.save(doc, COLLECTION);
    }

    @Override
    public Map<String, Integer> loadSnapshot(long regionId, long cityId) {
        Query q = new Query(Criteria.where("_id").is(documentId(regionId, cityId)));
        CityWorldStateDocument found = mongoTemplate.findOne(q, CityWorldStateDocument.class, COLLECTION);
        if (found == null || found.getTiles() == null) {
            return Collections.emptyMap();
        }
        return Map.copyOf(found.getTiles());
    }
}
