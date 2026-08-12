package com.eaiselp.runtime.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eaiselp.data.entity.Artifact;
import com.eaiselp.data.service.ArtifactService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * SearchController 单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>关键词为空 → 400</li>
 *   <li>正常搜索 → 投影摘要（content 裁剪 200 字符）</li>
 *   <li>无结果 → 空页</li>
 *   <li>长 content 裁剪验证</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock ArtifactService artifactService;

    @InjectMocks SearchController controller;

    @Test
    void search_关键词为空_返回400() {
        var result = controller.search("", 1, 10);
        assertEquals(400, result.getCode());
    }

    @Test
    void search_关键词纯空格_返回400() {
        var result = controller.search("   ", 1, 10);
        assertEquals(400, result.getCode());
    }

    @Test
    void search_关键词为null_返回400() {
        var result = controller.search(null, 1, 10);
        assertEquals(400, result.getCode());
    }

    @Test
    void search_正常搜索_返回摘要() {
        Artifact a = new Artifact();
        a.setId(1L);
        a.setCaseId("case-1");
        a.setRole("team-po");
        a.setType("prd");
        a.setTitle("需求文档");
        a.setContent("这是产物正文内容");

        Page<Artifact> page = new Page<>(1, 10);
        page.setRecords(List.of(a));
        page.setTotal(1);
        when(artifactService.search(eq("关键词"), eq(1), eq(10))).thenReturn(page);

        var result = controller.search("关键词", 1, 10);

        assertEquals(0, result.getCode());
        assertEquals(1, result.getData().getTotal());
        assertEquals(1, result.getData().getRecords().size());
        var vo = result.getData().getRecords().get(0);
        assertEquals("需求文档", vo.getTitle());
        assertEquals("这是产物正文内容", vo.getSummary());
        assertEquals("prd", vo.getType());
    }

    @Test
    void search_无结果_空页() {
        Page<Artifact> emptyPage = new Page<>(1, 10);
        emptyPage.setRecords(List.of());
        emptyPage.setTotal(0);
        when(artifactService.search(anyString(), anyInt(), anyInt())).thenReturn(emptyPage);

        var result = controller.search("不存在的关键词", 1, 10);

        assertEquals(0, result.getCode());
        assertEquals(0, result.getData().getTotal());
        assertTrue(result.getData().getRecords().isEmpty());
    }

    @Test
    void search_长content裁剪到200字符() {
        String longContent = "a".repeat(500); // 500 字符
        Artifact a = new Artifact();
        a.setId(1L);
        a.setContent(longContent);
        a.setTitle("长文");

        Page<Artifact> page = new Page<>(1, 10);
        page.setRecords(List.of(a));
        page.setTotal(1);
        when(artifactService.search(anyString(), anyInt(), anyInt())).thenReturn(page);

        var result = controller.search("a", 1, 10);

        var vo = result.getData().getRecords().get(0);
        assertEquals(200, vo.getSummary().length(), "摘要应裁剪到 200 字符");
    }

    @Test
    void search_content为null_摘要为null() {
        Artifact a = new Artifact();
        a.setId(1L);
        a.setContent(null);

        Page<Artifact> page = new Page<>(1, 10);
        page.setRecords(List.of(a));
        page.setTotal(1);
        when(artifactService.search(anyString(), anyInt(), anyInt())).thenReturn(page);

        var result = controller.search("x", 1, 10);

        assertNull(result.getData().getRecords().get(0).getSummary());
    }
}
