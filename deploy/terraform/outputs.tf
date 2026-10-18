output "alb_dns_name" {
  description = "Point your DNS (CNAME/ALIAS) at this."
  value       = aws_lb.main.dns_name
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service" {
  value = aws_ecs_service.app.name
}

output "ecs_task_family" {
  value = aws_ecs_task_definition.app.family
}

output "rds_endpoint" {
  value = aws_db_instance.main.address
}

output "bootstrap_admin_secret_arn" {
  description = "Read the first admin password here, log in, then change it."
  value       = aws_secretsmanager_secret.admin.arn
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_ROLE_ARN repository variable."
  value       = var.github_repo == "" ? null : aws_iam_role.ci[0].arn
}
