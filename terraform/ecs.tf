resource "aws_ecs_cluster" "main" {
  name = local.name

  setting {
    # Container Insights se cobra por métrica publicada y para esta demo no
    # aporta nada que no den los logs.
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

resource "aws_cloudwatch_log_group" "gateway" {
  name              = "/ecs/${local.name}/gateway"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${local.name}/worker"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "dashboard" {
  name              = "/ecs/${local.name}/dashboard"
  retention_in_days = var.log_retention_days
}

locals {
  # Las mismas variables que el ConfigMap de la Fase 1. Sólo cambian los hosts:
  # los Services de Kubernetes pasan a ser los endpoints de RDS y ElastiCache.
  app_env = [
    { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}" },
    { name = "DB_USER", value = aws_db_instance.main.username },
    { name = "REDIS_HOST", value = aws_elasticache_cluster.main.cache_nodes[0].address },
    { name = "REDIS_PORT", value = tostring(aws_elasticache_cluster.main.cache_nodes[0].port) },
    { name = "MODEL_ADAPTER", value = var.model_adapter },
    { name = "OLLAMA_URL", value = var.ollama_url },
  ]

  db_password_secret = {
    name      = "DB_PASSWORD"
    valueFrom = aws_ssm_parameter.db_password.arn
  }

  log_options = {
    gateway   = { "awslogs-group" = aws_cloudwatch_log_group.gateway.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "ecs" }
    worker    = { "awslogs-group" = aws_cloudwatch_log_group.worker.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "ecs" }
    dashboard = { "awslogs-group" = aws_cloudwatch_log_group.dashboard.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "ecs" }
  }
}

# --- Gateway -----------------------------------------------------------------

resource "aws_ecs_task_definition" "gateway" {
  family                   = "${local.name}-gateway"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.gateway_cpu
  memory                   = var.gateway_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # Explícito para que no dependa de en qué máquina se construyó la imagen: en
  # una Mac con Apple Silicon, un `docker build` sin --platform produce arm64 y
  # la tarea muere con "exec format error", que no dice nada de la arquitectura.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = "gateway"
    image     = "${aws_ecr_repository.backend.repository_url}:latest"
    essential = true

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    environment = concat(local.app_env, [
      # El gateway no consume la cola: se escala el frente sin tocar la
      # capacidad de inferencia, y al revés.
      { name = "WORKER_ENABLED", value = "false" },
    ])

    secrets = [
      local.db_password_secret,
      { name = "ADMIN_TOKEN", valueFrom = aws_ssm_parameter.admin_token.arn },
    ]

    # Tiene que superar el spring.lifecycle.timeout-per-shutdown-phase de 40s, o
    # el SIGKILL corta requests vivos. Es el mismo invariante que el
    # terminationGracePeriodSeconds de la Fase 1; en Fargate el techo es 120s.
    stopTimeout = 60

    logConfiguration = {
      logDriver = "awslogs"
      options   = local.log_options.gateway
    }
  }])
}

resource "aws_ecs_service" "gateway" {
  name            = "gateway"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.gateway.arn
  desired_count   = var.gateway_min_count

  # On-demand y no Spot: sostiene las sesiones WebSocket del dashboard, y una
  # interrupción se ve como una desconexión.
  capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true # sin esto no hay salida a ECR y la tarea no arranca
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.gateway.arn
    container_name   = "gateway"
    container_port   = 8080
  }

  # Arranque de la JVM más las migraciones de Flyway. Sin esta gracia el ALB
  # declara la tarea unhealthy antes del primer 200 y ECS la mata: un
  # CrashLoopBackOff perfectamente circular.
  health_check_grace_period_seconds = 180

  # Si el deploy nuevo no llega a estabilizarse, ECS vuelve solo a la revisión
  # anterior en vez de dejar el servicio a medio actualizar.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  enable_execute_command = true

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener.http]
}

# --- Worker ------------------------------------------------------------------

resource "aws_ecs_task_definition" "worker" {
  family                   = "${local.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    # El initContainer de Kubernetes, traducido: un contenedor no esencial que
    # corre primero y del que el worker depende con condición SUCCESS.
    #
    # Hace falta por lo mismo que allá: el worker arranca con ddl-auto=validate
    # y sin Flyway, así que si el schema todavía no está migrado el contexto de
    # Spring no levanta y la tarea muere. Se recupera sola —ECS reintenta— pero
    # ensucia el arranque y lo retrasa por el backoff.
    #
    # A diferencia de la Fase 1 no espera al readiness del gateway sino a la
    # tabla de historial de Flyway. Es más directo (pregunta por lo que
    # realmente hace falta) y no depende de que haya DNS interno entre
    # servicios, que en ECS sin Service Connect no existe.
    {
      name      = "wait-for-schema"
      image     = "postgres:16-alpine"
      essential = false

      environment = [
        { name = "PGHOST", value = aws_db_instance.main.address },
        { name = "PGPORT", value = tostring(aws_db_instance.main.port) },
        { name = "PGUSER", value = aws_db_instance.main.username },
        { name = "PGDATABASE", value = aws_db_instance.main.db_name },
      ]

      secrets = [{ name = "PGPASSWORD", valueFrom = aws_ssm_parameter.db_password.arn }]

      command = [
        "sh", "-c",
        "until psql -tAc \"select 1 from flyway_schema_history where success limit 1\" 2>/dev/null | grep -q 1; do echo esperando el schema; sleep 5; done"
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options   = local.log_options.worker
      }
    },
    {
      name      = "worker"
      image     = "${aws_ecr_repository.backend.repository_url}:latest"
      essential = true

      dependsOn = [{ containerName = "wait-for-schema", condition = "SUCCESS" }]

      environment = concat(local.app_env, [
        { name = "WORKER_ENABLED", value = "true" },
        { name = "WORKER_CONCURRENCY", value = tostring(var.worker_concurrency) },
        # El gateway ya corrió las migraciones; los workers sólo validan.
        { name = "SPRING_FLYWAY_ENABLED", value = "false" },
      ])

      secrets = [local.db_password_secret]

      # El worker que recibe SIGTERM deja de leer del stream y termina los jobs
      # en vuelo. Si el SIGKILL llega antes, esos mensajes quedan en el PEL y
      # hay que esperar el XCLAIM del reclaimer: se recupera, pero se paga en
      # latencia en cada scale-down. Y en Spot esto no es hipotético: la
      # interrupción manda SIGTERM con 2 minutos de aviso, que alcanzan de sobra.
      stopTimeout = 60

      logConfiguration = {
        logDriver = "awslogs"
        options   = local.log_options.worker
      }
    }
  ])
}

resource "aws_ecs_service" "worker" {
  name            = "worker"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.worker.arn
  desired_count   = var.worker_min_count

  capacity_provider_strategy {
    capacity_provider = var.worker_use_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  enable_execute_command = true

  lifecycle {
    # El equivalente exacto de omitir `replicas` en el Deployment del worker de
    # la Fase 1. La escala la maneja Application Auto Scaling; si Terraform
    # siguiera el desired_count, cada `apply` devolvería el servicio a
    # worker_min_count y pisaría al autoscaler.
    ignore_changes = [desired_count]
  }
}

# --- Dashboard ---------------------------------------------------------------

resource "aws_ecs_task_definition" "dashboard" {
  family                   = "${local.name}-dashboard"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = "dashboard"
    image     = "${aws_ecr_repository.dashboard.repository_url}:latest"
    essential = true

    portMappings = [{ containerPort = 80, protocol = "tcp" }]

    # Sin esto el contenedor no arranca, y el error no se parece a la causa.
    #
    # El nginx del dashboard tiene `proxy_pass http://gateway:8080` para el modo
    # compose, donde no hay ingress. nginx resuelve el nombre del upstream al
    # cargar la config, no al primer request: si `gateway` no resuelve, no
    # levanta —"host not found in upstream"— y la tarea muere en loop. En kind
    # el nombre existe porque hay un Service; en ECS no hay DNS entre servicios.
    #
    # Apuntarlo a localhost hace que nginx arranque. La ruta queda muerta, pero
    # es la misma ruta muerta que en la Fase 1: el ALB manda /v1, /admin y /ws
    # al gateway antes de que lleguen acá. La alternativa cara sería ECS Service
    # Connect sólo para darle un nombre a un proxy que no se usa.
    extraHosts = [{ hostname = "gateway", ipAddress = "127.0.0.1" }]

    logConfiguration = {
      logDriver = "awslogs"
      options   = local.log_options.dashboard
    }
  }])
}

resource "aws_ecs_service" "dashboard" {
  name            = "dashboard"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.dashboard.arn
  desired_count   = 1

  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 1
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.dashboard.arn
    container_name   = "dashboard"
    container_port   = 80
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener.http]
}
