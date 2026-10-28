#!/bin/bash
# 최초 기동(볼륨이 비어 있을 때) 한 번만 실행된다.
# ./gradlew test 가 쓰는 qating_test 스키마를 만들고 앱 사용자에게 권한을 준다.
set -e

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS qating_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON qating_test.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
SQL
