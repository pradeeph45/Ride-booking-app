-- One database per service (database-per-service pattern).
-- The app user gets access to these schemas only; never to mysql.* or other schemas.
CREATE DATABASE IF NOT EXISTS rb_auth    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS rb_user    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS rb_booking CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS rb_matching CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS rb_payment CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS rb_invoice CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- The user name comes from MYSQL_USER in .env (default: rb_app).
GRANT ALL PRIVILEGES ON rb_auth.*     TO 'rb_app'@'%';
GRANT ALL PRIVILEGES ON rb_user.*     TO 'rb_app'@'%';
GRANT ALL PRIVILEGES ON rb_booking.*  TO 'rb_app'@'%';
GRANT ALL PRIVILEGES ON rb_matching.* TO 'rb_app'@'%';
GRANT ALL PRIVILEGES ON rb_payment.*  TO 'rb_app'@'%';
GRANT ALL PRIVILEGES ON rb_invoice.*  TO 'rb_app'@'%';
FLUSH PRIVILEGES;
