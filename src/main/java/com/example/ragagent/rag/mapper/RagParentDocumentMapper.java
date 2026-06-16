package com.example.ragagent.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragagent.rag.entity.RagParentDocumentEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagParentDocumentMapper extends BaseMapper<RagParentDocumentEntity> {

    @Select("""
            SELECT parent_id
            FROM rag_parent_documents
            WHERE source_id = #{sourceId}
            ORDER BY parent_index ASC
            """)
    List<String> selectParentIdsBySourceId(@Param("sourceId") String sourceId);

    @Delete("DELETE FROM rag_parent_documents WHERE source_id = #{sourceId}")
    int deleteBySourceId(@Param("sourceId") String sourceId);
}
