package com.example.ragagent.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * rag_parent_documents 表实体，保存父分块正文和可恢复元数据。
 */
@Getter
@Setter
@TableName("rag_parent_documents")
public class RagParentDocumentEntity {

    @TableId(type = IdType.INPUT)
    private String parentId;

    private String sourceId;

    private String title;

    private String content;

    private Integer parentIndex;

    private String documentHash;

    private String metadataJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
