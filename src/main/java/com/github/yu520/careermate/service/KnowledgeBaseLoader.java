package com.github.yu520.careermate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动时把本地知识库（面试题库 + 行业 JD）灌入向量库。
 *
 * <p>三个关键设计：
 * <ol>
 *   <li><b>文档 id 由「来源 + 标题」派生，是确定性的</b>。重复启动是幂等的（同 id 覆盖写），
 *       不会因为重启几次就在库里堆出好几份重复内容。</li>
 *   <li><b>两类知识用元数据区分</b>（{@code source} 字段）。JD 匹配只该检索行业 JD，
 *       出面试题只该检索题库 —— 混在一起检索会互相污染。</li>
 *   <li><b>灌库失败不让应用启动失败</b>。向量库和 Embedding 都是外部依赖，
 *       它们没起来时应用照样要能启动；否则本地 Ollama 一没开，整个后端连不上，
 *       连 /health 都用不了，根本没法排查问题。</li>
 * </ol>
 */
@Component
public class KnowledgeBaseLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseLoader.class);

    /** 所有知识文件的格式约定：`### [分类][标签] 标题`，正文到下一个 `###` 为止。 */
    private static final Pattern HEADER_PATTERN =
            Pattern.compile("^###\\s*\\[([^\\]]+)]\\s*\\[([^\\]]+)]\\s*(.+)$");

    /** 参与灌库的知识文件。新增知识库只需在这里加一个文件名。 */
    private static final List<String> KNOWLEDGE_FILES = List.of(
            "interview-questions.md",
            "industry-jd.md");

    private final RagService ragService;

    public KnowledgeBaseLoader(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        for (String file : KNOWLEDGE_FILES) {
            try {
                List<Document> documents = loadFromClasspath(file);
                if (documents.isEmpty()) {
                    log.warn("知识文件未解析出任何条目，跳过：{}", file);
                    continue;
                }
                ragService.add(documents);
                total += documents.size();
                log.info("知识文件已灌入向量库：{}（{} 条）", file, documents.size());
            } catch (Exception e) {
                // 故意只告警不抛出：详见类注释第 3 点。
                log.warn("知识文件灌入失败，对应的 RAG 检索将不可用（应用继续启动）：{} -> {}",
                        file, e.getMessage());
            }
        }
        log.info("知识库灌入完成，本次共 {} 条", total);
    }

    /** 解析单个知识文件。包级可见，便于单测直接喂字符串验证解析逻辑。 */
    List<Document> loadFromClasspath(String fileName) throws Exception {
        ClassPathResource resource = new ClassPathResource(fileName);
        if (!resource.exists()) {
            return List.of();
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return parse(reader, fileName);
        }
    }

    /**
     * 逐行扫描，遇到 `###` 头就把上一条收尾。
     * 之所以按「一个条目一个文档」切分：每条的知识单元是自足的
     * （面试题是「问题+答案」，行业 JD 是「岗位+要求」）。
     * 切细会丢掉上下文，切粗会把不同知识域混进同一个向量、拉低检索精度。
     */
    List<Document> parse(BufferedReader reader, String sourceName) throws Exception {
        List<Document> documents = new ArrayList<>();
        String category = null;
        String tag = null;
        String title = null;
        StringBuilder body = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            Matcher matcher = HEADER_PATTERN.matcher(line.trim());
            if (matcher.matches()) {
                addIfComplete(documents, sourceName, category, tag, title, body);
                category = matcher.group(1).trim();
                tag = matcher.group(2).trim();
                title = matcher.group(3).trim();
                body.setLength(0);
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        addIfComplete(documents, sourceName, category, tag, title, body);
        return documents;
    }

    private void addIfComplete(List<Document> documents, String sourceName, String category,
                               String tag, String title, StringBuilder body) {
        if (title == null) {
            return;
        }
        String bodyText = body.toString().trim();
        if (bodyText.isEmpty()) {
            log.warn("条目没有正文，已跳过：{}", title);
            return;
        }
        // 标题与正文一起进向量：只嵌标题会丢掉正文里的关键词（比如具体技术栈），
        // 只嵌正文又会让「用标题式提问去检索」失准。
        String text = title + "\n" + bodyText;
        // id 由「来源 + 标题」派生：跨文件同名条目也不会互相覆盖。
        String id = UUID.nameUUIDFromBytes(
                (sourceName + "#" + title).getBytes(StandardCharsets.UTF_8)).toString();

        documents.add(new Document(id, text, Map.of(
                "category", category,
                "tag", tag,
                "title", title,
                "source", sourceName)));
    }
}
