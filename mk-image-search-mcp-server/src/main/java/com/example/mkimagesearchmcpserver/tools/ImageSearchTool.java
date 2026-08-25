package com.example.mkimagesearchmcpserver.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ImageSearchTool {

    /**
     * Pexels API Key：优先读配置 pexels.api-key，
     * 其次读环境变量 PEXELS_API_KEY，不再硬编码到源码。
     */
    private final String apiKey;

    public ImageSearchTool(
            @Value("${pexels.api-key:${PEXELS_API_KEY:}}") String apiKey) {
        this.apiKey = apiKey;
    }


    private static final String API_URL = "https://api.pexels.com/v1/search";

    @Tool(description = "search image from web")
    public String searchImage(@ToolParam(description = "Search query keyword") String query) {
        try {
            return String.join(",", searchMediumImages(query));
        } catch (Exception e) {
            return "Error search image: " + e.getMessage();
        }
    }


    public List<String> searchMediumImages(String query) {

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", apiKey);


        Map<String, Object> params = new HashMap<>();
        params.put("query", query);


        String response = HttpUtil.createGet(API_URL)
                .addHeaders(headers)
                .form(params)
                .execute()
                .body();


        return JSONUtil.parseObj(response)
                .getJSONArray("photos")
                .stream()
                .map(photoObj -> (JSONObject) photoObj)
                .map(photoObj -> photoObj.getJSONObject("src"))
                .map(photo -> photo.getStr("medium"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }
}