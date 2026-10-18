package com.github.yu520.careermate.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索的统一入口。
 *
 * <p>为什么不各处在 Agent 里直接调 VectorStore：
 * 检索参数（topK、相似度阈值、结果怎么拼成上下文）是需要统一调的地方。
 * 分散到每个 Agent 里，改一次阈值就要翻遍所有 Agent，而且很容易某个 Agent 忘了设阈值，
 * 把不相关的片段塞进 prompt 反而干扰模型。
 */
@Service
public class RagService {

    /**
     * 知识来源标识，写入每个文档的 {@code source} 元数据。
     * 集中定义在这里而不是各处写字符串字面量：过滤表达式一旦拼错，
     * 检索会「静默返回空结果」而不是报错，是最难排查的一类问题。
     */
    public static final String SOURCE_INTERVIEW_QUESTIONS = "interview-questions.md";
    public static final String SOURCE_INDUSTRY_JD = "industry-jd.md";
    public static final String SOURCE_RESUME = "resume";

    /** 默认召回条数。太多会稀释重点，太少容易漏。 */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 默认相似度阈值。
     * 设为 0.5 是「宁可漏、不可错」：低于这个分值的片段基本是噪声，
     * 塞进 prompt 会诱导模型顺着噪声编答案。
     */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

    private final VectorStore vectorStore;

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 写入向量库。传入的文档若带确定性 id，则重复写入是幂等的。 */
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        vectorStore.add(documents);
    }

    /**
     * 语义检索。
     *
     * @return 命中的文档；无命中时返回空列表而不是 null，调用方不必判空
     */
    public List<Document> search(String query, int topK, double similarityThreshold) {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build());
        return hits == null ? List.of() : hits;
    }

    public List<Document> search(String query) {
        return search(query, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    /**
     * 按知识来源检索。
     *
     * <p>为什么需要它：向量库里同时躺着面试题库和行业 JD，两者语义相近
     * （都充满技术名词），不加过滤时 JD 匹配会检索出面试题、出题环节会检索出岗位要求。
     * 用元数据过滤把「检索范围」收窄，比调高相似度阈值有效得多。
     *
     * @param source 知识来源，即加载时写入的 {@code source} 元数据
     */
    public List<Document> searchBySource(String query, String source, int topK) {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                // filterExpression 是 Spring AI 的元数据过滤 DSL，这里是「等于」比较。
                // source 由本项目的加载器写入，不是外部输入，因此不存在注入问题。
                .filterExpression("source == '" + source + "'")
                .build());
        return hits == null ? List.of() : hits;
    }

    /**
     * 检索并把结果拼成可直接塞进 prompt 的参考文本。
     *
     * <p>拼装时带上分值与元数据：模型能看到「这条有多相关」，
     * 调试时也能一眼看出是哪几条片段影响了回答。
     */
    public String searchAsContext(String query, int topK) {
        return toContext(search(query, topK, DEFAULT_SIMILARITY_THRESHOLD));
    }

    /** 同 {@link #searchAsContext}，但把检索范围限定在某个知识来源内。 */
    public String searchAsContextBySource(String query, String source, int topK) {
        return toContext(searchBySource(query, source, topK));
    }

    private String toContext(List<Document> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        return hits.stream()
                .map(doc -> "- [相似度 %.3f] %s".formatted(
                        doc.getScore() == null ? 0d : doc.getScore(),
                        doc.getText()))
                .collect(Collectors.joining("\n"));
    }
}
