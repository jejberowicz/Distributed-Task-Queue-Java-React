output "url" {
  description = "La demo, publica."
  value       = var.certificate_arn == "" ? "http://${aws_lb.main.dns_name}" : "https://${aws_lb.main.dns_name}"
}

output "admin_token" {
  description = "Para /admin. Se lee con: terraform output -raw admin_token"
  value       = random_password.admin_token.result
  sensitive   = true
}

output "cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "backend_repository_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "dashboard_repository_url" {
  value = aws_ecr_repository.dashboard.repository_url
}

output "registry" {
  description = "Host de ECR, para el docker login."
  value       = split("/", aws_ecr_repository.backend.repository_url)[0]
}

output "db_endpoint" {
  value = aws_db_instance.main.address
}

output "redis_endpoint" {
  value = aws_elasticache_cluster.main.cache_nodes[0].address
}
