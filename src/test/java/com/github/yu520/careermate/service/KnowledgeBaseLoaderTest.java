package com.github.yu520.careermate.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KnowledgeBaseLoader} 的 Markdown 解析测试。
 *
 * <p>为什么这是全项目最该测的一段：解析器的失败方式是**静默的**。
 * 格式写错一个字，知识库就变成 0 条，而 {@code KnowledgeBaseLoader.run()} 里
 * 故意捕获了所有异常（不让应用启动失败），所以它只会打一行 warning。
 * 结果就是：应用正常启动、接口正常返回，但 RAG 检索永远返回空 —— 这种问题极难发现。
 *
 * <p>这些测试不需要 Spring 上下文、不需要 Ollama、不需要 Qdrant：
 * {@code parse} 是包级可见的，直接喂字符串即可。
 */
class KnowledgeBaseLoaderTest {

    /** 构造器需要 RagService，但 parse() 不用它，所以传 null 是安全的。 */
    private static KnowledgeBaseLoader newLoader() {
        return new KnowledgeBaseLoader(null);
    }

    /** parse 接收 BufferedReader，这里把字符串包一层。 */
    private static BufferedReader reader(String markdown) {
        return new BufferedReader(new StringReader(markdown));
    }

    @Test
    @DisplayName("按 ### 头切分：两个条目 → 两个文档")
    void parsesTwoEntries() throws Exception {
        String markdown = """
                # 知识库标题（这一行不属于任何条目，应被忽略）

                ### [Java基础][简单] JVM 内存怎么划分？
                堆、虚拟机栈、方法区。

                ### [Redis][中等] 缓存穿透怎么解决？
                缓存空值，或用布隆过滤器。
                """;

        List<Document> docs = newLoader().parse(reader(markdown), "test.md");

        assertEquals(2, docs.size());
    }

    @Test
    @DisplayName("分类、标签、标题、来源四个元数据都正确写入")
    void metadataIsExtracted() throws Exception {
        String markdown = """
                ### [Java基础][简单] JVM 内存怎么划分？
                堆、虚拟机栈、方法区。
                """;

        Document doc = newLoader().parse(reader(markdown), "interview-questions.md").get(0);

        assertEquals("Java基础", doc.getMetadata().get("category"));
        assertEquals("简单", doc.getMetadata().get("tag"));
        assertEquals("JVM 内存怎么划分？", doc.getMetadata().get("title"));
        assertEquals("interview-questions.md", doc.getMetadata().get("source"));
    }

    @Test
    @DisplayName("文档正文 = 标题 + 换行 + 答案（两者都要进向量）")
    void textContainsTitleAndAnswer() throws Exception {
        String markdown = """
                ### [Java基础][简单] JVM 内存怎么划分？
                堆、虚拟机栈、方法区。
                """;

        Document doc = newLoader().parse(reader(markdown), "test.md").get(0);

        assertTrue(doc.getText().startsWith("JVM 内存怎么划分？"), "正文应以标题开头：" + doc.getText());
        assertTrue(doc.getText().contains("堆、虚拟机栈、方法区。"), "正文应包含答案：" + doc.getText());
    }

    @Test
    @DisplayName("文档 id 是确定性 UUID：同样输入两次得到同一个 id（重复灌库才能幂等）")
    void idIsDeterministic() throws Exception {
        String markdown = """
                ### [Java基础][简单] JVM 内存怎么划分？
                堆。
                """;

        String first = newLoader().parse(reader(markdown), "test.md").get(0).getId();
        String second = newLoader().parse(reader(markdown), "test.md").get(0).getId();

        assertEquals(first, second);
        assertDoesNotThrow(() -> UUID.fromString(first), "id 应该是合法的 UUID 字符串");
    }

    @Test
    @DisplayName("同标题不同来源 → id 不同（避免跨文件同名条目互相覆盖）")
    void idDependsOnSource() throws Exception {
        String markdown = """
                ### [分类][标签] 同名标题
                正文。
                """;

        String a = newLoader().parse(reader(markdown), "a.md").get(0).getId();
        String b = newLoader().parse(reader(markdown), "b.md").get(0).getId();

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("只有标题没有正文的条目被跳过（不会往库里塞空文档）")
    void entryWithoutBodyIsSkipped() throws Exception {
        String markdown = """
                ### [A][B] 没有正文的条目

                ### [C][D] 有正文的条目
                这里有答案。
                """;

        List<Document> docs = newLoader().parse(reader(markdown), "test.md");

        assertEquals(1, docs.size(), "只应保留有正文的那一条");
        assertEquals("有正文的条目", docs.get(0).getMetadata().get("title"));
    }

    @Test
    @DisplayName("完全空的输入 → 0 条，不抛异常")
    void emptyInputYieldsNoDocuments() throws Exception {
        assertEquals(0, newLoader().parse(reader(""), "test.md").size());
    }

    @Test
    @DisplayName("格式不符的行（不是 ### 头）不进条目，也不会让解析崩掉")
    void linesBeforeFirstHeaderAreIgnored() throws Exception {
        String markdown = """
                这是一段说明文字，不属于任何条目。
                ## 这是二级标题，也不该被识别为条目
                ### 没有方括号的标题也不算
                ### [正确][格式] 只有这条应该被解析
                正文。
                """;

        List<Document> docs = newLoader().parse(reader(markdown), "test.md");

        assertEquals(1, docs.size());
        assertEquals("只有这条应该被解析", docs.get(0).getMetadata().get("title"));
    }

    @Test
    @DisplayName("真实知识库文件能解析出预期数量（防止改格式时不知不觉改坏）")
    void realKnowledgeFilesStillParse() throws Exception {
        KnowledgeBaseLoader loader = newLoader();

        // 这两个文件在 src/main/resources 下
        int questions = loader.loadFromClasspath("interview-questions.md").size();
        int jds = loader.loadFromClasspath("industry-jd.md").size();

        assertTrue(questions >= 20, "面试题库条数异常（实际 " + questions + "），是不是格式被改了？");
        assertTrue(jds >= 5, "行业 JD 条数异常（实际 " + jds + "），是不是格式被改了？");
    }
}
