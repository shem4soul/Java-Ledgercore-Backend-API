variable "aws_region" {
  type    = string
  default = "eu-west-1"
}

variable "name" {
  type    = string
  default = "ledgercore"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "image_tag" {
  description = "Image tag already pushed to ECR (the CD pipeline replaces it on every deploy)."
  type        = string
}

variable "container_cpu" {
  type    = number
  default = 512
}

variable "container_memory" {
  type    = number
  default = 1024
}

variable "desired_count" {
  type    = number
  default = 2
}

variable "min_capacity" {
  type    = number
  default = 2
}

variable "max_capacity" {
  type    = number
  default = 6
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "db_allocated_storage" {
  type    = number
  default = 20
}

variable "db_multi_az" {
  description = "Set true in production for automatic failover."
  type        = bool
  default     = false
}

variable "db_backup_retention_days" {
  type    = number
  default = 7
}

variable "deletion_protection" {
  type    = bool
  default = true
}

variable "acm_certificate_arn" {
  description = "ACM certificate for HTTPS. Leave empty to serve plain HTTP (not for real money)."
  type        = string
  default     = ""
}

variable "cors_origins" {
  type    = string
  default = "https://app.example.com"
}

variable "bootstrap_admin_email" {
  type    = string
  default = "admin@example.com"
}

variable "alarm_email" {
  description = "Email for CloudWatch alarm notifications (optional)."
  type        = string
  default     = ""
}

variable "github_repo" {
  description = "owner/repo allowed to deploy via GitHub OIDC (leave empty to skip creating the CI role)."
  type        = string
  default     = ""
}
