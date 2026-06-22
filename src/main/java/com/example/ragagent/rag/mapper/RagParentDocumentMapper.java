package com.example.ragagent.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragagent.rag.entity.RagParentDocumentEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 父文档表 Mapper，提供按 sourceId 查询和删除父分块的能力。
 */
@Mapper
public interface RagParentDocumentMapper extends BaseMapper<RagParentDocumentEntity> {

    /**
     * 按 sourceId 查询父分块 ID，结果按父分块顺序排列。
     */
    @Select("""
            SELECT parent_id
            FROM rag_parent_documents
            WHERE source_id = #{sourceId}
            ORDER BY parent_index ASC
            """)
    List<String> selectParentIdsBySourceId(@Param("sourceId") String sourceId);

    /**
     * 删除指定 sourceId 下的全部父分块。
     */
    @Delete("DELETE FROM rag_parent_documents WHERE source_id = #{sourceId}")
    int deleteBySourceId(@Param("sourceId") String sourceId);
}
