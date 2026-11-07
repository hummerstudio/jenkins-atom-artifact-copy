# jenkins-atom-artifact-copy

从别的构建把归档产物复制到当前 workspace。

copyartifact 插件在 Jenkins 内部做这件事（用 job 模型和指纹）；原子只有 workspace，但 Jenkins 用普通 HTTP 提供产物，所以两个请求就够了：先列出某个构建归档了什么，再取匹配的。

- 选择器：`lastSuccessfulBuild` / `lastCompletedBuild` / `lastBuild` / `number`
- 匹配不到时**把该构建实际有的产物列出来**，不用去翻构建页
- `**` 跨目录，`*`/`?` 不跨斜杠；产物路径按真实子目录落盘，不会被拍平
- 401/403 提示 token 需要该 job 的读权限

```bash
mvn verify   # build + src/test/java/atom/ArtifactCopyIT.java（JUnit 5，对着 tools/fake_jenkins.py）
```
