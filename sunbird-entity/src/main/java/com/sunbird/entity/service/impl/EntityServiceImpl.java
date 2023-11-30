package com.sunbird.entity.service.impl;

import com.sunbird.entity.ConfigurationPanel;
import com.sunbird.entity.model.*;
import com.sunbird.entity.model.dao.*;
import com.sunbird.entity.repository.*;
import com.sunbird.entity.service.AuditService;
import com.sunbird.entity.service.EntityService;
import com.sunbird.entity.service.WorkflowService;
import com.sunbird.entity.util.Constants;
import com.sunbird.entity.util.DateUtils;
import com.sunbird.entity.util.QueryUtils;
import com.sunbird.entity.util.ServerProperties;
import org.apache.htrace.fasterxml.jackson.core.type.TypeReference;
import org.apache.htrace.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class EntityServiceImpl implements EntityService {

	public static final Logger LOGGER = LoggerFactory.getLogger(EntityServiceImpl.class);

	public static ObjectMapper objectMapper = new ObjectMapper();

	@Autowired
	EntityRepository entityRepo;

	@Autowired
	ParentEntityRepository parentEntityRepo;

	@Autowired
	ChildEntityRepository childEntityRepo;

	@Autowired
	BookmarkRepository bookmarkRepo;

	@Autowired
	ServerProperties serverProperties;

	@Autowired
	WorkflowService workflowService;

	@Autowired
	AuditService auditService;

	@Autowired
	EntityRelationMappingRepository entityRelationMappingRepo;

	@Override
	public EntityDao addUpdateEntity(EntityDao entityDao, String userId) {
		try {
			EntityDao oldEntity = null;
			setEntityDefaultData(entityDao);
			if (entityDao.getId() != null) {
				Optional<EntityDao> entityObj = entityRepo.findById(entityDao.getId());
				if (entityObj.isPresent()) {
					oldEntity = entityObj.get();
					// set existing data
					entityDao.setCreatedBy(oldEntity.getCreatedBy());
					entityDao.setCreatedDate(oldEntity.getCreatedDate());
				}
				entityDao.setUpdatedBy(userId);
				entityDao.setUpdatedDate(DateUtils.getCurrentDateTimeInUTC());
			} else {
				entityDao.setCreatedBy(userId);
				entityDao.setCreatedDate(DateUtils.getCurrentDateTimeInUTC());
			}

			entityRepo.save(entityDao);
			saveChildEntity(entityDao);
			auditService.addEntityAudit(oldEntity, entityDao);
			return entityDao;
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "addUpdateEntity", e.getMessage()));
		}
		return null;

	}

	/**
	 * Sets default value for the entity
	 * 
	 * @param entityDao
	 *            EntityDao
	 */
	private void setEntityDefaultData(EntityDao entityDao) {
		if (entityDao.getIsActive() == null) {
			entityDao.setIsActive(Boolean.TRUE);
		}
		// set Entity status
		entityDao.setStatus(
				entityDao.getStatus() != null && entityDao.getStatus().equalsIgnoreCase(Constants.WorkflowState.DRAFT)
						? Constants.WorkflowState.DRAFT
						: Constants.WorkflowState.UNVERIFIED);
		// wf level
		entityDao.setLevel(Constants.WorkflowState.INITIATE);
	}

	@Override
	public EntityDao getEntityById(Integer id, SearchObject searchObject) {
		try {
			Optional<EntityDao> result = entityRepo.findById(id);
			if (result.isPresent()) {
				// filter the response set
				if (searchObject.getFilter() != null) {
					for (Map.Entry<String, Object> entry : searchObject.getFilter().entrySet()) {
						// append child Nodes
						if (entry.getKey().equals(Constants.Parameters.IS_DETAIL)
								&& entry.getValue().equals(Boolean.TRUE)) {
							//appendChildEntity(result.get());
							appendChildEntityMapping(result.get());
						}
					}
				}

				return result.get();
			}

		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "getEntityById", e.getMessage()));
		}
		return null;
	}

	@Override
	public Boolean addEntityRelation(EntityRelation entityRelation) {
		try {
			// get the mapping id
			Map<String, Object> search = new HashMap<>();
			search.put(Constants.Parameters.PARENT_ID, entityRelation.getParentId());
			search.put(Constants.Parameters.CHILD, entityRelation.getChild());
			entityRelation.setId(getEntityMappingId(search));
			if (entityRelation.getId() == null) {
				ParentEntityDao parentNode = new ParentEntityDao(0, entityRelation.getParent(),
						entityRelation.getParentId(), entityRelation.getChild(), null, null);

				parentNode = parentEntityRepo.save(parentNode);
				entityRelation.setId(parentNode.getId());
			} else {
				// delete all child entry
				childEntityRepo.deleteByMapId(entityRelation.getId());
			}
			// add all child nodes
			List<ChildEntityDao> childNodes = new ArrayList<>();
			entityRelation.getChildIds().forEach((childId) -> {
				ChildEntityDao childNode = new ChildEntityDao(0, entityRelation.getId(), childId);
				childNodes.add(childNode);
			});
			childEntityRepo.saveAll(childNodes);
			// store relations in cache
			ConfigurationPanel.setChildId(entityRelation.getParentId(), entityRelation.getChildIds());

			return Boolean.TRUE;

		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "addEntityRelation", e.getMessage()));
		}
		return Boolean.FALSE;
	}

	private Integer getEntityMappingId(Map<String, Object> search) {
		QueryDao queryDao = QueryUtils.queryBuilder(QueryUtils.Table.PARENT_NODE, search, Boolean.FALSE,
				QueryUtils.Clauses.AND);
		ParentEntityDao parentEntity = parentEntityRepo.customFindOne(queryDao.getQuery(), queryDao.getParams());
		return parentEntity != null ? parentEntity.getId() : null;
	}

	@Override
	public Boolean bookmarkEntity(Bookmark bookmarkEntityNode, String userId) {
		try {
			BookmarkDao bookmarkDao = new BookmarkDao();
			bookmarkDao.setNodeId(bookmarkEntityNode.getId());
			bookmarkDao.setUserId(userId);
			if (bookmarkEntityNode.getBookmark() == null || bookmarkEntityNode.getBookmark()) {
				bookmarkRepo.save(bookmarkDao);
			} else {
				bookmarkRepo.delete(bookmarkDao);
			}
			return true;
		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "bookmarkEntity", e.getMessage()));
			return false;
		}
	}

	@Override
	public void addEntities(List<EntityDao> entityList) {
		try {
			new Thread(() -> {
				if (entityList != null) {
					// save all parent entity
					List<EntityDao> entities = (List<EntityDao>) entityRepo.saveAll(entityList);

					// save children
					entities.forEach((entityObj) -> {
						saveChildEntity(entityObj);
					});
				}

			}).start();

		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "addEntities", e.getMessage()));
		}
	}

	/**
	 * To save the children entities and its relation
	 * 
	 * @param parentEntity
	 *            EntityDao - parent entity
	 * @return Boolean
	 */
	private Boolean saveChildEntity(EntityDao parentEntity) {
		try {
			if (parentEntity.getChildren() != null && parentEntity.getChildren().size() > 0) {
				// save children entity
				List<EntityDao> children = (List<EntityDao>) entityRepo.saveAll(
						objectMapper.convertValue(parentEntity.getChildren(), new TypeReference<List<EntityDao>>() {
						}));
				parentEntity.setChildren(
						objectMapper.convertValue(children, new TypeReference<List<Map<String, Object>>>() {
						}));

				// entity relation map
				Map<String, List<Integer>> childMap = new HashMap<>();
				children.forEach((child) -> {
					List<Integer> childId = new ArrayList<>();
					if (childMap.containsKey(child.getType())) {
						childId = childMap.get(child.getType());
					}
					childId.add(child.getId());
					childMap.put(child.getType(), childId);
				});
				// Save entity relation map
				for (Map.Entry<String, List<Integer>> entry : childMap.entrySet()) {
					EntityRelation entityRelation = new EntityRelation(0, parentEntity.getType(), parentEntity.getId(),
							entry.getKey(), entry.getValue(), null);
					addEntityRelation(entityRelation);
				}
			}
			return Boolean.TRUE;
		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "saveChildEntity", e.getMessage()));
			return Boolean.FALSE;
		}
	}

	@Override
	public List<EntityDao> getAllDataNodes(SearchObject searchObject) {
		try {
			QueryDao queryDao = QueryUtils.queryBuilder(QueryUtils.Table.DATA_NODE, searchObject.getSearch(),
					searchObject.getKeywordSearch(), QueryUtils.Clauses.AND);
			List<EntityDao> result = entityRepo.customFindAll(queryDao.getQuery(), queryDao.getParams());

			return result;
		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "getAllDataNodes", e.getMessage()));
			return null;
		}
	}

	/**
	 * Binds children entity with parent entity
	 * 
	 * @param result
	 *            EntityDao - parent entity
	 */
	private void appendChildEntity(EntityDao entityDao) throws Exception {

		Map<String, Object> search = new HashMap<>();
		search.put(Constants.Parameters.ID, ConfigurationPanel.getChildId(entityDao.getId()));
		QueryDao queryDao = QueryUtils.queryBuilder(QueryUtils.Table.DATA_NODE, search, Boolean.FALSE,
				QueryUtils.Clauses.AND);
		List<EntityDao> childEntity = entityRepo.customFindAll(queryDao.getQuery(), queryDao.getParams());

		if (entityDao.getChildren() == null) {
			entityDao.setChildren(new ArrayList<>());
		}

		entityDao.setChildren(objectMapper.convertValue(childEntity, new TypeReference<List<Map<String, Object>>>() {
		}));

	}

	@Override
	public Boolean deleteEntity(Integer id) {
		try {
			entityRepo.deleteById(id);
			return true;
		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "deleteEntity", e.getMessage()));
		}
		return false;
	}

	@Override
	public Boolean addFeedback(Map<String, Object> feedbackDocument) {
		try {
			// esRepository.writeAnyObjectToElastic(feedbackDocument, "uniqueId",
			// serverProperties.getEsProfileIndex(),
			// serverProperties.getEsProfileIndexType());
			return Boolean.TRUE;
		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "addFeedback", e.getMessage()));
		}
		return false;
	}

	@Override
	public Boolean reviewEntity(EntityVerification entityVerification, UserProfile userProfile) {
		try {
			Optional<EntityDao> entityObj = entityRepo.findById(entityVerification.getId());
			if (entityObj.isPresent()) {
				EntityDao oldEntity = (entityObj.get()).clone();
				EntityDao entityDao = entityObj.get();
				entityDao.setReviewedBy(userProfile.getUserId());
				entityDao.setReviewedDate(DateUtils.getCurrentDateTimeInUTC());
				//wfTransition(entityDao);
				entityDao.setStatus(entityVerification.getAction());
				if (checkReviewAccess(entityDao, userProfile.getRoles()) || true) {
					entityRepo.save(entityDao);
					//auditService.addEntityAudit(oldEntity, entityDao);
					return Boolean.TRUE;
				}

			}
		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "reviewEntity", e.getMessage()));
		}
		return Boolean.FALSE;
	}

	private void reviewAudit() {

	}

	/**
	 * Get workflow transition on review
	 * 
	 * @param entityDao
	 *            EntityDao
	 */
	private void wfTransition(EntityDao entityDao) {
		WfRequest wfRequest = new WfRequest();
		wfRequest.setWfId(entityDao.getWfId());
		wfRequest.setState(entityDao.getLevel());
		wfRequest.setAction(entityDao.getStatus());
		String userId = entityDao.getReviewedBy();
		wfRequest.setUserId(userId);
		wfRequest.setActorUserId(userId);
		wfRequest.setApplicationId(entityDao.getId().toString());
		wfRequest.setServiceName(Constants.WorkflowState.SERVICE);
		wfRequest.setUpdateFieldValues(Arrays.asList(new HashMap<>()));

		Boolean transition = workflowService.workflowTransition(wfRequest);
		if (transition) {
			entityDao.setLevel(wfRequest.getState());
			entityDao.setWfId(wfRequest.getWfId());
		}
	}

	private Boolean checkReviewAccess(EntityDao entityDao, List<String> userRoles) throws Exception {
		Boolean hasAccess = Boolean.FALSE;
		if (userRoles != null) {
			List<Map<String, Object>> data = workflowService.getworkflowAction(entityDao.getLevel());
			if (data != null) {
				for (Map<String, Object> roleObj : data) {
					if (entityDao.getStatus().equals(roleObj.get("action"))) {
						List<String> roles = objectMapper.convertValue(roleObj.get("roles"),
								new TypeReference<List<String>>() {
								});
						for (String roleName : roles) {
							if (userRoles.stream().anyMatch(name -> name.equals(roleName))) {
								hasAccess = Boolean.TRUE;
								break;
							}
						}
					}
					if (hasAccess) {
						break;
					}
				}
			}
		}

		return hasAccess;
	}

	@Override
	public List<EntityDao> searchAllEntityNodes(SearchObject searchObject) {
		try {
			QueryDao queryDao = QueryUtils.queryBuilder(QueryUtils.Table.DATA_NODE, searchObject.getSearch(),
					searchObject.getKeywordSearch(), QueryUtils.Clauses.AND);
			List<EntityDao> result = entityRepo.customFindAll(queryDao.getQuery(), queryDao.getParams());
			if (searchObject.getFilter() != null) {
				for (Map.Entry<String, Object> entry : searchObject.getFilter().entrySet()) {
					// append child Nodes
					if (entry.getKey().equals(Constants.Parameters.IS_DETAIL)
							&& entry.getValue().equals(Boolean.TRUE)) {
						updateHierarchy(result);
					}
				}
			}
			return result;
		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "getAllDataNodes", e.getMessage()));
			return null;
		}
	}

	private List<EntityDao> updateHierarchy(List<EntityDao> result) {
		for (EntityDao dao : result) {
			updateChildHierarchy(dao);
		}
		return result;
	}

	private void updateChildHierarchy(EntityDao dao) {
		try {
			appendChildEntityMapping(dao);

			List<Map<String, Object>> childrens =  dao.getChildren();

			List<Map<String, Object>> copyList = new CopyOnWriteArrayList<>(childrens);
			do {
				for (Map<String, Object> children : copyList) {
					EntityDao childrenDao = objectMapper.convertValue(children, new TypeReference<EntityDao>() {});
					appendChildEntityMapping(childrenDao);
					if(childrenDao != null && childrenDao.getChildren() != null) {
						copyList.addAll(childrenDao.getChildren());
						children.put("children", childrenDao.getChildren());
					}
					copyList.remove(children);
				}
			} while (copyList != null && copyList.size() > 0);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Boolean addEntityRelationMapping(EntityRelation entityRelation) {
		try {
			for (int childId: entityRelation.getChildIds()) {
				Map<String, Object> search = new HashMap<>();
				search.put(Constants.Parameters.PARENT_ID, entityRelation.getParentId());
				search.put(Constants.Parameters.CHILD_ID, childId);
				EntityMappingDao dao = getEntityMappingById(search);

				if (dao == null) {
					EntityMappingDao entityMappingDao = new EntityMappingDao();
					entityMappingDao.setParentId(entityRelation.getParentId());
					entityMappingDao.setChildId(childId);
					entityRelationMappingRepo.save(entityMappingDao);
				} else {
					//entityRelationMappingRepo.delete(dao);
				}
			}
			return Boolean.TRUE;

		} catch (Exception e) {
			LOGGER.error(String.format(Constants.Exception.EXCEPTION_METHOD, "addEntityRelation", e.getMessage()));
		}
		return Boolean.FALSE;
	}

	private EntityMappingDao getEntityMappingById(Map<String, Object> search) {
		QueryDao queryDao = QueryUtils.queryBuilder(QueryUtils.Table.NODE_MAPPING, search, Boolean.FALSE,
				QueryUtils.Clauses.AND);
		EntityMappingDao entityMappingDao = entityRelationMappingRepo.customFindOne(queryDao.getQuery(), queryDao.getParams());
		return entityMappingDao;
	}

	private void appendChildEntityMapping(EntityDao entityDao) throws Exception {

		Map<String, Object> searchObject = new HashMap<>();
		searchObject.put(Constants.Parameters.PARENT_ID, entityDao.getId());
		Map<String, Object> search = new HashMap<>();
		search.put(Constants.Parameters.ID, getChildIdMapping(searchObject));
		QueryDao queryDao = QueryUtils.queryBuilder(QueryUtils.Table.DATA_NODE, search, Boolean.FALSE,
				QueryUtils.Clauses.AND);
		List<EntityDao> childEntity = entityRepo.customFindAll(queryDao.getQuery(), queryDao.getParams());

		if (entityDao.getChildren() == null) {
			entityDao.setChildren(new ArrayList<>());
		}

		entityDao.setChildren(objectMapper.convertValue(childEntity, new TypeReference<List<Map<String, Object>>>() {
		}));

	}

	private List<Integer> getChildIdMapping(Map<String, Object> search) {
		QueryDao queryDao = QueryUtils.queryBuilder(QueryUtils.Table.NODE_MAPPING, search, Boolean.FALSE,
				QueryUtils.Clauses.AND);
		List<EntityMappingDao> entityMappingDaoList = entityRelationMappingRepo.customFindAll(queryDao.getQuery(), queryDao.getParams());
		return entityMappingDaoList != null ? entityMappingDaoList.stream().map(entity -> entity.getChildId()).collect(Collectors.toList()) : new ArrayList<>();
	}

	private void processBulkUpdateCompetency(String userDetails) throws IOException {
		File file = null;
		FileInputStream fis = null;
		XSSFWorkbook wb = null;
		try {
			file = new File("/home/sahilchaudhary/Downloads/KCM_23-11-23.xlsx");
			if (file.exists() && file.length() > 0) {
				fis = new FileInputStream(file);
				wb = new XSSFWorkbook(fis);
				XSSFSheet sheet = wb.getSheetAt(0);
				Iterator<Row> rowIterator = sheet.iterator();
				// incrementing the iterator inorder to skip the headers in the first row
				while (rowIterator.hasNext()) {
					long duration = 0;
					long startTime = System.currentTimeMillis();
					StringBuffer str = new StringBuffer();
					List<String> errList = new ArrayList<>();
					List<String> invalidErrList = new ArrayList<>();
					Row nextRow = rowIterator.next();
					if(nextRow.getCell(0).getCellType() != CellType.STRING) {
						String competencyArea = nextRow.getCell(1).getStringCellValue().trim();
						EntityDao entityDao = getEntityDAO("Competency Area",competencyArea);
						if (entityDao == null) {
							entityDao = new EntityDao();
							entityDao.setType("Competency Area");
							entityDao.setName(competencyArea);
							entityDao.setDescription(competencyArea + "Competency Area");
							entityDao.setCreatedBy(userDetails);
							System.out.println(entityDao);
							entityDao = addUpdateEntity(entityDao, userDetails);
						}
						String competencyType = nextRow.getCell(2).getStringCellValue().trim();
						EntityDao entityDaoType = getEntityDAO("Competency Type",competencyType);
						if (entityDaoType == null) {
							entityDaoType = new EntityDao();
							entityDaoType.setType("Competency Type");
							entityDaoType.setName(competencyType);
							entityDaoType.setDescription(competencyType + "Competency Type");
							entityDaoType.setCreatedBy(userDetails);
							System.out.println(entityDaoType);
							entityDaoType = addUpdateEntity(entityDaoType, userDetails);
						}
						if(entityDaoType != null) {
							EntityRelation entityRelation = new EntityRelation(0, "",entityDao.getId(), "", Arrays.asList(entityDaoType.getId()), "");
							addEntityRelationMapping(entityRelation);
							System.out.println(entityRelation);
						}
						String competencyName = nextRow.getCell(3).getStringCellValue().trim();
						EntityDao entityDaoName = getEntityDAO("Competency Name",competencyName);
						if (entityDaoName == null) {
							entityDaoName = new EntityDao();
							entityDaoName.setType("Competency Name");
							entityDaoName.setName(competencyName);
							entityDaoName.setDescription(competencyName + "competency Name");
							entityDaoName.setCreatedBy(userDetails);
							entityDaoName = addUpdateEntity(entityDaoName, userDetails);
							System.out.println(entityDaoName);
						}
						if(entityDaoName != null) {
							EntityRelation entityRelationType = new EntityRelation(0, "",entityDaoType.getId(), "", Arrays.asList(entityDaoName.getId()), "");
							addEntityRelationMapping(entityRelationType);

						}
						String competencySubTheme = nextRow.getCell(4).getStringCellValue().trim();
						EntityDao entityDaoSubtheme = getEntityDAO("Competency Sub-Theme",competencySubTheme);
						if (entityDaoSubtheme == null) {
							entityDaoSubtheme = new EntityDao();
							entityDaoSubtheme.setType("Competency Sub-Theme");
							entityDaoSubtheme.setName(competencySubTheme);
							entityDaoSubtheme.setDescription(competencySubTheme + "Competency Area");
							entityDaoSubtheme.setCreatedBy(userDetails);
							entityDaoSubtheme = addUpdateEntity(entityDaoSubtheme, userDetails);
						}
						if(entityDaoSubtheme != null) {
							EntityRelation entityRelationName = new EntityRelation(0, "",entityDaoName.getId(), "", Arrays.asList(entityDaoSubtheme.getId()), "");
							addEntityRelationMapping(entityRelationName);
						}
					}
				}
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
		} finally {
			if (wb != null)
				wb.close();
			if (fis != null)
				fis.close();
		}
	}

	private  EntityDao getEntityDAO(String compatencyType, String name){
		Map<String, Object> search = new HashMap<>();
		search.put("type", compatencyType);
		search.put("name", name);
		QueryDao queryDao = QueryUtils.queryBuilder(QueryUtils.Table.DATA_NODE, search, Boolean.FALSE,
				QueryUtils.Clauses.AND);
		EntityDao entity = entityRepo.customFindOne(queryDao.getQuery(), queryDao.getParams());
		return entity;
	}
}
