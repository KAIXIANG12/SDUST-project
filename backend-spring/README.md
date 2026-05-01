# Spring Boot 后端

这是面向落地版本的后端，接口路径统一为 `/api/v1`。

## 技术栈

1. Spring Boot 2.7.x：兼容当前电脑的 Java 11
2. Spring Web：REST API
3. Spring JDBC：连接 MySQL
4. MySQL 8：复用 `database/schema.sql` 和 `database/seed.sql`

## 启动前准备

在本目录执行：

```powershell
cd "E:\山东科技大学毕业设计\backend-spring"
mvn spring-boot:run
```

如果 MySQL 密码不是空，需要先设置环境变量：

```powershell
$env:DB_PASSWORD="你的MySQL密码"
mvn spring-boot:run
```

启动后前端仍然访问：

```text
http://localhost:5173
```

后端接口仍然是：

```text
http://localhost:8080/api/v1
```

## 迁移策略

当前 Spring Boot 后端先保持接口兼容，后续再继续拆分为 Controller、Service、Repository、DTO、权限拦截器、操作日志等更完整的工程结构。
