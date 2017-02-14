/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.store.graph.v1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.TypeCategory;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.instance.AtlasStruct;
import org.apache.atlas.repository.store.graph.EntityGraphDiscovery;
import org.apache.atlas.repository.store.graph.EntityGraphDiscoveryContext;
import org.apache.atlas.repository.store.graph.EntityResolver;
import org.apache.atlas.type.*;
import org.apache.atlas.type.AtlasBuiltInTypes.AtlasObjectIdType;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class AtlasEntityGraphDiscoveryV1 implements EntityGraphDiscovery {
    private static final Logger LOG = LoggerFactory.getLogger(AtlasEntityGraphDiscoveryV1.class);

    private final AtlasTypeRegistry           typeRegistry;
    private final EntityGraphDiscoveryContext discoveryContext;

    public AtlasEntityGraphDiscoveryV1(AtlasTypeRegistry typeRegistry, EntityStream entityStream) {
        this.typeRegistry     = typeRegistry;
        this.discoveryContext = new EntityGraphDiscoveryContext(typeRegistry, entityStream);
    }

    @Override
    public void init() throws AtlasBaseException {
        //Nothing to do
    }

    @Override
    public EntityGraphDiscoveryContext discoverEntities() throws AtlasBaseException {

        // walk through entities in stream and validate them; record entity references
        discoverAndValidate();

        // resolve entity references discovered in previous step
        resolveReferences();

        return discoveryContext;
    }

    @Override
    public void cleanUp() throws AtlasBaseException {
        discoveryContext.cleanUp();
    }


    protected void discoverAndValidate() throws AtlasBaseException {
        EntityStream entityStream = discoveryContext.getEntityStream();

        Set<String> walkedEntities = new HashSet<>();

        // walk through top-level entities and find entity references
        while (entityStream.hasNext()) {
            AtlasEntity entity = entityStream.next();

            if (entity == null) {
                throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "found null entity");
            }

            walkEntityGraph(entity);

            walkedEntities.add(entity.getGuid());
        }

        // walk through entities referenced by other entities
        // referencedGuids will be updated within this for() loop; avoid use of iterators
        List<String> referencedGuids = discoveryContext.getReferencedGuids();
        for (int i = 0; i < referencedGuids.size(); i++) {
            String guid = referencedGuids.get(i);

            if (walkedEntities.contains(guid)) {
                continue;
            }

            AtlasEntity entity = entityStream.getByGuid(guid);

            if (entity != null) {
                walkEntityGraph(entity);

                walkedEntities.add(entity.getGuid());
            }
        }
    }

    protected void resolveReferences() throws AtlasBaseException {
        EntityResolver[] entityResolvers = new EntityResolver[] { new IDBasedEntityResolver(),
                                                                  new UniqAttrBasedEntityResolver(typeRegistry)
                                                                };

        for (EntityResolver resolver : entityResolvers) {
            resolver.resolveEntityReferences(discoveryContext);
        }
    }

    private void visitReference(AtlasObjectIdType type, Object val) throws AtlasBaseException {
        if (type == null || val == null) {
            return;
        }

        if (val instanceof AtlasObjectId) {
            AtlasObjectId objId = (AtlasObjectId)val;

            if (!objId.isValid()) {
                throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, objId.toString());
            }

            recordObjectReference(objId);
        } else if (val instanceof Map) {
            AtlasObjectId objId = new AtlasObjectId((Map)val);

            if (!objId.isValid()) {
                throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, objId.toString());
            }

            recordObjectReference(objId);
        } else {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, val.toString());
        }
    }

    void visitAttribute(AtlasType attrType, Object val) throws AtlasBaseException {
        if (attrType == null || val == null) {
            return;
        }

        switch (attrType.getTypeCategory()) {
            case PRIMITIVE:
            case ENUM:
                return;

            case ARRAY: {
                AtlasArrayType arrayType = (AtlasArrayType) attrType;
                AtlasType      elemType  = arrayType.getElementType();

                visitCollectionReferences(elemType, val);
            }
            break;

            case MAP: {
                AtlasType keyType   = ((AtlasMapType) attrType).getKeyType();
                AtlasType valueType = ((AtlasMapType) attrType).getValueType();

                visitMapReferences(keyType, valueType, val);
            }
            break;

            case STRUCT:
                visitStruct((AtlasStructType)attrType, val);
            break;

            case OBJECT_ID_TYPE:
                visitReference((AtlasObjectIdType) attrType,  val);
            break;

            default:
                throw new AtlasBaseException(AtlasErrorCode.TYPE_CATEGORY_INVALID, attrType.getTypeCategory().name());
        }
    }

    void visitMapReferences(AtlasType keyType, AtlasType valueType, Object val) throws AtlasBaseException {
        if (keyType == null || valueType == null || val == null) {
            return;
        }

        if (isPrimitive(keyType.getTypeCategory()) && isPrimitive(valueType.getTypeCategory())) {
            return;
        }

        if (Map.class.isAssignableFrom(val.getClass())) {
            Iterator<Map.Entry> it = ((Map) val).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry e = it.next();
                visitAttribute(keyType, e.getKey());
                visitAttribute(valueType, e.getValue());
            }
        }
    }

    void visitCollectionReferences(AtlasType elemType, Object val) throws AtlasBaseException {
        if (elemType == null || val == null || isPrimitive(elemType.getTypeCategory())) {
            return;
        }

        Iterator it = null;

        if (val instanceof Collection) {
            it = ((Collection) val).iterator();
        } else if (val instanceof Iterable) {
            it = ((Iterable) val).iterator();
        } else if (val instanceof Iterator) {
            it = (Iterator) val;
        }

        if (it != null) {
            while (it.hasNext()) {
                Object elem = it.next();
                visitAttribute(elemType, elem);
            }
        }
    }

    void visitStruct(AtlasStructType structType, Object val) throws AtlasBaseException {
        if (structType == null || val == null) {
            return;
        }

        AtlasStruct struct;

        if (val instanceof AtlasStruct) {
            struct = (AtlasStruct) val;
        } else if (val instanceof Map) {
            Map attributes = AtlasTypeUtil.toStructAttributes((Map) val);

            struct = new AtlasStruct(structType.getTypeName(), attributes);
        } else {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_STRUCT_VALUE, val.toString());
        }

        for (AtlasAttribute attribute : structType.getAllAttributes().values()) {
            AtlasType attrType = attribute.getAttributeType();
            Object    attrVal  = struct.getAttribute(attribute.getName());

            visitAttribute(attrType, attrVal);
        }
    }


    void walkEntityGraph(AtlasEntity entity) throws AtlasBaseException {
        if (entity == null) {
            return;
        }

        validateAndNormalize(entity);
        AtlasEntityType type = typeRegistry.getEntityTypeByName(entity.getTypeName());

        recordObjectReference(entity.getGuid());

        visitStruct(type, entity);
    }


    boolean isPrimitive(TypeCategory typeCategory) {
        return typeCategory == TypeCategory.PRIMITIVE || typeCategory == TypeCategory.ENUM;
    }

    private void validateAndNormalize(AtlasEntity entity) throws AtlasBaseException {
        List<String> messages = new ArrayList<>();

        if (!AtlasEntity.isAssigned(entity.getGuid()) && !AtlasEntity.isUnAssigned(entity.getGuid())) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, "invalid guid " + entity.getGuid());
        }

        AtlasEntityType type = typeRegistry.getEntityTypeByName(entity.getTypeName());

        if (type == null) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, TypeCategory.ENTITY.name(), entity.getTypeName());
        }

        type.validateValue(entity, entity.getTypeName(), messages);

        if (!messages.isEmpty()) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_CRUD_INVALID_PARAMS, messages);
        }

        type.getNormalizedValue(entity);
    }

    private void recordObjectReference(String guid) {
        discoveryContext.addReferencedGuid(guid);
    }

    private void recordObjectReference(AtlasObjectId objId) {
        if (objId.isValidGuid()) {
            discoveryContext.addReferencedGuid(objId.getGuid());
        } else {
            discoveryContext.addReferencedByUniqAttribs(objId);
        }
    }
}