package com.github.yu520.careermate.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * 简历 PDF 解析工具，供 Agent 以 Tool Calling 的方式调用。
 *
 * <p>为什么把解析做成 Tool，而不是在 Agent 里直接调用：
 * 「何时读文件、读哪个文件」交给模型决定，解析能力对 Agent 就是透明的。
 * 将来要支持 Word、图片型简历 OCR，只需再挂一个 Tool，Agent 侧一行都不用改。
 */
@Component
public class PdfParseTool {

    /** 单份简历抽出的文本上限，防止超长简历把 prompt 撑爆、把费用推高。 */
    private static final int MAX_CHARS = 12_000;

    @Tool(description = "解析本地 PDF 文件并返回其中的纯文本内容。需要读取简历文件时调用。")
    public String parsePdf(
            @ToolParam(description = "本地 PDF 文件的绝对路径") String filePath) {

        File file = new File(filePath);
        if (!file.isFile()) {
            // 这里返回字符串而不是抛异常：异常会被框架包装成一大段堆栈丢回给模型，
            // 而模型真正需要的只是一句「文件不存在」，它就能自我纠正（比如换个路径重试）。
            return "解析失败：文件不存在或不是普通文件 -> " + filePath;
        }

        // PDFBox 3.x 的入口是 Loader.loadPDF；2.x 的 PDDocument.load 在 3.x 已被移除。
        try (PDDocument document = Loader.loadPDF(file)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                // 扫描件必须走 OCR 才有文字，这里明确告诉模型原因，
                // 避免它误以为「简历是空的」而编造内容。
                return "解析结果为空：该 PDF 可能是扫描件（图片型），需要 OCR 才能取到文字。";
            }
            if (text.length() > MAX_CHARS) {
                return text.substring(0, MAX_CHARS) + "\n...(内容过长，已截断)";
            }
            return text;
        } catch (IOException e) {
            return "解析失败：" + e.getMessage();
        }
    }
}
