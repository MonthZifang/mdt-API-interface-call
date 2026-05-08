<div align="center">
  <a href="https://github.com/MonthZifang/YUEYUEDAO-TECH">
    <img src="./md/logo.png" alt="YUEYUEDAO TECH Logo" width="720" />
  </a>

  <p><strong>YUEYUEDAO TECH 维护 MDT API接口调用</strong></p>

  <p>
    <a href="https://github.com/MonthZifang/YUEYUEDAO-TECH"><strong>查看月月岛科技详情</strong></a>
  </p>
</div>

# MDT API接口调用

复用原有 API mod 的调用思路，通过定义文件管理外部接口，供服务器命令、第三方平台或其他插件按模块名直接发起请求。

## 市场固定识别文件

仓库根目录固定提供以下文件，供插件市场识别：

```text
market.plugin.json
plugin.json
```

## 依赖

- 无强依赖。

## 配置文件

首次启动后建议维护以下配置文件：

```text
config/mods/config/mdt-api-interface-call/api-interface-call.properties
```

- 支持单独维护接口定义目录，避免在代码里硬编码 URL。
- 支持统一超时、重试、缓存与请求头规则。
- 支持把响应字段映射到 `uuid`、`comid`、`bound` 等常用变量。
- 适合作为绑定检测、外部资料同步和第三方平台联动的通用桥接插件。

## 功能说明

- 支持 GET、POST、PUT、DELETE 以及自定义 Header。
- 支持使用 `{uuid}`、`{comid}`、`{name}` 之类的占位符。
- 支持把响应中的指定字段提取给其他插件复用。
- 支持通过定义文件直接扩展新接口，无需重新改动主逻辑。

## 数据与写入说明

- 接口定义建议与版本一起提交，保证环境迁移时配置可复用。
- 对需要第三方绑定查询的插件，建议统一只调用本插件暴露的方法。

## 命令

- `api-call-run <module> <playerOrUuid>`：执行指定 API 定义模块。
- `api-call-list`：列出当前可用的 API 定义模块。
- `api-call-reload`：重新加载 API 接口定义与配置。
- `/apicall <module>`：触发当前玩家可用的 API 查询模块。

## Help 注册备注

- `help mdt-api-interface-call`：查看 MDT API接口调用 的独立命令说明。
- 中文备注建议写为“接口模块执行、接口列表、定义重载”。

## 附带资源

- 附带 `src/main/resources/definitions/` 下的多个示例定义文件，可直接改 URL 后使用。

## 插件入口

```text
com.mdt.apicall.ApiInterfaceCallPlugin
```

## 版本规则

- 当前插件版本：`v1`
- 当前需求市场版本：`v1`
