package com.github.yu520.careermate.controller;

import com.github.yu520.careermate.agent.ResumeParserAgent;
import com.github.yu520.careermate.model.ResumeProfile;
import com.github.yu520.careermate.service.ResumeStorageService;
import com.github.yu520.careermate.service.AsyncTaskPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 简历上传与解析入口。
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    private final ResumeParserAgent resumeParserAgent;
    private final ResumeStorageService resumeStorageService;
    private final AsyncTaskPublisher asyncTaskPublisher;

    public ResumeController(ResumeParserAgent resumeParserAgent,
                            ResumeStorageService resumeStorageService,
                            AsyncTaskPublisher asyncTaskPublisher) {
        this.resumeParserAgent = resumeParserAgent;
        this.resumeStorageService = resumeStorageService;
        this.asyncTaskPublisher = asyncTaskPublisher;
    }

    /**
     * 上传简历 PDF，返回解析出的结构化画像。
     *
     * @param file 简历 PDF（multipart 字段名固定为 file）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResumeProfile upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上传文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目前只支持 PDF 简历，收到的文件名：" + filename);
        }

        byte[] content = file.getBytes();

        // 先归档、再解析，顺序不能反：解析要调用大模型，可能耗时数秒甚至失败。
        // 若先解析后归档，一次模型抖动就会让用户把同一份简历重传一遍。
        String objectName = resumeStorageService.upload(content, filename);
        log.info("简历原件已归档：objectName={}, size={} bytes", objectName, content.length);

        // PdfParseTool 的入参是「本地文件路径」，而 PDFBox 也只能从文件/流读取，
        // 所以这里为工具落一份临时文件。
        // 必须用 finally 删除：简历属于个人信息，不能让它滞留在临时目录里。
        Path tempFile = Files.createTempFile("careermate-resume-", ".pdf");
        ResumeProfile profile;
        try {
            Files.write(tempFile, content);
            profile = resumeParserAgent.parse(tempFile.toAbsolutePath().toString());
        } finally {
            Files.deleteIfExists(tempFile);
        }

        // 向量化放到解析成功之后异步做：它要调用 Embedding 模型，是纯等待型耗时，
        // 而调用方此刻已经拿到解析结果，没必要为它多等一轮。
        // 投递失败只告警、不影响本次响应 —— 解析成功才是这个接口的核心价值，
        // 入库向量是附带产物，不该因为它把整个上传判为失败。
        if (!asyncTaskPublisher.publishResumeVectorize(objectName, profile)) {
            log.warn("简历向量化任务投递失败，本次跳过（解析结果已正常返回）：objectName={}", objectName);
        }

        return profile;
    }
}
