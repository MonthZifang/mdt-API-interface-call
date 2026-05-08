package com.mdt.apicall;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.gen.Player;
import mindustry.mod.Plugin;

public final class ApiInterfaceCallPlugin extends Plugin {
    @Override
    public void init() {
        Log.info("MDT API接口调用 loaded.");
        Log.info("配置目录建议: config/mods/config/mdt-api-interface-call");
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("api-call-run", "<module> <playerOrUuid>", "执行指定 API 定义模块。", args -> {
            Log.info("MDT API接口调用 命令占位已触发: api-call-run");
        });

        handler.register("api-call-list", "列出当前可用的 API 定义模块。", args -> {
            Log.info("MDT API接口调用 命令占位已触发: api-call-list");
        });

        handler.register("api-call-reload", "重新加载 API 接口定义与配置。", args -> {
            Log.info("MDT API接口调用 命令占位已触发: api-call-reload");
        });

    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("apicall", "<module>", "触发当前玩家可用的 API 查询模块。", (args, player) -> {
            player.sendMessage("[accent]MDT API接口调用[] 命令占位已触发: apicall");
        });

    }
}
