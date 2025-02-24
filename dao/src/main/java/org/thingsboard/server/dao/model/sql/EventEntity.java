/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.model.sql;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.Event;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.id.EventId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.model.BaseEntity;
import org.thingsboard.server.dao.model.BaseSqlEntity;
import org.thingsboard.server.dao.util.mapping.JsonStringType;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import java.util.UUID;

import static org.thingsboard.server.dao.model.ModelConstants.EPOCH_DIFF;
import static org.thingsboard.server.dao.model.ModelConstants.EVENT_BODY_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.EVENT_COLUMN_FAMILY_NAME;
import static org.thingsboard.server.dao.model.ModelConstants.EVENT_ENTITY_ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.EVENT_ENTITY_TYPE_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.EVENT_TENANT_ID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.EVENT_TYPE_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.EVENT_UID_PROPERTY;
import static org.thingsboard.server.dao.model.ModelConstants.TS_COLUMN;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@TypeDef(name = "json", typeClass = JsonStringType.class)
@Table(name = EVENT_COLUMN_FAMILY_NAME)
@NoArgsConstructor
public class EventEntity extends BaseSqlEntity<Event> implements BaseEntity<Event> {

    @Column(name = EVENT_TENANT_ID_PROPERTY)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = EVENT_ENTITY_TYPE_PROPERTY)
    private EntityType entityType;

    @Column(name = EVENT_ENTITY_ID_PROPERTY)
    private UUID entityId;

    @Column(name = EVENT_TYPE_PROPERTY)
    private String eventType;

    @Column(name = EVENT_UID_PROPERTY)
    private String eventUid;

    @Type(type = "json")
    @Column(name = EVENT_BODY_PROPERTY)
    private JsonNode body;

    @Column(name = TS_COLUMN)
    private long ts;

    public EventEntity(Event event) {
        if (event.getId() != null) {
            this.setUuid(event.getId().getId());
            this.ts = getTs(event.getId().getId());
        } else {
            this.ts = System.currentTimeMillis();
        }
        this.setCreatedTime(event.getCreatedTime());
        if (event.getTenantId() != null) {
            this.tenantId = event.getTenantId().getId();
        }
        if (event.getEntityId() != null) {
            this.entityType = event.getEntityId().getEntityType();
            this.entityId = event.getEntityId().getId();
        }
        this.eventType = event.getType();
        this.eventUid = event.getUid();
        this.body = event.getBody();
    }


    @Override
    public Event toData() {
        Event event = new Event(new EventId(this.getUuid()));
        event.setCreatedTime(createdTime);
        event.setTenantId(TenantId.fromUUID(tenantId));
        event.setEntityId(EntityIdFactory.getByTypeAndUuid(entityType, entityId));
        event.setBody(body);
        event.setType(eventType);
        event.setUid(eventUid);
        return event;
    }

    private static long getTs(UUID uuid) {
        return (uuid.timestamp() - EPOCH_DIFF) / 10000;
    }
}
