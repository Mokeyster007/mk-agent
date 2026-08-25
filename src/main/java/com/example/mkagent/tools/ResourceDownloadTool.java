package com.example.mkagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.example.mkagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;

@Component
public class ResourceDownloadTool {

    @Tool(
            name = "download_resource",
            description = """
                    从公开 HTTP 或 HTTPS 地址下载资源并保存到下载目录。

                    仅在用户明确要求下载公开资源时使用。
                    不可用于访问本机地址、内网地址或未知私有服务。
                    """
    )
    public String downloadResource(
            @ToolParam(
                    description = """
                            要下载的公开 HTTP/HTTPS 资源 URL。
                            例如：https://example.com/demo.pdf
                            """
            )
            String url,

            @ToolParam(
                    description = """
                            保存后的文件名，例如：demo.pdf。
                            不能包含目录、斜杠或 ..。
                            """
            )
            String fileName
    ) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";

        try {
            validatePublicHttpUrl(url);

            String safeFileName = sanitizeFileName(fileName);

            FileUtil.mkdir(fileDir);

            String filePath = fileDir + "/" + safeFileName;

            HttpUtil.downloadFile(
                    url,
                    new File(filePath)
            );

            return "资源下载成功，文件路径：" + filePath;

        } catch (Exception e) {
            return "资源下载失败：" + e.getMessage();
        }
    }

    private void validatePublicHttpUrl(String url) {
        URI uri = URI.create(url);

        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(
                    "只允许下载 HTTP 或 HTTPS 资源"
            );
        }

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机地址");
        }

        String lowerHost = host.toLowerCase();

        if ("localhost".equals(lowerHost)
                || "127.0.0.1".equals(lowerHost)
                || "0.0.0.0".equals(lowerHost)
                || "::1".equals(lowerHost)
                || lowerHost.startsWith("169.254.")) {
            throw new IllegalArgumentException(
                    "不允许访问本机或保留地址"
            );
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String safeFileName = fileName.trim();

        if (safeFileName.contains("..")
                || safeFileName.contains("/")
                || safeFileName.contains("\\")
                || safeFileName.contains("\0")) {
            throw new IllegalArgumentException("文件名不能包含路径字符");
        }

        return safeFileName;
    }
}