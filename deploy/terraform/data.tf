resource "aws_db_subnet_group" "main" {
  name       = local.prefix
  subnet_ids = aws_subnet.private[*].id
}

# Force TLS for every database connection.
resource "aws_db_parameter_group" "pg16" {
  name_prefix = "${local.prefix}-pg16-"
  family      = "postgres16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
  lifecycle { create_before_destroy = true }
}

resource "aws_db_instance" "main" {
  identifier     = local.prefix
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  db_name  = local.db_name
  username = "ledgercore"
  # RDS generates the master password and stores it in Secrets Manager; Terraform never sees it.
  manage_master_user_password = true

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_allocated_storage * 5
  storage_type          = "gp3"
  storage_encrypted     = true

  multi_az               = var.db_multi_az
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.pg16.name
  publicly_accessible    = false

  backup_retention_period      = var.db_backup_retention_days
  backup_window                = "02:00-03:00"
  maintenance_window           = "sun:03:30-sun:04:30"
  auto_minor_version_upgrade   = true
  performance_insights_enabled = true
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.prefix}-final"
  copy_tags_to_snapshot     = true
}

# ---- application secrets ----
resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "jwt" {
  name                    = "${local.prefix}/jwt-secret"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id     = aws_secretsmanager_secret.jwt.id
  secret_string = random_password.jwt.result
}

resource "random_password" "admin" {
  length           = 24
  special          = true
  override_special = "!#$%^*-_=+"
  min_upper        = 2
  min_lower        = 2
  min_numeric      = 2
}

resource "aws_secretsmanager_secret" "admin" {
  name                    = "${local.prefix}/bootstrap-admin-password"
  recovery_window_in_days = 7
}

resource "aws_secretsmanager_secret_version" "admin" {
  secret_id     = aws_secretsmanager_secret.admin.id
  secret_string = random_password.admin.result
}
