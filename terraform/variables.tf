variable "project" {
  description = "Prefijo de todos los nombres. Cambiarlo permite levantar dos stacks en la misma cuenta."
  type        = string
  default     = "inferqueue"
}

variable "region" {
  description = "us-east-1 es la más barata y la que más rápido recibe tipos de instancia nuevos."
  type        = string
  default     = "us-east-1"
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

# --- Datos gestionados -------------------------------------------------------

variable "db_instance_class" {
  description = "db.t4g.micro entra en el free tier (750 h/mes durante 12 meses) y es ARM, más barata que la t3."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "Sólo la major: RDS elige la minor y así un `apply` no se rompe cuando AWS deprecia un parche."
  type        = string
  default     = "16"
}

variable "db_allocated_storage" {
  description = "20 GB es exactamente el techo del free tier."
  type        = number
  default     = 20
}

variable "redis_node_type" {
  description = "cache.t4g.micro, también free tier por 12 meses."
  type        = string
  default     = "cache.t4g.micro"
}

variable "redis_engine_version" {
  description = "7.x hace falta para XAUTOCLAIM y para el campo `lag` de XINFO GROUPS."
  type        = string
  default     = "7.1"
}

# --- Cómputo -----------------------------------------------------------------

variable "gateway_cpu" {
  type    = number
  default = 512
}

variable "gateway_memory" {
  description = "La JVM con virtual threads y el pool de conexiones no entra cómoda en menos de 1 GB."
  type        = number
  default     = 1024
}

variable "gateway_min_count" {
  type    = number
  default = 1
}

variable "gateway_max_count" {
  type    = number
  default = 4
}

variable "worker_cpu" {
  type    = number
  default = 512
}

variable "worker_memory" {
  type    = number
  default = 1024
}

variable "worker_min_count" {
  description = <<-EOT
    Nunca 0. El PendingReclaimer y el TtlSweeper viven dentro del worker: sin
    ninguna tarea corriendo, un job que quedó huérfano en el PEL no lo reclama
    nadie y un job vencido no lo barre nadie. Mismo motivo que el
    minReplicaCount del ScaledObject de KEDA en la Fase 1.
  EOT
  type        = number
  default     = 1
}

variable "worker_max_count" {
  description = <<-EOT
    En kind el techo era 10 porque escalar no costaba nada. Acá cada tarea se
    factura por segundo, así que el techo es también el techo del gasto: 6
    tareas de 0.5 vCPU en Spot son unos 0.02 USD/hora si el pico dura.
  EOT
  type        = number
  default     = 6
}

variable "worker_use_spot" {
  description = <<-EOT
    Spot cuesta ~70% menos y AWS puede matar la tarea con 2 minutos de aviso.
    Para este worker eso no es un riesgo nuevo: perder un consumidor a mitad de
    un job es exactamente el caso que el PEL y el XCLAIM del PendingReclaimer ya
    resuelven. El gateway, en cambio, sostiene sesiones WebSocket y va on-demand.
  EOT
  type        = bool
  default     = true
}

variable "worker_scale_out_threshold" {
  description = "Profundidad de cola (XLEN de los dos streams) que dispara el scale-out."
  type        = number
  default     = 20
}

variable "worker_scale_in_threshold" {
  description = "Por debajo de esto, y sostenido, se devuelven tareas."
  type        = number
  default     = 5
}

# --- Aplicación --------------------------------------------------------------

variable "worker_concurrency" {
  description = "Virtual threads por tarea. Con MODEL_ADAPTER=mock no es el cuello de botella."
  type        = number
  default     = 4
}

variable "model_adapter" {
  description = <<-EOT
    mock. Fargate no tiene GPU, así que `ollama` acá sólo sirve apuntando
    OLLAMA_URL a algo alcanzable desde la VPC, y eso ya no es free tier.
  EOT
  type        = string
  default     = "mock"
}

variable "ollama_url" {
  type    = string
  default = ""
}

variable "log_retention_days" {
  description = "CloudWatch Logs cobra por almacenamiento; sin retención los logs se acumulan para siempre."
  type        = number
  default     = 7
}

variable "certificate_arn" {
  description = <<-EOT
    ARN de un certificado de ACM. Si se pasa, el ALB suma un listener 443 y el 80
    redirige. Sin dominio propio esto queda vacío y la demo es HTTP plano — el
    dashboard igual funciona porque deriva ws:// o wss:// de window.location.
  EOT
  type        = string
  default     = ""
}

variable "allowed_ingress_cidrs" {
  description = "Quién puede llegar al ALB. Restringirlo a la IP propia mientras se prueba es sano."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}
