package com.sunbird.entity.repository;

import com.sunbird.entity.model.dao.EntityMappingDao;
import com.sunbird.entity.model.dao.ParentEntityDao;
import org.springframework.data.repository.CrudRepository;

public interface EntityRelationMappingRepository extends CrudRepository<EntityMappingDao, Long>, CustomRepository<EntityMappingDao> {

}
