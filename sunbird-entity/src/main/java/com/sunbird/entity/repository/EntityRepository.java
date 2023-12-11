package com.sunbird.entity.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import com.sunbird.entity.model.dao.EntityDao;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EntityRepository extends CrudRepository<EntityDao, Integer>, CustomRepository<EntityDao> {

    @Query(value = "Select * from data_node t WHERE t.type = :type AND t.additional_properties->> :themeKey IN (:themeType)", nativeQuery = true)
    List<EntityDao> getEntityByTypeAndAdditionalProperties(@Param("type") String type, @Param("themeKey") String themeKey, @Param("themeType") List<String> themeType);
}
