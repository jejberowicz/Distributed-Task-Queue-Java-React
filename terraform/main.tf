provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

locals {
  name = var.project

  # Dos AZ: es el mínimo que exige un ALB y también el mínimo que exige un
  # subnet group de RDS. No es por alta disponibilidad, es un requisito.
  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  # Los streams que mira el autoscaler. Tienen que coincidir con
  # inferqueue.queue.streams.* de application.yml.
  queue_streams = ["infer:priority", "infer:standard"]
}
