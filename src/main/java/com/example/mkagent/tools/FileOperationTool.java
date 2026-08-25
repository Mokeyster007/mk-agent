package com.example.mkagent.tools;

import cn.hutool.core.io.FileUtil;
import com.example.mkagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 文件读写工具。
 *
 * 注意：
 * 当前仅允许操作 FILE_SAVE_DIR/file 目录。
 */
@Component
public class FileOperationTool {

    private static final String FILE_DIR =
            FileConstant.FILE_SAVE_DIR + "/file";

    @Tool(
            name = "read_file",
            description = """
                    读取系统文件目录中的 UTF-8 文本文件内容。

                    仅当用户要求读取此前生成或保存的文本文件时使用。
                    只能传入文件名，不能传入绝对路径或目录路径。
                    """
    )
    public String readFile(
            @ToolParam(
                    description = """
                            要读取的文件名，例如：notes.txt。
                            不允许包含目录、斜杠或 ..。
                            """
            )
            String fileName
    ) {
        try {
            String filePath = buildSafePath(fileName);

            if (!FileUtil.exist(filePath)) {
                return "读取文件失败：文件不存在：" + fileName;
            }

            return FileUtil.readUtf8String(filePath);

        } catch (Exception e) {
            return "读取文件失败：" + e.getMessage();
        }
    }

    @Tool(
            name = "write_file",
            description = """
                    将文本内容写入系统文件目录中的 UTF-8 文件。

                    当用户要求保存、导出普通文本内容时使用。
                    工具会覆盖同名文件。
                    """
    )
    public String writeFile(
            @ToolParam(
                    description = """
                            要保存的文件名，例如：learning-notes.txt。
                            不允许包含目录、斜杠或 ..。
                            """
            )
            String fileName,

            @ToolParam(
                    description = "要写入文件的完整文本内容"
            )
            String content
    ) {
        try {
            String filePath = buildSafePath(fileName);

            FileUtil.mkdir(FILE_DIR);
            FileUtil.writeUtf8String(content, filePath);

            return "文件写入成功，文件路径：" + filePath;

        } catch (Exception e) {
            return "写入文件失败：" + e.getMessage();
        }
    }

    private String buildSafePath(String fileName) {
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

        return FILE_DIR + "/" + safeFileName;
    }
}