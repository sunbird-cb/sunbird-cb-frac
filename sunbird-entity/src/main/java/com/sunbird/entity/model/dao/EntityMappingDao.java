package com.sunbird.entity.model.dao;

import com.sunbird.entity.util.QueryUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = QueryUtils.Table.NODE_MAPPING)
@Table(name = QueryUtils.Table.NODE_MAPPING)
public class EntityMappingDao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(name = QueryUtils.TableFields.PARENT_ID)
    private int parentId;

    @Column(name = QueryUtils.TableFields.CHILD_ID)
    private int childId;

    @Column
    private String status;

}
