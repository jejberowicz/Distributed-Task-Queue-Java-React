# Las mismas dos imágenes de la Fase 1, en un registry que Fargate pueda leer.
# `kind load` no existe acá: hay que empujarlas de verdad.

resource "aws_ecr_repository" "backend" {
  name                 = "${local.name}/backend"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  # Permite que `terraform destroy` borre el repo aunque tenga imágenes adentro.
  # Sin esto el destroy falla y hay que vaciarlo a mano.
  force_delete = true
}

resource "aws_ecr_repository" "dashboard" {
  name                 = "${local.name}/dashboard"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  force_delete = true
}

# ECR cobra por GB almacenado y una imagen de la JVM pesa ~250 MB. Sin esta
# política, cada `make push` deja atrás la anterior sin tag y en un mes hay
# varios GB de basura que nadie mira.
locals {
  ecr_lifecycle = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Conservar las ultimas 5 imagenes"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy     = local.ecr_lifecycle
}

resource "aws_ecr_lifecycle_policy" "dashboard" {
  repository = aws_ecr_repository.dashboard.name
  policy     = local.ecr_lifecycle
}
