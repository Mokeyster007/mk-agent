package com.example.mkagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileOperationToolTest {

    @Test
    public void testReadFile() {

        FileOperationTool tool = new FileOperationTool();
        String name = "编程导航.txt";
        String result = tool.readFile(name);
        assertNotNull(result);
    }
    @Test
    public void testWriteFile() {
        FileOperationTool tool = new FileOperationTool();
        String name = "编程导航.txt";
        String context = "编程导航待输入文本";
        String result = tool.writeFile(name,context);
        assertNotNull(result);

    }

}