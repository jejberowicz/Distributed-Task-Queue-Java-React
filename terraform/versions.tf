terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }

  # El estado queda en un archivo local a propósito: es una demo de un solo
  # operador y un backend remoto agrega un bucket y una tabla de locks que
  # también hay que crear y pagar.
  #
  # La contra es real y conviene saberla antes de que duela: si se pierde
  # terraform.tfstate, `terraform destroy` ya no sabe qué destruir y hay que
  # borrar a mano desde la consola. Para algo compartido, descomentar:
  #
  # backend "s3" {
  #   bucket       = "inferqueue-tfstate"
  #   key          = "phase2/terraform.tfstate"
  #   region       = "us-east-1"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}
