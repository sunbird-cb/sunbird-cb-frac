package com.sunbird.entity.service;

import java.util.List;
import java.util.Map;

import com.sunbird.entity.model.*;
import com.sunbird.entity.model.dao.EntityDao;
import org.springframework.web.multipart.MultipartFile;

public interface EntityService {

	public EntityDao addUpdateEntity(EntityDao entityDao, String userId);

	public EntityDao getEntityById(Integer id, SearchObject searchObject);

	public Boolean addEntityRelation(EntityRelation entityRelation);

	Boolean bookmarkEntity(Bookmark bookmarkEntityNode, String userId);

	public void addEntities(List<EntityDao> entityList);

	public List<EntityDao> getAllDataNodes(SearchObject searchObject);

	Boolean deleteEntity(Integer id);

	Boolean addFeedback(Map<String, Object> feedbackDocument);

	public Boolean reviewEntity(EntityVerification entityVerification, UserProfile userProfile);
	public ResponseDto searchAllEntityNodes(SearchObject searchObject);

	public Boolean addEntityRelationMapping(EntityRelation entityRelation);

	public ResponseDto getEntityByIdV2(Integer id, SearchObject searchObject);

	public ResponseDto bulkUpdateCompetencies(MultipartFile multipartFile, String userId);
}
