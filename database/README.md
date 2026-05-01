# 数据库初始化说明

## 1. 创建表

```powershell
mysql -u root -p < database/schema.sql
```

## 2. 导入种子数据

```powershell
mysql -u root -p < database/seed.sql
```

## 3. 后端切换到 MySQL

在启动后端前设置环境变量：

```powershell
$env:DATA_PROVIDER="mysql"
$env:DB_HOST="127.0.0.1"
$env:DB_PORT="3306"
$env:DB_USER="root"
$env:DB_PASSWORD="你的数据库密码"
$env:DB_NAME="student_feedback_system"
node backend/src/server.js
```

## 4. 默认测试账号

种子数据提供以下测试账号，初始密码均为 `admin123`：

1. `root`
2. `cs_admin`
3. `monitor_se_2401`
4. `student_demo`

当前种子数据为了便于本地初始化使用 `plain:` 密码格式。正式部署前应改成 PBKDF2 哈希密码，并更换 `AUTH_TOKEN_SECRET`。
