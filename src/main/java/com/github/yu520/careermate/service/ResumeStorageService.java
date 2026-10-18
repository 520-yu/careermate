package com.github.yu520.careermate.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 简历原件的对象存储（MinIO）。
 *
 * <p>为什么要留原件：解析结果是会变的（模型升级、提示词调整都会影响输出），
 * 但简历原件不该变。把 PDF 归档，将来重跑解析、或人工回溯「模型当时到底看到了什么」才有据可查。
 */
@Service
public class ResumeStorageService {

    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final MinioClient minioClient;
    private final String bucket;

    public ResumeStorageService(MinioClient minioClient, @Value("${minio.bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    /**
     * 上传简历原件。
     *
     * @param content          文件字节
     * @param originalFilename 原始文件名，仅用于取扩展名
     * @return 对象名（objectName），后续下载或删除都用它
     */
    public String upload(byte[] content, String originalFilename) {
        String objectName = buildObjectName(originalFilename);
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    // objectSize 传真实长度、partSize 传 -1，让 SDK 自己决定分片大小，
                    // 这样小文件不会被无谓地分片上传。
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType("application/pdf")
                    .build());
            return objectName;
        } catch (Exception e) {
            // 这里选择直接失败，而不是「解析成功但没存上」：
            // 原件是本项目的存档依据，静默丢档比直接报错难排查得多。
            throw new IllegalStateException("简历原件上传 MinIO 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按「日期目录 + UUID」组织对象名。
     * 用 UUID 而不是原始文件名，是为了避免同名简历互相覆盖；
     * 保留日期目录，则是让 bucket 里的文件能按时间顺序翻阅。
     */
    private String buildObjectName(String originalFilename) {
        String ext = "";
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            if (dot >= 0) {
                ext = originalFilename.substring(dot).toLowerCase();
            }
        }
        return "resume/" + LocalDate.now().format(DATE_DIR) + "/" + UUID.randomUUID() + ext;
    }

    /** bucket 不存在就建一个，避免首次部署时因为少建一个桶而整个上传接口不可用。 */
    private void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
