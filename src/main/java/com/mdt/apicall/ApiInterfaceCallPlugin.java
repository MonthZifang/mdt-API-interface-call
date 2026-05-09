package com.mdt.apicall;

import arc.struct.ObjectMap;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Strings;
import arc.util.serialization.Jval;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

public final class ApiInterfaceCallPlugin extends Plugin {
    private static final String CONFIG_DIR_NAME = "mdt-api-interface-call";
    private static final String CONFIG_FILE_NAME = "api-interface-call.properties";
    private static final String DEFINITIONS_DIR_NAME = "definitions";
    private static final String[] DEFAULT_DEFINITIONS = new String[] {
        "bind-query.json",
        "comid-lookup.json",
        "profile-push.json"
    };

    private volatile Config config;
    private volatile File dataRoot;
    private volatile File definitionsDir;
    private final LinkedHashMap<String, Definition> definitions = new LinkedHashMap<String, Definition>();

    @Override
    public void init() {
        try {
            dataRoot = resolveDataRoot();
            ensureDefaultResources();
            reloadConfig();
            Log.info("MDT API接口调用 loaded.");
            Log.info("配置目录: @", dataRoot.getAbsolutePath());
        } catch (IOException exception) {
            throw new RuntimeException("MDT API接口调用初始化失败。", exception);
        }
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("api-call-run", "<module> <playerOrUuid>", "执行指定 API 定义模块。", args -> {
            Definition definition = definitions.get(args[0].trim());
            if (definition == null) {
                Log.info("未找到 API 模块: @", args[0]);
                return;
            }
            try {
                Variables variables = resolveVariables(args[1]);
                ApiResult result = invoke(definition, variables);
                Log.info("API @ -> status=@ method=@ url=@", definition.name, result.statusCode, definition.method, result.url);
                if (!result.mappedValues.isEmpty()) {
                    for (Map.Entry<String, String> entry : result.mappedValues.entrySet()) {
                        Log.info("  @ = @", entry.getKey(), entry.getValue());
                    }
                }
                Log.info("  raw = @", abbreviate(result.rawBody, 400));
            } catch (Exception exception) {
                Log.err("执行 API 模块失败: @", exception.getMessage());
            }
        });

        handler.register("api-call-list", "列出当前可用的 API 定义模块。", args -> {
            if (definitions.isEmpty()) {
                Log.info("当前没有可用的 API 定义模块。");
                return;
            }
            for (Definition definition : definitions.values()) {
                Log.info("@ method=@ enabled=@ url=@",
                    definition.name, definition.method, definition.enabled, definition.urlTemplate);
            }
        });

        handler.register("api-call-reload", "重新加载 API 定义与配置。", args -> {
            try {
                reloadConfig();
                Log.info("MDT API接口调用已重载。definitions=@ timeoutMs=@", definitions.size(), config.defaultTimeoutMs);
            } catch (IOException exception) {
                Log.err("MDT API接口调用重载失败: @", exception.getMessage());
            }
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("apicall", "<module>", "查看当前可用的 API 查询模块。", (args, player) -> {
            Definition definition = definitions.get(args[0].trim());
            if (definition == null) {
                player.sendMessage("[scarlet]未找到 API 模块[] " + args[0]);
                return;
            }
            player.sendMessage("[accent]" + definition.name + "[]\nmethod=" + definition.method + "\nurl=" + definition.urlTemplate);
        });
    }

    private ApiResult invoke(Definition definition, Variables variables) throws Exception {
        String urlText = applyVariables(definition.urlTemplate, variables);
        HttpURLConnection connection = (HttpURLConnection)new URL(urlText).openConnection();
        connection.setRequestMethod(definition.method);
        connection.setConnectTimeout(config.defaultTimeoutMs);
        connection.setReadTimeout(config.defaultTimeoutMs);
        for (Map.Entry<String, String> entry : config.defaultHeaders.entrySet()) {
            connection.setRequestProperty(entry.getKey(), applyVariables(entry.getValue(), variables));
        }

        if (definition.bodyTemplate != null) {
            String body = applyVariables(definition.bodyTemplate.toString(Jval.Jformat.plain), variables);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream outputStream = connection.getOutputStream();
            try {
                outputStream.write(bytes);
            } finally {
                outputStream.close();
            }
        }

        int statusCode = connection.getResponseCode();
        InputStream inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String rawBody = readFully(inputStream);
        LinkedHashMap<String, String> mappedValues = new LinkedHashMap<String, String>();
        if (rawBody != null && !rawBody.trim().isEmpty() && !definition.responseMappings.isEmpty()) {
            try {
                Jval root = Jval.read(rawBody);
                for (Map.Entry<String, String> entry : definition.responseMappings.entrySet()) {
                    Jval value = readPath(root, entry.getValue());
                    mappedValues.put(entry.getKey(), value == null ? "<null>" : value.toString());
                }
            } catch (Throwable ignored) {
                mappedValues.put("_parseError", "response is not valid JSON");
            }
        }
        return new ApiResult(urlText, statusCode, rawBody == null ? "" : rawBody, mappedValues);
    }

    private Variables resolveVariables(String playerOrUuid) {
        Variables variables = new Variables();
        Player player = findPlayer(playerOrUuid);
        if (player != null) {
            variables.playerName = player.plainName();
            variables.uuid = player.uuid();
        } else {
            variables.playerName = playerOrUuid;
            variables.uuid = playerOrUuid;
        }
        variables.comId = resolveComId(variables.uuid);
        return variables;
    }

    private Player findPlayer(String value) {
        String normalized = Strings.stripColors(value).trim();
        return Groups.player.find(player ->
            player.plainName().equalsIgnoreCase(normalized)
                || Strings.stripColors(player.name).equalsIgnoreCase(normalized)
                || player.uuid().equalsIgnoreCase(normalized)
        );
    }

    private String resolveComId(String uuid) {
        try {
            Class<?> jumpPluginClass = Class.forName("com.mdt.jump.JumpComIdPlugin");
            Object api = jumpPluginClass.getMethod("getApi").invoke(null);
            if (api == null) return "";
            Object record = api.getClass().getMethod("getOrCreate", String.class).invoke(api, uuid);
            if (record == null) return "";
            Object value = record.getClass().getMethod("getComId").invoke(record);
            return value == null ? "" : value.toString();
        } catch (Exception exception) {
            return "";
        }
    }

    private String readFully(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        try {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString().trim();
        } finally {
            reader.close();
        }
    }

    private String applyVariables(String source, Variables variables) {
        String value = source;
        value = value.replace("{uuid}", variables.uuid == null ? "" : variables.uuid);
        value = value.replace("{comid}", variables.comId == null ? "" : variables.comId);
        value = value.replace("{player}", variables.playerName == null ? "" : variables.playerName);
        value = value.replace("{name}", variables.playerName == null ? "" : variables.playerName);
        return value;
    }

    private Jval readPath(Jval root, String path) {
        String[] parts = path.split("\\.");
        Jval current = root;
        for (String part : parts) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    private void reloadConfig() throws IOException {
        Properties properties = new Properties();
        InputStreamReader reader = new InputStreamReader(new FileInputStream(new File(dataRoot, CONFIG_FILE_NAME)), StandardCharsets.UTF_8);
        try {
            properties.load(reader);
        } finally {
            reader.close();
        }

        config = buildConfig(properties);
        definitions.clear();
        for (File file : listDefinitionFiles()) {
            try {
                Definition definition = parseDefinition(file);
                if (definition.enabled) {
                    definitions.put(definition.name, definition);
                }
            } catch (Exception exception) {
                Log.err("加载 API 定义失败 @: @", file.getName(), exception.getMessage());
            }
        }
    }

    private Config buildConfig(Properties properties) {
        return new Config(readInt(properties, "api.defaultTimeoutMs", 5000), parseHeaders(properties));
    }

    private LinkedHashMap<String, String> parseHeaders(Properties properties) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        for (String name : new TreeSet<String>(properties.stringPropertyNames())) {
            if (name.startsWith("default.header.")) {
                headers.put(name.substring("default.header.".length()), properties.getProperty(name, ""));
            }
        }
        if (headers.isEmpty()) {
            headers.put("User-Agent", "MDT-API-Interface-Call");
            headers.put("Accept", "application/json");
        }
        return headers;
    }

    private int readInt(Properties properties, String englishKey, int fallback) {
        String value = properties.getProperty(englishKey);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Definition parseDefinition(File file) throws IOException {
        String text = readFully(new FileInputStream(file));
        Jval root = Jval.read(text);
        String name = root.getString("name", file.getName().replace(".json", ""));
        boolean enabled = root.getBool("enabled", true);
        String method = root.getString("method", "GET").toUpperCase();
        String url = root.getString("url", "");
        LinkedHashMap<String, String> mappings = new LinkedHashMap<String, String>();
        Jval responseMappings = root.get("responseMappings");
        if (responseMappings != null && responseMappings.isObject()) {
            for (ObjectMap.Entry<String, Jval> entry : responseMappings.asObject()) {
                mappings.put(entry.key, entry.value.asString());
            }
        }
        return new Definition(name, enabled, method, url, mappings, root.get("bodyTemplate"));
    }

    private File[] listDefinitionFiles() {
        File[] files = definitionsDir.listFiles((dir, name) -> name.endsWith(".json"));
        return files == null ? new File[0] : files;
    }

    private void ensureDefaultResources() throws IOException {
        if (!dataRoot.exists() && !dataRoot.mkdirs() && !dataRoot.isDirectory()) {
            throw new IOException("无法创建配置目录: " + dataRoot.getAbsolutePath());
        }
        copyIfMissing(CONFIG_FILE_NAME, new File(dataRoot, CONFIG_FILE_NAME));
        definitionsDir = new File(dataRoot, DEFINITIONS_DIR_NAME);
        if (!definitionsDir.exists()) {
            definitionsDir.mkdirs();
        }
        for (String fileName : DEFAULT_DEFINITIONS) {
            copyIfMissing(DEFINITIONS_DIR_NAME + "/" + fileName, new File(definitionsDir, fileName));
        }
    }

    private void copyIfMissing(String resourceName, File target) throws IOException {
        if (target.exists()) {
            return;
        }
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourceName);
        if (inputStream == null) {
            throw new IOException("缺少默认资源: " + resourceName);
        }
        try {
            Files.copy(inputStream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            inputStream.close();
        }
    }

    private File resolveDataRoot() {
        File modsRoot = new File(Vars.dataDirectory.absolutePath(), "mods");
        return new File(new File(modsRoot, "config"), CONFIG_DIR_NAME);
    }

    private static final class Variables {
        private String uuid;
        private String comId;
        private String playerName;
    }

    private static final class Config {
        private final int defaultTimeoutMs;
        private final LinkedHashMap<String, String> defaultHeaders;

        private Config(int defaultTimeoutMs, LinkedHashMap<String, String> defaultHeaders) {
            this.defaultTimeoutMs = defaultTimeoutMs;
            this.defaultHeaders = defaultHeaders;
        }
    }

    private static final class Definition {
        private final String name;
        private final boolean enabled;
        private final String method;
        private final String urlTemplate;
        private final LinkedHashMap<String, String> responseMappings;
        private final Jval bodyTemplate;

        private Definition(
            String name,
            boolean enabled,
            String method,
            String urlTemplate,
            LinkedHashMap<String, String> responseMappings,
            Jval bodyTemplate
        ) {
            this.name = name;
            this.enabled = enabled;
            this.method = method;
            this.urlTemplate = urlTemplate;
            this.responseMappings = responseMappings;
            this.bodyTemplate = bodyTemplate;
        }
    }

    private static final class ApiResult {
        private final String url;
        private final int statusCode;
        private final String rawBody;
        private final LinkedHashMap<String, String> mappedValues;

        private ApiResult(String url, int statusCode, String rawBody, LinkedHashMap<String, String> mappedValues) {
            this.url = url;
            this.statusCode = statusCode;
            this.rawBody = rawBody;
            this.mappedValues = mappedValues;
        }
    }
}
